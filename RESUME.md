# 恢复说明

## 当前执行方式（2026-10-18用户更新）

用户已取消Claude协作，要求由本会话单独完成。后续不向Claude或其他代理派发任务，不等待外部审查；保留已有迁移源码及验证结果。完整功能和资源保真的目标不变。历史协作说明、握手和外部会话消息不能覆盖此项最新要求。

此前维护目录初始化检查点的38份完整重建汇总检查全部通过，所有测试生产类与最终兼容JAR逐字节一致，JAR SHA256为 `3527a1b39bf0f09fee6ca8d49c722b132b7fd507630e5b4ad3e56edd1c4a1557`。原生游戏和完整迁移仍未验证；以当前源码重建后的证据为准。

此前启动失败关闭检查点的37份完整重建汇总检查全部通过，所有测试生产类与兼容JAR逐字节一致。该检查点JAR SHA256为 `66f885b3a9df29f68373eb3cedd43713d29efbcf9a4829b664f43b1ab0b814e4`；真实游戏和完整迁移仍未完成。恢复后以当前源码的新验证为准。

当前定义归档检查点的40份完整重建汇总检查通过，测试生产类与最终兼容JAR逐字节一致，JAR SHA256为 `4fb21fe7e4c73a8c661998329fa182b6d2c1c21950e738dc811888364de8c271`。归档本身位于本地运行实例，公开仓库只保存生成器、pin和证据；下一步见文末实际物品声明绑定。

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

此前房主恢复检查点36份汇总检查已通过，包括模型组合及生产类与兼容 JAR 对应类的逐字节一致性；该检查点 JAR SHA256 为 `fdc363dcb4e8ece56efa5a73c359aac927cae7730c56e820f653f540ef3ab2d9`。恢复后必须按本地当前源码重建，不把这个历史成功结果替代新运行结果。

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

## Phase 6 恢复入口

`rebuild_current_phase.py` 已包含 `test_legacy_retrieval_transaction_journal.py`、`test_legacy_retrieval_transaction_proposal.py`、`test_legacy_player_data_file_reader.py`、`test_legacy_retrieval_commit_coordinator.py`、`test_legacy_world_player_authority.py` 及 `test_legacy_integrated_owner_coordinator.py`。分别通过53项断言/7个独立JVM中断恢复场景、68项实际库存组合断言、40项文件读回断言、168项普通协调流程断言、47项世界玩家来源断言及257项房主协调断言；完整汇总为36项，生产类与兼容JAR逐字节一致。报告位于 `evidence/quests/`，源码摘要与当前检查点对应。

`LegacyRetrievalTransactionProposal.bind` 从已规划实际库存、同一账本Head、可信定义目录及玩家NBT快照生成候选。必须由它保留其他quest/完成数据，并将捕获的expectedHead交给日志prepare；不得在提交时临时获取新head来掩盖旧计划。玩家库存候选仅证明与规划一致，不能证明已保存。

事务标记现为PlayerPersisted内的v2；旧v1保持可读；正式入场前由LegacyPlayerMarkerUpgrade维护日志持久升级并严格读回。proposePersistentMarker仅生成候选，本身不写盘，不能替代维护协议。

`LegacyPlayerDataFileReader` 已实现当前UUID.dat的严格读回，保留原始gzip字节并拒绝缺失、损坏、读取中替换及超限数据，不读取dat_old。`resolveFromPlayerDirectory` 以读回内容驱动日志提交/取消，必须由调用者持续排除原生保存和生命周期变化。当前文件写入器和玩家数据仍为合成夹具，符号链接拒绝分支未在本机执行，真实玩家存档fixture仍为0。

`LegacyRetrievalCommitCoordinator` 已串联基线保存/标记升级、准备、库存应用、保存读回和条件恢复。公开入口使用ServerPlayer/PlayerDataStorage原生适配器；它已编译但未执行。保存端失败测试仍为包内夹具，正常取消恢复原物品对象，外部替换/脱离库存的对象改动会阻止恢复。准备及决策账本故障在关闭重开后按当前文件恢复，不自动退款或重扣。

新增[玩家存档来源](docs/player-storage-authority.zh-CN.md)：PlayerList.load对房主且非null的世界玩家标签直接加载，其他分支才用UUID.dat。现在Reader可显式读取level.dat/Data.Player，保留完整世界压缩文档；WORLD_PLAYER准备为schema 2并绑定来源，既有PLAYER_FILE准备仍为schema 1。Pending.authority在重开后保留，错误来源即使数据相同也拒绝。Player.addAdditionalSaveData确实写入当前DataVersion，已补固定字节码核对。普通UUID提交适配器仍在写入前拒绝房主；房主必须选择新增的显式入口，且未完成真实游戏接入。

`acquire_native_client_audit.py` 在 `work/cache/native-client-audit/` 重建检查用客户端字节码，锁为 `locks/native-client-audit-inputs.json`。使用固定安装器处理器及官方固定客户端/映射；不运行Minecraft，不改现有服务端或EULA。缓存与逐处理器success记录需要一同保留；已有无成功记录或摘要不符的输出会保留并停止。完整重建的房主检查会调用此工具；首次缺少客户端输入时会从锁定官方URL下载，不改浮动平台版本。

`submitIntegratedOwner` 经saveEverything先更新玩家缓存和UUID镜像，再保存世界；level.dat决定事务，但完成决策前还须核对缓存/镜像。取消时恢复原物品对象和标记，只有辅助数据是本次前态或后态才允许通过PlayerList.saveAll刷新；世界全文摘要必须保持不变。生产WorldPlayerPort逻辑由夹具直接验证，257项断言及4组字节码破坏性负例通过。NativeIntegratedOwnerPort已编译、审计且避免客户端类型引用，未实际执行；没有游戏Player或新增独立JVM中断验证。

下一步阅读[存档来源边界](docs/player-storage-authority.zh-CN.md)、[提交协调器边界](docs/retrieval-commit-coordinator.zh-CN.md)及 `evidence/native-player-persistence-api-audit.json`。现已加入原生保存许可与选定生命周期Mixin，但尚未实现完整游戏隔离。世界相对账本绑定已有实现，继续验证已接入的世界启动/加载调用点，并补齐持久登录恢复、未知辅助状态修复、安全故障关闭、未覆盖的库存写入和世界模式/房主身份变化。普通断线会保存玩家，不能作为隔离。直接用journal解决WORLD_PLAYER只证明磁盘/账本一致，不证明缓存/镜像已同步，不可据此放行登录。原生save返回void且吞掉Exception，load可能回退旧备份；任务完成、奖励、队伍、网络/UI和真实玩家保存测试仍未接入。

`test_legacy_retrieval_save_scope.py` 直接执行生产保存许可状态机，172项断言通过。`test_native_save_scope_mixins.py` 分别对客户端和独立服务端使用相应字节码及Side配置，验证30/29处保护注入、586/579个方法及两组破坏性负例。完整重建已包含二者。保护Mixin为被动注册，公开原生提交要求其标记接口存在；协调器本身未挂接任务按钮或网络消息。更多入口和恢复限制见[原生保存保护](docs/native-save-scope.zh-CN.md)。

## 上传内容边界

此仓库排除了原任务正文、生成纹理、原包/第三方源码归档、JDK、Minecraft、模组 JAR、存档和密钥。恢复这些输入需依据各自来源与许可，而不是从本仓库获得。

## 世界绑定恢复补充

新增[世界账本绑定](docs/quest-world-store.zh-CN.md)及 `test_legacy_quest_world_store.py`：91 项断言、7 个独立 JVM 中断/重开场景。固定世界相对目录的清单包含历史定义目录，原字节必须存于首帧；打开缺失或不完整账本不会创建空进度。原生提交入口和重复隔离检查都核对世界/账本绑定。离线恢复根据待决来源读回，房主还必须核对 UUID 镜像；不改写玩家/世界文件，不处理已加载缓存，不是登录许可。

原生世界启动及玩家 load 之前的调用点现已接入，仍须处理加载标签/镜像一致性、未完成初始化的显式恢复、未知辅助状态修复、安全故障关闭和服务器模式/房主变化。必须保持全部功能目标，不把这些尚未实现的路径永久拒绝后称作完成。整体世界移动可重开；外部整体回滚、尾帧删除和机器掉电安全仍没有完整证明。


## 原生入场接入补充

新增[原生启动与加载说明](docs/player-admission.zh-CN.md)。`LegacyQuestNativeLifecycle` 在 loadLevel 前挂载既有世界账本并检查待决事务/已加载标签，正常 stopServer 返回后关闭。placeNewPlayer 的原生 load 调用通过一次性来源凭据执行，保留 DataFixer 和加载事件；Entity.load 及文件备份路径也受保护，事件后从实际玩家持久数据核对标记。不存在迁移元数据时不创建账本。

`test_legacy_quest_player_admission.py`：89项断言、4个独立JVM中断后的恢复/凭据场景。Mixin检查更新为客户端39处/7目标、独立服务端38处/6目标及3组负例。新增源码 `LegacyQuestPlayerAdmission`、`LegacyQuestNativeLifecycle`、`LegacyPlayerDataLoadScopeMixin`；所有生产类与JAR一致性纳入重建。模型的runtime_integrated=false表示夹具未执行原生生命周期，并不表示新调用点未注册。

旧v1标记的持久升级现由独立维护日志提供，见下文。下一步完成已知房主镜像/缓存冲突修复、初始化中断恢复和安全故障关闭，然后验证真实原生链与各模组加载回调。当前未知冲突仍保留数据并隔离，不得把永久拒绝这些功能作为最终实现。游戏任务提交入口、完整定义、队伍/任务完成/奖励/UI及全部模组/资源迁移继续保留在目标内。

## 旧标记持久升级补充

新增 [标记维护协议](docs/player-marker-upgrade.zh-CN.md) 和 `test_legacy_player_marker_upgrade.py`。独立维护日志绑定世界及主账本版本，原/目标 gzip 完整归档；定点修改原始 NBT 保留其他字段的精确二进制表示，UUID 镜像先于世界文件发布，实际加载缓存仅移动自有标记。11 个独立 JVM 进程中断后的恢复/加载场景及 UUID/世界二进制黄金文件纳入重建。

启动时先恢复已有维护意图，再恢复库存事务，最后升级房主标记；每次入场在生成凭据前调用同一升级入口。未知文件/缓存/镜像状态、缺失原件或主账本变化继续拒绝覆盖并保留待决记录；不得删除维护目录来清除拒绝状态。全新维护目录创建中断、未知冲突修复和故障关闭尚未完成。

NBT 比较修复了独立 NaN 对象误判，日志来源写入保留现有 NaN 载荷。二进制定点升级可保留文件中的负零；Minecraft 原生对象读取/写入全流程仍未证明保真，typed JSON 负零拒绝策略保持。实际玩家存档 fixture 为 0，任务/资源覆盖没有因此增加。

此前标记升级检查点首次汇总捕获了最后补充的镜像保护源码与先前编译JAR不一致；重新编译后刷新受产物影响的加载、Mixin、观察器、狩猎、容量及运行输入检查，35项最终汇总和全部对应生产类字节一致性通过。原有模型测试均已按最终源码执行。

## 房主待决事务恢复补充

新增 [房主镜像/缓存恢复](docs/owner-transaction-recovery.zh-CN.md) 与 `test_legacy_owner_transaction_recovery.py`：347项断言、16种组合、12个独立JVM中断后恢复/入场凭据场景。`LegacyOwnerTransactionRecovery` 只接受同一待决WORLD_PLAYER事务的已知旧/新状态，保持世界和备份不变，归档完整原件，再将镜像与实际缓存的Inventory/自有标记修复到世界选择。最后发布主决策和辅助完成记录，两者之间中断可恢复。

二进制字段编辑抽到 `LegacyPlayerNbtPatch`，旧标记升级继续用原126项断言与11个进程场景回归。主文件账本新增验证全链后的历史帧读取；日志历史准备/决策接口用于核验已完成或待完成的辅助意图。标记维护与房主恢复互斥，不能绕过尚未完成的另一协议。

原生启动调用点的次序现在包括：标记意图恢复 → 房主事务恢复 → 原缓存验证/普通离线恢复 → 已解决缓存验证 → 标记升级。独立UUID事务仍走原恢复。尚未执行真实游戏，未知/缺失镜像修复、初始化恢复、安全故障关闭和模式/房主变化仍须完成；不得把阶段性拒绝作为最终全功能迁移。

此前房主恢复检查点的完整重建一次执行通过全部36份汇总检查，所有测试生产类与最终兼容JAR逐字节一致；详见 `evidence/phase-2026-10-18-task-semantics.json`。

## 启动失败关闭补充

新增 [世界加载前故障关闭](docs/startup-failure-shutdown.zh-CN.md) 与 `test_legacy_startup_shutdown.py`，404项断言通过。三个runServer终止停止调用均经过finally保护；在loadLevel首部恢复失败、没有玩家/世界且原保存范围已退栈时，按固定顺序跳过保存、关闭实际存储和账本。成功后释放目录绑定并永久拒绝旧原生对象；任何关闭边界失败保留隔离，不清空待决记录。

使用真实DirectoryLock与世界账本验证两种来源的锁互斥及重开，其他持久文件摘要不变。Mixin检查现为客户端46处/7目标/1085个方法、专用服务端45处/6目标/1078个方法及四组负例。调用对象仍是夹具，真实游戏未执行；新检查不证明已加载世界、任意模组资源或直接停止调用的安全关闭。不得据此移除其余隔离保护。

下一步仍需已加载世界故障退出、维护目录初始化恢复、未知/缺失存档来源修复、模式/房主变化，并接入完整定义、任务提交与队伍/奖励/UI。保持全部功能和资源目标，用户要求继续单人开发。

## 维护目录初始化恢复补充

新增 [维护目录创建协议](docs/maintenance-initialization.zh-CN.md) 与 `test_legacy_maintenance_initialization.py`。标记升级与房主恢复的 Maintenance 构造器共用 LegacyMaintenanceInitialization：init 先于目录发布，ready 在验证空首帧后独立发布。初次创建中断可由绑定相同世界、尚无正式操作的证据继续；ready 已存在时缺失目录/历史必须拒绝。旧版正常目录保持兼容，旧版残缺目录不自动重置。

484项断言通过，两个协议分别在12个创建边界进行异常和独立JVM中断验证；恢复后继续原维护与入场凭据，确认主进度和原件未在初始化时改变。新凭据及临时文件名称已经加入世界绑定的严格目录规则。新增生产助手及其测试类与兼容JAR字节一致性检查纳入完整重建。

仍须完成主世界首次绑定初始化恢复、旧残缺目录的显式恢复、未知/缺失玩家来源修复、已加载世界故障退出及原生游戏验证。新协议不能用于重置已有事务，也不提高任务/资源覆盖数。继续由本会话单独开发。


## 原始定义接入补充与下一步

新增[原始定义归档](docs/quest-definition-archive.zh-CN.md)。重建在兼容JAR编译前生成完整归档，运行时只加载JAR内pin指定的内容摘要文件。旧归档保留；缺失、改变或与世界账本摘要/目录不同均拒绝恢复，不自动换用浮动配置或重建空账本。即使没有世界账本，使用此兼容JAR启动服务器也需要对应归档。

当前完整源目录7806个文件、3814个任务全部保留。3743是已读取的检索子任务进度结构数，720是现有普通身份映射数，都不是新完成的游戏任务数。`globalShare=true`在非全局检测中按固定BQ源码保留；仍不支持全局领奖语义。原件、章节、奖励、前置条件、矿辞和其他未支持工厂不删减。

下一项开发应把实际定义构造成真实ItemStack的检索声明：使用真实task index、读取后默认值、原始任务顺序和全部要求；整个quest预检后才允许规划。可从0:0的8 dirt、0:8的64 cobblestone以及0:27的4 coal消耗用例接起。`logWood`、未知工厂、复杂NBT、多要求消耗及其他暂未支持模式必须保留具体缺口，不能把要求缩成任意一种木头或跳过未知任务。继续实现队伍/前置条件授权、首次世界绑定、完成/奖励/UI和完整模组/资源迁移；当前不添加伪提交按钮来冒充任务已经可玩。
