# 任务索引顺序提交规划器

`LegacyQuestRetrievalPlanner` 是用于主线 `0:2` 所需子集的纯计算模块，尚未接入 FTB、游戏事件、导出、奖励或存档事务。它接收一个 quest 的原任务声明、`task index → LegacyRetrievalEngine.State`、库存快照及入口检查结果，输出完整计划。它本身不扣除任何游戏物品，也不能作为“该 quest 已迁移完成”的证据。

## 保留的源行为

依据锁定 BetterQuesting `3.8.89-GTNH` 提交 `815d738923d46e9083f988421e5329193e2e5940` 的 `QuestInstance.detect` 与 `TaskStorage → SimpleDatabase → LookupLogic`：

- 按原 task index 升序执行，不使用声明列表的插入顺序，不把非连续 index 重新编号。
- 跳过发起者已经完成的任务。前一个任务尚未完成时，仍继续检测后面的任务。
- 每个检索任务调用现有 `LegacyRetrievalEngine`，后一任务使用前一任务返回的库存；每个任务分别保存 UUID 进度行和完成集合。
- `9 gravel + 5 logs` 先检测 index 0，再提交 index 1 的 2 logs，最终两项任务完成并剩 3 logs。交换两个任务的 index 后，先扣除的 2 logs 使后续检测仅见 3 logs，不再完成同一组要求。
- 手动扣物仍只来自发起者；离线成员保留独立进度并接收原算法的增量和任务完成标记。任务完成不等于离线玩家的 quest 完成或奖励领取。
- 输出分别保留 quest 循环的 dirty 信号及每个任务的 party dirty 信号。只有未完成的部分进度改变时，原 quest 循环未必标脏，但任务本身仍可标脏；不能把这两种信号混为一个。

## 输入及拒绝边界

`Quest` 必须明确给出 AND、负数 repeatTime、simultaneous/global/editMode 均为 false；globalShare 保留原始值，非全局检测中不影响算法。所有任务必须是已审查的 `bq_standard:retrieval`、ignored=false，且提供引擎接受的配置。引擎仍仅支持非消耗 AND 多要求及单要求手动消耗。

空 quest、重复 index、未知/非检索任务、缺少检索配置，以及上述未支持模式，都会在任何任务运行前整体拒绝。未知任务即使发起者已完成，也不能悄悄略过。`UnsupportedQuestException` 返回原声明及逐项问题；原完整 NBT/配置仍由调用方保存，不能用本模块的有限字段替换或删去原需求。

`Gate` 是调用方已验证的 quest cache、canSubmit、unlocked、questAlreadyComplete 快照。它不是权限校验实现。这里支持的非重复 quest 在入口不满足时输出未执行计划；未来服务端桥接仍须真实校验身份、前置、队伍与配置。

`K` 必须是不可变身份键，或调用方独立深复制、在规划期间及之后均不修改的快照；匹配谓词必须纯净。容器结构的复制不能使可变 ItemStack/NBT 自动成为不可变对象。本轮测试使用不可变 String 身份，不证明 Minecraft 物品、NBT 或 ore/tag 映射准确。

## 计划与后续事务

`Plan` 包含前后库存、前后各任务状态、按顺序的检测结果、合并消耗清单、是否执行、发起者的任务 AND 结果及 quest dirty 信号。缺失的声明任务状态规范化为空状态；输入中的其他 task index 状态原样保留，不擅自删除。

全部计算完成后才返回计划；后续任务或谓词失败时不返回部分计划，原输入仍不改变。这只是一次原子计算，不是可恢复的存档事务。未来提交适配器仍需在服务端主线程验证快照、处理并发/重放、持久化进度与扣物的崩溃恢复，并最终发布同步。本模块不触发 FTB 的完成回调，也不调用 quest setComplete、奖励或库存 API。

## 独立对照与复现

运行 `python work/migration/test_legacy_quest_retrieval_planner.py`。本机输入默认来自仓库根目录，可用 `GTNH_TEST_INPUT_ROOT` 指向已恢复的锁定输入；输出仍写当前仓库的忽略目录 `work/migration/quest-planner-check/`。

runner 对五个原 BQ 源文件做 SHA-256 检查，原样提取并执行 `QuestInstance.detect`、`SimpleDatabase.getEntries`、`LookupLogic.getRefCache/computeRefCache`。每个任务再执行原 `TaskRetrieval.detect/checkAndComplete/onInventoryChange/Detector`。检索夹具由现有 runner 的字符串常量取得，不执行或改写原 runner。库存/玩家/cache/property 及存储包装仍是测试夹具；真实旧任务循环和排序方法直接参与对照。

当前通过 9,716 项断言、2,414 次源码对照（包括非全局 globalShare=true 的原循环对照）。覆盖正常及反向任务顺序、重复提交、稀疏 index、非首位 actor、offline、未达标前任务后仍交付、部分进度累积、入口拒绝、未知类型与未支持模式整体拒绝、后任务失败不修改输入，以及固定种子的多次随机检测。规划器和 oracle 分别取得独立输入副本，并逐次验证调用前后输入不变，避免一个实现的修改污染另一侧。

报告 `LegacyQuestRetrievalPlannerCheck.json` 绑定生产源码、测试、runner、原源码及生成 oracle 的哈希；明确 `atomic_calculation_only=true`、`runtime_integrated=false`、`export_enabled=false`、`game_runtime_verified=false`。测试成功不增加已迁移任务数，也不解除 `logWood` 完整身份集合、队伍生命周期、UI/网络、持久化事务和实际游戏验证的阻塞。
