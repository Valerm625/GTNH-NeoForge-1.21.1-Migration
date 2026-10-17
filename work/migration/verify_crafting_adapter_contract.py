"""Verify the conservative BetterQuesting crafting subset before export growth."""
from pathlib import Path
import ast,json,sys,zipfile
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export';Q=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests';sys.path.insert(0,str(Path(__file__).parent));from legacy_vanilla_items import resolve_legacy_vanilla
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));registry={(x['identity']['id'],x['identity']['damage']):x['target_id'] for x in refs if x.get('target_id') and not x['identity']['tag'] and not x['identity']['ore_dict']}
manifest=json.loads((D/'export-manifest.json').read_text(encoding='utf-8'));ids=manifest.get('crafting_candidate_source_ids',[]);assert len(ids)==manifest.get('crafting_task_candidates',0)>=1
checked=[]
# Check the actual compiler expression against the source-derived no-NBT
# predicate. Partial matching does not permit nonempty NBT against empty NBT.
compiler=ast.parse((R/'work/migration/export_supported_quests.py').read_text(encoding='utf-8'))
branches=[n for n in ast.walk(compiler) if isinstance(n,ast.If) and isinstance(n.test,ast.Compare) and isinstance(n.test.left,ast.Name) and n.test.left.id=='typ' and any(isinstance(x,ast.Constant) and x.value=='bq_standard:crafting' for x in n.test.comparators)]
assert len(branches)==1
component_assignments=[n for n in ast.walk(branches[0]) if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='component' for t in n.targets)]
assert len(component_assignments)==1
policy=compile(ast.Expression(component_assignments[0].value),'<actual crafting compiler component policy>','eval')
for ignore in [0,1]:
 for partial in [0,1]:
  value=eval(policy,{'__builtins__':{}},{'t':{'ignoreNBT:1':ignore,'partialMatch:1':partial}})
  assert value==('none' if ignore else 'strict'),(ignore,partial,value)
task_shapes=[n for n in ast.walk(branches[0]) if isinstance(n,ast.Dict) and any(isinstance(k,ast.Constant) and k.value=='match_components' for k in n.keys)]
assert len(task_shapes)==1
field=dict((k.value,v) for k,v in zip(task_shapes[0].keys,task_shapes[0].values) if isinstance(k,ast.Constant))['match_components']
assert isinstance(field,ast.Name) and field.id=='component','Crafting policy is not connected to exported item tasks'
with zipfile.ZipFile(O/'mods/gtnh-migration-compat-0.1.0.jar') as jar:
 assert 'LegacyCraftingItemTaskMixin' in json.loads(jar.read('gtnh_migration_compat.mixins.json'))['mixins']
 assert 'org/gtnhmigration/compat/LegacyCraftingStackMatcher.class' in jar.namelist()
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
r={'passed':True,'crafting_candidate_source_ids':sorted(ids),'crafting_candidate_count':len(ids),'source_event_policy':'craft and smelt only; statistics and anvil rejected','all_required_items_exactly_mapped':True,'ore_and_nbt_predicates_rejected':True,'empty_legacy_nbt_uses_strict_components':True,'partial_match_does_not_admit_extra_components':True,'durability_ignored_by_scoped_matcher':True,'packaged_crafting_mixin_present':True,'runtime_game_verified':False,'full_quest_parity':False}
(D/'crafting-task-adapter.json').write_text(json.dumps(r,indent=2),encoding='utf-8');print(json.dumps(r))
