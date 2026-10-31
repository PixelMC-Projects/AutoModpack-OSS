# Local provisioning and explicit publication

All examples are placeholders. Keep real keys, bucket names, endpoints, account lists and server configuration outside the repository. This fork does not read or convert an existing production AutoModpack inventory. Test with synthetic files before operating your own authorized release.

## Separate credentials

| Material | Location | Purpose |
| --- | --- | --- |
| Publisher Ed25519 private key (PEM PKCS#8) | Restricted local publishing workstation only | Sign exact release content |
| Publisher public key (base64 X.509 DER) | Locally provisioned trust profiles | Verify releases |
| Server identity Ed25519 private key (base64 PKCS#8 DER) | Server secret environment only | Sign authenticated login metadata |
| Server identity public key | Locally provisioned trust profiles | Bind server identity and consent |
| Publisher OSS access credentials | Publisher environment only | Upload/read the chosen release prefix and explicitly update active pointer |
| Server OSS read credentials / STS token | Server environment only | `GetObject` for the chosen active/release prefix and short-lived GET URLs |

Server RAM policy must not permit `PutObject`, `DeleteObject`, policy changes, or reads outside its intended prefix. It does not need bucket listing. Keep the bucket private; public-read is not a fallback. Separate publisher and server RAM identities. The server's read key cannot publish a release, and its identity key is not the publisher signing key.

## Trust profile

An unconfigured client needs no file. An explicit empty profile is `{"schema":1,"packs":[]}`. Provision a configured client's `config/automodpack-oss/trust.json` through a trusted local distribution step:

```json
{
  "schema": 1,
  "packs": [{
    "packId": "example-pack",
    "identity": "Example server",
    "publicKey": "<publisher public X.509 base64>",
    "serverIdentityPublicKey": "<independent server public X.509 base64>",
    "manifestUrl": "https://objects.example.test/packs/example/active.json",
    "allowedHosts": ["objects.example.test"],
    "allowedRoots": ["mods", "config", "resourcepacks"],
    "minimumSequence": 1
  }]
}
```

Use your OSS HTTPS bucket hostname, including its region, in your private profile. An OSS default HTTPS domain works; no separate domain, reverse proxy or web authorization service is needed. `manifestUrl` anchors the immutable release directory; the authenticated server advertises its exact pinned `releases/<id>/manifest.json`. The same trusted public profile is available on the server. Give new installations a current `minimumSequence`; `1` above is only an example. Unknown keys are rejected rather than offered as a clickable bypass.

## Publisher

Python 3.11+ and `pip install -r tools/requirements.txt` are required. Use a restricted directory outside every Git worktree; the key tool refuses to create a private key inside a worktree. On Windows restrict that directory's inherited ACL to the operator; Unix key files are created with mode `0600`. Never print private-key contents into shell history or logs.

```powershell
python tools/oss_publisher.py keygen --private-key '<private-directory>/publisher.pem' --public-key '<private-directory>/publisher-public.txt'
python tools/oss_publisher.py identity-keygen --private-key '<private-directory>/server-identity.pkcs8.b64' --public-key '<private-directory>/server-public.txt'
```

Inject the identity private file through `AUTOMODPACK_OSS_SERVER_IDENTITY_PKCS8` using your secret manager. The publisher PEM is a different file and stays off the server.

Create an explicit JSON specification; no recursive “upload the whole instance” option exists:

```json
{
  "schema": 1,
  "packId": "example-pack",
  "releaseId": "release-1",
  "sequence": 1,
  "consentProtocolVersion": 1,
  "managementScopeVersion": "scope-1",
  "files": [{
    "path": "mods/example.jar",
    "source": "mods/example.jar",
    "size": 4,
    "sha256": "<actual lowercase SHA-256 of the exact authorized file>",
    "redistributionConfirmed": true,
    "license": "<license or explicit redistribution grant>",
    "provenance": "<author/source and permission record>"
  }]
}
```

Each `source` is a regular file relative to the selected source root; links/reparse targets are refused. `path` is the exact managed destination. The operator confirms redistribution permission and records license/provenance for every file. The tool checks supplied size/hash before signing and copied bytes again. A declaration does not create legal permission. Do not list credentials, tokens, private keys, personal configs or the updater. Recognizable PEM private-key material is refused; this additional check is not a complete secret detector.

```powershell
python tools/oss_publisher.py build --spec '<private-directory>/release-spec.json' --source-root '<authorized-source>' --private-key '<private-directory>/publisher.pem' --output '<private-directory>/built' --base-url 'https://objects.example.test/packs/example'
```

This creates `<output>/<releaseId>/manifest.json`, `files/`, a local publish plan and a local redistribution record. Existing release directories are never overwritten. `releaseId` and `sequence` must advance for every change; do not reuse a sequence with different payload bytes. Review this result before uploading.

Inject publisher OSS credentials through `OSS_ACCESS_KEY_ID`, `OSS_ACCESS_KEY_SECRET`, and optionally `OSS_SECURITY_TOKEN`; these differ from the server's read-only variables. Explicitly upload:

```powershell
python tools/oss_publisher.py upload --release-dir '<private-directory>/built/release-1' --public-key '<private-directory>/publisher-public.txt' --bucket '<your-bucket>' --endpoint 'https://objects.example.test' --region '<your-region>'
```

Publisher HTTP requests use OSS V4 HMAC-SHA256 header authentication with an explicit region. Every versioned PUT sets `x-oss-forbid-overwrite: true`. A repeated upload reuses an existing object only after downloading and verifying exact size/SHA-256. Files upload before the signed immutable manifest. Upload never changes `active.json`. Bucket versioning/retention can add operational recovery; signatures/hashes remain the client content trust boundary. A reusable private agent entry is documented in [operator CLI](oss-operator-cli.md); it loads private credentials into only the child publisher's process environment and avoids retyping them.

Export the active envelope and an external server release pin as a separate local review step:

```powershell
python tools/oss_publisher.py export-active --release-dir '<private-directory>/built/release-1' --public-key '<private-directory>/publisher-public.txt' --output '<private-directory>/review/active.json' --server-pin-output '<private-directory>/review/server-active-release.json'
```

The pin contains schema, pack ID, release ID, sequence, immutable manifest URL and SHA-256 of the entire signed envelope. Nothing is uploaded or deployed by this command. Manually deploy the pin together with the matching server pack during your maintenance process. The server reads its pin at startup; it does not follow a changed remote `active.json` while running. The tool does not restart it or write its files.

OSS activation is an optional separate operator action. Supply `absent` for the first active object, or the SHA-256 of the current active envelope you inspected:

```powershell
python tools/oss_publisher.py activate --release-dir '<private-directory>/built/release-1' --public-key '<private-directory>/publisher-public.txt' --bucket '<your-bucket>' --endpoint 'https://objects.example.test' --region '<your-region>' --expected-active-sha256 absent
```

Activation re-verifies local signatures/files, the immutable remote manifest, all remote file hashes, expected prior active digest, same-pack identity and a strictly higher sequence. It writes only the OSS active object, never server files, and does not change a running server's pin. Use one activation operator at a time: expected digest is a preflight check, not a distributed compare-and-swap; concurrent activation may cause a stale pointer or denial of service. Clients still reject rollback. Intentional content rollback is published with a new, higher sequence.

## Server and client operation

Configure the server using a private absolute configuration path in `AUTOMODPACK_OSS_SERVER_CONFIG`; see [server security](oss/server-security.md) for exact fields, local release pin, account/IP checks, throttles and kill switch. Supply only read-only OSS credentials and the independent server identity secret. Disable new downloads by activating the configured external kill-switch file or removing/restricting the read credential. Already issued URLs can remain usable until short expiration; immediate universal URL revocation is not promised.

Clients read local trust and display an authorization decision after authenticated login. Revoking/disabling management prevents future staging and pending application. Completed downloads wait in `.automodpack-oss`. Players manually exit/relaunch; bootstrap applies before Forge mod discovery, then players manually reconnect. Modified files are preserved as conflicts. Keep backups and the highest-seen ledger for recovery/downgrade protection; do not delete them as routine repair.

This development round performs only local fixture tests and builds. It does not upload actual mod files, touch production servers/clients, or start a game GUI.
