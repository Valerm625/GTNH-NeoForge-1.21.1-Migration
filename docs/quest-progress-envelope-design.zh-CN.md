# 任务进度身份外层：只读核对与下一阶段接口

本文仅设计外层进度 envelope，不实现生产类，不改变已冻结测试、运行配置或任务导出。基准源码是 BetterQuesting `3.8.89-GTNH`，提交 `815d738923d46e9083f988421e5329193e2e5940`。本轮检查了提取的 `work/source` 与 `work/source-release-2.8.4`：其中没有 `QuestProgress.json` 或 `QuestProgress/*.json` 玩家存档 fixture。因此，下述存档结构来自固定源码的读写方法；真实整合包定义 fixture 只用于验证任务身份和整数类型，不冒充玩家存档证据。

## 实际加载和保存路径

[SaveLoadHandler](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/handlers/SaveLoadHandler.java) 先加载任务定义，再加载进度，随后才加载队伍数据：

1. 世界的 `betterquesting/QuestProgress.json` 存在时，读取 UTF-8 JSON，调用 `NBTConverter.JSONtoNBT_Object(..., true)`，取根 `questProgress` 的 Compound List，调用 `QuestDatabase.readProgressFromNBT(..., false)`。
2. 然后遍历 `betterquesting/QuestProgress/` 中名称以 `.json` 结尾的文件，同样解码，逐文件调用 `readProgressFromNBT(..., true)`。`File.listFiles()` 的结果未排序。
3. `savePlayerProgress(player)` 把所有 quest 的进度按 `users=[player]` 筛选，写入 `QuestProgress/<player UUID>.json`。读取步骤没有从文件名解析 UUID，也没有检查内容 UUID 必须等于文件名。

[JsonHelper.ReadFromFile](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/utils/JsonHelper.java) 会忽略不存在文件及名称包含 `.DS_Store`/`malformed_` 的文件；解析异常时备份为 `malformed_<原名>.json` 并返回空对象。迁移工具不能把这个空对象当作“原存档为空”的证据：应保留原字节及错误，禁止覆盖原文件。

## 字段、类型和身份

以下是源码 writer 产生的层级，不是实测玩家档案：

| 层级 | NBT 字段 | Writer 的精确类型与意义 |
| --- | --- | --- |
| 文件根 | `questProgress` | List<Compound>，每项一个 quest 的进度记录。 |
| quest | `questIDHigh`, `questIDLow` | 两个 LongTag，分别是 UUID 的两个有符号 64 位半部。 |
| quest | `completed` | List<Compound>，每项包含玩家 `uuid` StringTag，保存整个完成信息 compound。常规操作写 `claimed` ByteTag 和 `timestamp` LongTag。 |
| quest | `tasks` | List<Compound>；其元素位置不是任务身份。 |
| task | `index` | IntTag，由任务数据库的整数 ID 写出，必须与任务定义的 index 绑定。 |
| task | `taskID` | StringTag，任务 factory ID；本检索 codec 对应 `bq_standard:retrieval`。 |
| retrieval task | `completeUsers` | List<String>，独立的任务完成玩家集合；不是 quest 的 `completed`。 |
| retrieval task | `userProgress` | List<Compound>，行内为 `uuid` StringTag、`data` IntArrayTag；旧 `data` Int List 由现有 codec 支持。 |

身份逻辑来自 [QuestDatabase](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/QuestDatabase.java)、[TaskStorage](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/tasks/TaskStorage.java) 和 [NBTConverter.UuidValueType](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/utils/NBTConverter.java)：

- 读取 quest 时优先取同时存在且为数字类型的 High/Low。否则，若旧 `questID` 是数字类型，调用 [UuidConverter.convertLegacyId](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/utils/UuidConverter.java)，即非负整数 `n → UUID(0,n)`；负值会被断言参数检查拒绝。读取进度不创建不存在的 quest。
- 源 `hasKey(...,99)` 接受多种数字 tag；writer 的规范形态仍是 Long/Long 或历史 Int。最小迁移版本可先仅激活这两个已审查整数形态，其他数字转换保留为不支持，不能悄悄强转或声称与旧容错完全等价。
- 进度加载不调用 `tryReadIdString`。不要从任务名称、目录名、Base64 文件名或字符串 `questID` 推断身份。两个半部必须保留完整 64 位；不能经过 JavaScript Number 或其他双精度浮点中间值。
- task index 缺失/非数字时，旧进度路径使用 `-1`，查不到任务后不应用。任务**定义**读取里的 `nextID()` 回填不适用于进度读取；JSON List 元素的 `0:10` 序号也不能代替 `index`。
- 已存在的普通任务只接收相同 factory ID 的进度，除了下面单独讨论的 placeholder 恢复路径。结构相似的 `bq_standard:optional_retrieval` 不能冒充普通 retrieval。
- 逻辑 key 应为 `(source quest UUID, task index, exact factory ID)`，在同一个已验证的世界/整合包上下文中使用。不能只用 FTB task long ID、玩家文件名或当前 team UUID。

## 格式化 JSON 不能当成普通 JSON 数据模型

[NBTConverter](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/utils/NBTConverter.java) 的 `format=true` writer 在字段名末尾追加 `:<tag ID>`；List 写成带 `0:<元素类型>`、`1:<元素类型>` 等键的 JSON 对象，空 List 写成 `{}`。IntArray 则是 JSON 数字数组。例如字段名会是 `questProgress:9`、`questIDHigh:4`、`index:3`、`uuid:8`、`data:11`；旧 Int List 的 `data:9` 内每项为 `0:3` 等。

读取 compound 时只切分最后一个冒号。读取 List 对象时按 JSON 对象迭代顺序追加元素，**不解析或排序冒号前的数字序号**。下一阶段不能先对键排序再“恢复数组”，也不能丢弃 tag 后缀再猜类型。该读取函数也有无标签类型推断和错误回退；例如无标签整数被推断为 LongTag，并不自动成为旧 `data` 所需 Int List。

最小导入器应保留原 UTF-8 字节、对象顺序和 tag 类型；对重复 JSON 键、剥离类型后发生的键碰撞、未知 tag ID、混合元素类型、越界/非整数数值或解析错误，先完整保留并拒绝激活。不能依靠 Gson 覆盖前值、NBT 转换器返回空字符串或默认空 List 来掩盖损失。原始 JSON 的字节保存与解析后 NBT 的树保存是两个不同保证。

## 合并的精确作用范围

`merge=false` **不是全 store 清空**：

| 调用层 | `false` 的实际范围 | `true` 的实际范围 |
| --- | --- | --- |
| QuestDatabase | 不重置全库；只处理输入出现、身份可识别且定义存在的 quest。缺席 quest 保留。 | 同样只处理出现且已存在的 quest。 |
| QuestInstance | 清当前被处理 quest 的 `completed` map，再读传入完成记录；对任务调用 TaskStorage。 | 完成记录按玩家 UUID 覆盖整个 compound，缺席 UUID 保留；不是只合并 claimed/timestamp 字段。 |
| TaskStorage | 不 reset 任务库，也不重置缺席 task；只把参数传给输入中出现且身份路由成功的 task。 | 相同路由规则。 |
| Retrieval 进度层 | 清这个已匹配 task 的计数 map 和完成 set，再加载。 | 每 UUID 行覆盖、任务完成 set 并集，缺席 UUID 保留。 |

[QuestInstance](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/QuestInstance.java) 对 `completed` compound 整体复制和保存；除 `uuid` 外，不能仅提取 claimed/timestamp 后丢掉其余内容。quest 完成记录与 task 完成集合必须分开保管，不能相互推导。

同一文件中重复 quest/task 记录按列表出现顺序应用；后者可能覆盖前者，且 `false` 的重置范围仅限上述被路由对象。跨玩家文件的顺序源自未排序的目录枚举；存在冲突时，迁移器必须保留每份输入和来源顺序证据，不能自行采用字母排序、文件时间或“取最大值”。没有原顺序证据且结果会改变时，应阻止相关身份的激活，保留冲突记录供后续处理。

加载某个任务时，需求数必须来自已验证任务定义：磁盘读入 `data` 保留共同前缀并截断/补零，运行中的长度不匹配 getter 才返回全零。定义摘要是解释依据和审计证据，不是触发任意清零、按物品重新配对或更换身份的理由。

## Placeholder 恢复必须单独验证

[TaskPlaceholder](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/placeholders/tasks/TaskPlaceholder.java) 的 factory 是 `betterquesting:placeholder`；配置保存在 `orig_data`，进度保存在 `orig_prog`。`TaskStorage.readProgressFromNBT` 有三条特殊路径：

1. 现有任务是 placeholder、传入 factory 不同：保存整个传入 task compound 为 `orig_prog`。
2. 两者都是 placeholder：读入传入的 `orig_prog`。
3. 现有任务已恢复为普通任务、传入 factory 是 placeholder：直接把 `orig_prog` 交给现有任务。该分支本身没有再次校验内部 factory/index。

最小新 envelope 应把 placeholder 记录及嵌套原件视作不透明、不可激活的数据；不能简单 unwrap 并套用 retrieval codec。未来支持恢复时，必须同时验证外层 index、定义侧恢复身份、内部 factory/index 和来源记录，保留整条包装链。placeholder writer 还忽略 `users` 筛选并写出整个 `orig_prog`，因此即使文件名是某玩家，也不能假定该文件的所有嵌套 UUID 都属于此人。

## 现有 codec 的接入点与最小建议接口

`LegacyRetrievalProgressCodec.readLegacy` 仅识别进度层 `userProgress`/`completeUsers`。真实 task compound 多出的 `index`、`taskID` 会被它保留并拒绝激活，这是正确的防误读边界。外层必须先验证身份、已存在定义及 task 类型，再提取一个**新的进度 compound**；原 task、quest、文件外层同时归档，不能直接就地删字段。

建议下一阶段只实现下列接口，均返回不可变结果，不触发 FTB 保存或任务完成事件：

```text
TaskKey(UUID questId, int taskIndex, String factoryId)
DefinitionCatalog.lookup(TaskKey) -> 已验证的任务定义与需求数
inspectTypedJson(rawBytes, sourceIdentity) -> 原字节 + 有类型NBT/诊断
inspectQuestProgress(rawNbt, catalog) -> 有序记录 + 原NBT + 不透明记录 + 诊断
planApply(previousStore, orderedRecords, merge) -> 新store候选 + 原件索引 + 阻塞项
writeStore/readStore -> 独立schema版本、精确TaskKey、活动进度、原件和不透明记录
```

第一版可以从已解析的 NBT 入手，将旧格式 JSON 解析作为独立阶段；不得对外声称已经支持玩家 JSON 文件迁移。`DefinitionCatalog` 不可由进度本身临时创建任务。有效 task key、正常进度和候选 state 可以单独列出用于审查，但未解决身份冲突、未知版本或未知语义时不得自动提交为可运行存档。

必须阻止或隔离的情况包括：未知 schema；不可识别/不完整/有歧义的 quest 身份；缺失、负数、越界或不受支持类型的 index；没有对应定义；factory 不符；placeholder 未完成身份核验；未知字段可能影响语义；损坏的完成记录；未知任务类型；带冲突的重复或跨文件覆盖；内层 codec 拒绝的数据。每种情况都保留原字节/NBT和定位信息，不把未应用记录当成不存在。store 的更新不能清掉旧文件未提及的 key。

## 已有实际 fixture 和下一阶段测试缺口

本地供应的 master 包中有两个可核验的**定义** fixture：

- `work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/AndSoItBegins-AAAAAAAAAAAAAAAAAAAAAA==/SticksnStones-AAAAAAAAAAAAAAAAAAAAAg==.json`：SHA256 `b10a26b4012b0b407dd97bb5d486fbf4d819ee85c37248ea8ab61946e2d05f6b`。真实身份为 High=0、Low=2；任务 index=0/1，factory 均为 `bq_standard:retrieval`；需求 shape 分别为 2/1。
- `work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/TheGreenRevoluti-GbGDSZsbQUekpRKT9ZPdjw==/CropSticks-O65QUnR0QuGSoQrsUMTB1g==.json`：SHA256 `f3a9e572ac771aef9f76c6121ad7a4cb3f5238df6b9adc76238df056d389a52e`。High=`4300463009256719073`，Low=`-7881005862838025770`，证明不能把所有 quest 身份压缩为旧整数 ID，也不能用双精度数值保存两半 UUID。

已有 `LegacyRetrievalProgressCodecCheck` 只验证内部进度读写与现代 NBT；尚未执行 JSON→NBT、QuestDatabase/TaskStorage 外层路由、placeholder 恢复、全库/分片加载或真实玩家存档迁移。

下一阶段应使用固定源码方法提取 oracle 和真实 1.21 NBT，先验证：上述两种 UUID；canonical 与 legacy ID 路径；index 与列表位置不同；factory mismatch；缺席 quest/task 在 `false` 下保留；重复记录顺序；quest completed compound 与 task completeUsers 独立；placeholder 不透明保存；未知字段/损坏 JSON 原件保存；内层 shape 规则；跨文件冲突拒绝。生成的组合测试数据必须标为合成 fixture，不能称为真实旧玩家存档。真实存档缺口仍需在证据报告中保留。
