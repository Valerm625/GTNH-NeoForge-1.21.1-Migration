"""Verify the conservative BetterQuesting item-choice reward subset."""
from pathlib import Path
import json,sys
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export';Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests';sys.path.insert(0,str(Path(__file__).parent));from legacy_vanilla_items import resolve_legacy_vanilla
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));registry={(x['identity']['id'],x['identity']['damage']):x['target_id'] for x in refs if x.get('target_id') and not x['identity']['tag'] and not x['identity']['ore_dict']}
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'));ids=manifest.get('choice_reward_candidate_source_ids',[]);assert len(ids)==manifest.get('choice_reward_candidates',0)
checked=[]
for path in sorted(Q.rglob('*.json')):
 data=json.loads(path.read_text(encoding='utf-8'));qid=str(data['questIDHigh:4'])+':'+str(data['questIDLow:4'])
 if qid not in ids:continue
 found=False
 for reward in data.get('rewards:9',{}).values():
  if reward.get('rewardID:8')!='bq_standard:choice':continue
  found=True;choices=list(reward.get('choices:9',{}).values());assert choices
  for item in choices:
   assert not item.get('OreDict:8') and not item.get('tag:10')
   key=(item['id:8'],item.get('Damage:2',0));target=registry.get(key) or resolve_legacy_vanilla(item['id:8'],key[1],item.get('OreDict:8',''),item.get('tag:10'));assert target,key
 if found:checked.append(qid)
assert sorted(checked)==sorted(ids)
r={'passed':True,'choice_reward_candidate_source_ids':sorted(ids),'choice_reward_candidate_count':len(ids),'all_choices_exactly_mapped':True,'ore_and_nbt_choices_rejected':True,'runtime_game_verified':False,'full_quest_parity':False}
(D/'choice-reward-adapter.json').write_text(json.dumps(r,indent=2),encoding='utf-8');print(json.dumps(r))
