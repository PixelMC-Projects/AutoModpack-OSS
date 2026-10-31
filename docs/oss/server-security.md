# Forge 1.20.1 server / 服务端安全边界

This fork does not require Manager, a domain, a reverse proxy, or a second public port. The Minecraft login channel carries signed metadata only. Every file byte, including the active manifest, comes directly from OSS HTTPS; redirects and local-server/CDN fallbacks are forbidden.

本分支不需要 Manager、自有域名、反代或第二个公网端口。游戏登录通道只传签名元数据和短期下载地址，文件字节一律直连 OSS HTTPS，不走游戏服或平台 CDN。

## Trust and configuration

Keep all real configuration and secret values outside the checkout and game directory. Set `AUTOMODPACK_OSS_SERVER_CONFIG` to an absolute external JSON path. Missing, disabled, incomplete, invalid-key, or unverified-active configuration disables synchronization. Normal Forge login remains available; legacy upstream hosting is disabled unconditionally.

External JSON fields:

| Field | Meaning |
| --- | --- |
| `enabled` | Must explicitly be `true` |
| `packId` | Exact locally provisioned pack identity |
| `activeReleaseFile` | Absolute external local activation pin path; loaded once at startup |
| `trustStore` | Absolute external trust JSON path, same publisher/server public keys as provisioned clients |
| `bucket`, `region` | Explicit OSS bucket and region; only `https://<bucket>.oss-<region>.aliyuncs.com` is signed |
| `killSwitchFile` | Absolute external latch-file path; creating it revokes sessions and stops further URL issuance |
| `urlLifetimeSeconds` | Default 180, permitted 120–300 |
| `sessionLifetimeSeconds` | Default 900 after acceptance, permitted 180–3600 |
| `activeRefreshSeconds` | Default 0 (startup-only immutable snapshot); optional health recheck 300–3600 |
| `accessKeyIdEnvironment`, `accessKeySecretEnvironment` | Default `AUTOMODPACK_OSS_READ_KEY_ID` and `AUTOMODPACK_OSS_READ_KEY_SECRET` |
| `securityTokenEnvironment` | Optional STS token, default `AUTOMODPACK_OSS_READ_SECURITY_TOKEN` |
| `serverIdentityKeyEnvironment` | Default `AUTOMODPACK_OSS_SERVER_IDENTITY_PKCS8`, base64 PKCS8 Ed25519 private key |

The **publisher private key remains on the user's local publication machine**. The separate server identity key authenticates login metadata and must match the independently provisioned client public key. Compromising that identity key or the game server cannot forge a new publisher-signed manifest. Public keys must be provisioned out of band; keys sent by the server never establish trust.

Use an OSS RAM policy with only `GetObject` on the one active pointer and the intended immutable release prefix. Do not grant ListObjects, PutObject, DeleteObject, ACL, or bucket-policy mutation. OSS signing uses V4/HMAC-SHA256, including optional STS security tokens. SDK-equivalent offline test vectors cover UTF8, spaces, plus, literal percent, and STS encoding; they contain synthetic credentials only.

## Authentication and handshake evidence

Forge 1.20.1 patches `ServerLoginPacketListenerImpl` so successful `MinecraftSessionService.hasJoinedServer(...)` verification enters `NEGOTIATING`. Its next `tick()` invokes `NetworkHooks.tickNegotiation`, then `HandshakeHandler.tickServer` begins Forge mod-list/channel compatibility checks. `OnlineAuthenticationProofMixin` redirects only that actual session-service invocation in `ServerLoginPacketListenerImpl$1.run`, retaining a proof only for a non-null verified profile. The outer login mixin checks that exact UUID/name, online mode, a dedicated non-memory connection, and `NEGOTIATING` before sending any offer. Self-reported UUIDs, offline mode, integrated owner shortcuts, and authentication-unavailable fallback produce no synchronization authorization.

The initial query `0x6F535331` requests a random client nonce. The subsequent Ed25519-signed offer and each signed download response bind that nonce, pack/release/sequence, and the exact increasing query ID. A stale reply ID does not match the outstanding transaction and cannot advance this protocol. A query has one response only; subsequent messages require the next ID. Source and compiled-mixin checks are required when upgrading Minecraft/Forge; this integration is intentionally limited to Forge 1.20.1.

The authorization page has a bounded 180-second window. Declining, unavailable trust, errors before acceptance, or `ready` continue ordinary Forge compatibility checks. Acceptance starts a bounded download session. During this phase the outer tick is paused before Forge compatibility rejection and its original 600-tick login timeout. Clients send metadata keepalive every 20 seconds while downloading; the server separately enforces 60 seconds idle and the configured total session deadline. Completion disconnects only this login and instructs the user to restart/join manually. No process exit, game relaunch, restart, or reconnect is automatic.

The local `activeReleaseFile` JSON contains `schema:1`, `packId`, `releaseId`, `sequence`, `manifestSha256` (SHA256 of the exact complete signed envelope bytes), and immutable `manifestUrl`. It is read once at startup. Its URL must be the locally trusted active-pointer directory followed by `releases/<releaseId>/manifest.json`. The immutable manifest is fetched, publisher-verified and cached. Optional health rechecks re-fetch only that same pinned URL and verify its exact digest and release tuple. The server never follows a changing remote `active.json`, lists a bucket, or chooses a newest object. Uploading or remotely activating a candidate cannot change what an already-running game server announces.

Deploy in this order: upload the new immutable release; stage the matching game-server update and exported server pin; during the planned maintenance replace both; start the game server manually; only then do joining clients see the new release. The code does not claim that an operator-selected pin independently proves every running server mod matches the pack. Editing the pin while the server is running has no effect until the next deliberate startup. Client trust configuration stays stable between releases. Client persistent sequence checks and the provisioned minimum also protect across restarts.

## Abuse controls and remaining OSS risk

Sessions bind the real authenticated account, connection's remote IP, active release/sequence, and server-held random capability. At most two live sessions per account, 64 per IP (NAT allowance), and 1024 globally are retained. A shared NAT does not collapse all accounts into one identity. New-session limits are eight per account and 128 per IP per 15 minutes. File URL issuance is limited to 240/account/minute, 2400/IP/minute and 10000 globally/minute; metadata to 360/account/minute, 3600/IP/minute and 20000 globally/minute. New grants also have a 2048/minute global ceiling, and rate-limit registries have bounded sizes. Each exact active-manifest path gets at most three issued URLs per session. One outstanding query per connection serializes metadata, and the client downloads serially. Session expiry, disconnect, explicit revocation, activation changes, and the kill switch stop further issuance.

**These are URL-issuance controls, not a download-byte, concurrency, or precise cost ceiling.** A signed URL can be reused during its remaining 2–5 minute validity; recipients may copy it or issue parallel requests. An attacker who steals the RAM read key can sign URLs outside this application and bypass every local rate limit. A read-only principal cannot revoke its own permissions or reliably cancel already issued URLs. Use OSS-side policy/rate controls, monitoring and administrator credential rotation/revocation for those risks; none are presented as guaranteed fee caps.

Creating `killSwitchFile` latches this runtime closed within the periodic one-second check (also checked on protocol operations). Removing the file does not reopen the already-latched runtime. An optional external revocation notification can be configured using `externalRevocationEndpoint` (HTTPS) and `externalRevocationTokenEnvironment` (external administrator service bearer). It sends a bounded asynchronous POST containing `event`, `packId`, `time`. This optional service is **not needed for ordinary synchronization**. It must hold its own administrator authority to revoke RAM/OSS access; a 2xx acknowledgement alone is not proof that OSS access was revoked. Local code never calls PutBucketPolicy/ACL or pretends the read key can do so. If notification fails, an OSS administrator must revoke/rotate the read credentials and confirm the policy's actual effect.

## Local verification boundary

Unit and fixed synthetic official-SDK signature tests do not prove a live Mojang/Forge login, real OSS policy, production TLS, client rendering, or restart/import acceptance. This implementation is built and tested locally only until those tests are explicitly authorized. No production server is contacted or restarted by these tests.
