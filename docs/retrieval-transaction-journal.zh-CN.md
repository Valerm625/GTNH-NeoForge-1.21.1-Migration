# 检索事务日志与实际库存候选

Phase 6 已纳入完整重建和兼容JAR对应检查，但尚未完成世界运行时集成，不增加任务导出数或已迁移功能覆盖。

## 当前实现

`LegacyRetrievalTransactionJournal` 在现有独立文件账本中追加事务记录，使用 `gtnh-generated-transaction:v1/` 命名空间区分生成记录与原始输入。原始字节和既有账本帧继续保留。

准备阶段保存事务UUID、actor、原账本摘要、完整前后玩家NBT及前后进度；账本进度仍保持旧值。新候选玩家数据在 `NeoForgeData.PlayerPersisted` 下携带 `gtnh_migration:retrieval_transaction` 标记，绑定事务UUID及准备记录字节摘要。日志类本身不写玩家文件、不扣物、不发奖励。

恢复可以由调用者提供实际持久化的玩家文件内容，也可以通过 `resolveFromPlayerDirectory` 读取当前UUID.dat。新标记与预期新库存同时吻合才提交新进度；旧标记与旧库存同时吻合则取消该次事务。其他组合拒绝恢复并保留记录，不能盲目补扣、退款或清空进度。取消日志事务不会恢复已经变动的内存库存，调用层必须在恢复正常游戏前处理这一点。

同一账本同时仅允许一笔待决事务。重复恢复、未知格式、外部写入绕过日志、玩家身份或标记不匹配均有检查。已解决事务后允许正常库存变化，但仍校验该玩家最后一个事务标记。

## 库存和状态候选

`LegacyRetrievalTransactionProposal.bind` 绑定实际库存Prepared、规划所用账本Head、完整可信Catalog及玩家NBT。只在该quest的所有task index/factory与目录一致、规划前状态与账本一致时生成结果。其他quest的任务行及不透明完成记录保持原样，完成/奖励事件不触发。

`Prepared.proposedPlayerData` 按原生保存形状核对主背包0–35、盔甲100–103，保留副手150；检测语义仍只读旧40槽。每个ItemStack必须通过实际NBT codec精确往返，未知字段、重复/未知槽、顺序异常、UUID或库存不符均保留输入并拒绝。更新数量使用真实ItemStack编码，整栈消耗删除对应条目。外层玩家字段和其他NeoForgeData不变。

日志prepare强制接收候选捕获的expectedHead。并发的新账本帧即使不改进度，也会使旧候选失效；不能以提交时新读到的head替换旧摘要。所有日志操作仅持有共享store监视器，不再同时持有日志对象锁。

以上没有证明玩家当前磁盘文件等于候选before。正式协调器必须先确保该基线已持久化，复核现场库存、权限和参与者，保持提交期间无其他写入，实际保存后再读回。日志取消也不等于内存库存已恢复。

## 本次验证

在具有本地依赖的原工作区执行：

```powershell
python -u work/migration/test_legacy_retrieval_transaction_journal.py
python -u work/migration/test_legacy_retrieval_transaction_proposal.py
python -u work/migration/test_legacy_player_data_file_reader.py
```

报告位于 `evidence/quests/LegacyRetrievalTransactionJournalCheck.json`，包含本次源码SHA-256。测试使用真实NBT库及独立文件账本，但玩家NBT是合成数据，写入器是测试夹具；没有真实Player、世界或原生PlayerDataStorage调用。

- 53项断言覆盖准备/提交/取消、原件与不透明字段保留、深复制、无效数据拒绝、旧标记回退拒绝和两个日志对象竞争同一账本。
- 7个独立JVM进程中断边界：准备临时文件、准备发布、玩家临时文件、玩家发布、决策临时文件、决策发布、事务结束。
- 每个中断场景由新进程恢复，再用另一进程重复恢复；既有账本帧保持不变，恢复不改写玩家文件。

另有68项实际Inventory/ItemStack到玩家NBT候选、日志准备/关闭/重开/恢复组合断言，覆盖40槽、组件/副手、其他quest/完成记录、离线成员、旧版本计划及重复扣物。

这些是进程中断与夹具协议的验证，不是原生玩家保存、机器掉电或完整游戏行为的证明。真实玩家存档fixture数量为0。

## 尚未完成

1. 将已验证的库存候选与日志接入实际游戏协调器。原物品/矿辞匹配、认证和参与者仍需真实验证；当前候选验证现代NBT，不能替代原身份映射。
2. 实现原生玩家保存协调器，确保标记和库存一同保存，将现有文件读回层接入受隔离的实际保存流程；不能仅根据保存方法返回认定成功，也不能将旧备份回退当成新提交。
3. 在登录、死亡/克隆、退出、重载及异常恢复期间阻止未解决事务进入正常游戏，妥善恢复或重新加载内存库存。
4. 完成世界生命周期、队伍、网络/UI、任务完成与奖励集成；审查与外层调用者的锁顺序。
5. 处理账本增长、容量扩展、历史整理及外部删除完整尾部历史的检测限制。
6. 验证完整NeoForge转换链及实际Player/世界保存，不能从基础类和夹具通过推断已完成。

当前27项完整重建检查通过；日志、候选及文件读取类已经与兼容JAR对应类逐字节核验。完整任务迁移仍为未完成，依赖闭合实际导出仍为2/3814，旧物品身份映射仍为720/8388。

## 原生保存API的静态证据

`evidence/native-player-persistence-api-audit.json` 固定实际NeoForge server及相关类摘要。Inventory.save的字节码在忽略常量池编号/显示空白后与原版相同。PlayerDataStorage.save同步创建临时文件、writeCompressed、safeReplaceFile并触发保存事件，捕获Exception后返回void；它不是SavedData的异步worker接口，返回也不代表成功。load可能读取dat_old备份。Entity将持久数据保存在NeoForgeData；锁定ServerPlayer.restoreFrom只自动保留PlayerPersisted子树，再发Clone事件。实际死亡/克隆、事件和掉落行为仍需另行测试。本审计未执行任何实际Player方法。

## 保留重生标记与旧版兼容

对锁定NeoForge 21.1.262的ServerPlayer.restoreFrom检查发现，根NeoForgeData里的自定义标记不会自动随玩家重建保留。新标记使用schema_version=2，位于 `NeoForgeData.PlayerPersisted.gtnh_migration:retrieval_transaction`。runner固定server JAR摘要并核对原生复制范围的15条字节码指令；NBT夹具只模拟这个范围，没有执行实际Player、Clone事件或死亡掉落。

原schema_version=1及NeoForgeData直下的位置仍可读取，旧事务记录和来源字节不改写。标记身份仍由事务UUID和准备记录摘要决定，因此旧位置中已保存的匹配标记仍能解决待决事务。新旧位置同时存在、版本与位置不符、命名空间类型错误则拒绝，原数据保留。

`proposePersistentMarker` 先核对已解决的账本历史，返回可保存的新布局副本；它不修改输入、库存、账本或文件，重复调用结果不变，并保留其他PlayerPersisted和NeoForgeData数据。正式协调器必须在允许旧标记玩家进入游戏前保存并读回该候选；只兼容读取旧标记无法防止它在之后重生时丢失。恢复/迁移协调器尚未接入，不能将这份候选函数宣称为实际玩家迁移完成。

新增17项检查包含v1读取及待决事务恢复、升级候选不写盘、不改库存/其他数据、重复升级、新布局在模拟复制范围后校验通过、旧布局丢失后拒绝及歧义拒绝。日志累计53项断言，既有7个进程中断场景继续通过。

## 当前玩家文件的读取与恢复

`LegacyPlayerDataFileReader.read` 仅读取传入可信玩家目录中的 `<UUID>.dat`。缺失、损坏或类型不符直接拒绝；不会读 `dat_old` 或临时文件，也不调用Player、DataFixer、加载事件或任何文件写入。它保留原始gzip字节、SHA-256及深复制NBT快照，未知外层NBT仍保留；这不等于所有物品/旧版本NBT已经语义验证。

读取要求目录和目标是实际目录及普通文件，不跟随这两个路径本身的符号链接。前后比较文件身份、大小和修改时间，`verifyUnchanged` 再次完整读取并比较原始字节摘要。同一逻辑NBT经过重新压缩也会导致旧快照校验失败。属性及摘要校验不是写入锁，也不防御不可信祖先路径；正式协调器必须使用可信世界目录，并从读回到日志决策完成持续排除原生保存和玩家生命周期变化。

gzip必须恰有一个完整成员，支持标准可选头字段，校验头校验和（若存在）、CRC32和长度；串接成员、尾部垃圾、截断及额外NBT尾字节均拒绝。压缩/解压上限各64MiB，NBT记账上限256MiB；超过上限保留原文件并阻止本次恢复，不截断。容量扩展仍未完成，不能据此宣称全量存档支持。

`resolveFromPlayerDirectory` 从待决记录选择actor，读取并复核当前文件，再按既有标记/库存规则提交或取消。40项断言覆盖深复制、标准可选头、完整性损坏、读取中替换、容量、UUID及库存类型、当前文件缺失/损坏而旧备份有效、关闭重开后的提交和取消；确认玩家及备份文件字节未被恢复过程改写。本机未成功创建测试符号链接，因此报告的 `symbolic_link_checked=false`，未将该分支计作已执行。

这些测试使用真实Minecraft NBT库与文件系统，但玩家内容和文件写入器仍为合成夹具；真实玩家存档数量仍为0。原生PlayerDataStorage保存、内存恢复和登录/死亡/克隆隔离尚未接入。
