# 多要求检索的运行时接入设计与实际 API 边界

本文基于锁定的 FTB Quests、BetterQuesting 源码和当前安装的 NeoForge/FTB Teams/Minecraft 类签名，研究把 `LegacyRetrievalEngine`、`LegacyQuestRetrievalPlanner` 接入独立任务类型的最小路线。本文没有注册新任务、接入存档、发送网络消息或运行游戏。现有 2/3,814 项任务和 47 个候选的边界不变；纯模型及隔离测试不能增加迁移完成数。

可行路线是：新建 `Task` 子类，保持旧 quest UUID、原 task index 和 factory ID；使用独立的 UUID 进度账本；从服务端一次执行整个旧 quest 的 planner；另行同步每位查看者的显示数据。不能通过继承 `ItemTask` 或调用 `TeamData.setProgress` 来代替这几层。完整激活还缺少旧式完成/奖励、队伍生命周期、身份映射和可恢复库存事务，以下逐项说明具体原因。

## 核验基准与定位方式

源码简称如下，后文路径和行号均相对于对应根目录，行号指本次锁定版本。

| 简称 | 本地根目录或安装文件 | 锁定版本 |
| --- | --- | --- |
| FTB | `work/exact-sources/FTB-Quests/v2101.1.36/common/src/main/java/dev/ftb/mods/ftbquests/` | `2101.1.36`，提交 `8c53f35a97d8897c861b097f1e39f4fc3beb3a15` |
| BQ | `work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java/` | `3.8.89-GTNH`，提交 `815d738923d46e9083f988421e5329193e2e5940` |
| MC | Foundation 下 `libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar` | SHA-256 `26ca9c40d7e1681190b428583c38816852218e78df3f8bdb60a59a78503aec71` |
| NeoForge | Foundation 下 `libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar` | SHA-256 `4070305f97a08409d9b4e714f619f0ec2d97d29c9939ef43a89d927259942f98` |
| FTB Teams | Foundation 下 `mods/ftb-teams-neoforge-2101.1.11.jar` | SHA-256 `e6bcd670493624e6906df872a9c6af4a33fe6405cac57a32c59cd4cd5838c655` |

Foundation 指仓库忽略目录 `outputs/GTNH-NeoForge-1.21.1-Migration-Foundation/`。MC、NeoForge 和 FTB Teams 的下述 API 通过安装 JAR 的 `javap` 核验；这证明签名存在，不证明游戏内线程、同步或恢复流程已通过。FTB 和 BQ 的行为依据本地锁定源码，不使用其他版本文档推定。

## 自定义任务的真实注册入口

FTB `quest/task/TaskTypes.java:20` 提供公开静态接口方法：

```java
TaskTypes.register(ResourceLocation name, TaskType.Provider provider,
                   Supplier<Icon> iconSupplier);
// Provider 的函数签名：Task create(long id, Quest quest)
```

因此可以注册一个本项目命名空间下的 `legacy_retrieval` 类型，provider 构造新的 `Task` 子类。该名称是设计建议，当前尚无对应生产注册。`TaskType.createTask(long, Quest, String)` 位于 `quest/task/TaskType.java:41`，磁盘加载从类型字符串查找 provider；`getTypeForNBT()` 保留外部 namespace。无需把新类型伪装成 `ftbquests:item`。

注册必须在服务端 `ServerQuestFile` 创建前完成，并在客户端保持同一类型可用。`quest/ServerQuestFile.java:54` 构造时为当时存在的 `TaskTypes.TYPES` 分配内部编号；`quest/BaseQuestFile.java:1002` 写入类型表和任务定义，`:1094` 起读取，未知任务类型在 `:1166` 处抛异常。仅在玩家登录时注册太晚。实际实现还须验证客户端类隔离及懒加载图标，不能把客户端 GUI 类放进专用服务端必经的初始化路径。

新任务需要实现或覆盖的现有入口是：

| API | 用途和限制 |
| --- | --- |
| `Task(long id, Quest quest)`、`getType()` | FTB 图中任务身份；另存旧 quest UUID、原 index、factory ID，不以列表位置重编号 |
| `writeData(CompoundTag, HolderLookup.Provider)` / `readData(...)` | 任务定义的磁盘往返，不能存玩家运行时进度 |
| `writeNetData(RegistryFriendlyByteBuf)` / `readNetData(...)` | 任务定义的同步，不能冒充 UUID 进度同步 |
| `onButtonClicked(Button, boolean)` | 打开自有多要求视图并发起一次 quest 检测请求 |
| `drawGUI(TeamData, GuiGraphics, int, int, int, int)`、提示文本入口 | 可复用 FTB 图标/布局容器，但不能独自改掉外层按钮的标量进度逻辑 |
| `submitTask(TeamData, ServerPlayer, ItemStack)` | 现有服务端提交入口；手动和自动调用共用签名，不能从 `ItemStack.EMPTY` 推断手动点击 |
| `checkOnLogin()`、`submitItemsOnInventoryChange()`、`autoSubmitOnPlayerTick()` | 必须明确选择检测时机；第一阶段应禁止未经旧语义核验的自动调用 |

定义读写的具体方法见 `quest/task/Task.java:305` 至 `:329`。`TaskType.setGuiProvider(...)` 管的是创建任务的编辑界面，不会替换运行时的 `TaskButton`。

## 能复用哪些物品 UI

`ItemTask` 的定义、匹配和显示都围绕一个 item/count；`quest/task/ItemTask.java:153` 生成的是现代物品匹配系统展开的列表。它不能证明旧 `logWood` 身份集合已完整映射，也不能表达同一旧任务中的多要求计数与独立完成 latch。

`ItemTask.onButtonClicked`（`:222`）在部分非消耗场景直接打开配方；`client/gui/quests/ValidItemsScreen.java:29` 的构造参数明确要求 `ItemTask`。其提交按钮在 `:81` 发出 `SubmitTaskMessage(task.id)`，`:94` 只为可点击的消耗任务启用提交。把多要求任务塞进这里，会让“检测整个旧 quest”变成“交付一个 FTB ItemTask”，或让非消耗任务没有对应检测按钮。

可复用的是 `Panel`、`Button`、图标、物品渲染和 tooltip 等界面组件。需要新视图逐行显示原要求、该 UUID 的进度、是否已 latch、消耗模式及已证明的匹配对象；同一旧任务的要求仍是一项任务的数据，不能拆成多个可独立领取或完成的 FTB 任务。

外层 `client/gui/quests/TaskButton.java` 还存在三个必须接管的读取点：

- `:77` 至 `:80` 的点击条件读取 `selfTeamData.canStartTasks(...)` 和队伍共享的 `isCompleted(task)`。
- `:179` 的 tooltip 读取 `TeamData.getProgress(task)`。
- `:231` 的勾选状态读取共享的 `isCompleted(task)`；`:214` 才调用任务自身的 `drawGUI`。

因此仅覆盖 `drawGUI` 不够。可以对新类型做受限 UI 适配，让上述显示读取自有的当前查看者快照，并把按钮作为整个旧 quest 的检测入口。其他 FTB 类型继续使用原逻辑。不要把任意 UUID 的进度写入共享 `TeamData`，也不要用 `getMaxProgress() = 1` 掩盖多要求状态：`Task.getRelativeProgressFromChildren` 本身为 final，仍从共享标量取值，章节和依赖计算也仍会受到影响。

## 手动请求必须调度整个旧 quest

BQ `betterquesting/network/handlers/NetQuestAction.java:47` 的 `requestDetect` 发送 quest UUID 集合，`:90` 的服务端检测进入 `QuestInstance.detect`。`betterquesting/questing/QuestInstance.java:149` 先检查当前 UUID、quest cache、`canSubmit` 和解锁，再按原任务存储顺序检测所有发起者尚未完成的任务。前一任务未完成并不阻止后一任务检测。

FTB `net/SubmitTaskMessage.java:13` 的消息只有 `long taskId`；`:28` 将处理放入队列，随后验证服务端玩家、队伍锁、任务存在和 `canStartTasks`，并在 `withPlayerContext` 中调用 `task.submitTask(data, player)`。这足以定位一个自定义任务，但不会自动保留 BQ 的 quest 顺序、UUID 进度或旧入口条件。

有两种明确的接入选择：

1. 复用该消息时，新任务必须从 taskId 查到原 quest，调用整个 `LegacyQuestRetrievalPlanner.plan`，而不是只运行被点击的任务。还必须识别可信的手动入口，并审计所有 `submitTask` 调用者。两参数 `Task.submitTask` 在 `:286` 是 final，只把 `ItemStack.EMPTY` 传给三参数方法；登录、库存、其他检测也可能传相同值。
2. 推荐为新类型增加独立提交消息和服务。消息只携带任务/quest 引用及有界的版本/请求标识；actor 从认证连接取得，成员、计数、完成标记、匹配结果全部由服务端读取。自定义任务的普通自动提交入口在对应语义尚未实现时保持禁用。这样手动来源无需借助对 EMPTY 的猜测。

下述 NeoForge API 在当前安装 JAR 中存在，可支撑第二条路线：

```java
RegisterPayloadHandlersEvent.registrar(String version)
PayloadRegistrar.playToServer(Type<T>,
    StreamCodec<? super RegistryFriendlyByteBuf, T>, IPayloadHandler<T>)
PayloadRegistrar.playToClient(Type<T>,
    StreamCodec<? super RegistryFriendlyByteBuf, T>, IPayloadHandler<T>)
PayloadRegistrar.executesOn(HandlerThread.MAIN)
IPayloadContext.player()
IPayloadContext.enqueueWork(Runnable)
IPayloadContext.reply(CustomPacketPayload)
PacketDistributor.sendToPlayer(ServerPlayer, CustomPacketPayload, ...)
```

`RegisterPayloadHandlersEvent` 是 mod bus 事件；线程选择应明确使用 MAIN，不能依靠未经核验的默认值。请求需有大小限制、重复请求处理和版本检查；仅靠客户端不可点击不能防止过期或重复提交。

不要用 FTB 的 `requireSequentialTasks` 模拟旧顺序：`Task.checkTaskSequence`（`:289`）要求前一个任务完成后才允许后一个，和 BQ 的“依次运行，即使前项尚未完成也继续”不同。不要使用当前仅为 `gtnh_legacy_single_retrieval` 标记服务的 `LegacyInventoryObserver` 直接代跑新任务；该类按 FTB submit task 列表调用单任务入口，没有 quest 级事务和自有 UUID 账本。

## 持久化与同步必须独立

FTB `quest/TeamData.java:77` 的 `taskProgress` 是 taskId 到 long 的队伍共享映射。`:340` 的 `serializeNBT` 和 `:371` 的 `deserializeNBT` 只处理固定字段；额外塞进输入 CompoundTag 的未知根字段不会被下一次序列化自动保留。`:438`、`:490` 的网络读写为 private，`STREAM_CODEC` 在 `:56` 直接构造 `TeamData`。这里没有可直接挂载任意任务 UUID 状态的公开序列化回调。

`saveIfChanged()`（`:146`）将队伍数据写到世界的 `ftbquests/<teamId>.snbt`。`PerPlayerData` 不是检索状态容器，只有编辑权限、自动置顶及置顶任务等字段。`CustomTask` 的 progress 回调最终也委托到相同共享标量；它的定义同步不是个人进度扩展协议。

推荐使用世界级、独立命名的 `SavedData` 账本，由 overworld 的 `ServerLevel.getDataStorage()` 管理，避免维度切换生成多个账本。以下签名已由 MC JAR 核验：

```java
abstract CompoundTag SavedData.save(CompoundTag tag, HolderLookup.Provider registries)
void SavedData.setDirty()
SavedData.Factory<T>(Supplier<T> constructor,
    BiFunction<CompoundTag, HolderLookup.Provider, T> deserializer,
    DataFixTypes type)
<T extends SavedData> T DimensionDataStorage.computeIfAbsent(
    SavedData.Factory<T> factory, String id)
void DimensionDataStorage.save()
```

实际实现还需确认自有存储的 data fixer 配置和异常加载策略；这里不把未核验的构造参数默认值写成可直接运行的样例。账本以原 quest UUID、task index、factory ID 定位原状态，任务内部保存完整 UUID 进度行与完成集合。配置版本/身份映射版本和事务序号放在自有外层元数据，不能覆盖旧字段或改写旧完成集合。离线 UUID、仅有完成标记的 UUID 和当前不在队伍的历史 UUID 都不能因一次同步或成员筛选而删除。

新 typed JSON reader 与 progress envelope 是导入/写回层；即使其纯测试通过，也仍须把原始数据和解析结果绑定到同一配置版本，保存未知字段，并对无效原 NBT 采用拒绝激活及保留原件的策略。不能把有限模型重新序列化后当作完整原存档的替代品。

FTB Teams 确有 `Team.getExtraData()`、`TeamManager.getExtraData()` 和 `markDirty()`，但这些 API 属于 FTB Teams，不属于 FTB Quests 的 `TeamData`。其存在不能证明自有数据会随 quest 同步，也不适合直接承载跨退队、换队、删队的全局 UUID 历史。

同步使用自有 snapshot/delta 消息，包含明确配置版本、账本 revision、当前查看者以及对应任务的多要求状态。初始消息应与 FTB 任务定义同步握手衔接：`ServerQuestFile.playerLoggedIn`（`:217`）先发送 quest 定义，客户端请求 `RequestTeamDataMessage` 后才接收队伍数据；不能把新类型的进度抢先发给尚无定义的客户端。换队、退出、重载、管理员重置及配置改变都须使旧快照失效。只发送显示所需的状态，不能默认把全部离线成员的历史发送给每个客户端。

## 为什么共享完成投影仍是阻塞项

`TeamData.setProgress(Task, long)`（`:666`）是 final，达到最大值后会直接调用 `markTaskCompleted`；后者在 `:708` 调用 `Task.onCompleted`，并为在线成员立即再次调用 `FTBQuestsInventoryListener.detect`。`Task.onCompleted` 也是 final，它设置任务完成、发事件并可能触发 quest 完成；`Quest.onCompleted`（`quest/Quest.java:605`）继续处理通知、章节、依赖完成和自动奖励。

因此在库存计划中途调用一次 `setProgress`，就可能在后一个旧任务尚未计算或库存尚未全部扣除时发生嵌套检测和领奖。即使把调用移到最后，共享 completed 映射仍会把一个 UUID 的状态传播为整个队伍完成。简单写入 0/1 并不是无副作用的 UI 投影。

队伍生命周期会进一步改变该状态：`TeamData.mergeData`（`:755`）对任务进度使用 `Long::max`，并合并共享完成记录；`:775` 的 `copyData` 直接复制这些映射。`ServerQuestFile.teamCreated`（`:277`）和 `playerChangedTeam`（`:300`）分别调用复制/合并，加入队伍时还会检查自动奖励。登录 `checkQuestBookOnLogin`（`:243`）可以根据已有标量重新完成任务/quest。只接管 submit，不接管这些路径，会在重登或换队时重新引入错误。

完整桥接必须为新类型及包含它的旧 quest 定义独立的、可说明 actor 的完成/前置/领奖读取规则，并限制 FTB 的自动完成、force/reset、复制/合并和登录路径。不能只给 `TeamData.getProgress` 加一个“当前玩家”读取：不少章节、队伍缓存和依赖计算没有玩家上下文；`withPlayerContext` 的栈也不是一个长期的 UUID 存储接口。

最小可验证阶段可以先实现自有 UI 和只读 plan 预览，保持奖励及完成投影关闭。只有在 UUID 语义和生命周期接管完成后，才允许正式提交和计入迁移覆盖率。若选择直接修改 FTB 源码而非 Mixin，修改范围仍须覆盖上述读取和生命周期，不能仅把 final 去掉后假定行为已等价。

## 一次提交的库存与状态边界

`LegacyQuestRetrievalPlanner.plan` 已经能按原 task index 生成前后库存、前后任务状态、每项检测结果和总消耗；它不触碰真实物品。服务端桥接建议采用下面的严格顺序：

1. 解析请求、认证 actor、检查队伍锁和原 BQ 入口条件。`Gate` 只是已验证结果的输入，不会替调用方执行 `canSubmit`、解锁或 quest cache 逻辑。
2. 从服务器取得全部参与 UUID 及在线参与者、稳定的原任务顺序、当前账本和配置 revision。FTB Teams 的 `getMembers()` 返回 `Set<UUID>`，`getOnlineMembers()` 返回在线玩家集合；不能把集合枚举顺序未经审查地视为旧 `ParticipantInfo` 顺序。
3. 创建深快照。旧手动消耗只扣 actor；扫描范围要明确映射旧主库存 0–35 和盔甲 36–39，不包含新版本副手。MC `Inventory` 暴露 `items`、`armor`、`offhand`；直接遍历 `getContainerSize()` 会引入旧版没有的副手。在线成员检测快照、actor 扣物快照、原状态都各自独立。
4. 使用已证明的旧身份匹配运行整个 planner。`K` 必须不可变，或是调用方深复制后不再修改的身份快照。把可变 `ItemStack` 当作 key 并浅复制 List，不满足此条件。原 ore 身份集合、meta/NBT 与新组件之间的映射不能由现代 tag 名称猜测。
5. 在任何写入前复核配置、参与者、账本 revision 和所有参与计算的槽快照。任一变化则丢弃计划并重新请求/计算；不能只检查计划中被扣除的槽，因为非消耗检测也依赖未扣物品。
6. 在服务端主线程和本桥接重入保护内，整体应用 actor 库存差量与所有任务 UUID 状态。过程中不调用 `TeamData.setProgress`，不发奖励或完成事件。计划校验失败不产生部分扣物；应用阶段异常需撤回尚未发布的本次变更，并明确处理物品/容器回调是否已经产生外部副作用。
7. 完成持久化协议后标脏、同步库存及自有状态，再由经过独立验证的完成适配器处理允许发布的事件。库存同步可使用现有 `Inventory.setChanged()` 和容器广播入口，但其调用次序及监听重入必须实际验证。重入请求排队至本次事务结束，不得边扣边递归执行 planner。

`0:2` 的必要验收例是 `9 gravel + 5 logs`：原 index 0 先检测 5 logs，index 1 再消耗 2 logs，最终剩 3 logs。点击任一相关任务入口都应调度该 quest 的完整旧循环；不能按按钮点击顺序改变任务执行顺序。颠倒两个源 index 的对照必须出现原算法应有的不同结果。重复提交、非连续 index、非首位 actor、离线成员和先前 task latch 均须保留。

这里还存在独立于主线程原子性的崩溃边界：玩家库存通常在玩家存档中，`SavedData` 是另一个文件。调用 `setDirty()`、`DimensionDataStorage.save()` 或 FTB `saveNow()` 不能把两份文件变成同一个事务。必须设计并验证跨重启恢复协议，例如持久化事务日志及与玩家库存同次保存的事务水位，再对未完成事务恢复或隔离。日志单独存在也不足以保证恰好一次扣物；必须能够区分库存已保存而进度未保存及相反的情况。未证明这一点前不能宣称“原子扣物已完成”，不能盲目重扣或退款。

## 激活前仍缺少的具体实现与验收

| 缺失 | 必须交付的证据 | 当前不允许声称的结果 |
| --- | --- | --- |
| 自定义类型加载和定义同步 | 锁定 FTB 下服务器加载、客户端收到类型表、配置读写往返及未知类型拒绝 | 注册代码存在即游戏可用 |
| 旧物品/NBT/ore 身份快照 | `logWood` 全部旧身份的逐项映射、深快照及现代组件对照 | 原生 NeoForge tag 自动等于旧 ore dictionary |
| 自有 UUID 账本和 envelope 接入 | 完整原数据保留、重复/无效记录策略、重启读写、未知字段保护 | 纯 codec 已完成存档迁移 |
| 手动入口与库存应用器 | 真实库存的整 quest 顺序、5 logs、反序、重放、异常及监听重入测试 | planner 的原子计算等于实际扣物事务 |
| 崩溃恢复 | 在日志、库存、状态各保存边界注入中断，再重启验证不丢物、不重复扣物 | 主线程或标脏即具备存档原子性 |
| UUID 完成/奖励和队伍变化 | 加入、退出、离线重登、删队、force/reset、前置解锁与重复领奖对照 | 队伍共享 scalar 或 `Long::max` 与旧语义等价 |
| 运行时 UI 与网络 | 每要求/每 UUID 进度、latch、完整检测按钮、过期消息与重新登录对照 | 复用 ItemTask 图标就保留了所有交互 |

当前可继续实现的最小单元是“注册独立任务定义 + 自有只读快照/预览消息 + 明确禁止激活的完整校验结果”，随后补齐实际库存事务、完成与持久化协议。未支持的 repeat、ignored、非 AND、未知任务类型等仍整体拒绝，原需求留存；不能为了让 UI 可点击而跳过任务、删除要求或把队伍历史压缩为一个数字。

主要源码依据：[FTB TaskTypes](https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/quest/task/TaskTypes.java)、[FTB SubmitTaskMessage](https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/net/SubmitTaskMessage.java)、[FTB TeamData](https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/quest/TeamData.java)、[FTB ServerQuestFile](https://github.com/FTBTeam/FTB-Quests/blob/8c53f35a97d8897c861b097f1e39f4fc3beb3a15/common/src/main/java/dev/ftb/mods/ftbquests/quest/ServerQuestFile.java)、[BQ QuestInstance](https://github.com/GTNewHorizons/BetterQuesting/blob/815d738923d46e9083f988421e5329193e2e5940/src/main/java/betterquesting/questing/QuestInstance.java)。
