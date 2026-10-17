"""Verify the conservative BetterQuesting crafting subset before export growth."""
from pathlib import Path
import json,sys
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export';Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests';sys.path.insert(0,str(Path(__file__).parent));from legacy_vanilla_items import resolve_legacy_vanilla
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));registry={(x['identity']['id'],x['identity']['damage']):x['target_id'] for x in refs if x.get('target_id') and not x['identity']['tag'] and not x['identity']['ore_dict']}
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'));ids=manifest.get('crafting_candidate_source_ids',[]);assert len(ids)==manifest.get('crafting_task_candidates',0)>=1
checked=[]
for path in sorted(Q.rglob('*.json')):
 data=json.loads(path.read_text(encoding='utf-8'));qid=str(data['questIDHigh:4'])+':'+str(data['questIDLow:4'])
 if qid not in ids:continue
 found=False
 for task in data.get('tasks:9',{}).values():
  if task.get('taskID:8')!='bq_standard:crafting':continue
  found=True;assert not task.get('allowCraftedFromStatistics:1',0);assert task.get('allowCraft:1',1);assert task.get('allowSmelt:1',1);assert not task.get('allowAnvil:1',0)
  req=list(task.get('requiredItems:9',{}).values());assert req
  for item in req:
   assert not item.get('OreDict:8') and not item.get('tag:10')
   key=(item['id:8'],item.get('Damage:2',0));target=registry.get(key) or resolve_legacy_vanilla(item['id:8'],key[1],item.get('OreDict:8',''),item.get('tag:10'));assert target,key
 if found:checked.append(qid)
assert sorted(checked)==sorted(ids)
r={'passed':True,'crafting_candidate_source_ids':sorted(ids),'crafting_candidate_count':len(ids),'source_event_policy':'craft and smelt only; statistics and anvil rejected','all_required_items_exactly_mapped':True,'ore_and_nbt_predicates_rejected':True,'runtime_game_verified':False,'full_quest_parity':False}
(D/'crafting-task-adapter.json').write_text(json.dumps(r,indent=2),encoding='utf-8');print(json.dumps(r))
