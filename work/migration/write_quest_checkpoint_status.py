"""Update authored status from measured results without implying full parity."""
from pathlib import Path
import json,re,shutil
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export'
q=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'));v=json.loads((O/'docs/verification/validation-summary.json').read_text(encoding='utf-8'));r=json.loads((O/'docs/verification/test-runtime-files.json').read_text(encoding='utf-8'))
for name in ['QuestAdapterShapeCheck','LegacyRetrievalCounterCheck','QuestSnbtCheck','checkpoint-verification']:
 assert json.loads((D/(name+'.json')).read_text(encoding='utf-8'))['passed']
n=q['dependency_closed_active_quests'];remaining=q['source_quests']-n
p=O/'migration-metadata.json';m=json.loads(p.read_text(encoding='utf-8'));m['last_phase']='bounded two-quest trial export, scoped retrieval semantics, independent verification and checkpoint';m['validation_state'].update({'active_modern_quests':n,'active_modern_quests_runtime_verified':False,'remaining_quests_not_exported':remaining,'quest_adapter':'compiled; pure reducer and target-shape tests pass; actual Mixin/game unverified','runtime_input_files':len(r['files']),'modern_artifacts':f"18 downloaded JARs + 1 local version-pinned adapter; {v['version_ranges_checked']} dependency ranges passed",'feature_parity':False,'server_world_started':False,'client_rendering_verified':False});p.write_text(json.dumps(m,ensure_ascii=False,indent=2),encoding='utf-8')
p=O/'docs/quest-migration/coverage.json';d=json.loads(p.read_text(encoding='utf-8'));d.update({'active_modern_quests':n,'remaining_quests_not_exported':remaining,'active_export_is_trial':True,'game_runtime_verified':False,'full_quest_parity':False});p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
p=O/'STATUS.zh-CN.md';s=p.read_text(encoding='utf-8');s=s.replace('**活动现代任务导出0**',f'**活动现代任务试导出{n}（未实机验证）**');s=re.sub(r'输出目录：\d+个运行输入文件哈希、\d+条依赖区间通过',f"输出目录：{len(r['files'])}个运行输入文件哈希、{v['version_ranges_checked']}条依赖区间通过",s)
heading='## 任务试导出检查点'
if heading in s:s=s[:s.index(heading)].rstrip()+'\n'
s+='''
## 任务试导出检查点

- 新增2个依赖闭合的试导出任务（Your First Night、Loading the Chunks），1章、4个真实FTB SNBT配置文件。原3814个任务中仍有3812个未导出，不能称为完整任务迁移。
- 非消耗、单种物品检索使用明确标签启用的适配器：汇总在线成员主背包和盔甲栏；部分进度可下降，完成后锁定，不因反复扫描累加。原1.7.10没有副手，因此不统计副手。完整队伍成员变动/历史行为尚未等价。
- 真实FTB SNBT解析器往返4份配置通过；独立源码对照验证2个任务、数量、前置与奖励，4种故意损坏均被拒绝；已编译计数器10000次随机观察与目标类5个shadow方法/注入形状检查通过。
- 上述检查不等于游戏实际Mixin应用、任务初始化、世界开服或客户端渲染通过。仍保持eula=false；不代用户同意。
- 任务配置已加入启动前SHA-256完整性检查；不支持任务保留明确阻塞原因，未替换为空任务或免费奖励。
'''
p.write_text(s,encoding='utf-8')
p=O/'README.md';s=p.read_text(encoding='utf-8').replace('Active modern quest export is zero.','Two dependency-closed quests are exported as an explicitly unverified trial; 3,812 remain unexported. The actual FTB SNBT parser, compiled counter, scoped target-shape tests and independent negative fixtures pass, but do not prove game initialization.');p.write_text(s,encoding='utf-8')
p=O/'docs/migration-matrix.md';s=p.read_text(encoding='utf-8').replace('active modern export: 0','active modern trial export: 2; not runtime verified').replace('active exported quests 0','active trial quests 2; not runtime verified');line='Quest checkpoint: 2 of 3814 dependency-closed trial quests; 4 real-parser-checked SNBT files; scoped retrieval adapter, 10000 reducer observations and 4 negative fixtures. Full quest/runtime parity remains false.'
if line not in s:s+='\n'+line+'\n'
p.write_text(s,encoding='utf-8')
tools=O/'tools/migration-sources';tools.mkdir(parents=True,exist_ok=True)
for name in ['acquire_quest_sources.py','add_quest_parity_adapter.py','export_supported_quests.py','LegacyRetrievalCounterCheck.java','QuestSnbtCheck.java','QuestAdapterShapeCheck.java','test_quest_export_java.py','verify_quest_checkpoint.py','write_quest_checkpoint_status.py','stage_test_runtime.py','rebuild_current_phase.py','build_capacity_compat.py','loader_probe.py']:
 shutil.copy2(R/'work/migration'/name,tools/name)
print(json.dumps({'active_trial_quests':n,'remaining_quests':remaining,'runtime_inputs':len(r['files']),'dependency_ranges':v['version_ranges_checked'],'full_parity':False}))
