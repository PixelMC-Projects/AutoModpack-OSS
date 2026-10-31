# AutoModpack OSS

面向 **Minecraft 1.20.1 / Forge 47** 的 AutoModpack 定制版：玩家在已认证的游戏登录连接上获取更新提示，经明确授权后，从私有阿里云 OSS 下载已签名的整合包文件。文件先进入暂存区，玩家手动重启后，才在模组加载前安装。

发布签名私钥仅保存在本地发布机；游戏服务器持有 OSS 只读凭据和独立的服务器身份密钥。客户端预配公钥、OSS 主机和最低发布序号，逐文件检查签名清单中的路径、大小和 SHA-256。游戏服失陷后仍不能签出新的可接受文件；服务器和 OSS 均可能造成不可用，发布私钥失陷则需要人工更换信任配置。

未配置信任时不信任任何服务器。首次授权先展示管理说明，同意按钮等待 10 秒后才可点击，授权绑定服务器身份、发布者、整合包、管理范围版本及协议版本。个人文件冲突会保留并阻止安装；完全相同的文件可以在授权后登记管理。支持撤销授权、停止服务端续签，以及断线后复用已完整校验的暂存文件。不自动重启、退出、启动或重连游戏，不使用上游自更新，不依赖 Manager、额外域名或反向代理。

部署顺序：

1. 将同一 Forge 1.20.1 构建放入客户端与服务端 `mods/`。目前仅做本地开发与自动化验证，尚未完成真实游戏验收。
2. 在仓库外生成独立的发布密钥和服务器身份密钥；预配客户端 `config/automodpack-oss/trust.json`。默认信任文件为 `{"schema":1,"packs":[]}`。
3. 配置服务端外部配置路径和 OSS 只读环境变量，使用现有 Minecraft 登录连接提供短期下载授权。
4. 发布机仅列入已获分发许可的明确文件，核对哈希后构建并上传不可变版本；另一步明确导出、激活发布。游戏服仅采用人工部署的本地版本 pin。
5. 玩家连接、阅读并同意管理范围，下载后自行重启，再手动加入服务器。

配置与操作见 [部署和发布](docs/oss-deployment.md)，安全边界与协议见 [协议说明](docs/oss-protocol.md)，测试和构建见 [开发验证](docs/oss-testing.md)。示例均使用占位信息，不含生产配置或密钥。

## English

An AutoModpack fork for **Minecraft 1.20.1 / Forge 47**. Authenticated Minecraft login queries deliver update metadata and short-lived private Alibaba Cloud OSS download URLs. Players explicitly authorize management; verified files are staged and installed before mod loading on the next manual launch.

The local publishing workstation alone holds the release-signing private key. Game servers receive read-only OSS credentials and a separate identity key. Clients pin both public keys, OSS hosts, permitted roots and a minimum release sequence. Signed manifests bind pack/version, consent scope, relative paths, sizes and SHA-256 hashes. A compromised game server cannot authorize new unsigned files. Publisher-key compromise requires a manual trust migration.

No configuration means no trusted servers. First consent shows the management details and enables Agree only after 10 seconds. Local edits cause a preserved-file conflict; byte-identical files may be adopted after consent. Consent is revocable, complete staged files survive reconnects, and no automatic restart, exit, launch or reconnect occurs. Upstream self-updating is disabled. No Manager, extra domain or reverse proxy is required.

Install the supported build on client and server; provision trust locally, configure read-only server access, build an explicitly licensed file list, upload an immutable signed release, then explicitly export/activate it. Servers adopt a manually deployed local release pin at startup. Players consent, download and restart manually. See [deployment](docs/oss-deployment.md), [protocol/security](docs/oss-protocol.md), and [validation](docs/oss-testing.md). Current status is local development and automated verification; real-game acceptance is pending.

## Upstream and license

Based on [Skidamek/AutoModpack](https://github.com/Skidamek/AutoModpack), tag `v4.0.7`, commit `8278844b8d198e68bee9d4c2db2dd65744dced36`. The original project and contributors retain their attribution. This fork keeps [LGPL-3.0](LICENSE) and the bundled [third-party notices](LICENSES). It is a separate OSS distribution workflow; upstream installation/update instructions do not describe this fork.
