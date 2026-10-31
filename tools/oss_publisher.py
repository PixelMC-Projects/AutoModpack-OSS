#!/usr/bin/env python3
"""Explicit-file, signed immutable OSS release publisher (no automatic activation)."""
from __future__ import annotations

import argparse
import base64
import datetime as dt
import email.utils
import hashlib
import hmac
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tomllib
import urllib.error
import urllib.parse
import urllib.request
import zipfile

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

ROOTS = {"mods", "config", "resourcepacks", "shaderpacks", "defaultconfigs", "kubejs", "scripts"}
TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}\Z")
HEX = re.compile(r"[0-9a-f]{64}\Z")
MAX_FILE = 4 * 1024**3
MAX_TOTAL = 16 * 1024**3
MAX_FILES = 10000
MAX_MANIFEST = 8 * 1024**2
MAX_METADATA = 128 * 1024
RESERVED = re.compile(r"(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?\Z")


def canonical_json(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key: " + key)
        result[key] = value
    return result


def read_json(path):
    data = Path(path).read_bytes()
    if len(data) > MAX_MANIFEST:
        raise ValueError("JSON exceeds limit")
    return json.loads(data, object_pairs_hook=unique_object)


def token(value):
    if not isinstance(value, str) or not TOKEN.fullmatch(value):
        raise ValueError("invalid identity/version token")
    return value


def relative_path(value, managed=True):
    if not isinstance(value, str) or not value or len(value) > 240 or "\\" in value:
        raise ValueError("invalid relative path")
    parts = value.split("/")
    if len(parts) < 2 and managed:
        raise ValueError("managed file must be below an allowed root")
    if any(not p or p in (".", "..") or p.endswith((".", " ")) or
           any(ord(c) < 32 or c in ':<>"|?*' for c in p) or RESERVED.fullmatch(p) for p in parts):
        raise ValueError("unsafe relative path: " + value)
    if managed and parts[0] not in ROOTS:
        raise ValueError("unmanaged root: " + value)
    if managed and parts[0] == "config" and parts[1].lower().startswith("automodpack"):
        raise ValueError("updater control/trust files cannot be release-managed")
    if managed and parts[0] == "mods" and parts[1].lower().startswith("automodpack"):
        raise ValueError("the synchronization mod cannot update itself through a pack release")
    if PurePosixPath(value).is_absolute():
        raise ValueError("absolute path forbidden")
    return value


def checked_file(root, relative):
    relative_path(relative, managed=False)
    root = Path(root).absolute()
    if root.is_symlink() or not root.is_dir():
        raise ValueError("source root is not a regular directory")
    for ancestor in (root, *root.parents):
        info = ancestor.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("symlink/reparse root forbidden")
    current = root
    for part in relative.split("/"):
        current = current / part
        info = current.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError("symlink/reparse source forbidden")
    if not current.is_file():
        raise ValueError("source is not a regular file")
    return current


def digest_file(path):
    digest, length, tail = hashlib.sha256(), 0, b""
    with Path(path).open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
            length += len(block)
            if length > MAX_FILE:
                raise ValueError("file exceeds size limit")
            scan = tail + block
            if b"-----BEGIN " in scan and b"PRIVATE KEY-----" in scan:
                raise ValueError("private key material cannot be packaged")
            tail = scan[-128:]
    return length, digest.hexdigest()


def reject_updater(managed_path, source):
    if not managed_path.startswith("mods/") or not managed_path.lower().endswith(".jar") or not zipfile.is_zipfile(source):
        return
    with zipfile.ZipFile(source) as archive:
        if len(archive.infolist()) > 100000:
            raise ValueError("jar entry limit exceeded")
        budget = 4 * MAX_METADATA
        for item in archive.infolist():
            if item.filename not in ("fabric.mod.json", "quilt.mod.json", "META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                continue
            if item.file_size > MAX_METADATA:
                raise ValueError("jar mod metadata exceeds limit")
            with archive.open(item) as stream:
                raw = stream.read(MAX_METADATA + 1)
            budget -= len(raw)
            if len(raw) > MAX_METADATA or budget < 0:
                raise ValueError("jar mod metadata exceeds limit")
            if item.filename.endswith(".toml"):
                metadata = tomllib.loads(raw.decode("utf-8"))
                ids = [entry.get("modId") for entry in metadata.get("mods", [])]
            else:
                metadata = json.loads(raw, object_pairs_hook=unique_object)
                ids = [metadata.get("quilt_loader", {}).get("id")] if item.filename == "quilt.mod.json" else [metadata.get("id")]
            if any(value in ("automodpack", "automodpack_mod") for value in ids):
                raise ValueError("updater jars require manual distribution; pack replacement is forbidden")


def https_url(value, query=False):
    parsed = urllib.parse.urlsplit(value)
    try:
        port = parsed.port
    except ValueError as error:
        raise ValueError("invalid HTTPS URL") from error
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None or
            parsed.password is not None or port not in (None, 443) or parsed.fragment or
            (parsed.query and not query) or "\\" in value or any(ord(c) < 33 for c in value)):
        raise ValueError("expected HTTPS URL on port 443 without credentials/query/fragment")
    if any(p in (".", "..") for p in parsed.path.split("/")):
        raise ValueError("noncanonical URL path")
    return parsed


def validate_manifest(manifest):
    if manifest.get("schema") != 1:
        raise ValueError("unsupported manifest schema")
    for key in ("packId", "releaseId", "managementScopeVersion"):
        token(manifest.get(key))
    for key in ("sequence", "consentProtocolVersion"):
        limit = 2**31 - 1 if key == "consentProtocolVersion" else 2**63 - 1
        if type(manifest.get(key)) is not int or manifest[key] <= 0 or manifest[key] > limit:
            raise ValueError("invalid " + key)
    created = dt.datetime.fromisoformat(manifest["createdAt"].replace("Z", "+00:00"))
    if created.tzinfo is None or created.utcoffset() != dt.timedelta(0):
        raise ValueError("createdAt must be UTC")
    files, seen, total = manifest.get("files"), set(), 0
    if not isinstance(files, list) or not 0 < len(files) <= MAX_FILES:
        raise ValueError("invalid file count")
    for entry in files:
        path = relative_path(entry.get("path"))
        if path.casefold() in seen:
            raise ValueError("duplicate/case-colliding path")
        seen.add(path.casefold())
        size = entry.get("size")
        if type(size) is not int or not 0 <= size <= MAX_FILE or not HEX.fullmatch(entry.get("sha256", "")):
            raise ValueError("invalid hash or size")
        https_url(entry.get("url", ""))
        total += size
    if total > MAX_TOTAL:
        raise ValueError("release exceeds total size limit")


def load_public(path):
    raw = Path(path).read_bytes()
    try:
        key = serialization.load_pem_public_key(raw)
    except ValueError:
        key = serialization.load_der_public_key(base64.b64decode(raw.strip(), validate=True))
    if not isinstance(key, Ed25519PublicKey):
        raise ValueError("expected Ed25519 public key")
    return key


def verify_envelope(data, public_key):
    if len(data) > MAX_MANIFEST:
        raise ValueError("manifest exceeds limit")
    envelope = json.loads(data, object_pairs_hook=unique_object)
    if envelope.get("schema") != 1:
        raise ValueError("unsupported envelope schema")
    payload = base64.b64decode(envelope["payload"], validate=True)
    signature = base64.b64decode(envelope["signature"], validate=True)
    public_key.verify(signature, payload)
    manifest = json.loads(payload, object_pairs_hook=unique_object)
    validate_manifest(manifest)
    return manifest


def keygen(args):
    private_path, public_path = Path(args.private_key).absolute(), Path(args.public_key).absolute()
    if private_path == public_path:
        raise ValueError("key output paths must differ")
    if private_path.exists() or public_path.exists():
        raise FileExistsError("key output already exists")
    resolved = private_path.resolve()
    if any((p / ".git").exists() for p in (resolved.parent, *resolved.parents)):
        raise ValueError("private key must be outside a Git worktree")
    key = Ed25519PrivateKey.generate()
    private_path.parent.mkdir(parents=True, exist_ok=True)
    public_path.parent.mkdir(parents=True, exist_ok=True)
    identity = getattr(args, "purpose", "publisher") == "server-identity"
    private = key.private_bytes(serialization.Encoding.DER if identity else serialization.Encoding.PEM,
                                serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    if identity:
        private = base64.b64encode(private) + b"\n"
    public = base64.b64encode(key.public_key().public_bytes(serialization.Encoding.DER,
                                                          serialization.PublicFormat.SubjectPublicKeyInfo)) + b"\n"
    # O_EXCL prevents accidental replacement. Keep this directory restricted with OS ACLs.
    with os.fdopen(os.open(private_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as out:
        out.write(private)
    with os.fdopen(os.open(public_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644), "wb") as out:
        out.write(public)
    print("Created " + ("server identity" if identity else "local publisher") + " Ed25519 key pair; public key is base64 X.509 DER.")


def authorized_inputs(args):
    spec = read_json(args.spec)
    if spec.get("schema") != 1 or not isinstance(spec.get("files"), list) or not 0 < len(spec["files"]) <= MAX_FILES:
        raise ValueError("invalid explicit release specification")
    base = args.base_url.rstrip("/")
    https_url(base)
    release_id = token(spec["releaseId"])
    entries, sources = [], []
    for item in spec["files"]:
        if item.get("redistributionConfirmed") is not True or not isinstance(item.get("license"), str) or not item["license"].strip() or not isinstance(item.get("provenance"), str) or not item["provenance"].strip():
            raise ValueError("every explicit file needs redistributionConfirmed=true, license, and provenance")
        path = relative_path(item["path"])
        source = checked_file(args.source_root, item["source"])
        reject_updater(path, source)
        length, sha = digest_file(source)
        if type(item.get("size")) is not int or length != item["size"] or sha != item.get("sha256"):
            raise ValueError("authorized file bytes changed: " + path)
        entries.append({"path": path, "size": length, "sha256": sha,
                        "url": base + "/releases/" + release_id + "/files/" + urllib.parse.quote(path, safe="/")})
        sources.append(source)
    manifest = {"schema": 1, "packId": spec["packId"], "releaseId": release_id,
                "sequence": spec["sequence"], "consentProtocolVersion": spec["consentProtocolVersion"],
                "managementScopeVersion": spec["managementScopeVersion"],
                "createdAt": dt.datetime.now(dt.timezone.utc).isoformat().replace("+00:00", "Z"), "files": entries}
    validate_manifest(manifest)
    estimated = canonical_json({"schema": 1, "payload": base64.b64encode(canonical_json(manifest)).decode("ascii"), "signature": "A" * 88})
    if len(estimated) > MAX_MANIFEST:
        raise ValueError("manifest exceeds limit")
    return spec, manifest, sources


def validate_spec(args):
    _, expected, _ = authorized_inputs(args)
    release_dir = getattr(args, "release_dir", None)
    public_key = getattr(args, "public_key", None)
    if bool(release_dir) != bool(public_key):
        raise ValueError("existing release validation needs both release-dir and public-key")
    if release_dir:
        _, _, _, actual, _ = local_release(args)
        for field in ("schema", "packId", "releaseId", "sequence", "consentProtocolVersion", "managementScopeVersion", "files"):
            if actual[field] != expected[field]:
                raise ValueError("existing signed release does not match authorized spec/profile")
    print("Validated explicit release specification and exact source bytes; no key generation or network access.")


def build(args):
    spec, manifest, sources = authorized_inputs(args)
    release_id, entries = manifest["releaseId"], manifest["files"]
    base = args.base_url.rstrip("/")
    output = Path(args.output).absolute() / release_id
    if output.exists():
        raise ValueError("immutable release directory already exists")
    private = serialization.load_pem_private_key(Path(args.private_key).read_bytes(), password=None)
    if not isinstance(private, Ed25519PrivateKey):
        raise ValueError("expected Ed25519 private key")
    payload = canonical_json(manifest)
    envelope = canonical_json({"schema": 1, "payload": base64.b64encode(payload).decode("ascii"),
                               "signature": base64.b64encode(private.sign(payload)).decode("ascii")})
    if len(envelope) > MAX_MANIFEST:
        raise ValueError("manifest exceeds limit")
    output.mkdir(parents=True, exist_ok=False)
    for entry, source in zip(entries, sources):
        dest = output / "files" / Path(entry["path"])
        dest.parent.mkdir(parents=True, exist_ok=True)
        # Recheck copied bytes to detect a source changing during publication.
        shutil.copyfile(source, dest)
        if digest_file(dest) != (entry["size"], entry["sha256"]):
            raise ValueError("source changed during copy; discard incomplete output")
    (output / "manifest.json").write_bytes(envelope)
    (output / "publish-plan.json").write_bytes(canonical_json({"schema": 1, "baseUrl": base,
        "manifestUrl": base + "/releases/" + release_id + "/manifest.json",
        "manifestSha256": hashlib.sha256(envelope).hexdigest()}))
    (output / "redistribution-record.json").write_bytes(canonical_json({"schema": 1, "files": [
        {"path": entry["path"], "sha256": entry["sha256"], "license": item["license"], "provenance": item["provenance"],
         "redistributionConfirmed": True} for entry, item in zip(entries, spec["files"])]}))
    print("Built signed release " + release_id + "; nothing uploaded or activated.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("OSS redirect refused")


def sign_v4_headers(method, url, bucket, region, key_id, secret, security_token, headers, when=None):
    """OSS V4 header signing; no network and no ambient credential lookup in this pure function."""
    parsed = https_url(url)
    when = (when or dt.datetime.now(dt.timezone.utc)).astimezone(dt.timezone.utc)
    timestamp = when.strftime("%Y%m%dT%H%M%SZ")
    date = timestamp[:8]
    signed = dict(headers)
    signed["Date"] = email.utils.format_datetime(when, usegmt=True)
    signed["x-oss-date"] = timestamp
    signed["x-oss-content-sha256"] = "UNSIGNED-PAYLOAD"
    if security_token:
        signed["x-oss-security-token"] = security_token
    canonical_headers = "".join(name.lower() + ":" + str(value) + "\n" for name, value in
        sorted(signed.items(), key=lambda pair: pair[0].lower()) if name.lower().startswith("x-oss-") or name.lower() in ("content-type", "content-md5"))
    canonical_uri = urllib.parse.quote("/" + bucket + urllib.parse.unquote(parsed.path), safe="/")
    canonical_request = method + "\n" + canonical_uri + "\n\n" + canonical_headers + "\n\nUNSIGNED-PAYLOAD"
    scope = date + "/" + region + "/oss/aliyun_v4_request"
    to_sign = "OSS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + hashlib.sha256(canonical_request.encode()).hexdigest()
    signing_key = ("aliyun_v4" + secret).encode()
    for value in (date, region, "oss", "aliyun_v4_request"):
        signing_key = hmac.new(signing_key, value.encode(), hashlib.sha256).digest()
    signature = hmac.new(signing_key, to_sign.encode(), hashlib.sha256).hexdigest()
    signed["Authorization"] = "OSS4-HMAC-SHA256 Credential=" + key_id + "/" + scope + ",Signature=" + signature
    return signed


class OSS:
    def __init__(self, endpoint, bucket, region):
        parsed = https_url(endpoint)
        if parsed.path not in ("", "/") or not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,61}[a-z0-9]", bucket) or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)+", region):
            raise ValueError("endpoint must be an HTTPS origin; invalid bucket/region")
        self.endpoint, self.host, self.bucket, self.region = endpoint.rstrip("/"), parsed.hostname.lower(), bucket, region
        self.key = os.environ["OSS_ACCESS_KEY_ID"]
        self.secret = os.environ["OSS_ACCESS_KEY_SECRET"]
        self.session_token = os.environ.get("OSS_SECURITY_TOKEN")
        if any(not isinstance(value, str) or not value or len(value) > limit or any(ord(c) < 32 or ord(c) == 127 for c in value)
               for value, limit in ((self.key,256),(self.secret,1024))) or (self.session_token and
               (len(self.session_token)>16384 or any(ord(c)<32 or ord(c)==127 for c in self.session_token))):
            raise ValueError("invalid OSS credential format; values suppressed")
        self.opener = urllib.request.build_opener(NoRedirect())

    def open(self, method, url, data=None, immutable=False, size=None, sha256=None):
        parsed = https_url(url)
        if parsed.hostname.lower() != self.host:
            raise ValueError("upload URL does not match configured OSS endpoint")
        headers = {}
        if method == "PUT":
            headers["Content-Type"] = "application/octet-stream"
            if size is not None:
                headers["Content-Length"] = str(size)
            if sha256 is not None:
                headers["x-oss-meta-sha256"] = sha256
        if immutable:
            headers["x-oss-forbid-overwrite"] = "true"
        headers = sign_v4_headers(method,url,self.bucket,self.region,self.key,self.secret,self.session_token,headers)
        return self.opener.open(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=60)

    def request(self, method, url, data=None, immutable=False, size=None, sha256=None):
        with self.open(method, url, data, immutable, size, sha256) as response:
            if response.status not in (200, 201, 204):
                raise ValueError("unexpected OSS status")
            if method == "GET":
                body = response.read(MAX_MANIFEST + 1)
                if len(body) > MAX_MANIFEST:
                    raise ValueError("remote manifest exceeds limit")
                return body
            return b""

    def digest(self, url):
        digest, size = hashlib.sha256(), 0
        with self.open("GET", url) as response:
            if response.status != 200:
                raise ValueError("unexpected OSS object status")
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > MAX_FILE:
                    raise ValueError("OSS object exceeds size limit")
                digest.update(block)
        return size, digest.hexdigest()

    def put_immutable(self, url, stream, size, sha256):
        try:
            self.request("PUT", url, stream, immutable=True, size=size, sha256=sha256)
        except urllib.error.HTTPError as error:
            if error.code not in (409, 412) or self.digest(url) != (size, sha256):
                raise


def local_release(args):
    root = Path(args.release_dir).absolute()
    raw = checked_file(root, "manifest.json").read_bytes()
    public = load_public(args.public_key)
    manifest = verify_envelope(raw, public)
    plan = read_json(root / "publish-plan.json")
    if plan["manifestSha256"] != hashlib.sha256(raw).hexdigest():
        raise ValueError("publish plan does not match signed manifest")
    for entry in manifest["files"]:
        file = checked_file(root, "files/" + entry["path"])
        if digest_file(file) != (entry["size"], entry["sha256"]):
            raise ValueError("release file changed: " + entry["path"])
        reject_updater(entry["path"], file)
        expected = plan["baseUrl"] + "/releases/" + manifest["releaseId"] + "/files/" + urllib.parse.quote(entry["path"], safe="/")
        if entry["url"] != expected:
            raise ValueError("manifest file URL does not match immutable release layout")
    expected_manifest = plan["baseUrl"] + "/releases/" + manifest["releaseId"] + "/manifest.json"
    if plan["manifestUrl"] != expected_manifest:
        raise ValueError("manifest URL does not match immutable release layout")
    return root, raw, public, manifest, plan


def upload(args):
    root, raw, _, manifest, plan = local_release(args)
    oss = OSS(args.endpoint, args.bucket, args.region)
    for entry in manifest["files"]:
        # urllib streams regular file objects; memory stays bounded by the HTTP implementation.
        with checked_file(root, "files/" + entry["path"]).open("rb") as source:
            oss.put_immutable(entry["url"], source, entry["size"], entry["sha256"])
    oss.put_immutable(plan["manifestUrl"], raw, len(raw), hashlib.sha256(raw).hexdigest())
    print("Uploaded immutable release " + manifest["releaseId"] + "; active release unchanged.")


def activate(args):
    _, raw, public, manifest, plan = local_release(args)
    oss = OSS(args.endpoint, args.bucket, args.region)
    active_url = plan["baseUrl"] + "/active.json"
    # Activation is deliberately a separate operator command, with an expected previous digest.
    try:
        previous = oss.request("GET", active_url)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        previous = None
    expected = "absent" if previous is None else hashlib.sha256(previous).hexdigest()
    if args.expected_active_sha256 != expected:
        raise ValueError("active release changed; inspect it before explicit activation")
    if previous is not None:
        old = verify_envelope(previous, public)
        if old["packId"] != manifest["packId"] or old["sequence"] >= manifest["sequence"]:
            raise ValueError("activation must advance the same pack sequence")
    uploaded = oss.request("GET", plan["manifestUrl"])
    if uploaded != raw:
        raise ValueError("immutable remote manifest not uploaded or does not match")
    # Verify remote file bytes before advertising a release. GET permission is required on publisher.
    for entry in manifest["files"]:
        if oss.digest(entry["url"]) != (entry["size"], entry["sha256"]):
            raise ValueError("remote release object missing or bytes changed")
    oss.request("PUT", active_url, raw, immutable=previous is None)
    if oss.request("GET", active_url) != raw:
        raise ValueError("active release changed concurrently; inspect OSS before retrying")
    print("Explicitly activated " + manifest["releaseId"] + ".")


def export_active(args):
    _, raw, _, manifest, plan = local_release(args)
    output = Path(args.output)
    pin_output = Path(getattr(args, "server_pin_output", None) or output.with_name("server-active-release.json"))
    if output.absolute() == pin_output.absolute() or output.exists() or pin_output.exists():
        raise FileExistsError("activation export paths must be distinct and unused")
    output.parent.mkdir(parents=True, exist_ok=True)
    pin_output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as target:
        target.write(raw)
    pin = {"schema": 1, "packId": manifest["packId"], "releaseId": manifest["releaseId"], "sequence": manifest["sequence"],
           "manifestSha256": hashlib.sha256(raw).hexdigest(), "manifestUrl": plan["manifestUrl"]}
    with pin_output.open("xb") as target:
        target.write(canonical_json(pin))
    print("Exported active envelope and server release pin for " + manifest["releaseId"] + "; nothing uploaded or deployed.")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    key = sub.add_parser("keygen")
    key.add_argument("--private-key", required=True)
    key.add_argument("--public-key", required=True)
    key.set_defaults(func=keygen)
    identity = sub.add_parser("identity-keygen")
    identity.add_argument("--private-key", required=True)
    identity.add_argument("--public-key", required=True)
    identity.set_defaults(func=keygen, purpose="server-identity")
    build_p = sub.add_parser("build")
    for name in ("spec", "source-root", "private-key", "output", "base-url"):
        build_p.add_argument("--" + name, required=True)
    build_p.set_defaults(func=build)
    validate = sub.add_parser("validate-spec")
    for name in ("spec", "source-root", "base-url"):
        validate.add_argument("--" + name, required=True)
    validate.add_argument("--release-dir")
    validate.add_argument("--public-key")
    validate.set_defaults(func=validate_spec)
    export = sub.add_parser("export-active")
    for name in ("release-dir", "public-key", "output"):
        export.add_argument("--" + name, required=True)
    export.add_argument("--server-pin-output")
    export.set_defaults(func=export_active)
    for command, handler in (("upload", upload), ("activate", activate)):
        p = sub.add_parser(command)
        for name in ("release-dir", "public-key", "bucket", "endpoint", "region"):
            p.add_argument("--" + name, required=True)
        if command == "activate":
            p.add_argument("--expected-active-sha256", required=True)
        p.set_defaults(func=handler)
    args = parser.parse_args(argv)
    try:
        args.func(args)
    except Exception as error:
        # Network exception representations can contain signed URLs/credentials. Never echo them.
        if isinstance(error, (urllib.error.HTTPError, urllib.error.URLError)):
            print("Publication failed: OSS request failed (details suppressed).", file=sys.stderr)
        else:
            print("Publication failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
