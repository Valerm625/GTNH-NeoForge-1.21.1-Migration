raise SystemExit('Superseded by write_fuel_phase_status.py; avoid replacing current transport/fuel coverage with the older phase.')
from pathlib import Path
import json,shutil,hashlib
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs'
val=json.loads((D/'verification/validation-summary.json').read_text(encoding='utf-8'));coverage=json.loads((D/'quest-migration/coverage.json').read_text(encoding='utf-8'));core=json.loads((D/'core-items-migration/core-item-mapping.json').read_text(encoding='utf-8'));inputs=json.loads((D/'verification/test-runtime-files.json').read_text(encoding='utf-8'))
locks=[json.loads((W/f'cache/exact-sources/{repo}-{ver}-lock.json').read_text(encoding='utf-8')) for repo,ver in [('NewHorizonsCoreMod','2.9.84'),('GT5-Unofficial','5.09.54.205')]]
status=f'''# GTNH → NeoForge 1.21.1 迁移状态

**更新日期：2026-10-15（上海）。本目录是未完成迁移工程和受限测试实例，不是成品。**

目标未改变：保留原 GTNH 全部功能、任务进度、机器材料、配方与材质；不以另一套现代 GregTech 包替代，不把未实现机制改为占位物品或可勾选任务。

## 最新实质进展

### 精确版本来源已补齐

- 主来源仍为用户给出的 master 配置，README 标注 2.9.0-RC-2。
- 已按该 README 的模组版本取得 **NewHorizonsCoreMod 2.9.84**（提交 `{locks[0]['commit']}`）和 **GT5-Unofficial 5.09.54.205**（提交 `{locks[1]['commit']}`）源码。
- 来源标签 API、固定提交、ZIP SHA-256、许可证与对应源码 ZIP 均已保存。没有执行旧源码的 Gradle 脚本或旧版模组代码。
- 辅助 2.8.4 包仍独立保存。新实现有精确源码依据后，不再依赖较旧版本的推测。

### 物品、贴图、语言

- 已生成 **558 个物品定义**：477 个原生普通 Item、80 个 CoinItem 双层渲染物品、1 个带耐久/合成剩余行为的 WoodenBrickForm。477 个普通物品中含 Donation Coin，因此硬币总数仍为 81。
- 保留 **31 项动画贴图及 mcmeta**，验证图片尺寸、帧数、索引和时间合法，源 PNG 与动画元数据逐字节一致。
- 修正了先前参考 2.8.4 的不足：80 种硬币在 2.9.84 是底图+图标双层渲染，不是旧平面图；区块加载硬币最大堆叠为 **4**，5 枚勋章最大堆叠为 **1**。
- 原 7 个物品创意分类、558 项成员、24 条配置提示文字及 2 条原版燃料时长覆盖已生成。
- 保留精确版本英文/中文/俄文原译。原源码缺少的 {len(core['zh_cn_fallbacks'])} 个中文物品名、51 个俄文物品名回退到源码英文，不伪称完成翻译；提示文字主来源只有英文，保留原文。
- **物品定义不等于功能实现**：手套、凋灵防护戒指、量子面包、特殊铸模、唱片等 9 项特定类尚未实现；法杖部件、星门部件、电路等依赖的魔法/机器/世界系统也未随普通物品注册自动实现。

### 配方与早期玩法

- 已按精确源码实现 **127 条 GTCEu 机器配方**：112 条硬币兑换+15 条可完整解析的 CoreMod 配方，涵盖组装机、卷板、压缩、锻造锤、成型压机、打粉和真空冷冻。
- 112 条硬币兑换与 2.8.4 的输入输出数量、电路号、100 ticks 和 30/120/480/1920 EU/t 一一核对，数值没有差异。其他配方均记录源码文件、行号和完整原链。
- 守住配方条件：带外部模组门槛、循环、概率输出、流体/热量元数据等未完整适配的条目被排除并记录，不能为了增加数量抹掉条件。
- WoodenBrickForm 实现 300 最大耐久、不可修复、合成后留在格内的剩余物品逻辑；保留原边界 **299→300、300→消耗**。测试覆盖 302 个损伤状态且不修改输入栈。
- 增加原 8 砖有序合成、1 砖无序合成、生砖烧成砖 **3 条配方**。砖模获取依赖的匠魂 blankPattern 和刀具链尚未迁移，没有编造便宜替代获取方法。
- 上述 127+3 条是已实现定义，**未宣称全部 GTNH 配方完成**。精确核心源码扫描到 2694 条机器 builder 链，且这不包括整个 GT5U、各附属和其他脚本的全部配方。

### 保留的资源/任务成果

- 17 组原 2.8.4 共享矿石图标（68 PNG）、7 段原警报音频已转换为预览资源包。原版不染色叠加层与现代染色层的差异仍有37条延期记录；没有强改现代全局渲染模型。
- 3814 个任务/46 个章节无损中间数据仍完整，未变成可玩的 FTB Quests 任务书；活动任务导出数 **0**。
- 原任务 8388 种物品/metadata/NBT/矿辞身份中，已有 **{coverage['mapped_total_item_identities']}** 种具备实现定义映射；仍有 **{coverage['other_identities_unmapped']}** 种待映射。定义映射不等于任务语义和奖励已可运行。
- 原图有3条缺失前置引用、3组环，保留并记录；OR关系和完成奖励可能参与解锁，未擅自删边或强制完成。

## 本次验证证据

- 15个顶层模组 JAR及其内嵌依赖保留哈希来源。46条依赖版本检查通过，未改写第三方manifest或添加依赖绕过。
- 实际 NeoForge 4.0.45兼容矩阵解释 JEI 的版本区间：1.21.1可接受1.21约束，这不是当前阻碍。
- {val['decoded_png_count']}个PNG解码、{val['json_mcmeta_parsed_count']}个JSON/mcmeta解析通过，模型纹理和语言引用已核对。
- 独立源码核对557个普通/双层物品名与堆叠限制、112条完整硬币链；生成器报告不是唯一依据。
- 实际JS在记录型测试桩中执行，核对558项定义/行为、127条机器配方、3条砖配方及分类/提示/燃料。**这不是Minecraft运行测试。**
- 故意引入错误堆叠、缺失图层、错误电压及耐久差一后，4项回归测试均能报错。损坏测试在独立fixture目录执行，未改生产文件。
- 已从**实际输出文件夹**运行Java和NeoForge加载器，发现19个mod ID，停在EULA门槛；未创建世界、未完整初始化模组或执行KubeJS启动事件，未运行客户端。
- 输出运行输入共{len(inputs['files'])}个文件SHA-256验证通过。所有新实现都受版本锁定与再生成顺序控制。

## 仍不能宣布完成的关键项目

1. 尚缺GTNH整套机器材料、宇宙/星门、科技等级、世界生成、污染、核电、全部配方和难度门槛。
2. 神秘时代及附属、植物魔法、血魔法、巫术、匠魂、蜜蜂/作物等未达到完整功能等价。
3. 4个已注册普通物品另有原配置伤害/药水行为未迁移；报告单列，不能把物品可显示当成功能齐全。
4. 20条其他燃料覆盖需原方块/模组或metadata映射，未用错误现代物品替代。
5. 未完成原存档ID/metadata/NBT转换、多人同步、存读档与任务进度回归。
6. 未完成客户端纹理图集、动画、发光、颜色、声音与模型实际验收；预览图不代表游戏截图。

## 下一步运行验证需要用户确认

`eula.txt` 仍为 `eula=false`。请你自行阅读 Minecraft EULA，只有同意时再改为 `eula=true`。没有代你同意或通过代码绕过。

同意后可在本目录运行：

```bat
start-server-test.cmd --allow-incomplete-migration
```

测试启动器默认防误启动、先校验输入哈希，只监听127.0.0.1，保留正版验证/白名单；不会修改系统Java或PowerShell执行策略。即使开服成功，也仍需完整功能/客户端验收，不能据此称为成品。

## 关键记录

- `docs/core-items-migration/core-item-mapping.json`：精确物品/图层/动画/堆叠来源。
- `docs/core-recipes-migration/exact-core-recipes.json`：127条已迁移链与拒绝候选。
- `docs/early-game-migration/wooden-brick-form.json`：砖模与3条配方依据及缺口。
- `docs/verification/validation-summary.json`、`independent-source-check.json`、`mutation-tests.json`：分层测试边界。
- `licenses/`：原作者许可证、固定来源与对应源码。
- `tools/migration-sources/`：转换器/测试脚本副本；需原工作区目录，不是独立游戏安装器。

**最终目标保持激活；完整无损迁移未完成，不对全部兼容性或贴图正确性作保证。**
'''
(O/'STATUS.zh-CN.md').write_text(status,encoding='utf-8')
(O/'README.md').write_text('''# GTNH NeoForge 1.21.1 Migration — NOT A RELEASE

Read `STATUS.zh-CN.md` for current evidence and full-parity gaps.

The output contains a guarded local test runtime, 15 pinned modern JARs, 558 authored item definitions (including source-accurate layered coins and a behavioral wooden brick mold), 127 GTCEu recipe definitions and 3 early-game recipes. These definitions are not equivalent to porting all GTNH functions.

Exact source tags now match the supplied master manifest: NewHorizonsCoreMod 2.9.84 and GT5-Unofficial 5.09.54.205. Corresponding source archives, commit/hash locks and licenses are retained in `licenses/`. Core item textures and animations use those sources, not guessed modern replacements.

Static validation, independent source checks, generated JS recording-stub tests and four mutation tests pass. Actual loader discovery reaches the unaccepted Minecraft EULA gate. No full mod initialization, world startup, client rendering or complete quest/gameplay parity is verified. 3,814 quests remain in lossless migration IR, not an active modern questbook.

`start-server-test.cmd --allow-incomplete-migration` is a test entry point, not a production launcher. The user must read and accept EULA themselves before running. Loopback binding, online authentication and whitelist remain enabled. System Java and execution policy are unchanged.

See `docs/core-items-migration`, `docs/core-recipes-migration`, `docs/early-game-migration`, `docs/quest-migration` and `docs/verification`.
''',encoding='utf-8')
p=O/'migration-metadata.json';m=json.loads(p.read_text(encoding='utf-8'));m['pinned_implementation_sources']=locks;m['validation_state'].update({'implemented_item_definitions':558,'exact_core_plain_and_coin_items':557,'specialized_item_behaviors':1,'currency_items':81,'gtceu_machine_recipe_definitions':127,'early_game_recipe_definitions':3,'animated_texture_files':31,'creative_tabs':7,'runtime_input_files':len(inputs['files']),'quest_stack_identities_mapped':coverage['mapped_total_item_identities'],'active_modern_quests':0,'server_world_started':False,'client_rendering_verified':False,'feature_parity':False});m['notes']+=[x for x in ['Core items/recipes reconciled to pinned 2.9.84 and GT5U 5.09.54.205 source; 2.8.4 reference is no longer the implementation baseline for those blocks.','Definitions, static tests and source mapping do not prove full cross-mod behavior or runtime correctness.'] if x not in m['notes']];p.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
(D/'core-items-migration/README.md').write_text('''# Exact source-backed items

557 plain/two-pass item registrations plus one separately implemented wooden brick form. Ordinary item class constructor behavior is copied; 80 CoinItem renderers use original two-pass textures through generated layer0/layer1 models. Five chunkloader coin limits are 4; five medal limits are 1. Animation metadata is preserved and verified.

Source: CoreMod 2.9.84. External integrations, full recipes and nine specialized items remain pending. The four hazardous ordinary items in pending-item-hazards.json still need original protection/timing/potion behavior. A named material, circuit or wand part being registered does not make its parent technology or magic system functional.

Chinese and Russian translation gaps use original English and are reported, not claimed as translated. Original 7 tab classifications and 24 tooltip entries are provided; the tooltip source is English-only.

Tests are recording stubs/static checks. Client rendering, KubeJS registration and gameplay behavior await full game validation.
''',encoding='utf-8')
(D/'core-recipes-migration/README.md').write_text('''# Exact source-backed machine recipes

127 recipes from pinned CoreMod 2.9.84 plus voltage/time definitions from GT5U 5.09.54.205. Includes all 112 coin exchanges and 15 fully parsed unconditional core-item recipe chains. Machine types, counts, timing, EU/t, circuit metadata and zero-count nonconsumable tools are preserved.

Unsupported inputs/outputs, mod conditions, loops, chance/heat/fluid semantics are rejected with reasons rather than omitted from the translated chain. The 112 coin exchanges independently match both exact source and older reference numerical behavior. Other recipes and all integration effects are not declared complete.

Source license and corresponding source archives are under licenses/. Runtime recipe loading is unverified. This is neither all CoreMod recipes nor all GTNH recipes.
''',encoding='utf-8')
(D/'currency-migration/README.md').write_text('''# Currency — reconciled to master version

81 original currencies: 80 CoinItem two-layer assets plus one ordinary Donation Coin. Exact CoreMod 2.9.84 registration/render semantics and original stack limits are implemented. The prior 2.8.4 flat textures are quarantined outside active resources.

112 assembler exchanges match the 2.9.84 source and GT5U 5.09.54.205 timing/energy constants; original counts, circuit selectors and 56 reciprocal pairs are retained. Rewards, shops, donation mechanics and chunk-loading functions are not implemented merely by registering these items/recipes. The 2.8.4 auxiliary archive is still preserved for comparison.

Static and JS recording-stub tests passed. Full economy, client graphics and server behavior remain unverified.
''',encoding='utf-8')
(D/'migration-matrix.md').write_text('''# Full migration matrix — NOT COMPLETE

| Area | Concrete output | Remaining |
|---|---|---|
| Platform | NeoForge 21.1.262, portable Java 21, 15 locked JARs | EULA user consent; full initialization/world/client |
| Exact original implementation | CoreMod 2.9.84 and GT5U 5.09.54.205 pinned commits and source archives | Functional ports beyond current subset |
| Core items | 558 definitions/behaviors, layered coins, 31 animations, original stack limits | Specialized items, hazards, magic/tech/dimension integrations |
| Recipes | 127 machine + 3 brick recipes, source-audited | Entire GTNH progression and remaining recipe systems |
| Quests | All 3814 quests/46 chapters lossless IR; 224 stack identities mapped | 8164 remaining identities; task/reward semantics; active exports 0 |
| Resources | 17 shared ore sets/68 PNGs, 7 original audio samples, exact core item assets | Deferred ore overlays/custom material sets, all-mod textures, in-game rendering/audio |
| Tests | Source checks, 46 version constraints, generated script contract/mutation tests, pre-EULA discovery | Full server/client, save/load, progression/multiplayer and fidelity acceptance |

No unsupported tasks are replaced by checkmarks. No missing behavior is claimed complete because its item icon exists.
''',encoding='utf-8')
# Copy current reproducible transforms/tests and retire superseded local generators in delivered source bundle.
tools=O/'tools/migration-sources';tools.mkdir(exist_ok=True)
for name in ['port_exact_core_items.py','port_exact_core_recipes.py','port_core_integrations.py','port_wooden_form.py','port_extra_localization.py','verify_exact_source.py','verify_migration.py','test_generated_regressions.py','rebuild_current_phase.py','test_exact_core_items.cjs','test_exact_core_recipes.cjs','test_wooden_form.cjs','stage_test_runtime.py','port_currency.py','port_coin_recipes.py','CheckVersionRanges.java']:
 shutil.copy2(W/'migration'/name,tools/name)
(tools/'README.txt').write_text('Current build entry: rebuild_current_phase.py (requires original workspace work/exact-sources, work/source and runtime layout; not directly runnable from this copied source folder). Order is essential. Legacy 2.8.4 item/recipe generators are explicitly retired and refuse to run. JS tests accept output root as an argument. No script accepts EULA.\n',encoding='utf-8')
print(json.dumps({'status_updated':True,'items':558,'recipes':130,'mapped_identities':coverage['mapped_total_item_identities'],'remaining_identities':coverage['other_identities_unmapped'],'manifest_files':len(inputs['files']),'goal_complete':False}))
