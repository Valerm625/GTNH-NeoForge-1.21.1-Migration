# 正常启动注册与 GTCEu 客户端代码隔离

`test_native_startup_registration.py` 使用基础包的生产服务端分发、实际 ModLauncher/NeoForge 转换链和全部19个顶层模组。附加的测试入口只执行 `CommonModLoader.begin` 和组件注册，然后读取注册表；不会调用 Minecraft 服务端主程序、构建世界或玩家。现有 EULA 文件保持 false。测试模组和启动服务仅放在独立检查目录。

这里的 KubeJS `PlatformWrapper.isGeneratingData()` 为 false，正常启动脚本与注册回调实际执行，没有改写该分支或补造物品。

## 修复的问题

1. GTCEu 7.0.2 的 `IFancyUIMachine.createUIWidget` 在同一方法中引用客户端假世界。NeoForge 清理其他客户端方法后重算字节码帧，导致服务端提前解析 `ClientLevel` 并失败。`LampBlock` 构造器还会在服务端直接建立客户端渲染器。兼容模组的 CoreMod 将这两段原始指令移动到 `@OnlyIn(CLIENT)` 助手方法。机器界面保留原 `isRemote()` 判断；灯渲染器仅在物理客户端初始化。灯方块、状态、红石、亮度、掉落、物品数据及客户端渲染指令保留。
2. `BlockBuilder.tagBlock` 在固定 KubeJS 版本只接收一个标签。生成器改为链式调用，完整保留挖掘工具和等级两个标签；此前错误会中断16种燃料方块的注册回调。
3. 固定 Rhino 在该循环的 `const component` 绑定上重复读取首次值，实际得到 `tin:6,tin:6,tin:6`。改为每次迭代赋值的 `var`，继续严格核对原组件，不放宽检查条件。
4. 固定 GTCEu 的 `MaterialStack.fromString` 调用 `GTMaterials.get`，后者使用 `ResourceLocation.parse`。未限定的 `tin`/`antimony` 得到空材料；现在明确使用 `gtceu:tin`、`gtceu:antimony`，实际初始化对象为9锡、1锑。

上述 GTCEu 服务端崩溃也见[上游问题 #4155](https://github.com/GregTechCEu/GregTech-Modern/issues/4155)。本地修复针对固定7.0.2产物；源码参照提交 `f6200e018582c3e26e8db06c289d9e01c8250ac8`。`locks/gtceu-client-isolation.json` 锁定原JAR和两个输入类摘要，构建时核对；原上游JAR不改写，补丁随自有兼容模组加载。

## 验证结果与边界

| 项目 | 实际结果 |
|---|---:|
| 普通启动注册的迁移命名空间物品 | 574 |
| 已有身份映射目标存在 | 720 / 720 |
| 其中由脚本注册的映射行 | 228 / 228 |
| 可绑定的完整检索声明 | 87 |
| 灯方块 / 每个灯的状态数 | 32 / 16 |
| 实际材料组成 | 锡9、锑1 |
| 原燃料方块数据生成条目 | 16 |

720只覆盖已建立映射的旧身份，总分母仍为8388；注册存在不代表行为等价。87是检索声明解析与物品绑定数，不是完整任务数；完整依赖闭合试验导出仍为2/3814。

`GtceuClientIsolationCheck` 使用真实 ASM 和 Nashorn 执行同一补丁，比较移动前后每条客户端指令及操作数的顺序，检查无关方法不变、数据流合法，并拒绝未知方法布局与重复变换。生产服务端验证两助手被真实 DistCleaner 移除。另一个官方客户端数据生成检查验证两助手仍存在，32种灯实际构造了512个原始 `LampRenderer`。这些检查没有运行图形渲染，不能替代截图或游戏视觉对照。

KubeJS 2101 的燃料时长由内置插件生成 NeoForge `furnace_fuels` 数据映射。检查调用真实 `BuiltinKubeJSPlugin.generateData` 和 `VirtualDataPack.flush`，以真实 `FurnaceFuel.CODEC` 读取生成内容，对照16种原燃料时长，最高值1889568000 ticks。此时尚未加载资源，`ItemStack.getBurnTime` 仍为0；该值也记入报告，没有伪造已生效的燃烧结果。下一步需要真实资源重载、配方加载及炉子行为检查。

六个独立负例分别取消GTCEu客户端隔离、取消LDLib/GTCEu回调修复、仅取消流体回调创建的客户端守卫、恢复双参数标签、恢复循环const、去掉材料命名空间。各自必须重现预期错误且不能生成成功结果。每次均复制当前完整模组、配置和脚本，不改变基础包输入。

## 未解决项

此前LDLib配置访问器扫描和Rhino反射客户端类型的问题已由[客户端回调与配置扫描修复](library-client-callbacks.zh-CN.md)处理。注册阶段日志中的ERROR和反射扫描失败全部作为失败，不再允许已知错误类别。`all_startup_errors_resolved`只表示当前注册检查没有该类错误，不代表后续完整启动已经验收。

未执行后续公共/分侧初始化、完整网络生命周期、资源重载、服务器脚本配方、世界/玩家、机器运行、任务完成奖励和界面。所有剩余迁移目标继续保留。

## 复现与证据

运行 `python work/migration/test_native_startup_registration.py`，或完整入口 `python work/migration/rebuild_current_phase.py`。

- `work/migration/native-startup-check/NativeStartupRegistrationCheck.json`：输入/源码摘要、统计、原客户端指令保留检查、六个负例及错误列表。
- 同目录 `registry-details.json`：720条映射、3814项绑定诊断、实际生成燃料数据等详细结果；`launch.log` 为正例原始日志。
- 同目录每个UUID子目录保存各次正负例及日志，不覆盖历史失败结果。
- `evidence/NativeStartupRegistrationCheck.json`：公开检查点的汇总证据。
