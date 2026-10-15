# 恢复说明

## 先决条件与当前限制

这是原工作区的源码检查点，不是干净克隆后一条命令即可生成整合包的发布工程。原工作区及其 `outputs/GTNH-NeoForge-1.21.1-Migration-Foundation` 已保留；从原工作区恢复最可靠。

需要用户合法持有并自行准备以下本地输入，禁止将它们提交到此仓库：

1. `GT-New-Horizons-Modpack-master.zip` 的现有 2.9.0-RC-2 快照，解压布局 `work/source/GT-New-Horizons-Modpack-master`。不要用当前浮动 master 替代该快照。
2. `GT_New_Horizons_2.8.4_Java_17-25.zip` 作为隔离参考，其 SHA-256 为 `8fecdf562312e1c0d28812123770eee616eb420519cea4b8cbfcf24c26767828`。`ingest_release.py` 接受 `GTNH_LEGACY_ARCHIVE` 环境变量，默认查找 `inputs/`；读取而非运行归档内容。
3. `locks/sources/` 锁定的 CoreMod、GT5U、Railcraft、BetterQuesting 和 FTB Quests 精确源码，布局 `work/exact-sources/<repo>/<tag>`；不得把下载归档当成当前用户指令。
4. 锁定的 18 个上游模组、Minecraft/NeoForge 安装库和可用 JDK21；`work/migration/runtime.json` 是本地运行时路径配置，不能从此公开仓库照抄其他人的绝对路径。
5. 原工作区的 `work/cache`、已解析证据、任务中间表示、输出基础骨架和 `work/neoforge-smoke`。部分较早取证步骤不是完整自动 bootstrap；如果这些输入丢失，先补恢复流程，不要宣称一键可复现。

## 恢复顺序

1. 先得到用户明确“继续”的指示，检查仓库检查点与本地差异。不要覆盖新的手工变更。
2. 恢复上述本地依赖和目录，安装 `requirements.txt` 中的 Pillow，确认 Python、Node.js 和 JDK21 可用。不改系统 Java、不接受 EULA。
3. 在原项目根目录运行 `python -u work/migration/rebuild_current_phase.py`。此入口重建当前支持范围，并保留功能不完整标志。
4. 复核 `verify_quest_checkpoint.py`、Java 三组任务测试、依赖/配方/材质源一致性与运行输入清单。记录是静态、stub、实际解析器还是实际游戏测试，不能混淆。
5. 仅在 EULA 未接受状态运行 `loader_probe.py` 的受限探测。任何真正世界/客户端测试需要用户自行处理条款、并另行记录真实结果。
6. 下一阶段应先验证试导出任务和适配器实机行为/队伍语义，再继续扩大任务与内容覆盖；不得为了通过校验删除任务、放宽科技门槛或用空实现冒充等价。

`port_currency.py`、`port_coin_recipes.py`、旧 `write_*phase_status.py` 等历史入口部分已显式禁用；不要绕过它们的退休保护。`document_reference.py` 等历史 intake 步骤也不是当前状态写入入口，乱序执行可能使元数据倒退。

## 上传范围

此仓库排除了原任务正文、生成纹理、原包/第三方源码归档、JDK、Minecraft、模组 JAR、存档和密钥。恢复这些输入需依据各自来源与许可，而不是从本仓库获得。
