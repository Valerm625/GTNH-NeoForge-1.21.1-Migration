# 任务进度身份外层：纯模型已实现，存档桥接未完成

Phase 3 已实现 `LegacyQuestProgressEnvelope` 不可变候选模型及独立 typed JSON 读取器，并用真实 1.21.1 NBT 验证；尚未连接文件存储、游戏或任务导出。基准源码是 BetterQuesting `3.8.89-GTNH`，提交 `815d738923d46e9083f988421e5329193e2e5940`。本轮检查了提取的 `work/source` 与 `work/source-release-2.8.4`：其中没有 `QuestProgress.json` 或 `QuestProgress/*.json` 玩家存档 fixture。因此，下述存档结构来自固定源码的读写方法；真实整合包定义 fixture 只用于验证解码和任务身份，进度均为合成数据，不冒充玩家存档证据。

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
- 源 `hasKey(...,99)` 接受多种数字 tag；writer 的规范形态仍是 Long/Long 或历史 Int。当前 envelope 仅接受这两个已审查整数形态；半对 UUID、两种身份并存或其他数字类型保留原 NBT 并拒绝整个候选，不能悄悄强转或声称与旧容错完全等价。
- 进度加载不调用 `tryReadIdString`。不要从任务名称、目录名、Base64 文件名或字符串 `questID` 推断身份。两个半部必须保留完整 64 位；不能经过 JavaScript Number 或其他双精度浮点中间值。
- task index 缺失/非数字时，旧进度路径使用 `-1`，查不到任务后不应用。任务**定义**读取里的 `nextID()` 回填不适用于进度读取；JSON List 元素的 `0:10` 序号也不能代替 `index`。
- 已存在的普通任务只接收相同 factory ID 的进度，除了下面单独讨论的 placeholder 恢复路径。结构相似的 `bq_standard:optional_retrieval` 不能冒充普通 retrieval。
- 逻辑 key 应为 `(source quest UUID, task index, exact factory ID)`，在同一个已验证的世界/整合包上下文中使用。不能只用 FTB task long ID、玩家文件名或当前 team UUID。

## 格式化 JSON 不能当成普通 JSON 数据模型

[NBTConverter](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/api/utils/NBTConverter.java) 的 `format=true` writer 在字段名末尾追加 `:<tag ID>`；List 写成带 `0:<元素类型>`、`1:<元素类型>` 等键的 JSON 对象，空 List 写成 `{}`。IntArray 则是 JSON 数字数组。例如字段名会是 `questProgress:9`、`questIDHigh:4`、`index:3`、`uuid:8`、`data:11`；旧 Int List 的 `data:9` 内每项为 `0:3` 等。

读取 compound 时只切分最后一个冒号。读取 List 对象时按 JSON 对象迭代顺序追加元素，**不解析或排序冒号前的数字序号**。现有读取器保留这一顺序，不能先对键排序再“恢复数组”，也不能丢弃 tag 后缀再猜类型。源读取函数也有无标签类型推断和错误回退；例如无标签整数被推断为 LongTag，并不自动成为旧 `data` 所需 Int List。

已实现的 `LegacyTypedNbtJsonReader` 保留原 UTF-8 字节、对象顺序账本和 tag 类型；对重复 JSON 键、剥离类型后发生的键碰撞、未知 tag ID、混合元素类型、越界/非整数数值或解析错误，完整保留原件并拒绝候选 NBT。现代 Float/Double NBT 公开 API 会把负零归一为正零，因此负零也保留原字节并拒绝；没有用反射或全局适配掩盖该缺口。不能依靠 Gson 覆盖前值、NBT 转换器返回空字符串或默认空 List 来掩盖损失。原始 JSON 的字节保留与解析后 NBT 的树保留是两个不同保证；两者都不等于已经保存到玩家存档。支持域见 [typed JSON 读取器](typed-nbt-json-reader.zh-CN.md)。

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

当前 envelope 遇到 placeholder 时保留完整输入 NBT（含嵌套原件）并拒绝整个候选，不简单 unwrap 后套用 retrieval codec。未来支持恢复时，必须同时验证外层 index、定义侧恢复身份、内部 factory/index 和来源记录，保留整条包装链。placeholder writer 还忽略 `users` 筛选并写出整个 `orig_prog`，因此即使文件名是某玩家，也不能假定该文件的所有嵌套 UUID 都属于此人。

## 已实现的候选接口与存储边界

`LegacyRetrievalProgressCodec.readLegacy` 仅识别进度层 `userProgress`/`completeUsers`。真实 task compound 多出的 `index`、`taskID` 会被它保留并拒绝，这是正确的防误读边界。新 envelope 先验证身份、已存在定义及 task 类型，再复制 task compound，仅从新副本移除已验证的 `index`/`taskID` 后交给 codec；完整输入 NBT 另作深拷贝保留，不就地删字段。

已实现接口均只生成内存结果，不触发 FTB 保存、任务完成或领奖：

```text
TaskKey(UUID questId, int taskIndex, String factoryId)
Definition(TaskKey key, int requirementCount)
Catalog(已验证quest UUID集合, 已验证Definition集合)
LegacyTypedNbtJsonReader.inspect(rawUtf8, sourceIdentity)
  -> 原字节 + 来源 + 顺序账本 + 可选NBT + 诊断
LegacyQuestProgressEnvelope.readLegacy(rawNbt, catalog, previousState, merge)
  -> 原NBT + 可选State候选 + 诊断
State.questCompletions() -> quest UUID → 玩家UUID → 完整completion compound深拷贝
State.tasks() -> TaskKey → 独立逐UUID检索状态
```

`accepted=true` 仅表示该读取层产生了可解释的候选；不表示可运行存档。Catalog 必须来自独立验证的定义，不能由进度临时创建任务，同 quest 的重复 index 即使 factory 不同也拒绝。reader 可以解析完整任务定义的 typed JSON，但它没有把定义转换成进度的权限；把完整定义交给 envelope 会因未知外层字段被整份拒绝。`completed` 除 `uuid` 路由字段外是源 API 明确保留的不透明 compound，允许各种额外 Tag 类型并按 UUID 整 compound 覆盖；这里不执行或推导 claimed/timestamp、完成或领奖行为。

当前没有 `writeStore/readStore` 文件存储接口，没有按文件名或时间排序的跨文件接口，也没有版本化的世界账本。单次调用只解释一个显式传入、内部有序的 NBT 文档；输入与 previous 不变，返回 NBT 也使用防御副本。后续保存必须另行实现原字节、原 NBT、定义版本与候选状态的关联及恢复协议，不能将有限模型写回后冒充完整玩家档案。

当前整份拒绝的情况包括：未知 schema/外层字段；不可识别、不完整或有歧义的 quest 身份；缺失、负数或不受支持类型的 index；没有对应定义；factory 不符；placeholder；损坏的完成记录；未知任务类型；内层 codec 拒绝的数据。读取器保留原字节，envelope 保留原 NBT 与定位信息；任一记录失败都不暴露部分候选。合法重复记录在单个文档中按源顺序处理；跨文件冲突还没有可执行导入接口，不能猜测排序或静默跳过。缺席 key 继续保留。

## 已有实际 fixture、验证结果和剩余缺口

本地供应的 master 包中有两个可核验的**定义** fixture：

- `work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/AndSoItBegins-AAAAAAAAAAAAAAAAAAAAAA==/SticksnStones-AAAAAAAAAAAAAAAAAAAAAg==.json`：SHA256 `b10a26b4012b0b407dd97bb5d486fbf4d819ee85c37248ea8ab61946e2d05f6b`。真实身份为 High=0、Low=2；任务 index=0/1，factory 均为 `bq_standard:retrieval`；需求 shape 分别为 2/1。
- `work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/TheGreenRevoluti-GbGDSZsbQUekpRKT9ZPdjw==/CropSticks-O65QUnR0QuGSoQrsUMTB1g==.json`：SHA256 `f3a9e572ac771aef9f76c6121ad7a4cb3f5238df6b9adc76238df056d389a52e`。High=`4300463009256719073`，Low=`-7881005862838025770`，证明不能把所有 quest 身份压缩为旧整数 ID，也不能用双精度数值保存两半 UUID。

`LegacyTypedNbtJsonReaderCheck` 通过 **2,932 项断言、577 次固定解码源码对照、74 个拒绝案例**，包括上述两份真实定义的完整 NBT 树。`LegacyQuestProgressEnvelopeCheck` 通过 **4,857 项断言、1,017 次路由源码对照、567 次真实 NBT 往返、32 个整份拒绝案例**，覆盖完整 UUID 与旧 Int、稀疏 index、factory mismatch、三层局部 merge、重复记录顺序、独立完成集合、不同类型的 opaque completion 字段、整 compound 覆盖及输入/previous/返回值隔离。

envelope oracle 原样提取固定源码的 `QuestDatabase.readProgressFromNBT`、`QuestInstance.readProgressFromNBT`、`TaskStorage.readProgressFromNBT`、`UuidValueType.tryReadId/readId` 和 `UuidConverter.convertLegacyId`；旧 NBT 方法名称代理到真实 1.21.1 NBT。内层检索存取复用已有 codec，不将其算作本测试独立的内层 oracle；定义注册表、factory 字符串和触达记录是夹具。严格拒绝有意区别于旧源码的数字强转、静默跳过及 placeholder 恢复，不冒称这些分支已兼容。

`LegacyProgressImportPipelineCheck` 的 **22 项组合断言**将真实 `0:2` 定义解码后提供的身份/需求数，与合成 typed JSON 进度、envelope、codec 和 planner 连通；使用来源符号物品谓词验证 5 logs 先检测再扣 2、剩 3 及重复提交。`logWood` 只是来源符号夹具，现代成员未映射；授权与参与者也是夹具，不是游戏运行结果。完整定义误用为进度仍拒绝。

独立 runner 为 `test_legacy_typed_nbt_json_reader.py`、`test_legacy_quest_progress_envelope.py` 和 `test_legacy_progress_import_pipeline.py`，报告分别在 `work/migration/typed-nbt-json-check/`、`quest-progress-envelope-check/` 和 `progress-import-pipeline-check/`。`runtime_integrated=false`、`export_enabled=false` 保持；真实玩家存档 fixture 为 0。placeholder 恢复、全库/分片实际加载、原件持久化、跨文件冲突、队伍、联网、世界和奖励均未完成验证。下一步实现边界见 [运行时桥接设计](retrieval-runtime-bridge-design.zh-CN.md)。
