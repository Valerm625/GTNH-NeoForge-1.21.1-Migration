# 旧物品 NBT 表示与合成保留

兼容模组新增 `gtnh_migration:legacy_item_tag` 数据组件，以及迁移有序配方的 `gtnh:keep_legacy_nbt` 选项（默认 false）。这是完整迁移所需的旧数据表示和合成保留阶段。它还没有完成旧 NBT 到现代名称、耐久、附魔、能源或其他物品能力的双向映射，不能直接替代完整旧物品实现。

## 原始数据与比较

组件保存未压缩的、带名称的旧 NBT compound 原始字节。JSON 使用 Base64；物品 NBT 存档与网络传输保留这些字节。组件不存在表示旧物品没有 tag；存在但内容为空 compound 表示旧物品具有空 tag。这两者不会合并。该组件不等同于 `minecraft:custom_data`，也不自动从现代组件推断旧数据。

旧 Minecraft 1.7.10 的标签类型 1–11 均可表示。读取不经现代 `FloatTag`、`DoubleTag` 或 `ListTag`，以免规范化正负零、NaN 位模式或空列表的元素类型。原始根名称、字段顺序与重复字段均保留。比较遵循旧解码后的含义：忽略根名称和字段顺序；重复字段采用最后一个值；保留数字类型与列表元素类型，浮点按旧 `==` 规则比较。

组件自身的相等性与 hash 使用原始字节，以满足现代不可变组件的稳定约定。合成则单独使用旧 NBT 内容比较，避免把 NaN 的非自反比较用作现代组件的 `equals`。共享不可变组件按独立旧 tag 内容比较；不模拟外部代码人为让多个旧 ItemStack 共享同一可变 compound 的别名。

读取限制为 16 MiB 和 512 层；损坏、截断、尾随字节、负长度及旧版不存在的类型均拒绝。这里不是世界或库存批量导入器，不会改写原存档。

## 合成行为

`gtnh:keep_legacy_nbt=true` 时，`LegacyShapedRecipe.matches` 先扫描所有非空输入，再执行原有配方形状和材料检查。各输入必须同时没有 tag，或同时具有内容相等的 tag；不忽略工具格或大网格的外围格。

生成结果时，复制第一个具有旧 tag 的输入；没有这样的输入则保留输出模板的 tag。此步骤在已接入的旧电量阶段之前执行。结果为独立 ItemStack，原始输入和模板不被修改；组件不可变且不暴露内部数组。关闭选项时不执行这些检查或复制。

旧源码会在该步骤前后调用 `GTUtility.updateItemStack`，实际委托 `GTGenericItem.isItemStackUsable`。这些具体物品回调尚未迁移，不能用空回调宣称等价。旧 tag 中的电量与现代 GT 能力组件也尚未建立双向映射，因此不能宣称本轮已恢复二者组合后的完整电动物品行为。

## 检查依据

固定 GT5U `5.09.54.205` 的 `GTShapedRecipe.matches` NBT 段和结果复制段由脚本原样提取。比较后端在隔离类加载器中调用官方 Minecraft 1.7.10 服务端 JAR 的实际 NBT 类；不构造服务器、世界或玩家。文件和类摘要见 [来源锁](../locks/legacy-crafting-nbt-source.json)。旧游戏 JAR 不进入公开源码检查点。

真实 NeoForge 资源重载中的检查覆盖：

- 43 种 tag 状态，包含无 tag、空 compound、全部旧标签类型、不同数值类型、嵌套、非 ASCII 与 NUL 字符、NaN、正负零、空列表类型、字段顺序和重复键。
- 两种裁剪选项与原配方、JSON 解码、网络解码，共 11,094 次输入配对比较。
- 4 次配方往返、129 次实际 ItemStack 的 JSON/NBT/网络往返，并逐字节核验。
- 关闭选项、材料不匹配、4×4 外围格、输出模板、直接组装和输入不变检查；6 种无效数据拒绝。
- 移除 NBT 检查与复制的生产类负例必须失败；既有格子、缓存、电量和 GT 桥接负例继续保留。

结果见 [NativeResourceBridgeCheck.json](../evidence/NativeResourceBridgeCheck.json)。测试夹具增加到 23 条；活动迁移配方仍为 170 条（167 条机器配方），完整任务导出仍为 2/3814。物品行为映射、无序合成及覆盖 NBT、附魔、客户端展示和真实工作台均需继续完成。

`LegacyCraftingNbt` 中的 GT5 保留阶段沿用 LGPL-3.0。原始二进制适配器 `LegacyItemTag` 为本地 MIT 代码。完整重建入口为 `python work/migration/rebuild_current_phase.py`，EULA 保持 false。
