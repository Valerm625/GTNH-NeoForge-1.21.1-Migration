raise SystemExit('Superseded by write_material_phase_status.py; do not restore stale material mappings/counts.')
from pathlib import Path
import json,shutil,datetime
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs'
v=json.loads((D/'verification/validation-summary.json').read_text(encoding='utf-8'));q=json.loads((D/'quest-migration/coverage.json').read_text(encoding='utf-8'));manifest=json.loads((D/'verification/test-runtime-files.json').read_text(encoding='utf-8'));f=json.loads((D/'fuel-block-migration/fuel-blocks.json').read_text(encoding='utf-8'))
status=f'''# GTNH → NeoForge 1.21.1 迁移状态

**更新：2026-10-15（上海时间）。这是未完成迁移工程及本机测试实例，不是成品整合包。**

目标保持原样：全包功能、任务与进度、机器材料、配方、世界生成及贴图无缺失。不能把能加载的现代模组集合当作 GTNH 完整移植，不能把定义存在当作玩法已实现。

## 本阶段新增的真实输出

### 原版燃料方块

- 实现 CoreMod 2.9.84 中 **16 个木炭/压缩木炭/煤/焦煤方块**定义、原始 PNG、立方体模型/方块状态、原英中俄名称和方块分类页。
- 原硬度、石质声音、石材地图颜色、采掘等级、堆叠64、原XML燃烧时间均明确设置；五重煤块原采掘等级是2，而同层木炭/焦煤是3，**保留该差异，未按层级统一**。
- 用 Mojang 官方1.7.10服务端字节码核对：这类方块 `setHardness(h)` 将内部抗爆值抬至 `5h`，对外除5，因此现代显式抗爆值为 `h`，不是误乘5。
- 增加 **28 条原压缩/锻造锤配方**，维持15秒、2 EU/t及9:1/1:9数量关系。另2条木炭基础矿辞映射仍未实现，未将名称相似的方块硬凑成替代品。
- 16条原燃料值完整保存，最大 **1,889,568,000 ticks**，未换算错单位或截断。原XML尚有4条外部mod/metadata燃料条目待处理。

### Railcraft 与 Ender Storage 真实候选

- 下载并核对 **Railcraft Reborn 1.2.12、EnderStorage 2.13.0.191、CodeChickenLib 4.6.1.529** 的NeoForge 1.21.1 JAR、来源哈希和内部依赖；共18个下载JAR加1个本地适配JAR，活动目录 **19个顶层JAR**。
- 使用原清单 Railcraft **9.17.31** 固定提交源码确认旧 `Railcraft:cube:0` 是焦煤块，匹配现代 `railcraft:coal_coke_block`。
- 增加 **2条基础焦煤压缩/解压机器配方**；按原GTNH配方移除逻辑，只删除现代2条免费工作台拆装配方，不删除焦炉生产配方。原焦煤图标/方块PNG以有署名和原许可的可选资源包保存。
- 原焦炉和现代焦炉存在加工时间/杂酚油产量差异，仍明确未完成。轨道、信号、蒸汽锅炉、车厢及GTNH难度门槛也没有宣称等价。
- 原EnderStorage配置要求 **256桶**，现代实现固定 **16桶**。已编译独立的 `gtnh-migration-compat-0.1.0.jar`，通过版本锁定的Mixin把共享容量倍率改为256；**第三方JAR未修改**。
- 容量适配已通过编译、真实目标字节码单一匹配、编译后处理函数返回值以及4个错误原值拒绝测试。**尚未证实游戏中Mixin目标转换、注液/排液/频率共享/重载/联网同步**。
- 用真实CodeChickenLib读写器生成并回读配置，保留钻石私有锁、非无政府模式和3×9库存。原神秘时代研究/要素门槛与现代普通合成配方不等价，配方/特效/原版末影箱设置差异全部列为未完成。

### 长燃料保存/同步验证

最初从未打补丁Minecraft类看到短整数保存风险，随后检查**实际NeoForge补丁类**确认它已用整数NBT保存，并在双方支持时使用VAR_INT高级菜单同步包。因此**没有添加重复的熔炉补丁，也没有修改全局网络协议**。

- 真实Minecraft `NbtIo` 和NeoForge `AdvancedContainerSetDataPayload` 编解码执行 **120组边界/随机值回读测试**，包括1,889,568,000与Integer.MAX_VALUE，全部通过；短整数对照会损坏其中116个值。
- 这是真实二进制编解码测试，不是替身模拟；但它**不等于实际熔炉存读档或已连接客户端测试**。最后一份燃料耗用后重载、GUI和mod机器消费行为仍要验收。

## 累计实现与未验证边界

| 项目 | 已落盘实现/检查 | 未宣称完成 |
|---|---|---|
| 平台 | NeoForge21.1.262、独立Java21、19个顶层JAR；57项版本约束通过 | 完整mod初始化、世界启动、客户端 |
| 核心物品 | 558个定义/行为，含真实双层硬币、31项动画、木制砖模 | 全部外部集成/特定行为；4种物品危险效果仍未移植 |
| 方块 | 新增16个燃料方块及其16个BlockItem | 所有原方块、世界生成和mod采掘层级等价 |
| 配方 | **157条机器配方＋3条制砖配方**定义及脚本测试 | 全部GTNH配方、科技门槛和可达性 |
| 分类与提示 | 8个分类页、24条原提示、2条原版燃料覆盖 | 全部物品/附属、完整汉化和显示 |
| 任务 | 3814任务/46章节无损中间数据；{q['mapped_total_item_identities']}种身份已有定义映射 | {q['other_identities_unmapped']}种身份未映射；**活动现代任务导出0** |
| 资源 | 17组共享矿石图标、7段音频、核心物品资产、16燃料方块及2焦煤图 | 37条矿石延期记录；全包纹理/动画/着色/发光与游戏内渲染 |

精确来源：master配置、CoreMod2.9.84、GT5-Unofficial5.09.54.205、Railcraft9.17.31固定提交；2.8.4完整包仍作为独立辅助来源，未混装旧JAR。

### 验证层级

- 静态/来源：{v['decoded_png_count']}个PNG解码、{v['json_mcmeta_parsed_count']}个JSON/mcmeta解析；源纹理/动画字节、旧ID/栈上限/物理属性/配方数量核对。
- 生成代码：物品、砖模、方块、机器配方在记录型API测试桩执行；砖模302种损伤值、4项原回归与3项新增燃料错误注入都能正确判定。
- 真库执行：NBT/网络编解码、CodeChickenLib配置读写、已编译容量处理函数测试；不把它们说成运行整个游戏。
- 实际输出：{len(manifest['files'])}个运行输入哈希验证，通过实际输出目录Java/NeoForge加载器识别23个mod ID（含平台和内嵌库），随后停在EULA门槛。未创建世界。

## 还缺什么

全GTNH机器材料/核电/污染/宇宙星门/生物群系/战斗与难度、神秘时代及附属/植物魔法/血魔法/巫术/匠魂/蜜蜂作物等，均远未完成全功能适配。还有原存档ID与NBT迁移、完整任务语义、所有交叉模组配方与多人测试。普通物品/贴图注册与续作模组存在都不构成功能等价证明。

原任务3条缺失前置、3组环仍保留；不删边或自动完成来获得虚假的绿色结果。语言缺译只回退原英文并记录，不伪称全量翻译。

## 用户确认与下一步测试

`eula.txt` 当前仍为 `eula=false`。请你自行阅读 Minecraft EULA，仅在同意时改成 `eula=true`；工程不会代你同意或绕过。

同意后测试命令：

```bat
start-server-test.cmd --allow-incomplete-migration
```

只监听127.0.0.1，保留正版验证/白名单；无RCON或公网发布。默认启动防误用，运行前校验输入哈希。未修改系统Java或PowerShell策略。即使开服成功，仍须完整客户端与功能验收，不可称成品。

## 审计文件

- `docs/fuel-block-migration/fuel-blocks.json`、`independent-block-checks.json`、`long-fuel-verification.json`
- `docs/railcraft-migration/coke-bridge.json`
- `docs/enderstorage-migration/compatibility-differences.json`
- `docs/compat-mod.lock.json`、`tools/compat-mod/src/`
- `docs/verification/validation-summary.json`、`output-runtime-loader.json`
- `docs/core-items-migration/`、`docs/core-recipes-migration/`、`docs/early-game-migration/`、`docs/quest-migration/`

**仍为进行中的工程，不是已验证无缺失、完全兼容或材质无错误的成品。**
'''
(O/'STATUS.zh-CN.md').write_text(status,encoding='utf-8')
(O/'README.md').write_text('''# GTNH NeoForge 1.21.1 Migration — INCOMPLETE

Read STATUS.zh-CN.md for verified progress, provenance and all remaining fidelity requirements.

Current output: 18 downloaded modern JARs plus one locally authored version-pinned EnderStorage capacity adaptation; 558 item definitions/behaviors, 16 fuel blocks, 157 GTCEu machine recipe definitions and 3 early-game recipes. These outputs do NOT establish full GTNH feature parity.

The actual loader reaches the unaccepted Minecraft EULA gate. No full mod initialization/world startup or client rendering is verified. Generated scripts use source-backed recording-stub tests; real NBT/network codecs, config serialization and compiled adapter functions have separate bounded tests. Adapter target-class transformation and live storage behavior remain unverified.

Three resource-pack previews preserve original shared ore assets, alarm samples and Railcraft coke textures. Model/texture references and bytes are checked but no client rendering guarantee exists. All 3,814 quests/46 chapters remain lossless migration IR; active modern quests: zero.

start-server-test.cmd --allow-incomplete-migration is a guarded loopback-only TEST entry point. The user must review and accept Minecraft EULA themselves. System Java and execution policy are unchanged. Never distribute this as a finished pack.

Original licenses and pinned source references are retained. Railcraft texture attribution is inside its resource pack. EnderStorage third-party JAR is unmodified; local compatibility source and MIT license are included under tools/compat-mod and licenses/GTNH-Migration-Compat.
''',encoding='utf-8')
meta=O/'migration-metadata.json';m=json.loads(meta.read_text(encoding='utf-8'));m['validation_state'].update({'modern_artifacts':'18 downloaded JARs + 1 local version-pinned adapter; 57 dependency ranges passed','active_top_level_jars':19,'fuel_block_definitions':16,'gtceu_machine_recipe_definitions':157,'early_game_recipe_definitions':3,'creative_tabs':8,'runtime_input_files':len(manifest['files']),'quest_stack_identities_mapped':q['mapped_total_item_identities'],'capacity_adapter':'compiled and function/shape tested; runtime unverified','long_fuel_codec_tests':120,'server_world_started':False,'client_rendering_verified':False,'feature_parity':False});m['status']='in-progress-test-runtime-not-release';m['last_phase']='source-backed fuel blocks, coke progression, EnderStorage capacity/config candidate adaptation';meta.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
(D/'migration-matrix.md').write_text('''# Full-scope migration matrix — NOT COMPLETE

| Area | Concrete implementation | Not proven / remaining |
|---|---|---|
| Runtime | Java21, NeoForge21.1.262, 19 JARs, 57 dependency checks | Full initialization/world/client |
| Original core items | 558 definitions/behaviors, 31 animation assets | Nine specialized items, four hazardous item behaviors, cross-mod semantics |
| Fuel blocks | 16 actual block definitions, original traits/assets/fuel, 28 compression/hammer recipes | Two charcoal base mappings; in-game harvesting, machines, save/sync |
| Railcraft | Actual modern port; original coke identity, two core machine recipes, two craft bypasses removed | Coking balance, GTNH tracks/signals/boilers/carts and progression |
| EnderStorage | Real artifact, original supported config, compiled 256-bucket adapter | Mixin application, storage behavior, ownership/frequencies, Thaumcraft gates, visuals |
| Core recipes | 157 machine +3 early-game definitions | All remaining GTNH recipe systems and progression |
| Quests | 3814/46 lossless IR, 230 stack identities mapped | 8158 identities, task/reward logic; active exported quests 0 |
| Graphics/audio | Authored core assets, 3 preview packs | Full atlas/models/render/audio testing |
| Tests | Static source checks, real serialization codecs, script/function contracts | End-to-end game, save/load, multi-user, full feature fidelity |

No claim of full migration or feature parity follows from passing a subset of tests.
''',encoding='utf-8')
(D/'fuel-block-migration/README.md').write_text('''# Fuel block port — source-backed subset

16 CoreMod2.9.84 fuel blocks with original attributes, PNGs, labels, harvest tiers, max stack and master XML burn ticks. Explicit modern resistance matches old outward resistance, not the old internal scaled field. Quintuple coal intentionally keeps original harvest level2; charcoal/coke at that stage use3.

28 original compression/hammer recipes are generated. Four originally deferred mappings reduced to two after exact Railcraft9.17.31 cube0 evidence and modern registration established coke block identity. Charcoal base ore-unifier mapping remains unresolved rather than guessed.

Installed NeoForge already patches furnace NBT to int and chooses full-int advanced menu payload when supported. 120 real NBT/payload codec roundtrips pass; no redundant custom furnace patch was added. GUI/session/world persistence and large fuel consumption need actual game testing. See long-fuel-verification.json.

The texture preview is a flat asset preview, NOT an in-game screenshot. Cross-mod tool-tier behavior is not proven by vanilla tag tests. Full pack remains incomplete.
''',encoding='utf-8')
(D/'railcraft-migration/README.md').write_text('''# Railcraft coke bridge — incomplete subsystem

Uses Railcraft Reborn1.2.12 and exact original Railcraft9.17.31 identity evidence: cube metadata0 is coke block. The original GTNH9:1 compressor (300ticks,2EU/t) and1:9 hammer (100ticks,24EU/t) replace two verified modern free crafting recipes, without deleting coke-oven production. Original Coke PNGs appear in the optional Railcraft texture pack with attribution/license.

Coking timing/creosote amounts, replacement GTNH coke oven, other machines, rails, signaling and steam/cart systems remain unresolved. See coke-bridge.json. The modern port was loaded only as far as the EULA gate, not gameplay-tested. This bridge is not full Railcraft parity.
''',encoding='utf-8')
# Refresh auditable implementation sources and tests, never execute copied files automatically.
T=O/'tools/migration-sources';T.mkdir(exist_ok=True)
names=['port_fuel_blocks.py','verify_fuel_blocks.py','verify_long_fuels.py','LongFuelRoundTrip.java','test_fuel_blocks.cjs','add_transport_candidates.py','port_railcraft_coke.py','test_railcraft_coke.cjs','build_capacity_compat.py','verify_capacity_compat.py','CapacityPatchCheck.java','verify_ender_config.py','EnderConfigRoundTrip.java','document_ender_differences.py','verify_migration.py','rebuild_current_phase.py','stage_test_runtime.py']
for n in names:shutil.copy2(W/'migration'/n,T/n)
(T/'README.txt').write_text('Current authored-output entry point: rebuild_current_phase.py; requires original work/exact-sources, work/source, caches and outputs layout. Run it in original workspace, not in this copied source folder. It does not fetch or accept licenses or EULA. Version/source-dependent transforms are explicitly ordered; old2.8.4 generators are retired. Java codec tests use exact launcher classpath to avoid installer-only libraries. Active mod selection is pinned in docs/artifacts.lock.json, ftb-candidates.json and compat-mod.lock.json. Full E2E remains pending.\n',encoding='utf-8')
print(json.dumps({'status_updated':True,'active_jars':19,'fuel_blocks':16,'machine_recipes':157,'early_game_recipes':3,'input_files':len(manifest['files']),'mapped_identities':q['mapped_total_item_identities'],'full_goal_complete':False}))
