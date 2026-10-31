# Local build and security verification

Target: Minecraft 1.20.1, Forge 47, Java 17 bytecode. Use JDK 25 to run the Gradle 9.6.1 wrapper and an installed/resolved Java 17 toolchain for target compilation. The fork is based on upstream v4.0.7. A local build is not real-game acceptance. No real server, client instance, OSS bucket or key is used by these tests.

```powershell
./gradlew.bat :core:test --console=plain
python -m unittest discover -s tools -p 'test_oss_publisher.py' -v
./gradlew.bat :1.20.1-forge:build --console=plain
```

Use the task names present in the checked-out Stonecutter configuration. Run one Gradle coordinator at a time. Proxy and Windows local-socket workarounds, if needed on the build workstation, belong in that invocation's environment rather than project defaults.

`OssSecurityTest` uses fresh temporary Ed25519 keys and synthetic bytes. It covers missing trust, unknown signing keys, payload tampering, independent server identity keys, signed unsafe paths/reserved control files, hosts, oversized entries, duplicate/case-colliding paths/JSON keys, canonical/transport binding, minimum/highest-seen sequence, same-sequence equivocation, hash/size failures, revoked consent, adoption without rewriting, owned-file update/removal, personal files and late conflicts, interrupted transaction recovery, stage reuse, symbolic links and Windows junctions. Symlink creation may be skipped where privileges are missing; the separate Windows junction test does not require symlink privilege. Real minimal ZIP fixtures test renamed updater rejection, bounded metadata and allowing an unrelated mod that merely depends on AutoModpack.

Publisher tests mock all OSS calls and use temporary files/keys outside the checkout. They cover authorized bytes/redistribution declarations, tampering/unknown keys, immutable output, unsafe/reserved paths, recognizable private-key rejection, key locations, explicit export and release pin, immutable upload without activation, active digest/sequence checks, remote-object verification, request signing and refused redirects. Six V4 header vectors were generated offline with the official `alibabacloud-oss-v2` 1.4.1 SDK using clearly marked fake credentials; the runtime publisher matches their headers exactly, including STS, Unicode/reserved keys and immutable PUT headers. Regeneration uses the optional SDK and `tools/generate_sdk_v4_header_vectors.py`; normal tests only read the committed vectors and need no SDK. Their “uploaded/activated” console lines refer only to mocks.

Manual acceptance still needs the real reviewed Forge client/server pair: authenticated login, first-consent countdown/details, rejection/revocation, responsiveness during large downloads, disconnect/rejoin with cached complete files, manual restart only, pre-mod-load installation, edited-file conflicts, and a second manual connection with matching content. Also verify untrusted servers, old manifests, changed keys/hosts/paths, offline-mode identity claims and disabled upstream download/update/restart routes cannot bypass OSS policy. These are not claimed complete by standalone core tests.
