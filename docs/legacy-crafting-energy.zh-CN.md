# 旧版合成电量阶段

`gtnh_migration:legacy_shaped` 新增显式的 `gtnh:legacy_energy` 布尔选项，默认为 false。启用后在已有 KubeJS 结果生成之后调用 `LegacyCraftingEnergy`，按固定 GT5 源码的电量阶段处理结果。它已通过真实配方、现代 GT 电池/工具能力和 JSON/网络序列化检查，但还没有增加活动迁移配方或完整任务。

这不是完整 `GTShapedRecipe` 移植。旧 IC2 物品能力、NBT 保留、`GTUtility.updateItemStack` 的物品更新、附魔、阶段门槛，以及所有电动工具配方与资源仍须继续迁移。不能把下述能力接口对照当作这些功能的等价证明。

## 来源与具体规则

锁定 GT5U `5.09.54.205` 提交 `a3e1e11241a814c9fa0dd0973d5699548428f689` 的 `GTShapedRecipe.java` 电量段，以及 `GTModHandler.isElectricItem/chargeElectricItem/dischargeElectricItem`。归档、源文件和提取表达式摘要见 [来源锁](../locks/legacy-crafting-energy-source.json)。

1. 输出必须具有电量能力，且等级小于 `Integer.MAX_VALUE`。
2. 对输出执行最多 `Integer.MAX_VALUE` 的实际放电。使用输出自身等级，忽略传输限制并允许内部放电；不能将其替换为无条件写零。初始电量高于该上限时会有残余。
3. 逐槽模拟读取最多 `Integer.MAX_VALUE` 的可放电量。正值参与累加，旧 `MetaGeneratedTool` 排除规则在现代 GT 后端使用真实 `IGTTool` 类型对应；所有旧物品到该类型的映射尚未验收。
4. 累加变量保留 Java `int` 语义。溢出后总数不大于零时不充电；不能悄悄改成长整数、饱和相加或任意限制数值。
5. 总数为正时，以输出自身等级、忽略传输限制的方式充电。容量和其他行为由结果物品的实际能力控制，不把第一个输入电池的容量复制到输出。

现代能力查询的构造器可能初始化组件，即使接下来只是模拟操作。因此对输入副本查询能力，保留实际输入的完整组件和数量；返回独立的结果，避免修改配方模板。

当前安装的 GTCEu 7.0.2 及已审查的用户重构候选，其 `ShapedEnergyTransferRecipe` 都在第一个匹配输入后返回，且未使用 `overrideCharge/transferMaxCharge` 字段。官方 [7.0.2 的 1.20.1 源文件](https://github.com/GregTechCEu/GregTech-Modern/blob/3603b68a04d07f68edd9a25dcc6c8a3f1e3dc4d2/src/main/java/com/gregtechceu/gtceu/api/recipe/ShapedEnergyTransferRecipe.java) 也保留该逻辑。这不能作为 GTNH 旧规则的替代。本阶段另建明确选择的旧版规则，没有猜测两个开关的设计含义或改写全部现代 GT 配方。

## 实际验证

`build_legacy_energy_reference.py` 从固定归档提取未修改的电量段和三个方法体，编译独立对照类。测试仅把旧物品/IC2 API 调用桥接到实际现代 GT 能力；没有伪装成运行了旧 IC2 或旧物品类。

`NativeCraftingEnergyCheck` 经真实模组初始化、资源重载及迁移配方序列化器执行：

- 5 种输出容量/预充电组合、5 种输入物品组合、125 种输入电量组合；对原配方、JSON 解码及网络解码分别检查，共 9375 次源码表达式对照。输入包括 LV/MV/HV 锂电池、真实钢钻及普通铁锭。
- 10 次 JSON/网络往返，保留显式选项、输出容量、电量及其他能量组件标志。
- 6 个额外边界：4×4 外围输入参与电量扫描、叠堆输入能力限制、非电动输出、关闭选项、明确的多电池/工具排除结果，以及明确的整数溢出结果。
- 每次对照核验输入和模板未变；修改返回物品数量后重新合成，结果仍独立。
- 将安装兼容 JAR 中的电量处理替换为返回原结果副本的空实现，检查在真实资源加载中失败。旧 API、旧输出方向、缺少原点与缺少缓存处理的既有负例继续保留。

公开结果位于 [NativeResourceBridgeCheck.json](../evidence/NativeResourceBridgeCheck.json)。其中仍为 170 条活动迁移命名空间配方（167 条机器配方），另有 21 条仅在隔离检查包中的测试配方。电量测试不是新增活动迁移配方的统计依据。

## 使用与后续

迁移配方可在 `event.custom` 的 `gtnh_migration:legacy_shaped` JSON 中设置 `"gtnh:legacy_energy": true`。普通配方和旧 `noShrink` 路径默认不启用，避免把未经逐项迁移的现代配方自动套入旧规则。

完整重建入口为 `python work/migration/rebuild_current_phase.py`。兼容模组编译现在从已校验的 GT JAR 提取锁定 LDLib 编译依赖，以解析真实 `IGTTool` 继承关系。编译与原生检查均使用该生产类型。第三方原始 JAR、派生 GT 和既有类摘要锁保持不变。

没有执行服务器、世界、玩家或工作台菜单，EULA=false。旧 IC2 的浮点电量及各物品管理器、FE/其他能源、旧工具类别映射、NBT/附魔/更新顺序和客户端视觉仍未验收。下一步按固定源配方与物品行为逐项接入，保留原材料、工具、容量、科技门槛和纹理要求。

`LegacyCraftingEnergy.java` 及编译类沿用 GT5U 的 LGPL-3.0；完整 LGPL/GPL 文本和来源说明随兼容模组保存，见 [NOTICE](../NOTICE.md)。
