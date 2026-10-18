# LDLib配置扫描与客户端回调

本修复针对GTCEu 7.0.2及其内嵌LDLib 1.0.35.a。`locks/library-client-callbacks.json`锁定两个原始JAR、八个输入类和LDLib参考源码归档的摘要。构建兼容模组时核对产物和目标类，依赖声明也固定两者版本。第三方原JAR保持不变，转换由兼容模组内的CoreMod执行。

## 实际故障与修复

LDLib的`AnnotationDetector.checkNoArgsConstructor`在检查普通`ConfigAccessor`时仍执行`instanceof LDLRegisterClient`。后者本身仅限客户端，服务端解析时会失败。原扫描器捕获异常后继续运行，使23个配置访问器全部缺失。补丁仅在该客户端注解检查前增加物理客户端判断；普通注解的模组依赖检查、无参构造检查和实例创建仍执行。

另外七个LDLib/GTCEu类的15个绘制方法或内部回调漏标了客户端范围。Rhino调用`getDeclaredMethods`时会解析这些方法的客户端参数或返回类型，产生错误。补丁为精确锁定的方法添加`@OnlyIn(CLIENT)`，保留其原指令、名称和签名。原本通用的`SceneWidget.setBeforeWorldRender`和`setRenderedCore`仍在两侧可用。

流体构造器还有一处提前创建`RegisterClientExtensionsEvent`回调的调用。仅给回调加分侧标记会在服务端引发`NoSuchMethodError`；因此在创建回调和注册监听器之前增加物理客户端判断。原客户端监听器、纹理选择、颜色和流体扩展注册代码完整保留。温度、密度、光照、黏度、声音、描述以及流体对象构造不变。

## 证据与范围

`LibraryClientCallbacksCheck`用实际ASM/Nashorn执行补丁。去掉允许新增的15个注解和两个条件守卫后，逐类比较全部字段、方法、指令、签名及方法句柄与原始产物一致；共51项断言、509个方法数据流检查和17个拒绝案例。重复应用、目标方法变化和流体事件类型变化必须拒绝。

服务端正常注册检查和官方CLIENT数据生成检查分别验证：

- 从原始JAR的`ConfigAccessor`注解独立枚举的23个访问器全部实际注册，无遗漏或重复。
- 15个客户端方法在服务端被真实DistCleaner移除，在客户端全部保留；七个目标类的完整方法描述符均可解析。
- 通用场景接口保留；实际构造检查正确接受无参类、拒绝无无参构造的类。空模组ID、已安装模组和未安装模组的过滤保持正确，客户端也检查客户端注解分支。
- 574个迁移物品、720条已有映射目标、87个可绑定检索声明和32种灯继续存在。客户端原有512个灯渲染器仍构造成功。
- 禁用整个新补丁会重现访问器缺失；仅去掉流体回调守卫会重现`NoSuchMethodError`。两种情况都不能生成成功报告。

正常注册检查不再允许LDLib/Rhino已知错误，任何ERROR或扫描失败都使检查失败。检查入口未调用服务器主程序、创建世界或玩家，EULA保持false。

这些证据证明本次修复的注册及分侧行为；未证明完整游戏、全部机器、资源重载、视觉效果、任务提交奖励或整包保真。任务试导出仍为2/3814，完整迁移目标不变。后续继续完整初始化与真实资源/配方加载。

运行`python work/migration/test_native_startup_registration.py`与`python work/migration/test_native_registry_datagen.py`。公开汇总分别在`evidence/NativeStartupRegistrationCheck.json`和`evidence/quests/NativeRegistryDatagenCheck.json`；本地UUID检查目录保留日志、字节码检查结果和详细注册清单。
