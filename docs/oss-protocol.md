# OSS signed release protocol, version 1

This document describes the fork's trust and installation rules. Server session/authentication details live in [server security](oss/server-security.md).

## Trust boundary

The local publishing workstation signs releases with Ed25519. Its private key must never be copied to a game server, a repository, OSS, or a client. The workstation may connect to OSS for publication; physical air-gapping is not assumed. An independently generated Ed25519 server identity key proves the locally provisioned server identity; it cannot sign pack releases. OSS access keys authorize object access, not executable content. Clients never import a public key from a server handshake and provide no “accept this unknown key” override. Profiles using the same key for publishing and server identity are rejected.

The client reads `config/automodpack-oss/trust.json`. Missing configuration is an empty trust store. Each profile pins `packId`, a display `identity`, publisher `publicKey` and `serverIdentityPublicKey` (base64 X.509 DER), `manifestUrl`, exact lowercase `allowedHosts`, `allowedRoots`, and positive `minimumSequence`. Only the following root names may be selected: `mods`, `config`, `resourcepacks`, `shaderpacks`, `defaultconfigs`, `kubejs`, `scripts`. An omitted root grants no management permission. `config/automodpack*` control files and `mods/automodpack*` are reserved and cannot be release-managed. No pack may manage the updater's control directory `.automodpack-oss`.

The reviewed transport uses the already authenticated Minecraft login connection. There is no required external authorization HTTP endpoint. An optional legacy `authorizationEndpoint` trust field is unused by this transport; if absent the compatibility API refuses all HTTP authorization endpoint hints. A trusted `manifestUrl` such as `.../active.json` anchors the permitted immutable manifest directory. The server offer must point to the same directory's `releases/<releaseId>/manifest.json`, as checked by `validateManifestUrl` after checking server identity. The game server selects its release from an externally provisioned local pin at startup; a remote active-pointer change does not silently switch a running game server.

## Signed manifest

`active.json` and an immutable `releases/<releaseId>/manifest.json` contain the identical UTF-8 JSON envelope:

```json
{"schema":1,"payload":"<base64 exact UTF-8 payload bytes>","signature":"<base64 Ed25519 signature over payload bytes>"}
```

The payload is a JSON object:

```json
{
  "schema": 1,
  "packId": "example-pack",
  "releaseId": "release-1",
  "sequence": 1,
  "createdAt": "2026-01-01T00:00:00Z",
  "consentProtocolVersion": 1,
  "managementScopeVersion": "scope-1",
  "files": [{
    "path": "mods/example.jar",
    "size": 4,
    "sha256": "<64 lowercase hex digits>",
    "url": "https://objects.example.test/packs/example/releases/release-1/files/mods/example.jar"
  }]
}
```

The signature is over the original decoded bytes; consumers do not reserialize JSON before checking it. Both envelope and payload reject duplicate JSON keys. Pack/release/scope tokens use `[A-Za-z0-9][A-Za-z0-9._-]{0,95}`. Sequence is a positive signed 64-bit integer. Consent protocol is a positive signed 32-bit integer. File count is 1–10,000; one file may contain at most 4 GiB; total signed bytes are at most 16 GiB. The envelope is at most 8 MiB and JSON nesting is bounded. The login offer contains only manifest URLs and bounded session metadata; it does not embed this manifest or its file list.

Canonical URLs are HTTPS, port 443 (or omitted), without credentials, query, fragment, backslashes or dot traversal. Hosts must exactly match the local OSS allowlist; wildcard/suffix matching is not used. No HTTP redirects are followed. A short-lived transport URL may add OSS authorization query parameters, but must retain the signed canonical scheme, host, normalized port and exact raw object path. Query credentials never enter persisted manifests, installer journals or logs.

Paths use `/` below a permitted root. Absolute paths, `..`, empty/`.` segments, Windows drive/ADS characters, backslashes, reserved device names, control characters, trailing dots/spaces, duplicate paths and case collisions are rejected. All existing ancestors are checked for symbolic links, junctions and other reparse targets. Files move within the chosen game instance; no arbitrary user-supplied destination is accepted. Installation uses atomic file moves and fails closed where the filesystem does not support them.

JARs under `mods/` additionally undergo bounded main-mod metadata inspection for Fabric, Quilt, Forge and NeoForge. The updater's own declared mod ID is rejected even if the JAR was renamed. Dependency declarations referring to AutoModpack do not make an unrelated compatibility mod the updater. Individual metadata entries are limited to 128 KiB, with a cumulative metadata budget; inspection does not extract archive members into the game directory. Updating the synchronization mod is a separate manual operation.

## Consent and replay resistance

Local consent binds the authenticated account, server identity fingerprint, publisher fingerprint, pack, protocol version and management scope version. A normal release increment reuses the grant; an identity/protocol/scope change requires a new local decision. The grant is checked again before applying pending content. Revocation removes the pending pointer without applying staged bytes. Only data explicitly authorized under the signed file list is managed; no directory-wide ownership is inferred.

File management applies to the selected game instance. Mojang account authentication authorizes the current online download session and is recorded with that consent. At pre-load there is no current authenticated online account; bootstrap checks whether this instance still has any matching active local grant. Different accounts using the same game directory share its installed mods. To stop this instance's pending/future management, disable management or revoke all matching local grants. This is not per-account filesystem isolation or startup-time reauthentication.

The verifier enforces the locally provisioned `minimumSequence`. The installer persists the highest verified sequence and payload SHA-256 for each pack, even before an update is installed; lower releases and different payloads reusing a sequence are rejected. Installed state also retains identity and sequence. Server messages cannot reset these records. A first installation has no history and therefore needs a current minimum sequence from the trusted profile distributor. Deleting local state or reinstalling an old trust profile removes history; an attacker controlling local files is outside this remote-server threat boundary. Key/identity rotation or an intentional downgrade requires explicit local maintenance; no network request performs it.

## Staging, installation and recovery

`StagedInstaller.prepare` validates consent and the target plan without changing live managed files. Each accepted download is streamed into `.automodpack-oss/staging/<id>/files`, limited to its signed length, hashed, flushed and moved into place only on a match. Reconnecting can reuse only complete staged files after re-verifying their signed manifest, consent tuple, size and hash. Partial files restart from zero; HTTP range resumption is not implemented.

After every file is present, `finish` atomically records a pending stage. The next launcher invocation calls `applyPending` before mod discovery. It re-verifies the manifest with the current local trust store, highest-seen sequence, current local consent, all target files and staged bytes. Byte-identical existing files may be adopted after consent without rewriting them. Replacements/removals are permitted only for unchanged previously managed files. Unknown differing files or user changes are preserved and block the transaction. A scope reduction cannot silently remove files outside the newly authorized roots.

Before moving files, the installer flushes an atomic transaction journal with the old ownership ledger and each operation. Replaced/removed originals move to `.automodpack-oss/backups/<id>/files`; verified new files then move into the instance. The new ledger is flushed before marking the journal committed. Completed backups are retained. On an interrupted uncommitted transaction, the next pre-load invocation restores originals and the old ledger. Recovery removes only bytes proven to be the transaction's new files. If a backup is missing/corrupt or recovery finds subsequent personal edits, it preserves the available evidence and throws; bootstrap must stop mod loading for manual recovery. It must never catch this exception and continue with a partially changed instance.

Atomic moves and forced file writes protect against process interruption. Filesystem/hardware failure, external concurrent writes and administrator changes can still require manual recovery. The local installer lock serializes this instance's own installer operations; it cannot lock out arbitrary editors or filesystem administrators.

Neither the security core nor the publisher starts, restarts, exits or reconnects a game process. Updater binaries and protected control files are distributed manually; the ordinary upstream updater path is disabled in this fork.

## Limits of the guarantee

A compromised game server holding only the independent identity key and read-only OSS credentials cannot produce a new accepted executable without the publisher signature. It can deny service, present stale releases (rejected below local sequence policy), leak already issued download URLs, and read objects its RAM policy allows. A valid signature establishes publisher authorization, not that a mod is bug-free or benign. A compromised release-signing private key, malicious trusted publisher, compromised client OS or deliberate local trust replacement falls outside the server-compromise guarantee.
