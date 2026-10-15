from pathlib import Path
import json,shutil,re
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/material-migration'
c=json.loads((D/'material-identity-map.json').read_text(encoding='utf-8'));r=json.loads((D/'material-bridge-recipes.json').read_text(encoding='utf-8'));q=json.loads((O/'docs/quest-migration/coverage.json').read_text(encoding='utf-8'));v=json.loads((O/'docs/verification/validation-summary.json').read_text(encoding='utf-8'));manifest=json.loads((O/'docs/verification/test-runtime-files.json').read_text(encoding='utf-8'));colors=json.loads((D/'known-material-color-differences.json').read_text(encoding='utf-8'))
path=O/'STATUS.zh-CN.md';old=path.read_text(encoding='utf-8');archive=O/'docs/history';archive.mkdir(exist_ok=True);history=archive/'status-before-material-mapping.zh-CN.md'
if not history.exists():history.write_text(old,encoding='utf-8')
text=f'''# GTNH → NeoForge 1.21.1 迁移状态

**2026-10-15（上海）。当前仍是未完成迁移工程与本机测试实例，不是成品整合包。**

目标没有缩水：完整保留原GTNH功能、任务进度、机器材料、配方和资源效果；不靠同名替代、删任务、空物品或放宽科技门槛冒充迁移成功。

## 本轮完成

### 原材料物品身份解析

- 从精确GT5-Unofficial5.09.54.205源码解析 **{c['legacy_numeric_materials']}个数值材料ID**、两组各32个MetaGeneratedItem前缀槽、材料构成与单位量；处理普通`0≤metadata<32000`区间，未将自定义物品、工具NBT或通配metadata错误套用该规则。
- 对照GTCEu7.0.2实际源码中的材料生成条件、标志依赖、聚合物限制、原版替代注册和材料标签。
- **{len(c['identity_verified'])}种材料身份**通过元素或递归成分比例静态核对，形成 **{len(c['pairs'])}个前缀/材料候选对**。这不是已运行注册表或全部材料性质的等价证明。
- 为任务物品增加 **{c['newly_mapped_quest_identities']}种源码级身份映射**。累计 **{q['mapped_total_item_identities']} / 8388** 种旧物品/metadata/NBT/矿辞身份有定义映射；仍有 **{q['other_identities_unmapped']}** 种未映射。
- 正确识别 Chrome→Chromium（同质子/中子数），Uranium→Uranium238（源码明确92质子146中子），不是按名字模糊匹配。
- 明确阻止：原/现代中子素核属性不一致、钚同位素不一致、钢成分不一致、螺丝材料单位1/8→1/9，以及聚合物禁止锻打的双层板生成。它们仍是待移植缺口。
- 独立解析发现初版材料ID正则遗漏11条无空格格式，已修复并重新验证全部ID。映射器可以检测自身解析遗漏，而不只验证自己生成的数字。

### 8条材料加工连接配方

已写入原CoreMod2.9.84中可完整证明的 **{r['implemented_recipes']}条**加工配方：云母绝缘板、硅酸铝棉、冷却部件半成品、钨丝等。保持原数量、300/400/1200等tick时长、电路与EU/t，不用现代默认代替。

材料输入采用GTCEu自身的`c:`标签匹配，以保留统一材料的替代接受能力；不会将原矿辞匹配缩成单一物品ID。共审查364条相关候选链，其中356条因未知物品、成分差异、条件分支、流体/概率/热量等未适配而拒绝生成，理由保留。

### 焊锡合金差异修正

- 原GTNH是 **9锡+1锑**；现代默认是 **6锡+3铅+1锑**。
- 已实现受当前定义签名保护的材料事件，恢复原组成，并只禁用该材料的现代自动分解，转用原分离配方。
- 原混合：9锡粉+1锑粉→10合金粉，电路1、100 ticks、7 EU/t。
- 原离心：10合金粉→9锡粉+1锑粉，**4720 ticks、10 EU/t**。数值根据原元素质量和ProcessingDust公式推导、独立核对，不是现代默认1770ticks/30EU/t。
- 移除已定位的现代默认混合/分解ID；若材料启动适配没有执行，配方脚本会在修改前报错，不静默生成不一致的经济循环。
- **未声称焊锡合金全功能等价**：熔化温度、材料颜色、危害、其他铸造/回收链和实际游戏事件仍待验证。保守身份映射仍把未适配的默认成分视为不等价。

### 下一阶段真实注册表检查

已生成`98_gtnh_material_registry_audit.js`，仅在真实服务器加载完成后检查 **253个目标物品及其标签成员关系**，写入`kubejs/export/gtnh-material-registry-check.json`。该报告目前不存在，**没有伪造游戏中已运行的结果**。记录型API测试覆盖正常、物品缺失、标签缺失和错误成员4种情况。

## 当前累计输出

| 区域 | 已实现/落盘 | 未证明或未完成 |
|---|---|---|
| 运行环境 | NeoForge21.1.262、独立Java21、19个顶层JAR（18下载+1本地适配） | 完整初始化、世界启动、客户端 |
| 原物品/方块 | 558个物品定义/行为、16燃料方块及BlockItem、31项动画 | 原全部特殊类、危险效果及跨模组行为 |
| 配方 | **{v['gtceu_machine_recipe_definitions']}条机器配方+3条制砖配方** | 原包全部配方、进度门槛、可达性、冲突与运行验证 |
| Railcraft | 焦煤身份/配方桥接、删除2条免费拆装配方 | 焦炉时间与油产量、锅炉/轨道/车厢/信号等全量适配 |
| EnderStorage | 原支持的配置、独立版本锁定256桶容量模块 | 实际Mixin应用/注排液/重载/联网、神秘研究门槛 |
| 任务 | 3814任务/46章节无损中间数据、{q['mapped_total_item_identities']}种身份映射 | **活动现代任务导出0**；{q['other_identities_unmapped']}种身份、任务与奖励语义 |
| 资源 | 原核心资产及3个预览资源包、7段音频 | 全包贴图图集/着色/动画/发光/声音验收；本轮记录{len(colors)}处已匹配材料的RGB差异但未盲改 |

精确来源：master配置、CoreMod2.9.84、GT5-Unofficial5.09.54.205、Railcraft9.17.31固定提交；2.8.4辅助实例仍独立保留。对应源码、作者许可证和来源哈希均留存。

## 测试证据的边界

- 静态/源码：778材料ID、276递归材料身份、1403候选对、249任务栈映射以及4组独立metadata锚点；原输入/输出来源行与数量/单位检查。
- 脚本：当前16个JS语法通过；物品、方块、机器配方、材料诊断及焊锡修正均通过记录型API执行检查，**不等同于Minecraft运行**。
- 错误注入：既有7项，加上本轮数量/标签缩窄/耗电、锡比例/分离耗时/遗漏默认配方移除6项均能检测。原砖模302种损伤状态继续通过。
- 真库：既有120组NBT/NeoForge网络编解码、真实CodeChicken配置读写、已编译容量函数/目标字节码测试通过。容量Mixin实际变换和联网储存仍未验证。
- 输出目录：{len(manifest['files'])}个运行输入文件哈希、57条依赖区间通过；实际加载器识别23个modID后停在EULA。**没有世界、没有材料事件执行、没有客户端渲染结果**。

## 仍有大量未完成系统

整套GTNH科技等级/原机器/材料性质/污染/核电/宇宙星门/世界生成/怪物与难度，以及神秘时代和附属、植物魔法、血魔法、巫术、匠魂、蜜蜂/作物等仍远未达到完整功能等价。原存档ID/NBT转换、全部任务语义、奖励、多人同步也未通过。普通图标存在或材料化学身份匹配都不证明这些系统已迁移。

## 用户确认与实际测试

`eula.txt`仍为`eula=false`。请自行阅读Minecraft EULA，仅在同意时修改；工程没有代同意或绕过。尚有大量无需开服的工作，不把这个单项门槛当作全部任务无法推进。

确认后，本目录测试入口：

```bat
start-server-test.cmd --allow-incomplete-migration
```

只绑定127.0.0.1、保留正版验证和白名单，默认防误启动，校验输入哈希。不更改系统Java或PowerShell策略。即使后来开服成功，也不能替代全功能与客户端验收。

## 审计入口

- `docs/material-migration/material-identity-map.json`、`material-definitions.json`
- `docs/material-migration/material-bridge-recipes.json`、`independent-material-verification.json`
- `docs/material-migration/soldering-alloy-adaptation.json`、`soldering-independent-verification.json`
- `docs/material-migration/runtime-registry-check-spec.json`、`known-material-color-differences.json`
- `docs/verification/validation-summary.json`、`output-runtime-loader.json`
- 旧阶段详细状态：`docs/history/`；来源/许可证：`licenses/`；重建脚本：`tools/migration-sources/`

**目标仍未完成。本目录不能宣称为无功能缺失、完全兼容或所有材质正确的成品。**
'''
path.write_text(text,encoding='utf-8')
(O/'README.md').write_text('''# GTNH NeoForge 1.21.1 Migration — INCOMPLETE TEST PROJECT

Read STATUS.zh-CN.md for complete progress and fidelity gaps.

19 top-level JARs; 558 authored item definitions/behaviors, 16 fuel blocks, 167 machine-recipe definitions and 3 brick recipes. Original material metadata now has a source-checked catalog and 249 additional quest-stack identity mappings (479 total). Identity mappings are not a save converter or proof of full material properties.

The default soldering alloy composition differs from GTNH; an explicit guarded adapter and source-matched mixing/centrifuging recipes restore the original tin:antimony ratio. Other alloys/nuclear identities/material-unit mismatches are withheld, not guessed. 8 new processing bridges use native material tags.

All 3,814 quests/46 chapters remain lossless migration IR. Active modern quest export is zero. World startup, actual material events, full task/recipe gameplay and client rendering remain unverified. The actual loader still stops at unaccepted EULA. A generated post-load registry audit will produce real evidence only after future server startup.

The guarded start-server-test.cmd --allow-incomplete-migration is a loopback-only test entry point. User EULA agreement is not automated. Tests distinguish source checks, recording stubs, real codec tests and actual loader discovery. None establishes complete GTNH parity.
''',encoding='utf-8')
p=O/'migration-metadata.json';m=json.loads(p.read_text(encoding='utf-8'));m['validation_state'].update({'gtceu_machine_recipe_definitions':v['gtceu_machine_recipe_definitions'],'runtime_input_files':len(manifest['files']),'quest_stack_identities_mapped':q['mapped_total_item_identities'],'material_stack_mappings':c['newly_mapped_quest_identities'],'soldering_component_adapter':'implemented and source/stub tested; runtime not executed','runtime_material_registry_audit':'253 generated checks, NOT yet executed','feature_parity':False,'server_world_started':False,'client_rendering_verified':False});m['last_phase']='conservative exact material identity mapping, processing bridges, guarded soldering alloy parity adaptation';p.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
(D/'README.md').write_text('''# Material mapping and adaptation — limited evidence

The map decodes old GT meta-item prefix slots plus numeric material IDs from exact GT5U source. Matches require explicit nuclear counts or recursively equivalent component ratios and equal item material amount. Modern generation predicates, required flags, polymer restrictions and vanilla overrides are checked. Chrome→Chromium and Uranium→Uranium238 are evidence-backed, not fuzzy-name guesses.

249 task identities map statically; no world save converter or actual target registry check is claimed. Steel/solder defaults, Neutronium/Plutonium identities, screw amount mismatch, unknown metadata and NBT-specific forms are not silently mapped. Solder has a separately guarded composition/recipe adapter; this does not yet make all properties equivalent. 204 RGB differences remain visible in a dedicated report.

8 source recipe bridges use native material tags rather than narrowing unification. Unsupported recipe clauses and material conditions remain rejected with source snippets. Actual registry and tag resolution is deferred to the generated ServerEvents.loaded audit; no live result file exists yet.

Source identity checks, independent anchors, JS API fixtures and intentional-error tests pass. Actual game initialization, recipes, metallurgy, resource rendering and task behavior remain unverified.
''',encoding='utf-8')
# Update the compact full-scope matrix without hiding previous high-priority gaps.
p=O/'docs/migration-matrix.md';s=p.read_text(encoding='utf-8');s=s.replace('157','167').replace('230 stack identities','479 stack identities').replace('8158 identities','7909 identities');s+='\nMaterial phase: 249 additional static stack identities; 8 tag-preserving source bridges; explicit 9:1 tin/antimony adapter with 2 recipes. Registry/runtime/material properties and 204 RGB differences remain unverified. No full feature parity claimed.\n';p.write_text(s,encoding='utf-8')
tools=O/'tools/migration-sources'
for name in ['material_catalog.py','port_material_bridges.py','port_soldering_alloy.py','generate_material_runtime_audit.py','verify_material_mapping.py','verify_soldering_alloy.py','test_material_bridges.cjs','test_material_runtime_audit.cjs','test_soldering_alloy.cjs','verify_migration.py','rebuild_current_phase.py']:
 shutil.copy2(W/'migration'/name,tools/name)
print(json.dumps({'status_updated':True,'material_mappings':c['newly_mapped_quest_identities'],'total_mapped_identities':q['mapped_total_item_identities'],'machine_recipes':v['gtceu_machine_recipe_definitions'],'runtime_inputs':len(manifest['files']),'goal_complete':False}))
