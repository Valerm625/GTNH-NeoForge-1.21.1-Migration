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

1. 用户已明确要求继续；恢复时先检查仓库检查点与本地差异，不要覆盖新的手工变更。
2. 恢复上述本地依赖和目录，安装 `requirements.txt` 中的 Pillow，确认 Python、Node.js 和 JDK21 可用。不改系统 Java、不接受 EULA。
3. 在原项目根目录运行 `python -u work/migration/rebuild_current_phase.py`。此入口重建当前支持范围，并保留功能不完整标志。
4. 复核 `verify_quest_checkpoint.py`、Java 任务解析、匹配器与适配器测试、依赖/配方/材质源一致性与运行输入清单。当前阶段检查还包括检索引擎、进度 codec、任务顺序规划器、typed JSON 读取器、身份 envelope、模型组合、库存观察器、真实 Sponge 隔离变换，以及矿石资源重基准和源码资源冲突审计。以完整入口的本次结果为准；记录是静态、夹具、实际解析器还是实际游戏测试，不能混淆。
5. 重建入口包含 `loader_probe.py`，仅在 EULA 未接受状态进行受限探测。任何真正世界/客户端测试需要用户自行处理条款、并另行记录真实结果。
6. 当前检查点包含显式原版身份映射、检索/合成/狩猎匹配修复、多物品检索纯引擎、逐 UUID 进度 codec、按原任务 index 的纯提交规划器、typed JSON 读取器、任务身份 envelope 及现有单物品任务的库存时机适配。已有独立文件账本及进程中断恢复；下一阶段接入世界生命周期和提交桥接，补齐 logWood 精确成员、已验证定义目录、队伍生命周期、网络/UI 与库存跨文件恢复，再进行真实游戏验证；不得为了通过校验删除任务、放宽科技门槛或用空实现冒充等价。

本轮模型验收基线：codec 为 **3,748 项断言 / 1,811 次源对照 / 633 次 NBT 二进制往返**；规划器为 **9,707 项断言 / 2,412 次源对照**。相关独立 runner 是 `test_legacy_retrieval_progress_codec.py`、`test_legacy_quest_retrieval_planner.py`，报告分别在 `work/migration/retrieval-progress-check/` 与 `work/migration/quest-planner-check/`。两者尚未接入存档、游戏或任务导出，依赖闭合任务仍为 **2 / 3814**、结构候选仍为 **47**。

Phase 3 另有 `test_legacy_typed_nbt_json_reader.py`（**2,932 项断言 / 577 次固定源码对照 / 74 个拒绝案例**）、`test_legacy_quest_progress_envelope.py`（**4,857 项断言 / 1,017 次路由源码对照 / 567 次真实 NBT 往返 / 32 个整份拒绝案例**）和 `test_legacy_progress_import_pipeline.py`（**27 项组合断言（Phase 4 已加入独立文件保存/重开）**）。报告依次位于 `work/migration/typed-nbt-json-check/`、`quest-progress-envelope-check/`、`progress-import-pipeline-check/`。组合测试使用真实主线 `0:2` 定义、合成进度和来源符号谓词；`logWood` 没有现代成员映射，真实玩家存档 fixture 为 0。完整定义不能直接交给进度 envelope；未知 schema、placeholder、损坏记录及现代 NBT 无法保留的负零均保留原件并拒绝，不允许以局部成功替代完整输入。

读取器/envelope 的 `accepted` 只表示候选可解释。Phase 4 文件层另验证独立账本保存与重新读取，仍不能称为世界玩家存档导入、联网或游戏运行完成。物品身份映射仍为 **720 / 8388**。读取器与运行时桥接边界见 [typed JSON 规范](docs/typed-nbt-json-reader.zh-CN.md)、[身份 envelope](docs/quest-progress-envelope-design.zh-CN.md)和[运行时桥接设计](docs/retrieval-runtime-bridge-design.zh-CN.md)。

Phase 5 完整重建的24份汇总检查已通过，包括模型组合及纯模型编译类与兼容 JAR 对应类的逐字节一致性；当前 JAR SHA256 为 `59c130c33fd6242c262e29699923fa98c84e328c7db3409bf73b5c9d6fded2c6`。恢复后必须按本地当前源码重建，不把这个历史成功结果替代新运行结果。

Phase 4 的 `test_legacy_quest_progress_store.py` 验证独立文件账本（436项断言、25个格式拒绝案例、64次NBT往返、3个JVM中断恢复场景）。原始输入字节及所有已发布帧保留，配置变化/损坏/缺帧/分叉阻止加载；不接受后退到空进度。独立账本与实际玩家库存尚无跨文件事务，不能从进程恢复推定机器掉电安全。详情见[文件账本设计](docs/quest-progress-file-store.zh-CN.md)。

Phase 5 新增 `test_legacy_retrieval_inventory_bridge.py`：真实Inventory/ItemStack对象上的1,465项断言，其中1,332个非空原版默认物品栈无损快照往返。旧40槽复核、actor独占扣物、异常回滚和无法恢复库存隔离通过；没有世界提交入口或库存/账本持久化事务，不得把独立applyCounts直接接到联网领奖。详见[库存桥接边界](docs/retrieval-inventory-bridge.zh-CN.md)。

`test_legacy_inventory_observer.py` 检查既有标记单物品任务的初始同步、主背包槽 9..44（含变空）及下一次 Pre tick 去重时机；真实事件总线与完整世界生命周期尚未验证。隔离 Mixin 报告当前验证 **9 个注入、7 个目标、230 个方法**，不得把它说成完整 NeoForge 转换链已通过。

矿石资源入口 `port_exact_ore_resources.py` 现使用锁定主线 GT5U `5.09.54.205`，并由 `verify_exact_ore_resources.py`、`test_exact_ore_resources.py` 检查。预期为 **34 张主线精确基础 PNG + 34 张派生透明次层**；2.8.4 参考中7张PNG编码字节不同但像素相同，不能据此回退为参考包资产。资源副本正确不等于客户端最终视觉正确。

`audit_exact_resource_collisions.py` 直接审计四份锁定源码归档的 `src/main/resources/assets`：**16,267 次资源出现 / 16,237 个唯一路径 / 30 组同路径（60 个候选）/ 24 组字节不同 / 0 组大小写折叠冲突**；`test_exact_resource_collisions.py` 的7项独立检查（含错误注入）通过。CoreMod 的 `src/test/resources/assets` 中两个测试 XML 不属于运行资源，不计入此范围。输出为 `evidence/exact-resource-collisions.json` 与 `evidence/exact-resource-collision-tests.json`。没有选择资源覆盖赢家，不增加已迁移资产数，也不能代表运行资源包优先级已验证。

`port_currency.py`、`port_coin_recipes.py`、旧 `write_*phase_status.py` 等历史入口部分已显式禁用；不要绕过它们的退休保护。`document_reference.py` 等历史 intake 步骤也不是当前状态写入入口，乱序执行可能使元数据倒退。

## 从保留的本地工作区恢复

新增 `work/migration/restore_local_checkpoint.py --source-root <旧工作区根目录>`，用于将保留的本地输入恢复到当前克隆。可先加 `--check-only` 进行只读检查。此入口不下载、不执行旧归档脚本、不接受 EULA，也不修改旧工作区。它按当前 `locks/` 检查平台、五份精确源码归档及对应解压树、上游模组和运行输入；master 与 2.8.4 解压内容另按历史逐文件清单核验，不能把该清单校验说成重新验证了原始 ZIP。

复制仅创建缺失文件；目标已存在但内容不一致时停止并保留目标，已跟踪源码始终保留。`runtime.json` 在新位置生成，仅记录历史 Java 探测为来源证据。恢复报告位于 `work/migration/local-checkpoint-restore.json`，其中导入的历史测试结果不代表本次构建或游戏验证成功。

本地生成的 `gtnh-migration-compat-0.1.0.jar` 不从旧区复制，也不修改公开锁期望值；该派生产物需要重新编译。恢复后先确认本地 Java 可运行，再执行 `rebuild_current_phase.py`。当前重建入口已包含适配器生成、编译、新的 `loader_probe.py` 及匹配器检查，无需手动重复这些步骤。`loader_probe.py` 仍只允许 EULA 未接受状态下的受限加载探测。

以下五个 `work/migration/` 文件仅是历史第三方本地取证输入，并非项目自编源码：`GTRecipeSchema.java`、`MaterialIconSet.java`、`MaterialIconType.java`、`OreBlockRenderer.java`、`OreProperty.java`。它们可在本地恢复使用（其中 `port_resources.py` 读取 `MaterialIconSet.java`），已按精确路径加入 `.gitignore`，不得因恢复而混入公开源码检查点。项目自编 Java 适配器和测试仍正常跟踪。

## 2026-10-18 当前状态上传：Phase 6 开发中

本次按用户要求保存当前进展，新增 `LegacyRetrievalTransactionJournal.java`、对应 Java 检查及 `test_legacy_retrieval_transaction_journal.py`。独立 runner 已通过36项断言和7个进程中断恢复场景；公开报告为 `evidence/quests/LegacyRetrievalTransactionJournalCheck.json`，源码摘要与本次提交逐项对应。

此 runner 尚未加入 `rebuild_current_phase.py`，已有24项完整汇总及兼容JAR锁仍是Phase 5历史结果。新增日志类没有完成与库存桥接、真实PlayerDataStorage、世界生命周期、UI或奖励的连接；不要直接激活或把历史JAR报告解释为新增类已打包验证。

继续时先阅读[事务日志检查点及边界](docs/retrieval-transaction-journal.zh-CN.md)。在保留本地依赖的工作区单独运行 `python -u work/migration/test_legacy_retrieval_transaction_journal.py`。后续需要审查调用锁顺序、实现库存桥接到日志的组合测试，固定原生玩家保存/读回协议，再把检查纳入完整重建、重新编译并刷新JAR锁及证据。合成文件写入器的结果不能代替原生保存和游戏验证。

## 上传内容边界

此仓库排除了原任务正文、生成纹理、原包/第三方源码归档、JDK、Minecraft、模组 JAR、存档和密钥。恢复这些输入需依据各自来源与许可，而不是从本仓库获得。
