"""Lossless typed quest migration IR; preserve unsupported mechanics instead of deleting them."""
from pathlib import Path
import json,hashlib,collections,base64,re
ROOT=Path(__file__).resolve().parents[2];W=ROOT/'work';O=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';B=W/'source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests';D=O/'docs/quest-migration';D.mkdir(exist_ok=True)
def qid(d,prefix='questID'):
 return f"{d[prefix+'High:4']}:{d[prefix+'Low:4']}"
def stable_id(namespace,text):
 return hashlib.sha256((namespace+'\0'+text).encode()).hexdigest()[:16].upper()
coins=json.loads((O/'docs/currency-migration/coin-mapping.json').read_text(encoding='utf-8'))
coinmap={x['legacy_id']:x for x in coins['records']}

implemented=dict(coinmap)
corepath=O/'docs/core-items-migration/core-item-mapping.json'
if corepath.exists():
    implemented.update({x['legacy_id']:x for x in json.loads(corepath.read_text(encoding='utf-8'))['records']})
formpath=O/'docs/early-game-migration/wooden-brick-form.json'
if formpath.exists():
    item=json.loads(formpath.read_text(encoding='utf-8'))['item'];implemented[item['legacy_id']]=item

fuelpath=O/'docs/fuel-block-migration/fuel-blocks.json'
if fuelpath.exists():
    implemented.update({x['legacy_id']:x for x in json.loads(fuelpath.read_text(encoding='utf-8'))['blocks']})
identitymaps={('Railcraft:cube',0):'railcraft:coal_coke_block',('Railcraft:fuel.coke',0):'railcraft:coal_coke'}


qs={};tasktypes=collections.Counter();rewardtypes=collections.Counter();itemrefs={};entityrefs=collections.Counter();fluidrefs=collections.Counter();dimensions=collections.Counter();chapters=[]
def walk(x,where,q):
 if isinstance(x,dict):
  if 'id:8' in x and ('Count:3' in x or 'Damage:2' in x):
   identity={'id':x['id:8'],'damage':x.get('Damage:2',0),'ore_dict':x.get('OreDict:8',''),'tag':x.get('tag:10')}
   key=json.dumps(identity,sort_keys=True,ensure_ascii=False)
   if key not in itemrefs:itemrefs[key]={'identity':identity,'occurrences':0,'quests':set(),'roles':collections.Counter(),'example_stack':x,'mapping_status':'unmapped','target_id':None}
   row=itemrefs[key];row['occurrences']+=1;row['quests'].add(q);row['roles'][where.split('/')[0]]+=1
   if identity['id'] in implemented and identity['damage']==0 and not identity['tag'] and not identity['ore_dict']:
    row['mapping_status']='item_definition_implemented_not_runtime_verified';row['target_id']=implemented[identity['id']]['target_id']
   elif not identity['tag'] and not identity['ore_dict'] and identitymaps.get((identity['id'],identity['damage'])):
    row['mapping_status']='source_identity_to_modern_registry_not_runtime_verified';row['target_id']=identitymaps[(identity['id'],identity['damage'])]
  for k,v in x.items():walk(v,where+'/'+k,q)
 elif isinstance(x,list):
  for v in x:walk(v,where,q)
for p in sorted((B/'Quests').rglob('*.json')):
 raw=p.read_bytes();d=json.loads(raw);id=qid(d);assert id not in qs
 props=d.get('properties:10',{}).get('betterquesting:10',{})
 deps=[qid(t) for t in d.get('preRequisites:9',{}).values()]
 tasks=list(d.get('tasks:9',{}).values());rewards=list(d.get('rewards:9',{}).values())
 for t in tasks:
  tasktypes[t['taskID:8']]+=1
  if t.get('target:8'):entityrefs[t['target:8']]+=1
  if 'dimension:3' in t:dimensions[str(t['dimension:3'])]+=1
  for f in t.get('requiredFluids:9',{}).values():fluidrefs[f.get('FluidName:8','')]+=1
 for r in rewards:rewardtypes[r['rewardID:8']]+=1
 for k,v in d.items():walk(v,k,id)
 qs[id]={'source_id':id,'candidate_target_id':stable_id('quest',id),'source_path':p.relative_to(B).as_posix(),'source_sha256':hashlib.sha256(raw).hexdigest(),'name':props.get('name:8',''),'prerequisites':deps,'task_types':[t['taskID:8'] for t in tasks],'reward_types':[r['rewardID:8'] for r in rewards],'raw_typed_data':d,'placements':[],'export_status':'not_exported; mechanics_and_registry_mapping_pending'}
for cp in sorted((B/'QuestLines').iterdir()):
 if not cp.is_dir():continue
 meta=json.loads((cp/'QuestLine.json').read_text(encoding='utf-8'));cid=qid(meta,'questLineID');placed=[]
 for p in sorted(cp.glob('*.json')):
  if p.name=='QuestLine.json':continue
  d=json.loads(p.read_text(encoding='utf-8'));id=qid(d);placed.append(id)
  if id in qs:qs[id]['placements'].append({'chapter_id':cid,'placement':d,'source_path':p.relative_to(B).as_posix()})
 chapters.append({'source_id':cid,'candidate_target_id':stable_id('chapter',cid),'directory':cp.name,'raw_typed_metadata':meta,'placed_quest_ids':placed})
missing=[{'quest_id':id,'quest_name':q['name'],'missing_prerequisite_id':dep} for id,q in qs.items() for dep in q['prerequisites'] if dep not in qs]
placements_missing=[{'chapter_id':c['source_id'],'missing_quest_id':q} for c in chapters for q in c['placed_quest_ids'] if q not in qs]
ids=[q['candidate_target_id'] for q in qs.values()]+[c['candidate_target_id'] for c in chapters];assert len(ids)==len(set(ids))
# Iterative topological check (no recursion-limit artifact). Preserve cycles if any; do not silently drop prerequisites.
indeg={q:0 for q in qs};followers=collections.defaultdict(list)
for q,r in qs.items():
 for d in r['prerequisites']:
  if d in qs:indeg[q]+=1;followers[d].append(q)
queue=collections.deque(q for q,n in indeg.items() if n==0);visited=0
while queue:
 q=queue.popleft();visited+=1
 for n in followers[q]:
  indeg[n]-=1
  if indeg[n]==0:queue.append(n)
with (D/'quests.lossless-ir.jsonl').open('w',encoding='utf-8') as f:
 for q in qs.values():f.write(json.dumps(q,ensure_ascii=False,separators=(',',':'))+'\n')
(D/'chapters.lossless-ir.json').write_text(json.dumps(chapters,ensure_ascii=False,indent=2),encoding='utf-8')
references=[]
for r in itemrefs.values():r['quests']=sorted(r['quests']);r['roles']=dict(r['roles']);references.append(r)
references.sort(key=lambda r:(-r['occurrences'],r['identity']['id']))
(D/'item-registry-references.json').write_text(json.dumps(references,ensure_ascii=False,indent=2),encoding='utf-8')
report={'quests':len(qs),'chapters':len(chapters),'source_task_types':dict(tasktypes),'source_reward_types':dict(rewardtypes),'unique_item_stack_identities':len(references),'mapped_coin_identities':sum(r['target_id'] is not None and r['identity']['id'] in coinmap for r in references),'mapped_total_item_identities':sum(r['target_id'] is not None for r in references),'other_identities_unmapped':sum(r['target_id'] is None for r in references),'missing_prerequisites_already_in_source':missing,'missing_placed_quests':placements_missing,'not_topologically_resolved':len(qs)-visited,'entity_references':dict(entityrefs),'fluid_references':dict(fluidrefs),'dimension_references':dict(dimensions),'active_quests_exported':0,'lossless_ir_written':True,'full_quest_parity':False,'semantics_requiring_explicit_adapters':['optional_retrieval','crafting vs smelting vs anvil task accounting','partial-match and ignore-NBT detection','cross-quest completion rewards','choice reward groups','global and party progress/reward sharing','repeat timing','dimension IDs and structure/biome restrictions','hunt/meeting entity NBT matching'],'known_rule':'Never replace unsupported tasks by checkmarks, empty rewards, air items or auto-completion.'}
(D/'coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
# Read back every quest and compare its raw typed tree to the authoritative source.
verified=0
for line in (D/'quests.lossless-ir.jsonl').read_text(encoding='utf-8').splitlines():
 q=json.loads(line);assert q['raw_typed_data']==json.loads((B/q['source_path']).read_text(encoding='utf-8'));verified+=1
assert verified==3814
print(json.dumps({k:report[k] for k in ['quests','chapters','unique_item_stack_identities','mapped_coin_identities','other_identities_unmapped','not_topologically_resolved','active_quests_exported']},ensure_ascii=False));print('LOSSLESS_QUEST_ROUNDTRIP_OK',verified)
