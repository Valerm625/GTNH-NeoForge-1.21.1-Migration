raise SystemExit('Superseded: use write_exact_phase_status.py; do not overwrite pinned-version progress with the older baseline report.')
from pathlib import Path
import json,hashlib,shutil,datetime
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
v=json.loads((O/'docs/verification/validation-summary.json').read_text(encoding='utf-8'));q=json.loads((O/'docs/quest-migration/coverage.json').read_text(encoding='utf-8'));res=json.loads((O/'docs/resource-migration/resource-mapping.json').read_text(encoding='utf-8'));cycles=json.loads((O/'docs/quest-migration/source-graph-cycles.json').read_text(encoding='utf-8'))
status=f'''# GTNH → NeoForge 1.21.1 迁移状态

**2026-10-15，上海时间。本目录是未完成的迁移工程和受限测试实例，不是成品整合包。**

目标保持不变：完整迁移原整合包功能，不以一套能启动的 GregTech 现代包替代 GTNH，不把缺失任务改成勾选完成，不把缺失物品换成空气或占位符。

## 本阶段实际完成

- 已安装 **NeoForge 21.1.262 / Minecraft 1.21.1**。独立 Java 21 已运行，随目录携带，不修改系统 Java。
- 活动目录实际包含 **{v['active_top_level_jars']} 个顶层 JAR**：GTCEu、AE2、JEI、Storage Drawers、Jade、ModernFix、FerriteCore、GuideME、Architectury、FTB Library/Teams/Quests、KubeJS、Rhino、Better Advanced Tooltips；内嵌库另计。
- 使用 GTCEu 原 JAR 内嵌的 LDLib 1.0.35.a 和 Configuration 3.1.0，移除了错误选择的 LDLib2 与无必要的外部 Configuration。
- **46 项已安装依赖的版本区间检查通过**。依据实际 NeoForge 4.0.45 兼容矩阵，Minecraft 1.21.1 接受 1.21 的约束：JEI 的原始 `[1.21,1.21.1)` 并非这里的实际加载阻碍。没有改写第三方 manifest，也没有设置依赖绕过。
- 实际加载器运行已识别 19 个 mod ID（包括游戏、加载器和内嵌库），到达 EULA 确认阶段。**未到达创建世界、`Done`、完整模组初始化阶段**。
- 依据新提供 2.8.4 原包实现，注册 **81 个真实硬币物品**，保持原 PNG、英文和中文名称，并记录旧 ID → 新合法 ID 映射。映射覆盖 master 中 2347 个任务涉及的 3366 处硬币引用。
- 从原 `AssemblerRecipes.makeCoinRecipes` 字节码逐条提取并移植 **112 条硬币兑换组装机配方 / 56 对双向兑换**。保留原输入输出数量、不可消耗电路（1/10）、100 ticks、30/120/480/1920 EU/t。未按币值猜测或新造配方。
- **17 组共享矿石图标 / 68 张 PNG** 已输出为预览资源包；原始主图字节保持一致，原版不存在的叠加层用透明图防止继承现代版多余图层。只处理经过核对的共享集合。
- **7 段 OpenSecurity 原音频** 转为合法小写资源路径，音频未重编码；验证了 Ogg 页边界和 CRC。只迁移资源和声音事件，未迁移警报方块/电脑/权限/触发逻辑。
- 3814 个任务、46 个章节已生成**保留原类型字段的无损中间数据**，逐个回读与原 JSON 对比通过。不是 FTB Quests 可玩任务书，活动任务导出数仍为 0。

## 验证范围与已知缺口

- 已验证：15 个下载文件的来源哈希、JAR 结构、依赖覆盖、46 条版本范围、149 张 PNG 可解码、86 个 JSON/mcmeta 可解析、模型贴图/语言引用、81 个注册和 112 个配方的 JavaScript 执行对照。
- 脚本执行对照使用记录型测试桩，**不是实际 Minecraft/KubeJS/GTCEu 联调成功**。需要完整启动、配方重载与游戏内测试。
- 原包和目标矿石着色逻辑不同：原版叠加层不染色，现代版会受材料色影响。dull/metallic/rough/shiny 和自定义集合等 **{len(res['deferred'])} 条记录明确延期**，未强行复制。17 组共享图标也尚未验证材料 ID、材料色和世界方块的视觉等价。
- 未启动客户端，未验证纹理图集、发光、动画、声音播放和实际渲染。预览图不是游戏截图。
- 任务引用有 **{q['unique_item_stack_identities']} 种旧物品/metadata/NBT/矿辞组合**。其中 61 种硬币身份有定义映射；其余 **{q['other_identities_unmapped']} 种仍未映射**。这不等同于丢失物品数量，但说明全任务迁移不能宣称完成。
- 原 master 本身包含 3 条缺失前置引用、{cycles['cycle_group_count']} 组前置依赖环。OR 逻辑和任务完成奖励可能参与解锁；保留原图，未擅自删边或自动完成。
- 原 GTNH 科技进度、全部机器/材料/配方、核电、污染、世界生成、宇宙维度、神秘时代及附属、植物魔法、血魔法、巫术、匠魂、蜜蜂/作物等，**远未完成功能等价迁移**。
- 2.8.4 的真实实现仅作辅助；master 为 2.9.0-RC-2 配置基线。本阶段硬币代码/配方来自 2.8.4，尚需与 2.9.0 对应 CoreMod 实现对照。

## 继续开服验证需要你操作的一步

当前 `eula.txt` 为 `eula=false`。请你自行阅读 Minecraft EULA；只有同意时再将该行改为 `eula=true`。本工程不会代你接受条款，不通过代码绕过此检查。

之后测试命令（在本目录终端执行）：

```bat
start-server-test.cmd --allow-incomplete-migration
```

- 默认不带参数时拒绝误启动，明确提示不是成品。
- 启动前校验 950 个运行输入文件 SHA-256。
- 服务端只绑定 `127.0.0.1`，开启正版验证、白名单；未开放公网、RCON 或 query。
- 不自动启用预览矿石资源包。客户端渲染须后续验证。
- Windows 系统 PowerShell 执行策略未改动；默认 CMD 启动器不依赖放宽该策略。
- 接受 EULA 并不解决剩余迁移缺口；只是允许下一阶段完整启动测试。

## 关键文件

- `docs/verification/validation-summary.json`：机器可读检查结果和未验证项。
- `docs/artifacts.lock.json`、`docs/ftb-candidates.json`：实际模组来源、版本、哈希。
- `kubejs/startup_scripts/00_gtnh_currency.js`：81 个硬币定义。
- `kubejs/server_scripts/10_gtnh_coin_exchange.js`：112 条原兑换配方。
- `docs/currency-migration/coin-mapping.json`、`coin-exchange-recipes.json`：来源映射与数值依据。
- `docs/resource-migration/resource-mapping.json`：迁移贴图/音频与延期原因。
- `docs/quest-migration/quests.lossless-ir.jsonl`：无损任务中间格式，非游戏配置。
- `docs/reference-2.8.4/README.md`：新增原包完整性和版本差异报告。

**最终验收尚未通过；不能称为完整无损、完全兼容或可玩成品。**
'''
(O/'STATUS.zh-CN.md').write_text(status,encoding='utf-8')
readme='''# GTNH NeoForge 1.21.1 Migration — INCOMPLETE

Read `STATUS.zh-CN.md` for the current verified state and remaining full-parity requirements.

This is a source-backed migration project and a guarded local test runtime, NOT a finished GT New Horizons port.

## Present

- NeoForge 21.1.262 for Minecraft 1.21.1, portable Java 21, and 15 pinned top-level mod artifacts.
- 81 source-backed GTNH coin item definitions and original textures/translations.
- 112 original 2.8.4 coin assembler exchanges translated to GTCEu/KubeJS, with bytecode provenance and contract tests.
- Preview resources: 17 shared ore icon sets (68 PNGs), 7 original alarm samples.
- Lossless intermediate representation for all 3,814 master quests and 46 chapters, not an exported playable questbook.

## Verification boundary

The actual loader reaches the Minecraft EULA gate. No complete world startup, mod initialization, client rendering, task progression or gameplay parity has been verified. EULA is false and must be accepted by the user only if they agree.

`start-server-test.cmd --allow-incomplete-migration` is a guarded loopback-only test entry point, not a final release launcher. It checks the runtime input hashes before starting. The optional PowerShell entry point may be blocked by the user's existing execution policy; the CMD entry point does not change that policy.

Original source master (README: 2.9.0-RC-2) and the auxiliary 2.8.4 release remain separate. Legacy JARs are never inserted into the NeoForge mods directory. Assets and recipes ported from 2.8.4 need reconciliation with 2.9.0 where that implementation is not supplied.

All original licenses/authorship remain with their owners. Local use of supplied assets is not a redistribution license for the whole pack. See provenance records before any public distribution.
'''
(O/'README.md').write_text(readme,encoding='utf-8')
mp=O/'migration-metadata.json';m=json.loads(mp.read_text(encoding='utf-8'));m['status']='in-progress-test-runtime-not-release';m['validation_state']={'portable_java_21':'packaged binary -version passes','neoforge_server_installation':'21.1.262 installed; actual loader stops at unaccepted EULA','modern_artifacts':'15 top-level JARs locked; embedded dependencies retained; 46 actual version ranges passed','original_release':'2.8.4 isolated and CRC-verified','currency_items':81,'currency_assembler_recipes':112,'script_contract_tests':'pass; recording stubs, not Minecraft runtime','feature_parity':False,'client_rendering_verified':False,'server_world_started':False};m['blocking_user_action']='Review Minecraft EULA and set eula=true yourself only if you agree; no consent has been given or automated.';m['notes']=[x.replace('the supplied source archive','the first supplied master source archive') for x in m['notes']];m['status_document']='STATUS.zh-CN.md';mp.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
matrix='''# Migration matrix — current evidence

| Area | Implemented | Not yet proven/completed |
|---|---|---|
| Runtime | NeoForge 21.1.262, portable Java 21; 15 JARs; pre-EULA discovery succeeds | Full initialization, world startup, client |
| GTNH currencies | 81 original plain-item definitions and original PNG/lang, 112 assembler exchange recipes | Actual registry/recipe loading; quest rewards and shops; reconciliation with 2.9.0 CoreMod |
| GregTech progression | GTCEu dependency baseline | GTNH machines, materials, recipes, tiers, pollution, nuclear and worldgen parity |
| AE2 | Genuine modern AE2 artifact and dependency | GTNH fork add-ons, custom recipes and progression |
| Quests | FTB runtime installed; all 3814 quests/46 chapters retained losslessly in migration IR | 8327 unresolved stack identities; special task/reward semantics; active quest export is zero |
| Magic / bees / crops / tools / space | Original JAR implementation and asset indices | Full functional ports, integration, licensing and runtime tests |
| Ore resources | 17 shared icon sets / 68 PNGs, static reference validation | 37 deferred mapping records; legacy untinted overlays; custom sets, material coloring and client rendering |
| Alarm resources | 7 byte-preserved Ogg samples and valid sound event paths | OpenSecurity functionality, triggers and actual audio decoding/playback |
| Validation | Hashes, dependency intervals, JS stub tests, PNG/JSON/Ogg integrity | EULA consent pending, full server/client tests, save/load, multiplayer and complete fidelity |

This matrix cannot be treated as a completed-port checklist. See STATUS.zh-CN.md and detailed verification reports.
'''
(O/'docs/migration-matrix.md').write_text(matrix,encoding='utf-8')
(D:=O/'docs/currency-migration/README.md').write_text('''# Currency implementation status

81 item definitions use the original 2.8.4 textures/translations. 112 coin-exchange assembler recipes retain all audited input/output counts, circuit settings, duration and EU/t from the original compiled makeCoinRecipes method. All 56 reciprocal pairs preserve ratios and energy/duration.

Static JS execution uses recording stubs, not the actual game. Coin rewards, choice groups, quest completion rewards, shops, machine recipes for minting blank coins, donation mechanisms and chunkloader payment behavior are not thereby implemented. No full economy or 2.9.0 parity is claimed.

See coin-mapping.json, coin-exchange-recipes.json and ../source-evidence/ for precise evidence. PNG bytes are unchanged. Original attribution remains with GTNH authors/artists; public redistribution needs a separate license review.
''',encoding='utf-8')
(O/'docs/resource-migration/README.md').write_text('''# Resource migration status

Shared ore pack: 17 exact-name source icon sets with no visible legacy overlay; base PNG bytes remain unchanged. Secondary layers are explicitly transparent where the old renderer had no overlay. No broad model override was applied, so modern-only ore sets are not forcibly recolored. 37 deferred records track legacy-only/custom sets and the old untinted-overlay mismatch.

Alarm pack: 7 original Ogg files, normalized legal resource paths, sounds.json events. Ogg page checksums and boundaries pass; this is not audio decoder or gameplay verification.

The alpha preview and coin preview are generated images, not Minecraft screenshots. Material identities, colors, emission, atlas behavior, animations and client rendering remain unverified. This is NOT an assertion of texture fidelity across the entire modpack.
''',encoding='utf-8')
# Durable copies of relevant self-authored tests/transformers, not automatically executed.
tools=O/'tools/migration-sources';tools.mkdir(exist_ok=True)
for n in ['port_resources.py','port_currency.py','port_coin_recipes.py','build_quest_ir.py','verify_migration.py','test_currency.cjs','test_coin_recipes.cjs','CheckVersionRanges.java']:
 shutil.copy2(W/'migration'/n,tools/n)
(tools/'README.txt').write_text('Auditable source copies. Python transformers depend on original work/source and work/source-release-2.8.4 layout and are not standalone installers here. Do not execute automatically. JS tests accept the output root as their first argument. The launcher hash test is ../VerifyRuntime.java.\n',encoding='utf-8')
print('STATUS_AND_METADATA_UPDATED')
assert '112' in (O/'STATUS.zh-CN.md').read_text(encoding='utf-8')
assert json.loads(mp.read_text())['validation_state']['server_world_started'] is False
