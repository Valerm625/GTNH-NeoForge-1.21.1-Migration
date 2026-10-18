# 旧版无序合成与信用币来源

新增 `gtnh_migration:legacy_shapeless` 配方序列化器，使用旧 Forge 的逐槽贪心匹配，并可显式启用已有的 `gtnh:keep_legacy_nbt`、`gtnh:legacy_energy` 阶段。两个阶段选项均默认 false。完整 `GTShapelessRecipe` 的覆盖 NBT、任意输入验证器、附魔、物品更新、不可移除标记和所有原材料集合仍未迁完；目前不以此直接激活未完成的信用币体系。

## 匹配为何需要单独实现

固定 Forge `10.13.4.1614` 的 `ShapelessOreRecipe` 按库存槽位顺序扫描非空物品，依次查找尚未使用的配料，选中第一个匹配项并移除。它不在后续失败时重新分配前面的选择。

例如配料依次是 `[铁或金, 铁]`：输入顺序 `[铁, 金]` 会先把铁分配给第一项，随后金不能满足第二项，因此失败；`[金, 铁]` 则成功。把该算法换成全局二分匹配会改变旧配方结果。

`LegacyShapelessMatch` 保留这一规则。每个非空格消耗一个配料声明，格内 64 个物品仍不能代替两个格。扫描覆盖整个输入库存，不把无序配方限制为左上 3×3。序列化保留配料顺序，不采用现代默认的 1–9 项限制；空配方、空候选集合和超过九项的算法边界也有对照，但真实工作台菜单的适用范围尚未验收。

配料的实际物品与标签测试仍通过现代 `Ingredient` 适配。已验证的是给定相同候选集合时的匹配算法；这不证明原整包所有 OreDictionary、metadata、通配符和自定义物品谓词都已映射。

## NBT、电量和容器

旧 GT5 无序配方的保留 NBT 片段与独立验证的有序片段分别从固定源码提取；匹配对照调用实际 Minecraft 1.7.10 NBT 类。启用时先比较所有非空输入的旧 tag，生成结果时复制第一个具有 tag 的输入，再执行可选电量阶段。电量片段与已固定的 GT5 有序片段逐字节相同；使用真实现代 GT 电池和电动工具能力验证。

原始 tag 与现代名称、耐久、附魔、能源等组件的双向行为映射仍不完整。本配方不隐式把 `CUSTOM_DATA` 当作完整旧 NBT。容器返回物和 KubeJS 配料动作通过现有运行时接口执行，并在实际资源加载中验证牛奶桶返回空桶及保留牛奶桶动作。

## 核验结果

来源锁为 [legacy-shapeless-source.json](../locks/legacy-shapeless-source.json)，实际运行结果见 [NativeResourceBridgeCheck.json](../evidence/NativeResourceBridgeCheck.json)。

- 400 种长度 0–3 的配料序列，三种实际物品和空格组成的 64 种输入，分别对原配方、JSON 解码、网络解码运行，共 76,800 次未修改 Forge 匹配方法对照。
- 5,547 次固定 GT5 无序 NBT 对照、81 次真实电量能力对照。
- 812 次配方 JSON/网络往返、9 项边界和 6 项容器/配料动作检查。
- 替换安装 JAR 中匹配器为全局重新分配算法的负例必须失败；此前的原点、缓存、电量、NBT和GT桥接负例保留。

隔离测试配方为 27 条。活动迁移命名空间仍是 170 条配方（167 条机器），完整任务导出仍是 2/3814。没有服务器、世界或玩家实例，EULA=false。

## 信用币迁移入口

[LegacyCreditSourceAudit.json](../evidence/LegacyCreditSourceAudit.json) 由 `audit_legacy_credit_sources.py` 从固定 GT5 归档与对应源码树共同验证。它列出 13 个 GT 信用币定义、metadata、13 张原 PNG 的摘要与尺寸、三种语言中的名称和提示、24 条双向八进制兑换配方。

工业铁币 `Credit_Iron` 来自 `GTProxy` 对 `IC2 coin` 的绑定，不是 `MetaGeneratedItem01` 中缺失的第十二号物品。该 IC2 主版本身份仍需单独核实。Greg 铜镍币带 907200 单位的材料数据，两个银币定义带 LUCRUM 要素；这些影响回收或模组交互，不能只迁移兑换配方和图标。记录没有把它们合并为 CoreMod 的另一套金币，也没有注册普通物品占位。清单不是游戏行为或视觉验收，信用币激活数仍为零。

`LegacyShapelessMatch` 沿用 Minecraft Forge Public Licence 1.0；GT5 测试表达式保持 LGPL-3.0。完整重建入口仍为 `python work/migration/rebuild_current_phase.py`。
