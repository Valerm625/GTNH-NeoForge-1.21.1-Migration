"""Update status from measured quest export and explicit vanilla identity evidence."""
from pathlib import Path
import json,re,shutil,sys
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
D=O/'docs/quest-migration/ftb-export'
sys.path.insert(0,str(Path(__file__).parent))
from legacy_vanilla_items import resolve_legacy_vanilla
q=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'))
v=json.loads((O/'docs/verification/validation-summary.json').read_text(encoding='utf-8'))
r=json.loads((O/'docs/verification/test-runtime-files.json').read_text(encoding='utf-8'))
vm=json.loads((O/'docs/quest-migration/vanilla-identity-mapping.json').read_text(encoding='utf-8'))
for name in ['QuestAdapterShapeCheck','LegacyRetrievalCounterCheck','QuestSnbtCheck','checkpoint-verification','crafting-task-adapter','choice-reward-adapter']:
    assert json.loads((D/(name+'.json')).read_text(encoding='utf-8'))['passed']
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'))
mapped=[x for x in refs if x.get('target_id') or resolve_legacy_vanilla(x['identity']['id'],x['identity']['damage'],x['identity']['ore_dict'],x['identity']['tag'])]
mapped_total=len(mapped)
unmapped=len(refs)-mapped_total
n=q['dependency_closed_active_quests']
remaining=q['source_quests']-n
crafting=q.get('crafting_task_candidates',0)
choice=q.get('choice_reward_candidates',0)
p=O/'migration-metadata.json'
m=json.loads(p.read_text(encoding='utf-8'))
m['last_phase']='explicit vanilla identity translation, conservative crafting-task subset, bounded quest export and independent verification'
m['validation_state'].update({'active_modern_quests':n,'active_modern_quests_runtime_verified':False,'crafting_task_candidates':crafting,'crafting_task_runtime_verified':False,'choice_reward_candidates':q.get('choice_reward_candidates',0),'choice_reward_runtime_verified':False,'remaining_quests_not_exported':remaining,'quest_stack_identities_mapped':mapped_total,'unmapped_quest_stack_identities':unmapped,'vanilla_identity_mappings_added':vm['newly_mapped_source_identity_count'],'vanilla_identity_occurrences_covered':vm['newly_mapped_source_occurrences'],'quest_adapter':'compiled; pure reducer and target-shape tests pass; actual Mixin/game unverified','runtime_input_files':len(r['files']),'modern_artifacts':f"18 downloaded JARs + 1 local version-pinned adapter; {v['version_ranges_checked']} dependency ranges passed",'feature_parity':False,'server_world_started':False,'client_rendering_verified':False})
p.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
p=O/'docs/quest-migration/coverage.json'
d=json.loads(p.read_text(encoding='utf-8'))
d.update({'mapped_total_item_identities':mapped_total,'other_identities_unmapped':unmapped,'mapped_vanilla_identity_identities':vm['newly_mapped_source_identity_count'],'mapped_vanilla_identity_occurrences':vm['newly_mapped_source_occurrences'],'active_modern_quests':n,'remaining_quests_not_exported':remaining,'active_export_is_trial':True,'game_runtime_verified':False,'full_quest_parity':False})
p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
p=O/'STATUS.zh-CN.md'
s=p.read_text(encoding='utf-8')
s=s.replace('**479 / 8388**',f'**{mapped_total} / 8388**').replace('仍有 **7909** 种未映射',f'仍有 **{unmapped}** 种未映射')
s=s.replace('**活动现代任务导出0**',f'**活动现代任务试导出{n}（未实机验证）**')
s=re.sub(r'输出目录：\d+个运行输入文件哈希、\d+条依赖区间通过',f"输出目录：{len(r['files'])}个运行输入文件哈希、{v['version_ranges_checked']}条依赖区间通过",s)
s=re.sub(r'- 为任务物品增加 \*\*\d+种源码级身份映射\*\*。累计 \*\*\d+ / 8388\*\* 种旧物品/metadata/NBT/矿辞身份有定义映射；仍有 \*\*\d+\*\* 种未映射。',f"- 为任务物品增加 **{d.get('mapped_material_item_identities',249)}种材料源码级身份映射** 和 **{vm['newly_mapped_source_identity_count']}种显式原版身份映射**。累计 **{mapped_total} / 8388** 种旧物品/metadata/NBT/矿辞身份有定义映射；仍有 **{unmapped}** 种未映射。",s)
for heading in ['## 原版物品身份映射检查点','## 任务试导出检查点']:
    if heading in s:
        s=s[:s.index(heading)].rstrip()+'\n'
s += f'''
## 原版物品身份映射检查点

- 对照实际 Minecraft 1.21.1 `Items` 类字段，新增显式旧版原版物品映射：{vm['newly_mapped_source_identity_count']} 个任务物品身份、{vm['newly_mapped_source_occurrences']} 次来源出现；累计 {mapped_total} / 8388 个身份有定义映射，仍有 {unmapped} 个未映射。
- 覆盖同名物品、明确旧版重命名和有限 metadata 变体（颜色、木材、鱼、矿物块、头颅等）；不对 NBT、矿辞、药水、刷怪蛋或未知模组物品猜测。
- 独立验证器确认 {vm['modern_items_field_count']} 个现代注册表字段，拒绝带 NBT/矿辞/药水/刷怪蛋的未适配身份；没有使用模糊名称匹配。

## 任务试导出检查点

- 结构上可转换候选从上一检查点的24个增加到本阶段的{q['structurally_supported_candidates']}个，其中 {crafting} 个候选包含受限 crafting 任务、{choice} 个候选包含受限 choice 奖励；依赖闭合并实际导出的任务仍为{n}个，1章、4个真实FTB SNBT配置文件。原3814个任务中仍有{remaining}个未导出，不能称为完整任务迁移。
- 非消耗、单种物品检索使用明确标签启用的适配器：汇总在线成员主背包和盔甲栏；部分进度可下降，完成后锁定，不因反复扫描累加。原1.7.10没有副手，因此不统计副手。完整队伍成员变动/历史行为尚未等价。
- 真实FTB SNBT解析器往返4份配置通过；独立源码对照验证2个任务、数量、前置与奖励，4种故意损坏均被拒绝；已编译计数器10000次随机观察与目标类5个shadow方法/注入形状检查通过。
- 上述检查不等于游戏实际Mixin应用、任务初始化、世界开服或客户端渲染通过。仍保持eula=false；不代用户同意。
- 任务配置已加入启动前SHA-256完整性检查；不支持任务保留明确阻塞原因，未替换为空任务或免费奖励。
'''
p.write_text(s,encoding='utf-8')
p=O/'README.md'
p.write_text(f'''# GTNH NeoForge 1.21.1 Migration — INCOMPLETE TEST PROJECT

Read `STATUS.zh-CN.md` for complete progress and fidelity gaps.

This is an incomplete, non-release migration test project. It does not claim full GTNH parity, a playable finished pack, or official support from GTNH, GregTech CEu, NeoForge, or FTB.

## Current measured checkpoint

- Minecraft 1.21.1 / NeoForge 21.1.262; 19 top-level JARs; 558 authored item definitions/behaviors; 16 fuel blocks; 167 machine recipe definitions and 3 early-game brick recipes.
- Explicit source identity coverage: {mapped_total} / 8388 total item identities mapped; {vm['newly_mapped_source_identity_count']} additional vanilla identities ({vm['newly_mapped_source_occurrences']} source occurrences) now have conservative 1.21.1 translations. {unmapped} identities remain unresolved; this is not a save converter.
- Quest conversion: {q['source_quests']} quests / 46 chapters retained as lossless intermediate data; {q['structurally_supported_candidates']} structural candidates ({crafting} crafting-task candidates); {n} dependency-closed trial quests exported; {remaining} remain unexported.
- Verification: actual FTB SNBT parser roundtrip, compiled retrieval adapter shape, reducer observations, source checks and runtime-input hashes pass. The loader still stops at the unaccepted EULA; world startup, actual Mixin application, complete quest initialization and client rendering are not verified.

## Boundaries

Unsupported tasks and identities remain explicitly blocked. No placeholder quests, empty rewards, guessed NBT, fuzzy item-name matching, or automatic EULA acceptance is used. The output runtime is loopback-only and marked incomplete.

See `STATUS.zh-CN.md`, `docs/quest-migration/coverage.json`, `docs/quest-migration/vanilla-identity-mapping.json`, and `docs/verification/validation-summary.json`.
''',encoding='utf-8')
p=O/'docs/migration-matrix.md'
p.write_text(f'''# Full-scope migration matrix — NOT COMPLETE

| Area | Concrete implementation | Not proven / remaining |
| --- | --- | --- |
| Runtime | Java21, NeoForge21.1.262, 19 JARs, {v['version_ranges_checked']} dependency checks | Full initialization/world/client |
| Original core items | 558 definitions/behaviors, 31 animation assets | Nine specialized items, four hazardous item behaviors, cross-mod semantics |
| Fuel blocks | 16 actual block definitions, original traits/assets/fuel, 28 compression/hammer recipes | In-game harvesting, machines, save/sync |
| Railcraft | Modern artifact, original coke identity, two core machine recipes, two craft bypasses removed | Coking balance, GTNH tracks/signals/boilers/carts and progression |
| EnderStorage | Real artifact, original supported config, compiled 256-bucket adapter | Mixin application, storage behavior, ownership/frequencies, Thaumcraft gates, visuals |
| Core recipes | 167 machine +3 early-game definitions | All remaining GTNH recipe systems and progression |
| Item identities | {mapped_total}/8388 mapped; {vm['newly_mapped_source_identity_count']} new explicit vanilla identities in this phase | {unmapped} identities, NBT/ore-dictionary/potion/entity semantics |
| Quests | {q['source_quests']}/46 lossless IR, {q['structurally_supported_candidates']} structural candidates ({crafting} crafting-task candidates), {n} trial quests exported | {remaining} quests, task/reward logic, runtime initialization |
| Graphics/audio | Authored core assets, 3 preview packs | Full atlas/models/render/audio testing |
| Tests | Static source checks, real serialization codecs, parser and script/function contracts | End-to-end game, save/load, multi-user, full feature fidelity |

No claim of full migration or feature parity follows from passing a subset of tests.

Vanilla identity checkpoint: {vm['newly_mapped_source_identity_count']} explicit source identities ({vm['newly_mapped_source_occurrences']} occurrences) resolve against {vm['modern_items_field_count']} actual 1.21.1 Items fields; NBT, ore-dictionary, potion and entity-specific identities remain blocked.

Quest checkpoint: {n} of {q['source_quests']} dependency-closed trial quests; 4 real-parser-checked SNBT files; scoped retrieval adapter, 10000 reducer observations and 4 negative fixtures. Full quest/runtime parity remains false.
''',encoding='utf-8')
tools=O/'tools/migration-sources'
tools.mkdir(parents=True,exist_ok=True)
for name in ['legacy_vanilla_items.py','verify_vanilla_identity_mapping.py','export_supported_quests.py','write_quest_checkpoint_status.py','rebuild_current_phase.py','verify_crafting_adapter_contract.py','verify_choice_reward_contract.py']:
    shutil.copy2(R/'work/migration'/name,tools/name)
print(json.dumps({'active_trial_quests':n,'remaining_quests':remaining,'mapped_identity_total':mapped_total,'unmapped_identity_total':unmapped,'new_vanilla_identities':vm['newly_mapped_source_identity_count'],'new_vanilla_occurrences':vm['newly_mapped_source_occurrences'],'full_parity':False},ensure_ascii=False))
