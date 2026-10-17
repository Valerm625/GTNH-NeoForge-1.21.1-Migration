# 旧 BQ 格式化 JSON 的独立读取器

`LegacyTypedNbtJsonReader.inspect(byte[] rawUtf8, String sourceIdentity)` 只负责检查旧 BQ `format=true` JSON 并生成真实 Minecraft 1.21.1 NBT。它不加载玩家世界、不解释任务身份、不连接进度 envelope，也不写入或激活 FTB 存档。`Result.accepted()` 仅表示这个读取层支持该输入。

结果保留原 UTF-8 字节的防御副本、来源身份、诊断、可选的防御复制 `CompoundTag`，以及按 JSON Pointer 组织的对象成员顺序账本。失败时不暴露部分 NBT；原字节仍完整保留。`CompoundTag` 自身不保证对象键迭代顺序，所以不能拿 NBT Map 的遍历顺序代替这个账本。List 和数组则按原出现顺序构建，绝不排序 List 的成员名前缀。

## 第一版支持域

依据锁定 BetterQuesting `3.8.89-GTNH` 的 `NBTConverter.java`，提交为 `815d738923d46e9083f988421e5329193e2e5940`，文件 SHA256 为 `f6a0362455bc0d226974313cd507e8be304bcb52175e3a40f9a14da8516e0db0`。

| Tag ID | 接受的 JSON 形态 |
| --- | --- |
| 1 | Byte 范围内的整数；也保留原 reader 明确支持的 JSON `true`/`false` 兼容路径。 |
| 2、3、4 | 对应 Short、Int、Long 范围内的整数，精确解析，不经过 double。 |
| 5、6 | 可表示为有限 Float、Double 的数字，按原 Java 浮点精度解析；负零、溢出和非零下溢到零拒绝。 |
| 7 | JSON 整数数组，每项必须在有符号 Byte 范围内。 |
| 8 | JSON 字符串，不把其他类型强制转换成文本。 |
| 9 | 格式化的 JSON 对象；成员有显式类型且类型相同。按原对象迭代顺序追加，不解析或排序 `0`、`1` 等前缀。空对象得到空 List。 |
| 10 | JSON 对象；只切分每个键的最后一个冒号，保留键内其他冒号。 |
| 11 | JSON 整数数组，每项必须在有符号 Int 范围内。 |

根必须是 Compound 对象；字段后缀为规范的 `:1` 至 `:11`。原 reader 的 `id <= 0` 是类型推断入口，`:0` 不是可无损恢复的 EndTag 表示，因此第一版拒绝 `:0`、缺少类型、负 ID、LongArray 等未定义类型及非规范类型后缀。这里没有宣称支持全部原始 NBT 值域。

整数 token 中的小数点和指数形式均拒绝，即使数值恰为整数；旧 writer 的整数形态不需要这种截断或强转。浮点正常舍入到原 Float/Double 精度不会被冒充为任意精度十进制转换，原词法表示仍在原字节中保留。

## 无损边界与拒绝条件

流式 Gson `JsonReader` 在对象树形成之前检查重复 JSON 键，因此不会先让 Gson 覆盖前值。剥离类型后发生的键碰撞、混合 List 类型、未知 ID、错误 JSON 形态、越界整数及任何解析错误都会拒绝，并保留原字节。完整 64 位 UUID 半部按 BigInteger 校验后转换为 LongTag，不经过 JavaScript Number 或浮点数。

原字节以 UTF-8 严格解码，非法字节序列不替换成 U+FFFD。实际依赖 Gson 2.10.1 的 `setLenient(false)` 仍接受字符串中的原始换行和大写布尔字面量，因此在流式解析前补充词法检查；原始控制字符、非 JSON 转义和大写字面量被拒绝。UTF-8 BOM 输入第一版也保留原件并拒绝。

输入限制为 16 MiB、嵌套深度 128、最多 1,000,000 个节点、数字 token 最长 128 字符。超过限制不能变成“空存档”或部分可用 NBT。诊断只包含错误码和路径，不拼入 Gson 的原文摘录或数值内容。

**现代负零缺口单独保留。** 实测 1.21.1 `FloatTag.valueOf(-0.0f)`、`DoubleTag.valueOf(-0.0d)` 以及两个类型的公开 `TYPE.load` 都归一化为正零。读取器不能用这些 API 伪称保存了旧浮点符号位，所以负零输入返回 `MODERN_NBT_NEGATIVE_ZERO`，原字节完整保留，`accepted=false`。没有引入反射或全局 Mixin；未来若需支持，必须单独设计访问或加载适配。最小正负 subnormal、最小 normal、最大有限 Float/Double 和正零都有真实 NBT 边界检查。

## 对照与证据范围

运行 `python work/migration/test_legacy_typed_nbt_json_reader.py`。测试输出位于忽略目录 `work/migration/typed-nbt-json-check/`，报告名为 `LegacyTypedNbtJsonReaderCheck.json`。runner 哈希锁定源文件，原样提取 `JSONtoNBT_Object`、`JSONtoNBT_Element`、`instanceNumber`、`fallbackTagID`，在旧 NBT 名称代理下调用真实 1.21.1 NBT。日志器和名称代理是夹具；只对读取器接受的支持域比较原解码结果，不把原 reader 的错误回退也当作正确迁移。

两个真实 fixture 来自用户提供 master 包的任务**定义**：`SticksnStones-AAAAAAAAAAAAAAAAAAAAAg==.json`（SHA256 `b10a26b4012b0b407dd97bb5d486fbf4d819ee85c37248ea8ab61946e2d05f6b`）及 `CropSticks-O65QUnR0QuGSoQrsUMTB1g==.json`（SHA256 `f3a9e572ac771aef9f76c6121ad7a4cb3f5238df6b9adc76238df056d389a52e`）。它们用于核对整个解码树、任务 index/factory 和完整 64 位 UUID，不是玩家进度存档。边界与拒绝用例全部为合成数据，真实玩家存档 fixture 数仍为 0。

报告绑定生产源码、Java check、Python runner、提取的 oracle、编译类、Minecraft/Gson JAR 及定义 fixture 的 SHA256。独立 runner 不验证兼容 JAR 打包结果；该项由后续统一构建核对。`runtime_integrated=false`、`export_enabled=false`，不因 JSON 读取测试通过而改变。
