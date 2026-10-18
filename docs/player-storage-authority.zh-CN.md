# 玩家持久化来源：UUID.dat 与单人世界 level.dat

原生玩家数据并非总由UUID.dat决定。锁定NeoForge 21.1.262中的PlayerList.load先取得WorldData.getLoadedPlayerTag；当服务器判断当前玩家是单人房主且该标签非null时，直接加载这份玩家NBT。其余分支才调用PlayerDataStorage.load，后者可能回退dat_old。因此只核对UUID.dat不足以证明房主下次登录将读取同一份库存。

这些结论来自固定server JAR的字节码，报告见 `evidence/quests/LegacyWorldPlayerAuthorityCheck.json`。PlayerList加载发生在加入players列表和登录事件之前；退出顺序是LoggedOut事件、保存玩家、移除玩家。Player.addAdditionalSaveData会写入当前DataVersion，随后保存Inventory；此前只查看Entity和PlayerDataStorage不足以确认版本标签来源。

## 显式文件来源

`LegacyPlayerDataFileReader.read(playerDirectory, actor)`保持读取UUID.dat。新增 `readWorldPlayer(worldDirectory, actor)`仅读取level.dat中的Data.Player，验证期望UUID、库存和标记。两者都保留完整压缩字节及整个NBT文档，`data()`返回玩家子树，`documentData()`返回整份文档的深复制。

level.dat缺失、损坏或没有Data.Player时明确拒绝，不自动改用level.dat_old或playerdata/UUID.dat。文件摘要涵盖整个level.dat；即使Player子树未变而其他世界元数据变化，旧快照也不能继续解决事务。读取及日志恢复都不修改世界元数据、玩家镜像或备份。

调用者必须先核实原生加载会使用哪个来源，并保持读回到决策完成期间没有其他写入。本读取器不决定服务器模式、不运行DataFixer，也不替代原生世界缓存。压缩/解压各64MiB和NBT记账256MiB限制继续适用；超限拒绝且保留原件，容量扩展仍待完成。

## 日志来源绑定

原UUID.dat准备记录仍写schema_version=1，按PLAYER_FILE解释，原来源字节保持不变。显式WORLD_PLAYER准备写schema_version=2并携带player_authority字段。来源归档路径格式和决策记录仍为v1；准备记录版本与PlayerPersisted里的标记版本是不同概念。

Pending.authority()在关闭重开后仍可读。resolveFromWorldPlayer只接受WORLD_PLAYER，resolveFromPlayerDirectory只接受PLAYER_FILE。接收文件Snapshot的resolve先重新检查全文摘要，再检查来源及玩家数据。旧的裸NBT resolve仅允许PLAYER_FILE，不能绕过WORLD_PLAYER的来源验证。错误文件来源即使具有相同UUID、库存和事务标记也被拒绝，账本不前进。

47项断言包括完整世界文档和原始压缩字节保留、深复制、外层元数据变化、损坏/缺失主文件及有效备份、玩家镜像与世界文件状态相反、重开恢复、旧schema兼容和4种日志来源字段损坏。使用真实Minecraft NBT库和独立账本，但世界文件、玩家数据及写入器为合成夹具。

## 房主缓存与世界保存协调

`acquire_native_client_audit.py` 用锁定的21.1.262安装器处理器、官方1.21.1客户端和映射，在独立忽略目录中重建检查用字节码。输入和处理器摘要见 `locks/native-client-audit-inputs.json`。没有运行游戏、修改原服务端实例或接受EULA；原客户端、映射及提取字节码不提交公开仓库。

固定字节码确认：IntegratedPlayerList.save先为房主调用saveWithoutId更新playerData缓存，再调用父类保存。getSingleplayerData返回该缓存。MinecraftServer.saveEverything先saveAll玩家，再saveAllChunks；后者将缓存传给世界保存。IntegratedServer覆写saveEverything，先委托父类，再检查剩余磁盘空间。因而保存结束后的异常也不能直接解释为库存未保存。

现提供单独的 `submitIntegratedOwner` 原生适配入口，普通 `submit` 仍拒绝房主，防止调用者误选UUID.dat来源。房主入口核对服务器线程、房主身份、在线玩家对象、连接和世界玩家目录；通过saveEverything保存，再读回level.dat，并核对内存、房主缓存和UUID镜像中的身份、库存及本项目事务标记。第一次保存尚无Data.Player时也必须先形成可读回的完整基线，之后才准备WORLD_PLAYER事务。

如果世界文件证明扣物尚未保存，先恢复实际库存中的原物品对象及标记。缓存和镜像只有属于本次前态或后态才允许继续；存在差异时，经PlayerList.saveAll从已恢复的活玩家刷新二者，不直接覆盖旧缓存对象，也不再次保存世界文件。最后重新检查缓存、镜像及整份level.dat摘要，全部一致才发布取消记录。未知缓存/镜像、修复失败、回调期间世界元数据变化均保留待决记录并进入隔离；不能从有效UUID镜像猜测房主事务已提交。

该入口保存整个服务器的玩家和世界；取消修复也可能保存所有玩家、统计及成就，并触发原生保存回调。调用层必须持续排除全服务器的其他提交、世界/玩家保存、库存变化和生命周期变化，直到决策完成。只锁账本或只保护房主不满足要求。

## 验证及未完成部分

`test_legacy_integrated_owner_coordinator.py` 通过257项夹具断言和4组破坏性字节码负例：首次世界玩家基线、缓存先更新/镜像先发布/世界发布后的失败、取消修复及修复失败、世界元数据变化、后续保存不重新扣物、重复提交和错误来源重开。生产WorldPlayerPort的取消/复核逻辑由夹具直接执行；底层世界、玩家文件和保存回调仍为合成实现。原生房主适配器完成编译及调用顺序审计，未实际调用Player、世界保存或客户端。

连续生命周期隔离、加载前恢复、原生备份回退、世界类型或房主身份切换、退出/死亡/克隆，以及未知辅助状态的隔离恢复入口仍需实现。直接调用journal.resolveFromWorldPlayer只验证世界文件和账本，不证明缓存与镜像已同步，不能据此恢复玩家入场。真实玩家存档fixture仍为0，任务导出仍为2/3814，完整迁移未完成。
