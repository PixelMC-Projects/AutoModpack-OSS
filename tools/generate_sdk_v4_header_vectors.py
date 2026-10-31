"""Regenerate fake-key, network-free V4 header vectors using the official optional OSS V2 SDK."""
import argparse
import datetime
import importlib.metadata
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

from alibabacloud_oss_v2.signer import SignerV4
from alibabacloud_oss_v2.types import Credentials, HttpRequest, SigningContext


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    when = datetime.datetime(2026, 1, 1, 0, 0, 0, tzinfo=datetime.timezone.utc)
    cases = [
        ("GET", "/packs/example/active.json", {}, None),
        ("PUT", "/packs/example/releases/r1/files/mods/a.jar", {"Content-Type": "application/octet-stream", "Content-Length": "7", "x-oss-forbid-overwrite": "true", "x-oss-meta-sha256": "0" * 64}, None),
        ("GET", "/packs/%E4%B8%AD%E6%96%87/a%20%2B%25.jar", {}, "fixture-token+/%=="),
        ("PUT", "/packs/metadata.json", {"Content-Type": "application/json", "x-oss-forbid-overwrite": "true"}, "fixture-token+/%=="),
        ("HEAD", "/packs/example/releases/r1/manifest.json", {}, None),
        ("GET", "/packs/a_b/colon%3Aname.jar", {}, None),
    ]
    vectors = []
    for method, path, headers, token in cases:
        url = "https://fixture-bucket.oss-cn-example.aliyuncs.com" + path
        request = HttpRequest(method, url, dict(headers))
        context = SigningContext(product="oss", region="cn-example", bucket="fixture-bucket", key=unquote(urlsplit(url).path[1:]),
            request=request, credentials=Credentials("fixture-key", "fixture-secret", token), signing_time=when)
        SignerV4().sign(context)
        vectors.append({"method": method, "url": url, "headers": headers, "securityToken": token,
                        "expectedHeaders": dict(request.headers)})
    output = {"testOnly": True, "generatedBy": "alibabacloud-oss-v2 " + importlib.metadata.version("alibabacloud-oss-v2"),
              "signingTime": when.isoformat(), "bucket": "fixture-bucket", "region": "cn-example", "keyId": "fixture-key",
              "secret": "fixture-secret", "vectors": vectors}
    target = Path(args.output)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Generated six fake-key official SDK V4 header vectors; no network access.")


if __name__ == "__main__":
    main()
