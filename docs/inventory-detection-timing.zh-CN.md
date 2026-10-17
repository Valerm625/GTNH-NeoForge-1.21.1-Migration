# 库存检测触发与时机的源码核对

本次核对支持现有标记检索任务采用“玩家库存通知入队、下一次服务器 Pre 阶段检测”的设计。没有发现需要为了现代容器的即时检测而扩大触发范围的依据。打开箱子或机器 GUI 时，玩家库存菜单暂不广播变化、检索可能延迟到 GUI 关闭的行为，在所检查的原版 1.7.10 路径中也存在，不能把现代 FTB 的更广泛触发直接当作原 GTNH 语义。

本记录来自只读源码、实际 JAR 字节码及现有测试代码审查。没有运行 Minecraft 世界，也没有证明 NeoForge 完整加载链、事件派发、多人或断线重生流程的运行时等价。

## 证据来源与版本锚点

- BetterQuesting：`3.8.89-GTNH`，提交 `815d738923d46e9083f988421e5329193e2e5940`。
- FTB Quests：`v2101.1.36`，提交 `8c53f35a97d8897c861b097f1e39f4fc3beb3a15`。
- 现代平台：Minecraft `1.21.1`、NeoForge `21.1.262`、FML loader `4.0.45`。
- 旧版平台缓存位于 `work/cache/legacy-platform/`。此次直接读取缓存中的真实类文件与 SRG 映射，没有执行旧版服务器。

| 输入 | SHA-1 |
| --- | --- |
| `minecraft-server-1.7.10.jar` | `952438ac4e01b4d115c5fc38f891710c4941df29` |
| `mcp-1.7.10-srg.zip` | `10b95bb342f55d681025bf2d698c893d9c4b4201` |
| 该 ZIP 中的 `joined.srg` | `4a4a35013981466596f82938099bb0882a415371` |
| 缓存的官方版本元数据 `1.7.10.json` | `ed5d8789ed29872ea2ef1c348302b0c55e3f3468` |

旧服务器 JAR 的 SHA-1 与缓存版本元数据中 `downloads.server.sha1` 一致；该条目的官方地址为 [Mojang 的 1.7.10 server.jar](https://launcher.mojang.com/v1/objects/952438ac4e01b4d115c5fc38f891710c4941df29/server.jar)，大小为 9,605,030 字节。这里的 SRG SHA-1 是本次对本地映射字节计算的身份锚点，不冒充另行验证过的发布方校验值。

对应 SHA-256 为：

- 旧服务器 JAR：`c70870f00c4024d829e154f7e5f4e885b02dd87991726a3308d81f513972f3fc`。
- SRG ZIP：`d49df5c445dcf00ada532ab3b0e4283bf4b05ac13f210647658dfd3cec8b34c2`。
- `joined.srg`：`43d4376110b2638213d3096f80f8395c076eb05b58d6c0dbd22c0d999b37a357`。

## GUI 打开期间的检测边界

锁定 BQ 的 `bq_standard/handlers/PlayerContainerListener.java:25–37` 只把监听器附到 `player.inventoryContainer`，并没有给每个打开的箱子或机器容器增加监听器。`sendSlotContents` 仅接受该玩家库存菜单的槽位 `9..44`，包括槽位变空的通知。

SRG 映射与真实 1.7.10 字节码给出了以下对应关系：

| 字节码名称 | SRG / 含义 |
| --- | --- |
| `mw` | `net.minecraft.entity.player.EntityPlayerMP` |
| `mw.h()V` | `func_70071_h_`，玩家服务器更新 |
| `yz` | `net.minecraft.entity.player.EntityPlayer` |
| `yz.bn` | `field_71069_bz`，`inventoryContainer` |
| `yz.bo` | `field_71070_bA`，`openContainer` |
| `zs.b()V` | `Container.func_75142_b`，`detectAndSendChanges` |

`mw.h()` 的字节码偏移 `34..38` 读取字段 `bo` 后调用 `zs.b()V`，即每次常规更新检测当前 `openContainer`；拾取通知 `mw.a(sa,int)` 也走当前 `openContainer`。对 `EntityPlayer` 的检查没有找到补充的常规 `inventoryContainer.detectAndSendChanges()` 路径。此证据限定于上述原版类及锁定 BQ，不推断其他旧模组可能增加的钩子。

现代真实 `ServerPlayer.tick()` 同样只对当前 `containerMenu` 调用 `broadcastChanges()`，而不是无条件广播 `inventoryMenu`。FTB 的 `FTBQuestsEventHandler.containerOpened()` 还会为非 `InventoryMenu` 的容器安装监听器；这是现代 FTB 的额外路径。

现有 `LegacyInventoryListenerMixin` 仅在 `menu == player.inventoryMenu` 且槽位为 `9..44` 时安排旧语义检测。因此打开其他容器期间，标记任务可以等到玩家库存菜单再次广播后才更新。不能仅因 FTB 原有监听器更早观察到箱子内的玩家背包槽，就增加旧版没有证实存在的即时检测。普通、未标记 FTB 任务继续使用其原有容器监听行为。

## 初始同步、空槽与 Pre 时机

BQ 的 `PlayerContainerListener.sendContainerAndContentsToPlayer()` 无条件调用 `updateTasks()`；`sendSlotContents()` 在主库存范围内也不要求新栈非空。`EventHandler.schedulePlayerInventoryCheck()` 使用集合合并重复请求，`onServerTick()` 在 `Phase.START` 处理待检测玩家。

现代 FTB 的 `FTBQuestsInventoryListener.slotChanged()` 原本忽略空栈。新的 HEAD 注入在原空栈判断之前安排检测，因而能保留旧版主库存清空通知。`LegacyInventoryRescanQueue` 按服务器保存待检测玩家并去重；`LegacyInventoryObserver.beforeTick(ServerTickEvent.Pre)` 取出这一批请求，检查任务文件、团队锁定状态和任务可开始状态，在玩家上下文中提交带 `gtnh_legacy_single_retrieval` 标记的任务。服务器停止事件清理对应队列。

现代 `AbstractContainerMenu.addSlotListener()` 在新增监听器后调用 `broadcastChanges()`，但只广播相对菜单既有快照发生变化的槽位。空库存或已经同步过的库存不保证产生初始 `slotChanged` 回调。`LegacyInitialInventorySyncMixin` 因此在该广播调用前，为玩家库存菜单上的 FTB 监听器补入一次初始检测请求。实际字节码中，重复注册同一监听器会先返回，不经过该注入点。

`LegacyTaskLoginTimingMixin` 只把标记任务的 `checkOnLogin()` 改为 `false`，避免 FTB 登录流程立即再检测一次；初始菜单监听注册已经负责排队。`LegacyInventoryListenerMixin` 的 redirect 只跳过“空 crafted stack 且任务带该标记”的 FTB 自动提交；未标记任务及正常 crafting 路径仍调用原 `submitTask()`。这项隔离服务于已经支持的标记检索范围，不代表多要求、消耗、队伍历史或所有检索任务都已接入。

## Loot chest 的三处独立触发

锁定 BQ 的 `bq_standard/items/ItemLootChest.java` 在第 `74`、`107`、`143` 行显式调用 `player.inventoryContainer.detectAndSendChanges()`。它们绕过“常规更新只广播当前打开容器”的限制，是需要独立保留的原始触发点。

本次库存桥接没有迁移 loot chest 的完整物品行为，也没有以全局轮询代替这些调用。将来迁移该物品时，必须逐处恢复对应玩家库存通知或经验证等价的排队行为，并检查打开其他 GUI 时的结果；不能因为普通菜单监听已接入就把这三处算作完成。

## EventBusSubscriber 装载依据及验证限制

此次直接检查的兼容 JAR 包含 `LegacyInventoryObserver.class`，并保留 `@EventBusSubscriber(modid="gtnh_migration_compat")`；其 `beforeTick` 和 `stopped` 方法为静态方法，保留 `@SubscribeEvent`，事件参数分别为 `ServerTickEvent.Pre` 和 `ServerStoppedEvent`。三个库存相关 Mixin 也存在于 JAR 的 `gtnh_migration_compat.mixins.json` 注册表中。

本地 `loader-4.0.45.jar` 的注解字节码表明，`EventBusSubscriber.bus()` 默认值为 `GAME`，默认物理端包含客户端与专用服务器。`AutomaticEventSubscriber` 的真实字节码包含对游戏事件总线的类注册路径；NeoForge `EventHooks.fireServerTickPre()` 则明确把 `ServerTickEvent.Pre` 投递到 `NeoForge.EVENT_BUS`。因此当前注解与事件类型使用的总线匹配，不存在“遗漏 bus 参数导致订阅了 MOD 总线”的问题。

这些是打包、签名及装载机制的静态证据。现有库存测试执行打包的队列和真实现代菜单，并检查桥接字节码形状；Sponge 注入应用由另外的隔离变换测试覆盖。它们均不等于执行 NeoForge 自动订阅、真实玩家登录、Pre 派发或世界中的任务检测。`game_events_executed`、`game_runtime_verified` 与完整迁移结论必须继续保持未验证状态。
