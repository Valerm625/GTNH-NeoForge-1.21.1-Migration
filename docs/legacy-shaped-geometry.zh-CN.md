# 旧版有序合成的格子定位与 noShrink

本阶段修复带外围空格的 GT 有序配方被裁剪后任意移动的问题，保留旧 Forge 的平移、镜像和固定逻辑 3×3 匹配边界。它只补齐已覆盖的格子匹配与序列化行为；GTNH 的 NBT、充放电、附魔、研究/阶段门槛及完整合成界面仍需继续迁移。

后续增加了显式的[旧版电量阶段选项](legacy-crafting-energy.zh-CN.md)，已通过真实现代GT能力和固定源码表达式对照；完整旧IC2、NBT/附魔及全部源电动配方仍未完成。

## 修复前的证据

固定 KubeJS 377 的 `ShapedKubeJSRecipe` 序列化器没有读取 `kubejs:shrink`。原版 `ShapedRecipePattern` 裁去外围空格，`CraftingInput` 又裁去输入空格且不保留原点，GT schema 中存在 `noShrink()` 并不使该方法生效。

真实资源加载中的 `['   ', ' I ', '   ']`、`noShrink()`、`noMirror()` 配方在修复前接受了 2×2 和 3×3 的每一个格子。历史结果、输入及产物摘要见 [失败基线](../evidence/shaped-recipe-baseline.json)。旧 Forge 固定算法只接受逻辑中心格：对应 3×3 的中心，也对应 2×2 的右下角。不能把后者擅自改成“不足 3×3 一律失败”。

## 实现

- GT schema 在 `kubejs:shrink=false` 时选择 `gtnh_migration:legacy_shaped` 序列化器。默认及重新设置 true 时仍走既有 KubeJS 路径；修正分类方法误命名为 `kjsShrink` 的问题。
- `LegacyShapedRecipe` 保存原始行列空格，保留原版键验证和现有 KubeJS 配料动作、剩余物品及结果修改器字段。JSON 和网络 codec 均保留原始图案、镜像及动作。
- `LegacyCraftingGridMixin` 在原生 `ofPositioned` 返回处记录原网格大小和裁剪原点。共享 EMPTY 不携带可变原点；相等性和哈希包含原点，避免位置不同但裁剪结果相同的输入被混为一项。
- `LegacyCraftingCacheMixin` 在实际 `RecipeCache.get` 入口检测原网格/原点变化，清除配方管理器弱引用，使既有 `validateRecipeManager` 路径在查询缓存前失效旧条目。原点不变时继续使用缓存；空输入保持原处理。原版缓存同时保存命中与未命中，二者都必须防止跨位置复用。

Forge 原算法固定遍历逻辑 3×3；2×2 之外视为空格，4×4 超出逻辑范围的物品可能不参与匹配。本阶段按固定来源保留这种行为，不将其改写为新的规则。现代 Ingredient 的矿辞/标签、NBT 等完整等价性不由格子比较证明。

## 固定源码对照与真实加载

来源为 GT5U `5.09.54.205` 指定的 Forge `10.13.4.1614` 官方 userdev 归档。归档和 `ShapedOreRecipe.java` 摘要见 [来源锁](../locks/legacy-shaped-source.json)。`build_legacy_shaped_oracle.py` 直接编译未修改的旧类；测试桩仅提供格子与 A/B 物品身份，矿辞分支明确拒绝，未声称模拟全部旧物品行为。

实际 NeoForge 模组初始化和资源加载后，以真实 GT KubeJS 配方对象检查：

| 检查 | 已通过范围 |
| --- | --- |
| 旧源码格子结果 | 9 种图案、2×2/3×3/4×4，共 3465 个输入；原对象、JSON 解码、网络解码共 10395 次对照 |
| 配方序列化 | 22 次 JSON/网络往返，含两种牛奶桶剩余物品/保留动作 |
| 默认及重置裁剪 | 26 次位置检查 |
| 结果、容器和动作 | 6 组原对象及往返对象检查，输入未被修改 |
| 原网格相等性/哈希 | 5 项检查，含 HashMap 中位置不同输入的区分 |
| 缓存入口 | 4 项实际 get 入口检查：初次、同位置、移位、EMPTY |

缓存入口测试在所需 Level 查询处以空参数停止，验证入口的失效决策，没有构造世界，也没有声称执行了世界内缓存命中或完整工作台操作。CLIENT 官方数据生成另验证真实 `CraftingInput` 捕获原点、EMPTY 保持无绑定，并定义 `RecipeCache` 以应用该侧 Mixin。

正常资源加载之外，旧 API、旧输出方向、移除原点 Mixin、移除缓存 Mixin 四个负例分别被拒绝。公开结果见 [资源检查](../evidence/NativeResourceBridgeCheck.json)。基础包文件在检查前后保持一致；EULA=false，无服务器、世界、玩家或 WorldStem 实例。

## 复现与边界

```powershell
python work/migration/rebuild_current_phase.py
```

使用既有固定本地运行输入。独立格子参考构建入口为 `build_legacy_shaped_oracle.py`，实际资源入口为 `test_native_resource_bridge.py`。详细日志仅在忽略目录 `work/migration/shaped-behavior-check/` 和 `work/migration/gtceu-kubejs-bridge/resource-check-*` 保留。

未验证真实菜单/玩家、客户端配方书与 JEI 展示、所有模组工作台和机器缓存。GTNH `GTShapedRecipe` 的 NBT、充电、附魔及科技门槛不属于此次格子实现，不能因此视为已支持。贴图保真、全部任务和全部功能的迁移目标保持未完成；依赖闭合导出仍为 2/3814。

`LegacyShapedRecipe.java` 及编译类保留 Minecraft Forge Public Licence 1.0；来源、修改范围及完整许可文本随兼容模组保存，见 [NOTICE](../NOTICE.md)。
