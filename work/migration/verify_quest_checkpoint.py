"""Independent, source-anchored checks for the bounded two-quest checkpoint."""
from pathlib import Path
import copy, hashlib, json, zipfile
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export'
m=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'))
p=json.loads((D/'actual-snbt-parsed.json').read_text(encoding='utf-8'))
assert set(m['files'])==set(p)
for name,rec in m['files'].items():
 assert hashlib.sha256((O/'config/ftbquests/quests'/name).read_bytes()).hexdigest()==rec['sha256'],name
 assert p[name]==rec['data'],f'Actual FTB parser changed semantic tree: {name}'
records={r['source_id']:r for r in m['exported']}
assert set(records)=={'0:0','0:2874'},'Review and update independent fixtures before expanding checkpoint'
quests={q['id']:q for name,d in p.items() if name.startswith('chapters/') for q in d['quests']}
assert len(quests)==2

def check(quests):
 for source_id,task_type,item,count,reward in [('0:0','item','minecraft:dirt',8,'minecraft:apple'),('0:2874','checkmark',None,None,'minecraft:cookie')]:
  rec=records[source_id];src=D/'source-quests'/Path(rec['source_path']).name
  assert hashlib.sha256(src.read_bytes()).hexdigest()==rec['source_sha256']
  original=json.loads(src.read_text(encoding='utf-8'));q=quests[rec['target_id']]
  assert len(q['tasks'])==len(original['tasks:9'])==1
  assert q['tasks'][0]['type']==task_type
  deps=[records[str(v['questIDHigh:4'])+':'+str(v['questIDLow:4'])]['target_id'] for v in original['preRequisites:9'].values()]
  assert q['dependencies']==deps==rec['dependency_ids']
  assert all(x in quests for x in deps)
  assert not q['can_repeat'] and q['dependency_requirement']=='all_completed'
  assert len(q['rewards'])==1 and q['rewards'][0]['item']['id']==reward
  assert q['rewards'][0]['count']==1 and not q['rewards'][0]['team_reward'] and q['rewards'][0]['auto']=='disabled'
  source_reward=next(iter(next(iter(original['rewards:9'].values()))['rewards:9'].values()))
  assert source_reward['id:8']==reward and source_reward['Count:3']==1
  props=original['properties:10']['betterquesting:10'];lang=p['lang/en_us.snbt'];prefix='quest.'+rec['target_id']
  assert lang[prefix+'.title']==props['name:8']
  assert '[Migration preview]' in lang[prefix+'.quest_desc'][0]
  if item:
   t=q['tasks'][0];source_task=next(iter(original['tasks:9'].values()));req=next(iter(source_task['requiredItems:9'].values()))
   assert t['item']['id']==req['id:8']==item and t['count']==req['Count:3']==count
   assert not t['consume_items'] and not t['only_from_crafting']
   assert t['tags']==['gtnh_legacy_single_retrieval'] and t['match_components']=='strict'
   assert not source_task['consume:1'] and not source_task['groupDetect:1']
check(quests)
mutations=[]
for kind in ['count','dependency','consume','reward']:
 bad=copy.deepcopy(quests);q=bad[records['0:0']['target_id']]
 if kind=='count':q['tasks'][0]['count']=7
 elif kind=='dependency':q['dependencies']=['DEADBEEFDEADBEEF']
 elif kind=='consume':q['tasks'][0]['consume_items']=1
 else:q['rewards'][0]['item']['id']='minecraft:diamond'
 try:check(bad)
 except AssertionError:mutations.append(kind)
 else:raise AssertionError('Broken fixture accepted: '+kind)
jar=O/'mods/gtnh-migration-compat-0.1.0.jar'
with zipfile.ZipFile(jar) as z:
 cfg=json.loads(z.read('gtnh_migration_compat.mixins.json'))
 assert 'LegacyRetrievalItemTaskMixin' in cfg['mixins']
 assert b'modId="ftbquests"' in z.read('META-INF/neoforge.mods.toml')
 assert b'versionRange="[2101.1.36]"' in z.read('META-INF/neoforge.mods.toml')
assert 'eula=false' in (O/'eula.txt').read_text(encoding='utf-8')
assert not (O/'kubejs/export/gtnh-material-registry-check.json').exists(),'Unexpected live evidence: review state before checkpoint'
r={'passed':True,'source_anchored_quests':2,'actual_parser_trees_compared':len(p),'deliberate_mutations_rejected':mutations,'dependency_closed':True,'eula_accepted':False,'game_runtime_verified':False,'full_quest_parity':False}
(D/'checkpoint-verification.json').write_text(json.dumps(r,indent=2),encoding='utf-8');print(json.dumps(r))
