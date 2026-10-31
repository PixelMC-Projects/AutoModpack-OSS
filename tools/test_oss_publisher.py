"""Offline fixtures only. No real game files, keys, buckets, or network access."""
import base64
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import zipfile

_spec = importlib.util.spec_from_file_location("oss_publisher", Path(__file__).with_name("oss_publisher.py"))
p = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(p)


class PublisherTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="oss-publisher-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.private, self.public = self.root / "keys/private.pem", self.root / "keys/public.txt"
        p.keygen(SimpleNamespace(private_key=str(self.private), public_key=str(self.public)))
        self.source = self.root / "source"
        (self.source / "mods").mkdir(parents=True)
        (self.source / "mods/fixture.jar").write_bytes(b"harmless test fixture; not a mod")
        self.spec = self.root / "spec.json"
        raw = (self.source / "mods/fixture.jar").read_bytes()
        self.spec.write_text(json.dumps({"schema": 1, "packId": "fixture-pack", "releaseId": "fixture-1", "sequence": 1,
            "consentProtocolVersion": 1, "managementScopeVersion": "scope-1", "files": [{"path": "mods/fixture.jar",
            "source": "mods/fixture.jar", "size": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
            "redistributionConfirmed": True, "license": "CC0-1.0", "provenance": "synthetic test fixture"}]}), encoding="utf-8")
        self.args = SimpleNamespace(spec=str(self.spec), source_root=str(self.source), private_key=str(self.private),
            output=str(self.root / "out"), base_url="https://objects.example.test/fixture")

    def build(self):
        p.build(self.args)
        return self.root / "out/fixture-1"

    def test_keys_signatures_and_immutable_output(self):
        release = self.build()
        manifest = p.verify_envelope((release / "manifest.json").read_bytes(), p.load_public(self.public))
        self.assertEqual("fixture-pack", manifest["packId"])
        self.assertEqual("https://objects.example.test/fixture/releases/fixture-1/files/mods/fixture.jar", manifest["files"][0]["url"])
        with self.assertRaises(ValueError):
            p.build(self.args)
        with self.assertRaises(FileExistsError):
            p.keygen(SimpleNamespace(private_key=str(self.private), public_key=str(self.public)))

    def test_tampered_unknown_key_and_file_rejected(self):
        release = self.build()
        outer = json.loads((release / "manifest.json").read_bytes())
        outer["payload"] = base64.b64encode(b"{}").decode()
        with self.assertRaises(Exception):
            p.verify_envelope(p.canonical_json(outer), p.load_public(self.public))
        other_private, other_public = self.root / "other-private.pem", self.root / "other-public.txt"
        p.keygen(SimpleNamespace(private_key=str(other_private), public_key=str(other_public)))
        with self.assertRaises(Exception):
            p.verify_envelope((release / "manifest.json").read_bytes(), p.load_public(other_public))
        (release / "files/mods/fixture.jar").write_bytes(b"changed")
        with self.assertRaises(ValueError):
            p.local_release(SimpleNamespace(release_dir=str(release), public_key=str(self.public)))

    def test_authorization_bytes_and_redistribution_must_match(self):
        spec = json.loads(self.spec.read_text())
        spec["files"][0]["redistributionConfirmed"] = False
        self.spec.write_text(json.dumps(spec))
        with self.assertRaises(ValueError):
            self.build()
        spec["files"][0]["redistributionConfirmed"] = True
        self.spec.write_text(json.dumps(spec))
        (self.source / "mods/fixture.jar").write_bytes(b"changed after authorization")
        with self.assertRaises(ValueError):
            self.build()

    def test_private_material_paths_and_control_files_rejected(self):
        for path in ("mods/../x.jar", "/mods/a.jar", "mods\\a.jar", "config/CON.txt", "config/a.",
                     "config/automodpack-oss/trust.json", "config/AutoModpack.json", "config/a:b.json"):
            with self.assertRaises(ValueError, msg=path):
                p.relative_path(path)
        secret = self.source / "mods/key.txt"
        secret.write_bytes(b"-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----")
        with self.assertRaises(ValueError):
            p.digest_file(secret)
        worktree = self.root / "worktree"
        (worktree / ".git").mkdir(parents=True)
        with self.assertRaises(ValueError):
            p.keygen(SimpleNamespace(private_key=str(worktree / "key.pem"), public_key=str(worktree / "public.txt")))

    def test_export_is_explicit_and_does_not_upload(self):
        release = self.build()
        export = self.root / "activation/active.json"
        with patch.object(p, "OSS", side_effect=AssertionError("network forbidden")):
            p.export_active(SimpleNamespace(release_dir=str(release), public_key=str(self.public), output=str(export)))
        self.assertEqual((release / "manifest.json").read_bytes(), export.read_bytes())
        pin = json.loads(export.with_name("server-active-release.json").read_bytes())
        self.assertEqual(hashlib.sha256(export.read_bytes()).hexdigest(), pin["manifestSha256"])
        self.assertTrue(pin["manifestUrl"].endswith("/releases/fixture-1/manifest.json"))
        with self.assertRaises(FileExistsError):
            p.export_active(SimpleNamespace(release_dir=str(release), public_key=str(self.public), output=str(export)))

    def test_upload_is_immutable_and_does_not_activate(self):
        release = self.build()
        calls = []
        class MockOSS:
            def __init__(self, endpoint, bucket, region):
                pass
            def put_immutable(self, url, stream, size, sha256):
                data = stream.read() if hasattr(stream, "read") else stream
                self_outer.assertEqual((len(data), hashlib.sha256(data).hexdigest()), (size, sha256))
                calls.append(url)
        self_outer = self
        with patch.object(p, "OSS", MockOSS):
            p.upload(SimpleNamespace(release_dir=str(release), public_key=str(self.public), endpoint="https://objects.example.test", bucket="fixture-bucket", region="cn-example"))
        self.assertEqual(2, len(calls))
        self.assertTrue(all("/releases/fixture-1/" in url for url in calls))
        self.assertFalse(any(url.endswith("/active.json") for url in calls))

    def test_activation_requires_expected_state_and_remote_verified_objects(self):
        release = self.build()
        raw = (release / "manifest.json").read_bytes()
        body = (release / "files/mods/fixture.jar").read_bytes()
        active = None
        calls = []
        class MockOSS:
            def __init__(self, endpoint, bucket, region):
                pass
            def request(self, method, url, data=None, **kwargs):
                nonlocal active
                calls.append((method, url))
                if method == "PUT":
                    active = data
                    return b""
                if url.endswith("/active.json"):
                    if active is None:
                        raise urllib.error.HTTPError(url, 404, "fixture", {}, None)
                    return active
                return raw
            def digest(self, url):
                return len(body), hashlib.sha256(body).hexdigest()
        args = SimpleNamespace(release_dir=str(release), public_key=str(self.public), endpoint="https://objects.example.test", bucket="fixture-bucket", region="cn-example", expected_active_sha256="wrong")
        with patch.object(p, "OSS", MockOSS):
            with self.assertRaises(ValueError):
                p.activate(args)
            self.assertFalse(any(method == "PUT" for method, _ in calls))
            args.expected_active_sha256 = "absent"
            p.activate(args)
            self.assertEqual(raw, active)
            args.expected_active_sha256 = hashlib.sha256(active).hexdigest()
            with self.assertRaises(ValueError):
                p.activate(args)  # Same signed sequence is not an activation advance.

    def test_oss_request_signs_dummy_credentials_and_disallows_redirects(self):
        class Response(io.BytesIO):
            status = 200
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                return Response(b"fixture")
        with patch.dict(os.environ, {"OSS_ACCESS_KEY_ID": "fixture-key", "OSS_ACCESS_KEY_SECRET": "fixture-secret"}), patch.object(p.urllib.request, "build_opener", return_value=Opener()):
            oss = p.OSS("https://objects.example.test", "fixture-bucket", "cn-example")
            oss.request("PUT", "https://objects.example.test/a.jar", b"fixture", immutable=True, size=7, sha256="0" * 64)
            self.assertEqual("true", requests[0].get_header("X-oss-forbid-overwrite"))
            self.assertEqual("7", requests[0].get_header("Content-length"))
            self.assertTrue(requests[0].get_header("Authorization").startswith("OSS4-HMAC-SHA256 Credential=fixture-key/"))
            with self.assertRaises(ValueError):
                oss.request("PUT", "https://other.example.test/a.jar", b"fixture")
        with self.assertRaises(ValueError):
            p.NoRedirect().redirect_request(None, None, 302, "fixture", {}, "https://other.example.test/")

    def test_duplicate_json_is_rejected(self):
        self.spec.write_text('{"schema":1,"schema":2}')
        with self.assertRaises(ValueError):
            p.read_json(self.spec)

    def test_renamed_updater_and_metadata_bombs_rejected_but_dependency_allowed(self):
        fixture = self.root / "renamed.jar"
        for name, metadata in (("fabric.mod.json", '{"id":"automodpack"}'),
                               ("META-INF/mods.toml", '[[mods]]\nmodId="automodpack_mod"\n'),
                               ("quilt.mod.json", '{"quilt_loader":{"id":"automodpack"}}')):
            with zipfile.ZipFile(fixture, "w", compression=zipfile.ZIP_DEFLATED) as archive:
                archive.writestr(name, metadata)
            with self.assertRaises(ValueError):
                p.reject_updater("mods/renamed.jar", fixture)
        with zipfile.ZipFile(fixture, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("META-INF/mods.toml", '[[mods]]\nmodId="fixture_compat"\n[[dependencies.fixture_compat]]\nmodId="automodpack_mod"\n')
        p.reject_updater("mods/compat.jar", fixture)
        with zipfile.ZipFile(fixture, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("fabric.mod.json", '{"id":"fixture","description":"' + "a" * (129 * 1024) + '"}')
        with self.assertRaises(ValueError):
            p.reject_updater("mods/fixture.jar", fixture)

    def test_server_identity_key_uses_distinct_private_format(self):
        secret, public = self.root / "identity.pkcs8.b64", self.root / "identity-public.txt"
        p.keygen(SimpleNamespace(private_key=str(secret), public_key=str(public), purpose="server-identity"))
        key = p.serialization.load_der_private_key(base64.b64decode(secret.read_bytes()), password=None)
        self.assertEqual(p.load_public(public).public_bytes(p.serialization.Encoding.DER, p.serialization.PublicFormat.SubjectPublicKeyInfo),
                         key.public_key().public_bytes(p.serialization.Encoding.DER, p.serialization.PublicFormat.SubjectPublicKeyInfo))
        self.args.private_key = str(secret)
        with self.assertRaises(ValueError):
            self.build()

    def test_validate_spec_has_no_writes_and_validates_existing_retry(self):
        args = SimpleNamespace(spec=str(self.spec), source_root=str(self.source), base_url=self.args.base_url)
        with patch.object(p, "OSS", side_effect=AssertionError("network forbidden")), patch.object(p, "keygen", side_effect=AssertionError("key generation forbidden")):
            p.validate_spec(args)
        self.assertFalse((self.root / "out").exists())
        release = self.build()
        args.release_dir, args.public_key = str(release), str(self.public)
        p.validate_spec(args)
        spec = json.loads(self.spec.read_text())
        spec["managementScopeVersion"] = "scope-changed"
        self.spec.write_text(json.dumps(spec))
        with self.assertRaises(ValueError):
            p.validate_spec(args)

    def test_v4_header_signing_matches_official_sdk_six_vectors(self):
        fixture = json.loads(Path(__file__).with_name("fixtures").joinpath("oss-v4-header-vectors.json").read_text(encoding="utf-8"))
        when = p.dt.datetime.fromisoformat(fixture["signingTime"])
        for vector in fixture["vectors"]:
            actual = p.sign_v4_headers(vector["method"], vector["url"], fixture["bucket"], fixture["region"], fixture["keyId"],
                fixture["secret"], vector["securityToken"], vector["headers"], when)
            self.assertEqual({k.lower(): v for k, v in vector["expectedHeaders"].items()}, {k.lower(): v for k, v in actual.items()})


if __name__ == "__main__":
    unittest.main()
