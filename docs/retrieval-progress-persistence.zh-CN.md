# 检索任务逐 UUID 进度持久化规范

本阶段实现 `LegacyRetrievalProgressCodec` 和独立测试，尚未连接 FTB 保存、任务注册、游戏事件、队伍生命周期或导出。`runtime_integrated=false`、`ftb_storage_integrated=false`、`export_enabled=false`。它只处理**一个已确认身份的任务的进度层**，不读取世界或整份旧玩家档案。

## 源码确定的语义

BetterQuesting 固定为 `3.8.89-GTNH`，提交 `815d738923d46e9083f988421e5329193e2e5940`。

| 对象 | 必须保存的行为 |
| --- | --- |
| `TaskBase.completeUsers` | UUID 集合，独立于计数。完成但没有进度行、进度全满但未标完成，都可以存在；codec 不重新推导完成状态。 |
| `TaskProgressableBase.userProgress` | 每 UUID 一整行计数。`merge=false` 先清空旧集合和进度；`merge=true` 完成集合取并集，传入行覆盖同 UUID 的旧行，不取最大值或求和。缺席 UUID 在 merge 中保留。 |
| 重复 UUID | 合法进度列表按顺序读取，最后一行覆盖前一行；完成列表重复项由集合去重。原输入仍保留。 |
| 磁盘读入 shape | `readUserProgressFromNBT` 对 `data` 的 IntArray 和旧 Int List 都按当前需求数量截断或零填充，保留共同前缀。 |
| 运行中 shape | `getUsersProgress` 在存储行长度不匹配时返回新的全零数组；并不立即替换存储行。这与磁盘加载不同。 |
| 写出 shape | `writeUserProgressToNBT` 原样写存储行的 IntArray，不按当前需求数量规范化。 |
| 同长度需求变化 | 源码只比较长度，不比较物品 ID 或需求摘要。同长度重排不能擅自清零或重新排列计数。 |
| users 筛选 | 写出时 `users=null` 保存所有 UUID；指定列表只写其中的进度行和完成标记。筛选结果应以 merge 应用，不能误用为全量替换。 |

依据：[TaskBase](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/bq_standard/tasks/base/TaskBase.java)、[TaskProgressableBase](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/bq_standard/tasks/base/TaskProgressableBase.java)、[TaskRetrieval](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/bq_standard/tasks/TaskRetrieval.java)。测试 runner 固定检查三个源文件的 SHA256。

## Codec 的输入输出合同

现代版本 1 使用真实 1.21.1 NBT：

```snbt
{
  schema_version: 1,
  userProgress: [
    {uuid: "00000000-0000-0001-0000-000000000001", data: [I; 9, 5]}
  ],
  completeUsers: ["00000000-0000-0001-0000-000000000001"]
}
```

- `readLegacy(payload, requirementCount, previous, merge)` 接收旧进度层，不接受 `schema_version`；`readModern(...)` 要求精确的 IntTag 版本 1。两个入口都接受 IntArray 或旧整数 List 的 `data`，canonical writer 只写 IntArray。
- 这两个入口都是**磁盘加载操作**。它们执行前缀截断/零填充，不供每次检测前重复调用。运行中的状态直接交给 engine。
- `writeModern(state)` 不过滤 UUID；重载的 `selectedUsers` 参数实现源筛选写出。进度行和完成集合按 UUID 自然顺序写出，与源 TreeMap/TreeSet 一致。
- `ReadResult.state()` 只在整个文档被接受时有值；`active()` 为 false 时桥接必须禁止提交、检测或覆盖保存。
- `ReadResult.original()` 每次返回输入 NBT 的深拷贝，包括未知字段、原始 tag 类型、重复行、被源加载语义截掉的数组后缀。调用者修改原输入或返回的拷贝不会改变保留副本。
- 缺少 `userProgress` 或 `completeUsers` 与源代码一样代表空列表；缺少某行的 `data` 代表全零行。缺少或非法 UUID 不接受。要求数量必须为正，符合当前 engine 的支持范围。

此 codec 不修改输入、旧状态或任何文件。首次导入旧档案时，未来存储桥接必须同时归档原任务记录，并独立保存解释后的活动状态；不能因活动状态已经完成 shape 规范化就丢弃原数组后缀或旧 List 形式。

## 未知和错误数据保留

版本未知、版本 tag 类型错误、任何未知字段、错误列表元素类型、非法 UUID、负计数，都会阻止**整个进度文档**激活。诊断包含字段路径和错误代码，原 NBT 树仍可由 `original()` 保存。不跳过坏行后悄悄激活剩余部分。被截断后缀里的负计数也不例外。

这是明确的保留策略，不能宣称等同于旧版对损坏数据的容错：旧版会记录异常后跳过部分 UUID，也会把某些错误 tag 类型当成空数据；本实现拒绝借此丢弃无法解释的数据。已知有效、非负的进度子集才做源等价测试。

未知字段不自动视作无关扩展。未来支持新字段必须增加已审查 schema 或显式解析规则；不得先删除未知字段来让记录通过。现代版本 1 的入口也不能回退到无版本旧格式。

保留的是 NBT **类型和值的树结构**，不是 gzip 压缩字节或 CompoundTag 键顺序。若输入在 NBT/旧 JSON 解析阶段就损坏，codec 尚未接收到 CompoundTag；外层导入器必须保留原文件字节和错误报告，禁止用空数据覆盖。二进制读取应使用有配额的 `NbtAccounter`，不能把无界读取当成兼容保证。

## 旧文件身份包装和 FTB 桥接

[TaskStorage](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/tasks/TaskStorage.java) 会在同一个任务 compound 添加 `taskID` 和 `index`；[QuestDatabase](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/QuestDatabase.java) 再附加 quest UUID，旧整数 quest ID 还有单独转换路径；[SaveLoadHandler](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/handlers/SaveLoadHandler.java) 的玩家保存使用 `users=[player]` 写入带类型的 JSON。

因此完整 TaskStorage 记录直接传给本 codec 会被未知字段规则阻止。未来桥接应先验证并完整保存 quest 身份、task index、factory ID 和包装层，再将明确识别的进度层交给 codec。不能无条件删除 `taskID`/`index`，更不能将某个未知任务的进度解释为 retrieval。`TaskPlaceholder.orig_prog` 的恢复、旧整数 quest ID 转换和带类型 JSON 解析尚不属于本实现。

建议活动状态的逻辑键为“已验证的源 quest UUID + task index + factory ID”，内部仍为全局 UUID→进度行及独立完成集合。离线或当前不在某个队伍的 UUID 不能按队伍成员列表过滤掉。磁盘物理文件可以按玩家分片，但逻辑状态不能退化为每队单值，也不能在迁队时无依据复制、取最大值或拼接历史。

锁定 FTB Quests `2101.1.36` 的 [TeamData](https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/quest/TeamData.java) 把 `task_progress` 保存为 task→long；`player_data` 仅包含权限、自动固定和固定任务列表，不能直接表示本矩阵。其 `mergeData` 对任务进度取 `Long::max`，不应代替旧逐 UUID 语义。[Task.readData/writeData](https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/quest/task/Task.java) 保存的是任务定义，不能用于玩家进度。

下一层需要独立的 namespaced 存储，或经过验证的扩展桥接；只向 FTB 投影必要的显示/完成结果。实际保存时机、原子写入与恢复、任务身份映射、迁队/重置、客户端同步和提交事务仍须单独实现与测试，本 codec 不提供这些保证。

## 实际验证方式

运行：`python work/migration/test_legacy_retrieval_progress_codec.py`。runner 使用恢复后的锁定 JDK 21 和真实 Minecraft 1.21.1 NBT 类，不调用服务器、世界或 registry bootstrap，也不改 EULA。

测试 oracle 原样提取源 `TaskBase`/`TaskProgressableBase` 的读写方法及 `TaskRetrieval` 的 getter/逐行读写方法。旧 NBT 类名称的薄包装直接委托现代 CompoundTag/ListTag/IntArrayTag；不是只让新 codec 自己往返。日志、需求列表数量和继承容器是夹具，因此不声称执行了 1.7.10 完整运行时。

覆盖 IntArray 与旧 Int List、截断/补零、运行中 getter 区别、缺省数据、UUID 覆盖与集合合并、独立完成标记、筛选保存、未知/非法数据隔离及深拷贝、真实 NbtIo 普通/压缩二进制往返，并用确定随机序列和源读写做对照。独立报告生成于 `work/migration/retrieval-progress-check/LegacyRetrievalProgressCodecCheck.json`，明确所有未集成标记。
