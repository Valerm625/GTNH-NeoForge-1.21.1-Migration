# 真实模组注册与 Mixin 包隔离

本检查运行固定 NeoForge 21.1.262 的官方 `forgedatauserdev` 入口，加载当前基础包的19个顶层JAR及其嵌套依赖，再由一个仅用于检查的附加模组读取注册表。它不运行服务端入口，不创建世界或玩家，也不修改已有EULA文件。

## 本次修复

此前兼容模组把Mixin和普通辅助类都放在 `org.gtnhmigration.compat` 下。真实ModLauncher在定义Entity时拒绝其引用的 `LegacyRetrievalNativeSaveScope$PlayerDataLoadHooks`，抛出 `IllegalClassLoadError`：Mixin配置声明的包保留给Mixin，普通运行时代码不能放在其中。

原有独立Sponge转换检查只转换字节码，没有定义完整游戏类；服务端发现检查在EULA提示处结束。因此，这两种既有检查通过不能证明此处能正常加载。

15个Mixin现已移至 `org.gtnhmigration.compat.mixin`，普通类继续使用原包。生成器、源文件位置、JAR路径、源码一致性检查和全部受影响的测试同步更新。旧布局作为负例：仅将检查用JAR的配置包改回父包，并调整Mixin相对名，实际启动必须再次出现相同异常；正式JAR保持不变。

## 当前可证明的结果

| 项目 | 数据生成模式实际结果 |
|---|---:|
| 已注册物品 | 11,631 |
| 已注册方块 | 6,011 |
| 已注册流体 | 880 |
| 已注册实体类型 | 149 |
| 现有映射中的原生目标 | 492 / 492 存在 |
| 在此注册表上完整绑定的检索声明 | 68 |
| 经过实际转换链定义的选定类 | 16 |
| 定义后确认存在的原生钩子接口 | 7 |
| 实际转换后EnderStorage容量常量 | 256,000 mB（256桶） |

此前39个检索声明是在纯原版注册表中完成绑定。现在68个使用真实模组注册表；两者验证环境不同。这些数字只证明检索声明可以解析并绑定，不代表前置、队伍、完成记录、奖励、提交和界面均已迁移。依赖闭合的完整任务导出仍为 **2 / 3814**。

现有720条物品映射中的另外228条使用 `gtnh_migration` 命名空间，由KubeJS启动脚本创建。固定KubeJS 2101.7.2-build.377的构造器读取 `PlatformWrapper.isGeneratingData()`，为真时跳过 `startupScriptManager.reload()`。本次注册表因此没有这些物品；这不是它们在正常游戏启动中失败的证据，也不能据此声称它们已通过验证。检查没有更改该分支或手动补造注册内容。

## 可复现入口和证据

运行 `python work/migration/test_native_registry_datagen.py`。完整重建入口已包含此检查。

- `work/migration/NativeRegistryAudit.java`：仅用于检查的模组，读取注册表和定义绑定结果。
- `work/migration/native-registry-check/NativeRegistryDatagenCheck.json`：汇总、输入哈希、源码哈希和负例结果。
- `work/migration/native-registry-check/registry-details.json`：完整注册名称、720条映射和3814个任务的绑定诊断，留在本地检查目录。
- `work/migration/native-registry-check/positive.log`、`negative.log`：真实启动日志。
- `evidence/quests/NativeRegistryDatagenCheck.json`：公开检查点的汇总证据。

开发环境JAR由固定安装器产物的客户端补丁、客户端基础字节码和NeoForge universal按优先级合并，每个被选条目保持原字节；三个输入均校验固定SHA256。客户端资源来自同一固定版本的client-extra。补充依赖按Mojang版本元数据校验长度、SHA1，并记录SHA256。实际发布包中的上游JAR不会被这些开发环境步骤改写。检查用模组不会安装到基础包。

两次运行各使用新目录，复制当前配置和脚本，核对基础包输入未变。正式检查验证全部492条原生目标，并保存全部绑定诊断；负例不产生成功结果。Windows非ASCII工作目录使用绝对类路径，分别按Java启动器和javac参数文件的字符编码读取规则写入。

范围仍限于数据生成的客户端发行环境。原生钩子接口存在不等于钩子已在玩家保存过程中执行，注册成功也不证明配方、机器行为、贴图渲染或完整GTNH保真。正常KubeJS启动注册已有[独立检查](native-startup-registration.zh-CN.md)，后续仍需完整生命周期和缺失内容迁移。

官方入口说明：[NeoForge 1.21–1.21.1 Data Generation](https://docs.neoforged.net/docs/1.21.1/resources/#data-generation)。版本行为同时以本仓库锁定的实际JAR为准。

后续客户端隔离修复验证：此CLIENT分发保留两个助手方法，并为32种灯构造512个原始LampRenderer；服务端独立检查确认这些助手仅在服务端被移除。实际图形渲染尚未执行。
