# 主线 0:2 多物品检索与手动交付设计

核对日期：2026-10-18。状态：源码设计，尚未实现；不得据此开放导出或增加完成度。基线是已锁定 master 快照的 3814 个任务、BetterQuesting `3.8.89-GTNH`（`815d738923d46e9083f988421e5329193e2e5940`）与 FTB Quests `2101.1.36`（`8c53f35a97d8897c861b097f1e39f4fc3beb3a15`）。2.8.4 发行包的运行数据不能冒充该快照的注册表证据。

## 要保留的具体行为

`0:2 Sticks 'n Stones` 的前置仅为 `0:0`；包含两个原任务，不能把第一个任务拆成彼此独立的 FTB ItemTask：

| 原 index | 要求 | 行为 |
| --- | --- | --- |
| 0 | 9 个 `minecraft:gravel:0`；5 个 `minecraft:log:0` 或 `logWood` 成员 | 不消耗；逐要求保存完成锁定 |
| 1 | 2 个 `minecraft:log:0` 或 `logWood` 成员 | 手动交付；累计消耗进度 |

两者都是 `groupDetect=false`、`requireOnlyOneItem=false`、`ignoreNBT=false`、`partialMatch=true`、`autoConsume=false`。任务奖励仍须是 1 火把与 5 生存币，任务文本、两个任务的显示及数量均不能合并抹去。

旧按钮按任务 index 依次执行整个 quest 的未完成任务。玩家持有 9 砾石和 **5 原木** 时，先完成 index 0 的检测，再由 index 1 扣除 2 原木，剩余 3 原木；不要求另备 7 原木。仅将原生 FTB 的单任务交付按钮暴露出来，不能保证这一行为。[BQ-R] [BQ-Q]

## 检测器的状态模型

`TaskRetrieval.Detector` 的真实状态是 `player UUID → int[requirement count]`，外加独立的 `completeUsers` 集合，不是每队一个总数。[BQ-R] [BQ-STATE]

- **单槽分配：**非消耗检测为每个参与者建立独立 `remCounts`，初始等于该槽的数量；按原要求顺序分配。同一次检测、同一参与者的一件物品不能重复计入两个重叠要求。不能用多个独立 ItemTask 各扫同一槽替代。
- **锁定粒度：**`groupDetect=false` 时只把 `0 < progress < required` 的要求清零重算；达到要求的单项保留，哪怕整个任务尚未完成。已锁定单项在下一轮不再占用槽预算，因此相同物品可以在不同检测轮次先后满足重叠要求。这是源码行为；不能改成“一直必须同时持有全部物品”。
- **整任务完成：**AND 情况是任意一个参与者的整行要求全部达标，随后把本轮 `ALL_UUIDS` 全部标为完成。不能把甲、乙不同要求的历史最大值拼接成一行。整个任务完成后，普通检测直接返回。
- **多人检测：**扫描所有在线参与者的旧主背包与盔甲；扫描得到的物品数分别进入每一名队员的进度行，包含离线队员。不是按玩家各自背包分配专属进度，也不是把各人进度相加。现代副手不在旧扫描范围内。
- **手动消耗：**只读取发起者自己的背包；真正删除的数量加到所有 `ALL_UUIDS` 的相应进度行，离线队员也获得记录。不取走远端队员物品；未达标的消耗进度可以跨次累计。源码对其他队员的增量没有再次 clamp，持久化模型应保留原值。
- **触发范围与扫描范围不同：**BQ `PlayerContainerListener` 自动监听主背包槽 9..44，包括变空槽，并在下个 server tick 去重处理；盔甲变动不单独触发，但实际 `detect` 会扫描盔甲。FTB 现有监听器额外忽略空槽事件，不能直接承接未完成进度回落。[BQ-E] [FTB-I]

初期引擎可明确支持本任务需要的“多要求不消耗 + 单要求手动消耗”。`groupDetect=true`、OR、重叠多要求消耗及异常槽回调需要独立源对照测试，不能顺带声明已支持。尤其一般消耗分支在重叠要求间继续调用槽删除函数，其耗尽/空返回边界需验证，不能从非消耗算法推断。

## 手动按钮、保存与 FTB 接口

BQ 协议为 `betterquesting:quest_action`，`action=1`，携带 quest UUID 列表；服务端 `NetQuestAction.detectQuest → QuestInstance.detect` 检查可提交/解锁状态，然后按原 index 检测所有未完成任务。FTB `SubmitTaskMessage` 只携带单个 task ID，虽有服务端队伍锁定与前置校验，但语义不等同。[BQ-Q] [FTB-S]

建议注册专用 `gtnh_migration:legacy_retrieval` Task 类型：FTB 的公开 `TaskTypes.register(ResourceLocation, Provider, iconSupplier)` 与 `TaskType.createTask` 支持外部命名空间；扩展 Task 的数据读写、网络读写、图标/悬浮说明和按钮即可保留“一个 BQ task 对应一个 FTB task”。通用 `CustomTask` 回调仍只有一个标量进度，不能独自保存逐要求、逐玩家状态。[FTB-T]

必须同时实现以下配套，单有自定义 Task 不足以称为无损迁移：

| 模块 | 可实施接口与责任 | 尚缺证据/约束 |
| --- | --- | --- |
| `LegacyRetrievalEngine` | 纯状态转换；保留要求顺序、每行槽预算、单项锁定、完成集合；输出明确的槽消耗计划 | 用锁定 BQ 算法作为独立参考，覆盖重叠要求、历史进度及多人差异 |
| `LegacyRetrievalStateStore` | 以原 quest UUID、task index、player UUID 保存版本化进度；配置与运行进度分开 | FTB `TeamData.taskProgress` 是每任务一个 long；不能把数组塞成一个数字或把成员历史存在共享 Task 对象 |
| `LegacyQuestDetectPayload` | 服务端校验身份、quest/前置、队伍及配置；按 index 完整执行；客户端显示原“检测/提交”行为 | 保留 UI 的两组要求、消耗提示、逐项进度；网络重放、双击、无权限与掉线需实测 |
| `LegacyInventoryObserver` | 初次容器同步及主背包槽改变（含空槽）入队，下个 tick 去重；仅非消耗或 autoConsume 执行自动检测 | 不把本任务的手动交付变成自动扣物；不擅自扩大为盔甲/副手自动触发 |
| `LegacyPartyBridge` | 建立本轮在线/全部参与者，接入加入、退出、重置、离线加载与奖励历史 | BQ 加队会重置加入者并同步已完成 quest/领取状态；FTB 共用 TeamData 不自动证明等价，需专门队伍适配 |
| FTB 进度投影 | 自有状态是事实来源，FTB scalar 仅作 UI/完成事件投影；真实逐项进度单独同步 | `TeamData.setProgress` 会触发完成回调及嵌套库存检测；必须避免半次手动操作中重新检测或重复扣除 |
| 观察/交付设备桥接 | 后续接入 BQ `retrieveItems` 与 `submitItem` 的外部库存途径 | 原观察站可检测邻接库存，提交接口可接收外部物品；只做玩家背包不能宣布整个 retrieval 系统已迁完 |

消耗在服务端主线程完成：先校验槽内容与快照，按原任务顺序更新状态及库存，再发布进度同步。保存需考虑崩溃时“物品已扣、进度未落盘”及反向重放；实施方案必须写明恢复协议。不要在每个子要求刚完成时提前触发 FTB 的嵌套检测。按钮重复到达时，已完成/已交足要求不得再次扣除。

BQ 的加入流程 `PartyInvitations.acceptInvite` 会先 `resetUser(..., true)`，随后 `SyncPartyQuests(..., true)`；后者同步已完成 quest 并标记已领取，而不是简单合并每个未完成 task 的所有计数。退出、管理员重置、任务重载及旧存档导入仍需完整追踪；目前不能将 FTB 默认队伍合并直接视作证明。[BQ-P]

## `logWood` 的身份边界

实际谓词是 `StackMatch(base, sample) OR OreDictionaryMatch(...)`。本任务 base 是 `minecraft:log` damage 0，不能丢掉直接匹配支路。`OreIngredient.apply` 遍历实时 `OreDictionary.getOres("logWood")` 并调用 `OreDictionary.itemMatches(target, input, false)`，含 wildcard metadata；`getMatchingStacks` 的创造栏展开只是展示辅助，不足以证明全部可匹配 metadata。两支还保留旧 NBT 规则，空 required NBT 不能接受非空 sample NBT。[BQ-N]

已找到的注册来源证明它远超六种原版树木，**但不是完整清单**：CoreMod 的 `GT_Loader_OreDictionary:181` 增加 ForbiddenMagic `TaintLog:32767`；`OreDictHandler:164-165` 在 Fether 已加载时增加 `nether_log`；GT5U 的 `BOPBlockRegistrator:36,45` 增加 Rainforest/Pine 原木，`LogBase:35` 还包含公共注册路径。必须覆盖所有实际加载模组、条件分支及加载后修改。[ORE-C] [ORE-G]

已直接读取安装库中的 1.21.1 `server-...-extra.jar`：`minecraft:logs` 包括 `logs_that_burn`、crimson/warped stems；`logs_that_burn` 包含 mangrove/cherry；`oak_logs` 包含 `oak_log`、`oak_wood` 及两种 stripped 变体。因此 `#minecraft:logs` 既可能接受旧包不接受的新物品，也不能证明涵盖旧 ForbiddenMagic/Fether/GT++ 等成员。NeoForge 提供的 `c:stripped_logs` 等标签同样不是旧 `logWood` 的证据。

开放 `0:2` 前需取得并锁定：

1. **旧运行时清单：**精确基线模组 JAR、配置/脚本、加载顺序及哈希；所有注册完成后的 `logWood` 原始条目（注册名、metadata/wildcard、NBT），记录后续注册/修改。明确这是该快照运行结果，而非 2.8.4 参考结果。
2. **谓词样本：**原版 log/log2 各有效 metadata、modded logs、带名称/附魔/NBT 的样本；用实际 BQ/Forge matcher 输出接受与拒绝。wildcard 必须保留，不能仅枚举创造栏条目就声称完整。
3. **逐项迁移关系：**每个被旧谓词接受的身份都有现代实现或可证明等价的映射，保留缺失项；纹理/行为身份与“恰好同名”分开取证。映射不全就保持任务阻塞。
4. **现代运行时清单：**完成 datapack/tag reload 后的注册表、各候选标签展开集合与来源；固定模组/数据包哈希，检查未知新增成员。使用独立 `gtnh_migration:legacy_log_wood` 显式成员或自定义谓词，不直接继承可漂移的宽标签。
5. **集合及行为对照：**报告旧接受集合映射后相对现代谓词的 missing/extra，全部差异都须解释并解决；实际游戏验证多人、按钮顺序、保存重载及材料获得途径。

## 最小验收集合与推进顺序

先实现纯引擎和源对照测试，再做状态持久化/按钮/队伍桥接，最后接入经过证明的身份集合与游戏验证。至少包含：单槽重叠要求不在一轮双计；单项完成后跨轮锁定；未完成进度在槽清空后归零；多人背包相加但不拼接历史行；离线成员同步；只扣发起者；手动需求不自动扣；`9 gravel + 5 logs → 两task完成且剩3 logs`；数量不足可累计交付；重复按钮不重复扣；副手排除；加入/退出、重载、崩溃恢复、奖励不得重复领取。

本设计没有减少任何要求。现阶段可以推进引擎、协议和取证模块；`logWood` 完整成员、队伍生命周期、外部设备和真实游戏验证尚未闭合，仍然阻止“完整 retrieval 已迁移”或“0:2 已等价可用”的声明。

## 2026-10-18 已实现范围

`LegacyRetrievalEngine` 已实现非消耗 AND 多要求和单要求手动消耗的不可变计算模型；9,651 项断言、9,618 次固定 BQ 源码算法对照通过。测试原样提取 `detect`、`checkAndComplete`、`onInventoryChange` 和 `Detector`，库存、参与者、持久化及物品谓词仍为夹具。报告见 `evidence/quests/LegacyRetrievalEngineCheck.json`，源码和 runner 均有哈希绑定。引擎已编译进兼容 JAR，但未接入运行事件、任务配置或导出。

下一步继续实现持久化格式与任务级提交协议，保留逐 UUID 的多列进度和源 task 顺序；之后再验证队伍生命周期、准确身份集合和游戏中的事务行为。此阶段不把未支持模式当作删除需求，也不增加已迁移任务计数。

[BQ-R]: https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/bq_standard/tasks/TaskRetrieval.java
[BQ-Q]: https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/QuestInstance.java
[BQ-P]: https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/party/PartyInvitations.java
[BQ-STATE]: https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/bq_standard/tasks/base/TaskProgressableBase.java
[BQ-E]: https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/bq_standard/handlers/PlayerContainerListener.java
[BQ-N]: https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/utils/ItemComparison.java
[FTB-I]: https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/util/FTBQuestsInventoryListener.java
[FTB-S]: https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/net/SubmitTaskMessage.java
[FTB-T]: https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/quest/task/TaskTypes.java
[ORE-C]: https://github.com/GTNewHorizons/NewHorizonsCoreMod/tree/f769c65f68a352160b1e35770f6f7c579716baa5/src/main/java/com/dreammaster
[ORE-G]: https://github.com/GTNewHorizons/GT5-Unofficial/blob/a3e1e11241a814c9fa0dd0973d5699548428f689/src/main/java/gtPlusPlus/xmod/bop/blocks/BOPBlockRegistrator.java
