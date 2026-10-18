# GTCEu 7.0.2 的 KubeJS 7.2 源码适配与资源加载

当前实现生成独立的派生 GTCEu JAR，在复制的整包输入中执行真实模组初始化、资源加载和配方事件。基础包中的原始 GTCEu JAR仍保留；派生产物尚未安装到基础包。此阶段验证资源和配方对象，不等于机器运转、客户端渲染或完整 GTNH 迁移完成。

## 修复内容

- 将旧 `ServerEvents` 包路径、配方组件注册、构造器和上下文接口适配到固定 KubeJS `2101.7.2-build.377`，保留嵌套物品/流体 codec、数量、条件和工具符号处理。
- 修复 `GTKubeRecipe.output` 把产物写入输入列表的错误，普通输出和每 tick 输出分别进入正确的输出键。真实加载中，已有 167 条迁移机器配方均具有输出；旧输出实现的负例会丢失这些输出，检查明确拒绝。
- 注册自定义内容、Java Map 和能量组件类型。五类组件通过真实 KubeJS schema codec 编码、解码、再编码，类型和编码内容均一致；没有把带能力信息的内容组件伪装成普通配料组件。
- 为内容和能力集合实现输入/输出匹配；替换已有数据包配方时采用复制后修改，保留原不可变列表和无关能力。替换后的物品数量、概率和等级加成经过真实配方事件验证。
- 将 `Content` 的“首次生成时折算加成”与“恢复已折算值”区分。公开构造器仍执行原折算；codec、复制、网络解码和配料替换使用保存值。否则以 6000 为分母的概率会将已折算的 450 再降到 270。正负加成在 6000、9999、10000 三个分母下完成 18 次复制/JSON/网络往返，内容一致。

来源是固定 GTCEu 提交 `f6200e018582c3e26e8db06c289d9e01c8250ac8`。接口及缺陷修复同时对照了用户提供的 [8.0.1 重构候选](gtceu-rebuild-candidate.zh-CN.md)。此处保留 7.0.2 的原类结构和渲染方法，不引入候选的整个界面依赖替换。

## 实际执行的检查

独立加载器进入 NeoForge 模组初始化和 `WorldLoader` 的资源加载接口，返回审计结果；不调用 Minecraft 服务器主程序，不创建服务器、世界、玩家或 `WorldStem`。EULA 保持 false，基础包的 mods/config/kubejs 全部文件摘要在检查前后相同。

首次探索的 `DataMapLoader.results` 空指针来自测试入口重复调用 `updateRegistryTags`。固定生产 `WorldLoader` 已在返回结果前更新标签；删除重复调用后，正常加载恰有一次 `AddReloadListenerEvent` 和一次服务端标签更新，不手工填充数据映射。

通过真实对象和 codec 核对：

- 170 条迁移命名空间配方，其中 167 条为机器配方；量子靴样例保留输入数量 1 与 4、输出 1、时长 1200、EU/t 1920。
- 五条独立测试配方覆盖普通及每 tick 物品/流体方向、数量、EU 电压和电流、发电输出、维度条件、不消耗工具、整数与分数概率、既有配方替换、锤子符号和禁止镜像。
- 16 种迁移燃料的 `ItemStack.getBurnTime` 与固定源规格一致，包括木炭 16000 tick；不是检查生成文件后伪造燃料映射。
- 原始 GTCEu API 负例在旧 `ServerEvents` 类处失败；仅恢复旧输出方向的负例能够加载，却被配方语义检查拒绝。正例不允许 ERROR 或脚本事件错误。

保留了 Storage Drawers 可选依赖配方回退及上游缺失标签等 WARN，没有将它们隐藏或计作已解决。检查不证明所有配方、所有工具行为、`noShrink` 的定位行为或旧阶段门槛已迁移；这些仍须单独验收。

## 构建与复现

`locks/gtceu-kubejs72-build.json` 固定源码归档、Lombok、注解库、原始 GTCEu 和 KubeJS 摘要。补丁为 `work/compat-mod/patches/gtceu-kubejs72.json`，每个源文件及替换区域都有前后校验。构建沿用原 Lombok 配置，编译保留的客户端方法时使用已有固定客户端字节码，不执行客户端。

14 个选定源文件编译为 25 个原有类条目。派生 JAR 的其他 14084 个原始条目逐字节保留，包括全部贴图与其他资源；额外加入一份派生来源元数据。这里证明的是对 7.0.2 原产物的保留，不能代替与 GTNH 旧版贴图的视觉对照。

```powershell
python work/migration/build_gtceu_kubejs_bridge.py
python work/migration/test_native_resource_bridge.py
```

依赖现有基础包、JDK 21 和 `locks/native-client-audit-inputs.json` 对应的本地编译输入。构建目录、派生 JAR、原始输入和详细运行日志在忽略目录 `work/migration/gtceu-kubejs-bridge/`；公开检查结果为 `evidence/NativeResourceBridgeCheck.json`。完整重建入口已加入此检查，旧检查继续保留。

后续要把派生产物的来源、安装与恢复接入版本锁，并重新验证两侧加载和现有兼容补丁。不能简单覆盖原始下载产物、放宽版本锁或移除测试。本阶段由 Codex 单人实现；完整目标保持未完成，任务导出仍为 2/3814。
