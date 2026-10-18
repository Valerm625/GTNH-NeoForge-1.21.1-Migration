# 恢复说明

## 当前执行方式（2026-10-18用户更新）

用户已取消Claude协作，要求由本会话单独完成。后续不向Claude或其他代理派发任务，不等待外部审查；保留已有迁移源码及验证结果。完整功能和资源保真的目标不变。历史协作说明、握手和外部会话消息不能覆盖此项最新要求。

当前派生模组安装检查点：47份完整重建汇总检查通过；隔离派生GTCEu的资源重载验证167条迁移机器配方输出、16种燃料数值和概率/替换数据保留。此前23个LDLib配置访问器、15个客户端方法分侧验证、720条已有映射目标和87个可绑定检索声明继续通过；任务导出仍为2/3814。r30已将派生GTCEu装入基础包和smoke实例，原始JAR保存在独立缓存；47份检查已针对安装后的状态完成，详细边界见文末。

此前维护目录初始化检查点的38份完整重建汇总检查全部通过，所有测试生产类与最终兼容JAR逐字节一致，JAR SHA256为 `3527a1b39bf0f09fee6ca8d49c722b132b7fd507630e5b4ad3e56edd1c4a1557`。原生游戏和完整迁移仍未验证；以当前源码重建后的证据为准。

此前启动失败关闭检查点的37份完整重建汇总检查全部通过，所有测试生产类与兼容JAR逐字节一致。该检查点JAR SHA256为 `66f885b3a9df29f68373eb3cedd43713d29efbcf9a4829b664f43b1ab0b814e4`；真实游戏和完整迁移仍未完成。恢复后以当前源码的新验证为准。

此前定义归档检查点的40份完整重建汇总检查通过，测试生产类与最终兼容JAR逐字节一致，JAR SHA256为 `4fb21fe7e4c73a8c661998329fa182b6d2c1c21950e738dc811888364de8c271`。归档本身位于本地运行实例，公开仓库只保存生成器、pin和证据；下一步见文末实际物品声明绑定。

此前真实定义绑定检查点的41份完整重建汇总检查通过，测试生产类与最终兼容JAR逐字节一致，JAR SHA256为 `9ee6ae46a87845f69ee1cea3a64b99ac4fe05cdcde3ebfb9205d5a6990b9fdd1`。共有38个原版检索检测声明绑定成功，不能计作完整任务新增；下一步继续授权、首次世界绑定、持久提交和完成/奖励。

此前前置策略检查点的42份完整重建汇总检查通过，测试生产类与最终兼容JAR逐字节一致，JAR SHA256为 `1f791f268323dab840e59b1f9eee5bce3472153d8900c84de3d835fdc84297f4`。六种前置逻辑对照通过，原生查询未执行真实玩家/世界；玩家缓存、队伍与提交授权仍待实现。

此前被动任务完成检查点的43份完整重建汇总检查通过，追加历史UUID字符串保留复验及生产类/JAR逐字节一致性通过。JAR SHA256为 `aed950abf8a14d8b2c8082d36b99199193fcbd005f4ca86d563d4bedc2f2aaea`。新增完成计划仍是未注册原生tick的纯模型，具体限制和下一步见文末及行为说明。

此前检索模式检查点的43份完整重建汇总检查通过，所有测试生产类与最终兼容JAR逐字节一致。JAR SHA256为 `7b3e811a9805a831b63c112b6b953133fe22e6e9da5b0e3766ec7d6f5522f6a7`。16种检索标志组合模型已验证，真实声明39个，原生自动事件/完整缓存/队伍/提交及游戏仍未接入；详情见文末。

此前真实注册检查点的44份完整重建汇总检查通过，兼容JAR SHA256为 `d840000cec3679510c2cc9c9de88888d93e2eac2a5bbb568e7045db9a02740a9`。15个Mixin移至独立包，修复真实ModLauncher的IllegalClassLoadError；492条原生目标均存在，实际模组注册表中68个检索声明可绑定，EnderStorage容量常量为256000mB。228条KubeJS脚本身份在数据生成中未执行，完整任务导出仍为2/3814，世界/玩家与完整迁移未验收。

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

下一项开发应把实际定义构造成真实ItemStack的检索声明：使用真实task index、读取后默认值、原始任务顺序和全部要求；整个quest预检后才允许规划。可从0:0的8 dirt、0:8的64 cobblestone以及0:27的4木炭（旧coal metadata=1）消耗用例接起。`logWood`、未知工厂、复杂NBT、多要求消耗及其他暂未支持模式必须保留具体缺口，不能把要求缩成任意一种木头或跳过未知任务。继续实现队伍/前置条件授权、首次世界绑定、完成/奖励/UI和完整模组/资源迁移；当前不添加伪提交按钮来冒充任务已经可玩。


## 真实物品声明绑定补充

新增[定义到物品绑定](docs/retrieval-definition-binding.zh-CN.md)与 `test_legacy_retrieval_definition_binding.py`。原始归档中的实际task index、要求数量和读取后默认值现已连接真实注册表/FrozenStack谓词；只在整个quest的任务要求与模式都可表达时返回声明。原件和全部诊断始终保留，0:27确认是metadata=1的木炭，不是普通煤。

生产查询入口 `LegacyQuestNativeLifecycle.retrievalDefinition` 从成功挂载的完整定义和该服务器注册表构造声明；尚未安装玩家提交网络/按钮。下一步接入来源正确的前置条件与队伍授权，完成首次世界绑定/进度导入和持久提交、完成/奖励/UI；不要把能绑定或夹具扣物当作完整任务已迁移。原始定义及未支持模式必须继续扩展实现，而非永久删除。


## 前置关系与入口状态补充

新增[原始前置关系策略](docs/quest-entry-policy.zh-CN.md)和 `test_legacy_quest_entry_policy.py`。从3814个任务、5460条引用读取六种questLogic，逐UUID/全局完成记录按固定源语义计算；隐藏依赖不省略，重复引用按Set计数，三个源缺失父任务保留在分母里。未实现的重复提交分支仍明确拒绝。

`retrievalEntry`已编译连接当前在线玩家对象、挂载世界、当前head、真实物品声明与入口状态，但仍只是只读查询。不能把Status或head当作权限票据，也不能把账本已挂载自动视为玩家QuestCache存在。下一步应完成真实队伍/玩家缓存及提交授权、首次世界绑定/进度导入、任务完成/奖励和提交入口，保留所有未完成模式与模组/资源目标。


## 被动任务完成检查（2026-10-18）

新增 `LegacyQuestCompletionPlanner`、`test_legacy_quest_completion_planner.py` 与[行为说明](docs/quest-completion-planner.zh-CN.md)。3400 项断言、295 次旧源码检查过程及 2950 次结果对照通过；覆盖 38 个完整绑定声明及合成边界。原 `onLivingUpdate` 每60 tick的检查和 `QuestInstance.update/setComplete/writeProgressToNBT` 完成记录行为已组合到现有 UUID State。新增记录采用源序列化 UUID/claimed/timestamp 字段，已有记录保持不变，任务完成集合不从计数推断。

活动缓存的实际遍历顺序由调用者明确提供；不擅自排序。锁定进度仍受完成前置约束，同遍前置完成和旧缓存再通知行为保留。`activeProjection` 仅覆盖传入的已支持声明；`Plan` 返回未发布的事件/提示/脏标记意图，不代表原生事件已发送。真实木炭声明经 Inventory、事务提案、子任务账本、60 tick完成计划、合成账本重开，验证4木炭扣物及重开后不重复扣物；这不是实际玩家文件/世界事务。

下一步：实现并验证完整玩家活动缓存、队伍来源和生命周期，再在现有存档排他范围内以当前账本head重新校验，提交被动完成记录并安全发布缓存/事件/提示。不得在手动检索提交中提前完成任务；不得将当前纯计划直接当成授权回调或绕过未决库存事务。重复/全局/同时/忽略及其他工厂仍缺失，依赖闭合导出仍为2/3814，完整迁移目标保持active。


## 检索标志组合扩展（2026-10-18）

`LegacyRetrievalEngine` 已补齐 consume/autoConsume/groupDetect/requireOnlyOneItem 的16种组合，支持多要求消耗、OR提前停止、组检测全重置及观察入口自动消耗条件。固定源算法对照为28,863项断言/28,825次结果或异常对照，其中586个原实现空移除异常在新版整份计划阶段拒绝，不写真实库存。源groupDetect无匹配时可能通过共享数组改变历史计数而不标脏，这个行为已保留；输入快照保持不可变。具体见[检索模式说明](docs/retrieval-mode-parity.zh-CN.md)。

真实声明增加0:1914（64绿宝石+8本书），完整绑定39个检索声明。绑定检查4112项断言/42次readFromNBT对照，任务顺序9844项断言/2446次对照，实际Inventory桥接1475项断言；被动完成模型扩展到39个声明，3458项断言/3000次对照。新增模式没有注册新的原生自动消耗事件，也没有增加完整任务导出；依赖闭合仍为2/3814。

下一步继续完整玩家缓存/队伍与提交生命周期，同时补齐尚缺物品身份、矿辞完整集合、NBT组件及原模组功能。不要把检索层OR与quest级taskLogic OR混淆：后者和重复/全局/同时/忽略等仍未实现。原源码的空移除异常边界、非正要求及整数溢出仍有明确拒绝，不得静默删除要求或返回部分成功。

## 真实模组注册与Mixin包隔离（此前）

见[真实注册说明](docs/native-registry-datagen.zh-CN.md)和 `test_native_registry_datagen.py`。原有EULA发现检查和独立字节码转换均未定义游戏目标类，漏掉了普通辅助类与Mixin同包造成的运行时加载拒绝。现在15个Mixin位于 `org.gtnhmigration.compat.mixin`，构建递归编译该目录，生成器与全部来源/字节码路径检查同步更新。

官方forgedatauserdev数据生成检查加载基础包的19个顶层JAR及嵌套依赖，通过真实ModLauncher定义16个选定类并确认7个钩子接口；旧父包布局作为负例重新触发IllegalClassLoadError。物品11631、方块6011、流体880、实体149；这些是安装模组注册数量，不能算作已迁移GTNH内容。720条映射中492条原生目标全部存在；另外228条gtnh_migration身份由KubeJS启动脚本生成，固定KubeJS在isGeneratingData为真时跳过reload，因此本检查未覆盖。未修改KubeJS该分支，未构造替代注册物品。

完整日志 `work/migration/native-registry-rebuild.log`，检查目录 `work/migration/native-registry-check/`；positive.log、negative.log及registry-details.json保留本地完整证据。公开汇总为 `evidence/quests/NativeRegistryDatagenCheck.json`。检查用开发JAR与附加测试模组仅在忽略目录，不安装到基础包，不发布上游二进制；EULA保持false，未创建世界/玩家。

下一步继续核实KubeJS原生启动注册及脚本API兼容，补足现有迁移内容在真实模组中的执行证据，再推进任务提交/完成/奖励、玩家缓存与队伍。不要用数据生成的68个绑定声明替换完整任务导出数字，也不要把原生物品存在当成功能和贴图保真证明。


## 正常启动注册检查点（当前，2026-10-18）

完整重建45份汇总检查通过，终端退出0；日志 `work/migration/native-startup-rebuild.log`。兼容JAR SHA256为 `9d1a011536f3bfabed560fc8bdf3729ba61b3660b4a2c1e36a5aee6b1267a88a`。单人执行与完整保真目标不变，EULA仍为false。

详见[正常启动注册与客户端隔离](docs/native-startup-registration.zh-CN.md)。生产服务端注册检查执行真实KubeJS启动回调，已有720条映射目标全部存在，包括228条脚本身份；574个迁移物品，87个检索声明可绑定。修复双参数tagBlock、循环const及未限定材料ID。GTCEu原JAR不改写，两个客户端指令区移入OnlyIn助手；41项字节码断言/31个方法数据流检查、四组真实启动错误回退通过；客户端数据生成保留两个助手及512个原灯渲染器。

实际锡锑组件为9:1。16种燃料通过真实KubeJS内置生成器及NeoForge数据映射codec校验；资源重载前getBurnTime为0，尚未证明炉子燃烧行为。注册检查只执行CommonModLoader.begin和组件修改，不是完整模组生命周期或游戏运行。52条已识别的LDLib/Rhino客户端类型扫描错误仍被保留，all_startup_errors_resolved为false。

下一步优先处理LDLib注解/客户端类型扫描错误，然后完成完整模组初始化与真实资源/配方重载，并验证燃料数据实际生效。`LDLRegisterClient`本身带OnlyIn(CLIENT)，其类型在通用AnnotationDetector扫描ConfigAccessor时被解析；本地字节码调查位于忽略目录 `work/native-startup-probe/`，尚未实施后续修复。随后继续任务提交、完成、奖励、玩家缓存和队伍集成。不要以87个声明或720个目标存在替代完整迁移验收；任务导出仍为2/3814，身份映射仍为720/8388。

当前复现入口 `test_native_startup_registration.py` 使用新UUID目录和现有生产服务端分发；公开证据 `evidence/NativeStartupRegistrationCheck.json`，详细映射/生成燃料JSON与正负日志在 `work/migration/native-startup-check/`。测试用启动服务和审计模组未安装到基础包。不要再使用前期重新编译GTCEu的临时probe产物替代正式原JAR加CoreMod方案。


## LDLib配置扫描与客户端回调检查点（2026-10-18，r27）

完整重建45份汇总检查通过，退出0；日志`work/migration/library-callbacks-rebuild.log`。兼容JAR SHA256为`532d9295e203a0f779cedd754685ebc766e58455e5d1ee7baa1a4a5f90fe4c1b`。上游GTCEu和内嵌LDLib原产物不改写，新增版本/类摘要锁和LDLib精确依赖。

详见[客户端回调修复](docs/library-client-callbacks.zh-CN.md)。两个物理客户端守卫分别保护LDLib客户端注解检查和GTCEu流体客户端监听器创建；15个客户端绘制/回调方法补齐OnlyIn标记。真实服务端/CLIENT数据生成验证23个配置访问器完整注册、客户端方法分侧正确、通用场景接口仍可用、模组依赖与构造器过滤保留。51项字节码断言、509个方法数据流检查、17个布局拒绝案例通过，去除新增注解和守卫后整个目标类与原始产物一致。六组真实启动负例通过；正例不再允许已知ERROR。

完整初始化的后续探索尚未纳入45项正式汇总：`work/full-lifecycle-probe/initialization-only.log`记录生产`ServerModLoader.load()`的公共/服务端设置、IMC和网络注册完成，进程退出0，无ERROR，未创建世界，EULA仍false。入口源码快照是同目录`InitializationOnlyMain.java.txt`。这是独立库初始化检查，没有调用服务器主程序。

真实资源/配方重载探索调用`WorldLoader.load`，只加载资源与注册表，不构造世界、玩家或WorldStem。原始失败日志`resource-original-api-failure.log`：GTRecipes$KJSCallWrapper引用不存在的`dev.latvian.mods.kubejs.bindings.event.ServerEvents`；当前KubeJS实际类在`plugin.builtin.event`，RECIPES字段类型及hasListeners签名保留。仅在该忽略目录的实验兼容JAR中改正字段owner后，重载继续至GTRecipeComponents静态初始化，当前`launch.log`重现`SizedIngredientComponent.NESTED`字段不存在，随后GTRecipeSchema初始化失败。尚未完成资源重载、配方或燃料实际生效验证。

实验补丁在`work/full-lifecycle-probe/resource_api_probe.js`；该目录的兼容JAR含额外实验CoreMod，不能覆盖基础包、作为r27产物发布或计为已验证迁移。正式兼容JAR仍只含已验证的两个CoreMod。下一步需按固定KubeJS/GTCEu真实API与源语义修复全部配方桥接差异，保留嵌套配料、数量、机会和条件语义，再把完整初始化与资源重载纳入可复现检查。不能把NESTED盲改为其他字段以求启动通过。

## 新增源码候选审计（2026-10-18，r28）

用户提供 `Valerm625/GregTech-Modern-Unofficial-Rebuild`。已克隆到忽略目录 `work/upstream/gt-modern-unofficial-rebuild`，固定 HEAD 为 `0c63302896851b340022d568b8211cebc603f1f1`，读取 Git 对象完成[接口、依赖和资源审计](docs/gtceu-rebuild-candidate.zh-CN.md)。候选是 8.0.1-unofficial，KubeJS 编译基线为 7.2-build.336。当前 377 对其 STAGE_KEY 引用的 javac 负例失败，MIRROR_KEY 正例成功；未执行候选整体构建或 GameTest，未安装候选、未向候选仓库推送。来源审计不改变 2/3814 导出、720/8388 身份或完整迁移未完成状态。

当前 r27 基础包与兼容 JAR 均保持不变，原 45 份检查仍适用。本轮增加独立可复现的 `audit_gtceu_rebuild_candidate.py`、锁和证据，不把审计数字混入游戏验收。继续单人工作；EULA 保持 false。

资源重载探索状态更新：忽略目录 `work/kjs-api-port/src` 保留从原 GTCEu 7.0.2 复制并修改的 KubeJS 7.2 适配源码。`work/kjs-api-port/compile.args` 与 `compile.log` 记录成功编译；它只替换 `work/full-lifecycle-probe/mods` 内的实验 GT JAR，基础包不变。该 probe 的兼容 JAR 已恢复为原 r27，两项正式 CoreMod 之外没有额外 CoreMod。此前尝试在脚本加载 ClassReader 的方式被 CoreMod 类型过滤器拒绝，不能绕过该限制。

最新 `work/full-lifecycle-probe/launch.log` 记录配方事件完成：KubeJS 报告新增 167、移除 3、失败 0，RecipeManager 加载 31778 条；随后资源标签更新时 DataMapLoader.results 为 null，整体进程退出 1。因此仍不能声称资源重载、燃料生效或配方功能验证通过。下一步应先检查独立资源加载入口是否缺少 NeoForge AddReloadListenerEvent 等生产生命周期步骤，不得用伪造空结果掩盖问题。实验代码尚未做来源补丁归档和配方语义完整测试，不作为交付产物。

新候选的通用 output 已改为输出键，可用于核对旧版输出写入输入键的缺陷；需要正反例覆盖普通/每 tick 输入输出、数量与概率。整体升级还涉及 ModularUI 与旧 LDLib 兼容层、五个移动/移除的源码路径、资源差异和现有任务映射，不能直接放宽版本锁或删除兼容代码求通过。

## KubeJS源码桥接与真实资源加载（2026-10-18，r29）

实现与边界见[GTCEu KubeJS 7.2 桥接](docs/gtceu-kubejs72-bridge.zh-CN.md)。`work/compat-mod/patches/gtceu-kubejs72.json` 包含14个固定源文件的校验与局部修改，其中11个有修改；`build_gtceu_kubejs_bridge.py` 直接从固定源码ZIP构建25个原有类，其他14084个原JAR条目逐字节保留，追加派生来源元数据。已对照用户提供的8.0.1重构源码，但没有整体替换到8.0.1。构建与测试源码现已纳入Git，不再依赖前期忽略目录中的手工源码或编译参数。

修复新KubeJS API、普通/每tick输出方向、不可变配料列表替换、内容匹配和能量组件注册。Content构造器保留首次概率加成折算；复制、codec、网络解码和替换恢复已折算值，避免6000分母的450再次缩为270。保留所有原客户端方法，不用空实现替代渲染。

独立资源检查执行实际ServerModLoader与资源重载：170条迁移命名空间配方，其中167条机器配方有输出；五条隔离夹具验证方向、数量、EU电流、发电、维度条件、不消耗、概率、替换和工具符号。16种燃料实际getBurnTime与规格相符；五类组件schema往返及18次Content复制/JSON/网络往返通过。恢复旧API会在ServerEvents类处失败；恢复旧输出键能加载却被语义检查拒绝。

前一阶段DataMapLoader空指针是测试入口重复更新标签：固定WorldLoader本身已调用updateRegistryTags，第二次调用读到清空的中间结果。现在只观察生产生命周期，检查一次重载事件和一次标签事件；没有伪造数据映射。无服务器/世界/玩家实例，EULA仍false，基础包mods/config/kubejs摘要前后相同。

本阶段派生JAR SHA256为`86ce239bfd36df2b137c303067aab0dc88b1ae8233101a5431f8a2caa269d6fc`，只在忽略的独立测试包使用。基础包和smoke目录仍为原始GTCEu 7.0.2加r27兼容JAR，不能把独立资源加载通过说成基础包已修复。构建产物位置见`work/migration/gtceu-kubejs-bridge/build.json`；详细正/负日志在同目录最新`resource-check-*`。公开证据`evidence/NativeResourceBridgeCheck.json`。完整重建已加入该检查，正式结果以本节后的完成记录及phase JSON为准。

下一步把派生产物的原始来源、源码补丁、安装/恢复、运行时文件清单和版本锁接成可复现流程，再重跑两侧原生注册和资源检查。原始下载锁必须继续标识原始JAR，不能改其URL记录去冒充派生文件。现有CoreMod六个GT目标类均未被这次源码重编译替换，但整体JAR摘要与运行输入会变化，需要显式区分原始/派生身份。然后继续任务提交/完成/奖励及客户端视觉。noShrink定位行为、旧阶段门槛和实际机器执行仍未验收，不能因API存在或测试通过删去这些要求。仍由单人完成全部迁移目标。

r29完成记录：`rebuild_current_phase.py`进程退出0，日志`work/migration/resource-bridge-rebuild.log`含`CURRENT_PHASE_REBUILD_COMPLETE_FULL_PARITY_STILL_FALSE`标记；46份汇总检查全部通过，公开结果已同步到`evidence/phase-2026-10-18-task-semantics.json`。派生GTCEu摘要仍为上述`86ce239b…`，基础包兼容JAR摘要仍为`532d9295e203a0f779cedd754685ebc766e58455e5d1ee7baa1a4a5f90fe4c1b`。这是源码与独立资源加载检查点，完整游戏、客户端视觉及整包功能等价均未完成。


## 派生模组安装与恢复（2026-10-18，r30）

`locks/artifacts.lock.json`及GT客户端/LDLib类摘要锁保持原始身份；新增`locks/gtceu-derived-artifact.json`固定派生JAR、源码补丁、25个重编译类及完整来源。`gtceu_artifact.py`严格核验派生产物与原包所有其他条目一致，原始JAR保存在`work/cache/original-artifacts/<sha256>/`。`install_gtceu_bridge.py`每次从源码重建，按固定摘要安装至Foundation和smoke；不将派生摘要伪装成下载文件。

安装会拒绝未知修改和双JAR加载。已知中断状态可以重跑恢复，原文件删除前再次核验缓存与原件；配置、EULA及其他模组不参与替换。`test_gtceu_artifact.py`覆盖16个安装/拒绝/恢复检查。恢复入口验证两个实例的派生身份和安装记录，导入原始缓存，但跳过活动派生JAR、安装记录和派生构建目录，要求在目标工作区重新生成。安装检查使用真实JAR的隔离复制和恢复计划夹具，未执行整份大工作区复制。

`build_capacity_compat.py`与两侧原生检查使用经过来源校验的派生GT；目标CoreMod类和LDLib仍逐项遵守旧摘要。资源检查正例直接复制已安装模组，另从源码重建核对摘要；两个负例分别替换为原API和旧输出方向。运行时清单包含派生来源记录，启动器另拒绝多余/缺失的模组JAR。基础包仍是17个原始上游JAR、1个派生GT和1个本地兼容JAR，共19个。

当前完整重建日志为`work/migration/installed-gtceu-rebuild.log`，结果以随后完成记录及新phase JSON为准。GT派生摘要仍为`86ce239bfd36df2b137c303067aab0dc88b1ae8233101a5431f8a2caa269d6fc`。后续继续noShrink定位、旧阶段门槛、实际机器执行和任务/客户端功能；完成率不能由来源锁或检查数量推算。完整迁移目标保持未完成，继续单人执行。


后续定位线索：已读取固定`kubejs-neoforge-2101.7.2-build.377-sources.jar`中的`recipe/special/ShapedKubeJSRecipe.java`与`recipe/schema/minecraft/ShapedKubeRecipe.java`。前者的JSON/网络codec只有镜像、配料动作和结果修改等附加字段，未读取`kubejs:shrink`；后者选择原版序列化器时也不考虑该键。现有GT schema虽提供noShrink方法，当前夹具仅证明工具符号与noMirror，不能证明noShrink效果。下一步用带边缘空格的真实合成配方、实际CraftingInput位置与往返codec建立失败证据，按固定旧源码语义修复，不能只保留一个无作用的方法名。


r30完成记录：完整入口退出0，`work/migration/installed-gtceu-rebuild.log`末尾为`CURRENT_PHASE_REBUILD_COMPLETE_FULL_PARITY_STILL_FALSE`，47份汇总检查全部通过。真实CLIENT数据生成、服务端正常注册与六组启动负例、已安装产物资源重载及两组资源负例均通过。16个安装/恢复检查纳入汇总；运行时清单2037个文件，包含派生身份记录。兼容JAR仍为`532d9295e203a0f779cedd754685ebc766e58455e5d1ee7baa1a4a5f90fe4c1b`，两个正式CoreMod及旧目标类锁未放宽。派生GT已安装，原始GT已保留；无世界/玩家/客户端视觉验收，任务导出仍为2/3814。候选源码审计也已使用缓存原件重新执行，结果和差异数字不变。


r30另行恢复输入预检通过，报告`evidence/local-restore-input-check.json`：五份固定源码归档及其解压树、18个上游身份、两个实例的派生GT、2036个需导入/核验的运行文件、9358个master输入和11977个旧包参考文件均通过。运行文件数比完整清单少1，是因为本地兼容JAR要求重建而未作为导入输入验证。master/旧包树仍按历史逐文件清单核验，不冒充重新审计原始ZIP；本次无整份工作区复制或恢复后启动。


## 有序合成定位、原点与缓存（2026-10-18，r31）

说明见[旧版合成定位](docs/legacy-shaped-geometry.zh-CN.md)。真实失败基线证明原noShrink在KubeJS377下会丢失外围空格；新增gtnh_migration:legacy_shaped序列化器和两个普通Mixin保留原网格坐标、相等性/哈希及配方缓存失效。GT schema仅在shrink=false选择新序列化器，并修正kjsCategory方法；默认与显式重置true仍走既有KubeJS路径。

固定Forge10.13.4.1614未修改源码在隔离几何测试桩中产生3465个输入结果，实际加载配方及JSON/网络往返共10395次对照通过；22次配方往返、26次默认/重置裁剪、6组容器动作、5项输入身份和4项实际缓存入口检查通过。原点和缓存Mixin缺失的负例被拒绝。缓存检查止于真实get入口后的Level查询，未构造World，不能声称游戏缓存命中已验证。旧逻辑3x3在2x2和4x4的边界按源码保留，未擅自重写。

新派生GT摘要57756c966739f22aee0d8eccfe061e39f36c1d9cac60b819ea74b10f7a55b239；旧86ce239b版本必须匹配previous_artifacts才能升级，先存入按摘要命名的派生缓存再替换，未知修改仍拒绝。原始下载锁、两个CoreMod和旧客户端类锁不放宽。安装/恢复检查扩为18项。Forge移植类的许可与归属随资源保留，不归入本地MIT声明。

完整重建日志为work/migration/shaped-geometry-rebuild.log，正式完成结果以随后记录为准。后续继续GTNH合成的NBT/充放电/附魔、阶段门槛、机器执行、任务提交/奖励及客户端视觉。无服务器、世界、玩家或WorldStem；EULA=false；任务依赖闭合导出仍2/3814，完整目标保持active，继续单人完成。

下一轮已核对的固定源码入口：GT5U `GTShapedRecipe.java` 的 `matches` 在 mKeepingNBT=true 时比较所有非空格是否带tag及完整tag内容，空compound与无tag不能擅自等同；结果先调用updateItemStack，再复制第一个带tag输入，处理电量、附魔后再次update。电量处理先清空结果电量，模拟读取各输入的可放电量，排除MetaGeneratedTool后以int累加，再给结果充电；不能把它简化为复制一个charge字段。`GTModHandler.java:2053/2077`包含IC2管理器、等级、模拟标志及int运算；`GTUtility.java:3119`附魔写回将等级先转byte再写short，属于必须明确对照的旧值域边界。这些只是待实现行为的源码定位，当前LegacyShapedRecipe没有实现它们。


r31完成记录：完整重建入口退出0，work/migration/shaped-geometry-rebuild.log末尾为CURRENT_PHASE_REBUILD_COMPLETE_FULL_PARITY_STILL_FALSE，47份汇总检查全部通过并已同步公开证据。兼容JAR摘要221f26a1637aed0f7c7b9d3ce8ea856a6377e110f9c37098b028c183d49e1944，GT派生摘要仍为本节57756c96版本；运行清单2037个文件，顶层模组19个。CLIENT验证17个选定类与7个保存钩子，并验证原网格；服务端注册及六个启动负例、资源正例及四个资源负例均通过。新增合成行为检查数量见本节和NativeResourceBridgeCheck.json。

首次完整执行曾因测试把七个钩子与目标列表前七项按下标绑定，在新增RecipeCache后发生错配；改为明确的钩子/类名映射，全部原断言保留，独立复查及完整入口重跑通过。失败日志保留work/migration/shaped-geometry-initial-failed.log。状态元数据同步纠正了过时的LDLib/资源重载待验收描述，但机器、真实工作台、玩家、任务提交/奖励和客户端视觉均仍未验收，full_parity=false。

r31只读恢复预检通过：五份固定源码归档/树、两个实例的派生身份、2036个运行输入、9358个master文件及11977个旧包参考文件一致，当前兼容JAR与公开锁匹配。报告evidence/local-restore-input-check.json已更新；未复制整份工作区，未启动恢复后的游戏。


## 旧版合成电量阶段（2026-10-18，r32）

`LegacyCraftingEnergy` 作为 `gtnh_migration:legacy_shaped` 的显式 `gtnh:legacy_energy` 选项接入实际配方组装；默认false。固定GT5 5.09.54.205的电量段和isElectricItem/chargeElectricItem/dischargeElectricItem表达式从锁定归档原样提取为独立对照类，只有物品/IC2调用由真实现代GT能力后端桥接。保留Int.MAX请求、逐槽模拟、IGTTool排除、int溢出以及输出容量；输入能力查询使用副本，结果独立，不修改模板。它没有迁移IC2本身、NBT/附魔/updateItemStack或全部源配方，不能据此宣布旧工具完整等价。

专项实际资源加载通过9375次表达式对照、10次JSON/网络往返及6个边界检查；真实LV/MV/HV电池、钢钻、铁锭参与。空实现替换负例被拒绝，既有四组资源负例保留，资源检查现为1正5负。21条隔离测试配方不装入基础包；活动迁移仍170条（167机器）、身份映射720、任务依赖闭合导出2/3814。GT派生JAR仍57756c96版本和25个重编译类，本轮仅修改本地兼容模组及测试/证据。

编译真实IGTTool需其LDLib父接口，build_capacity_compat.py从已锁定GT嵌套依赖提取到work/compat-mod/build/dependencies，并校验原LDLib摘要；不添加运行时模组副本。GT5移植类沿用LGPL-3.0，LGPL/GPL许可文本随资源保留。完整重建日志work/migration/legacy-energy-rebuild.log；完成结果以本节后续记录和phase报告为准。

下一步源配方入口已定位：ProcessingToolHead.java的toolHeadDrill分支约278行，LV锂/镉/钠三种电池分别配置100000/75000/50000容量，图案SXd/GMG/PBP，钢螺丝、钢板、小钢齿轮、LV电机和材料钻头，d为工具符号。不能只替换新GT输出名字而跳过原GT工具材料/耐久/附魔/NBT/充放电及贴图。MetaGeneratedItem01约4960行的CoverCopyPaste旧配方也已读取，但当前注册表未找到可直接证明等价的对应功能，不创建占位替代。候选8.0.1与当前7.0.2的首电池复制路径及未使用开关均非GTNH源规则，未将其整体套用。


r32完整重建完成记录：入口退出0，legacy-energy-rebuild.log末尾为CURRENT_PHASE_REBUILD_COMPLETE_FULL_PARITY_STILL_FALSE，47份汇总检查全部通过。客户端数据生成、服务端正常启动注册及六个启动负例、资源正例及五个资源负例均通过。兼容JAR摘要26baa6b39458b720e07c2bbc71abd7ca16697d736b10fba62ab32a5fe32fe9d9；GT派生摘要仍57756c966739f22aee0d8eccfe061e39f36c1d9cac60b819ea74b10f7a55b239。运行清单2037文件，顶层模组19个。新能力仅是显式选择的电量阶段，完整IC2/旧工具功能、源配方、NBT/附魔、玩家工作台和视觉仍未完成，EULA=false，无世界/玩家，目标继续active且由单人完成。

r32只读恢复输入预检通过：五份固定源码归档/树、2036个运行输入、9358个master文件、11977个旧包参考文件，以及两个实例的GT派生身份均一致，当前兼容JAR与公开锁匹配。未复制整份工作区，未进行恢复后游戏启动。
