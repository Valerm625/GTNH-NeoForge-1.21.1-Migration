# 无序合成的旧身份与 NBT 覆盖

`gtnh_migration:legacy_shapeless` 新增显式选项 `gtnh:overwrite_legacy_nbt`，默认 false。它移植固定 GT5 `5.09.54.205` 的覆盖阶段，位于保留 NBT 之后、电量阶段之前。完整物品更新、任意输入验证器、附魔和真实工作台仍未完成，未据此激活新配方或信用币。

## 原规则

按槽位顺序寻找第一个与输出属于同一旧物品、damage 相同或任一 damage 为 `32767`，且带 NBT 的输入。复制该输入的 compound，把结果 compound 的每个顶层键写入副本，再用副本作为结果 tag。

这不是递归合并。例如输入是 `{nested:{old:1}}`，输出是 `{nested:{new:2}}`，结果的 `nested` 只包含 `new`。输出缺少的顶层键仍来自输入；没有符合条件的带 tag 输入时，结果模板的 tag 保持不变。找不到第一个匹配的有 tag 输入前，不因遇到同物品但无 tag 的格子停止。

## 明确保存旧身份

新增持久化、网络同步的 `gtnh_migration:legacy_item_identity` 组件：

```json
{"item":"gregtech:gt.metaitem.01","damage":32000}
```

它保存原注册名和整数 damage，比较区分大小写。注册名解析为原物品对象的前提必须由后续导入器和物品适配器保证，组件自身不是自动映射表。同一个现代物品可能承载不同旧身份，不同现代物品也不能只凭 ID 不同就否认其原身份。

当前覆盖选项要求结果和每个非空输入都有明确身份。缺失时配方匹配返回 false，直接调用组装时给出错误；空结果保持为空。关闭选项的配方没有这项额外要求。该前提是尚未完成自动映射时的限制，不能称作所有旧配方已经可用；现代损伤、材料变化或自定义组件变化也不会自动更新这个来源记录。

## 原始字节与语义

`LegacyItemTag.withTopLevelOverrides` 按顶层名称组合原始字段字节，保留选中字段的 NaN 位、负零、空列表元素类型及嵌套内容。重复顶层名称按最后值处理，符合旧 compound 映射；输出中同名字段替换整个输入字段。根名称沿用被选中的输入，字段顺序与旧 HashMap 写出顺序不作为语义等价条件。

输入、模板和组件均不被修改，结果独立。覆盖后仍使用现有组件的 16 MiB 和 512 层读取边界。这里保存的是显式旧 tag；名称、附魔、能源、耐久等现代组件的完整双向映射尚未接入。

## 对照与范围

来源固定于 [legacy-crafting-overwrite-source.json](../locks/legacy-crafting-overwrite-source.json)。测试从锁定 GT5 归档原样提取 `keep`、`overwrite`、`areStacksEqual` 表达式，使用官方 1.7.10 NBT 对象实际执行复制和写键。独立比较对 compound 键排序、保持列表类型和顺序、调用旧叶子序列化；NaN 原始位另行检查，避免把序列化规范化当成位保留证明。

运行结果记录在 [NativeResourceBridgeCheck.json](../evidence/NativeResourceBridgeCheck.json) 的 `legacy_overwrite` 项，包括 11,094 次 NBT 对照、441 次身份规则对照、配方与物品 JSON/NBT/网络往返和边界检查。实际 JAR 中覆盖处理被替换为空实现的负例必须被拒绝。

测试中的 `fixture:*` 身份仅用于验证规则，不写入真实物品映射表。全自动身份转换、具体物品更新、附魔、任意验证器、客户端视觉和世界运行均未验收。EULA=false，没有服务器、世界或玩家实例。移植阶段与原源码表达式沿用 LGPL-3.0；本地身份组件和二进制组合器为 MIT。
