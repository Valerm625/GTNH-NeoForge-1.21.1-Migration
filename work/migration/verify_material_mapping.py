"""Independent evidence checks for conservative material mappings."""
from pathlib import Path
import json,re,zipfile,collections,math,hashlib,subprocess,uuid,shutil
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/material-migration';G=W/'exact-sources/GT5-Unofficial/5.09.54.205'
d=json.loads((D/'material-identity-map.json').read_text(encoding='utf-8'));defs=json.loads((D/'material-definitions.json').read_text(encoding='utf-8'));old=defs['legacy'];target=defs['modern'];aliases=d['element_proven_symbol_aliases']
# Independent symbol/ID parser avoids relying only on catalog's own table.
ids={}
for line in (G/'src/main/java/gregtech/api/enums/MaterialsIDMap.java').read_text(encoding='utf-8').splitlines():
 m=re.fullmatch(r'\s*r\(\s*(\d+),\s*Materials\.(\w+)\);',line)
 if m:assert int(m.group(1)) not in ids;ids[int(m.group(1))]=m.group(2)
assert {str(k):v for k,v in ids.items()}==d['id_table']
verified=set()
def identity(sym,stack=None):
 if sym in verified:return
 stack=set() if stack is None else set(stack);assert sym not in stack;stack.add(sym)
 a=old[sym];b=target[aliases.get(sym,sym)]
 if a['element'] is not None or b['element'] is not None:
  x=d['legacy_elements'][a['element']];y=d['modern_elements'][b['element']];assert x==y and x[0]>0,(sym,x,y)
 else:
  ac=a['components'];bc=b['components'];assert ac and bc and not b['has_unparsed_components'];da=math.gcd(*ac.values());db=math.gcd(*bc.values());assert {aliases.get(k,k):v//da for k,v in ac.items()}=={k:v//db for k,v in bc.items()}
  for k in ac:identity(k,stack)
 verified.add(sym)
for sym in d['identity_verified']:identity(sym)
assert all(x not in d['identity_verified'] for x in ['Steel','SolderingAlloy','Neutronium','Plutonium'])
assert aliases=={'Chrome':'Chromium','Uranium':'Uranium238'}
assert not any(k.startswith('screw|') for k in d['pairs'])
assert 'plateDouble|Rubber' not in d['pairs'],'Polymer NO_SMASHING flag ignored'
# Every active recipe material has a canonical tag and matched physical amount from both prefix definitions.
for key,p in d['pairs'].items():
 assert p['material_amount']==defs['legacy_prefixes'][p['legacy_prefix']]['amount']==defs['prefixes'][p['target_prefix']]['amount']
 assert p['legacy_material'] in verified
 if p['vanilla_override']:assert p['target_id']==p['vanilla_override']['target']
 else:assert p['target_id']=='gtceu:'+defs['prefixes'][p['target_prefix']]['pattern'].replace('%s',p['target_material'])
 if p['target_tag']:assert p['target_tag'].startswith('c:') and p['target_material'] in p['target_tag']
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));mapped=[]
for r in refs:
 if r.get('mapping_status')!='source_verified_material_identity_runtime_and_properties_pending':continue
 x=r['identity'];assert not x['tag'] and not x['ore_dict'] and 0<=x['damage']<32000
 sym=ids[x['damage']%1000];prefix=d['prefix_slots'][x['id']][x['damage']//1000];pair=d['pairs'][prefix+'|'+sym];assert r['target_id']==pair['target_id'];mapped.append(r)
assert len(mapped)==d['newly_mapped_quest_identities']
# Known legacy metadata anchors check array order and material-number arithmetic.
anchors=[('gregtech:gt.metaitem.01',11028,'ingot','Titanium','gtceu:titanium_ingot'),('gregtech:gt.metaitem.01',11300,'ingot','Bronze','gtceu:bronze_ingot'),('gregtech:gt.metaitem.01',17032,'plate','Iron','gtceu:iron_plate'),('gregtech:gt.metaitem.02',19035,'wireFine','Copper','gtceu:fine_copper_wire')]
for id,meta,pre,mat,out in anchors:assert d['prefix_slots'][id][meta//1000]==pre and ids[meta%1000]==mat and d['pairs'][pre+'|'+mat]['target_id']==out
recipes=json.loads((D/'material-bridge-recipes.json').read_text(encoding='utf-8'))
for recipe in recipes['records']:
 source=W/'exact-sources/NewHorizonsCoreMod/2.9.84'/recipe['source_file'];raw=source.read_text(encoding='utf-8');assert recipe['source_chain'] in raw;assert raw[:raw.index(recipe['source_chain'])].count('\n')+1==recipe['source_line'];assert hashlib.sha256(recipe['source_chain'].encode()).hexdigest()==recipe['source_sha256']
 for item in recipe['inputs']+recipe['outputs']:
  if item.get('pair_key'):assert item['pair_key'] in d['pairs'] and item['id']==d['pairs'][item['pair_key']]['target_id']
# Exact independently-reviewed anchor amounts/durations/electricity avoid circular generator expectations.
anchors_recipe={('AlloySmelterRecipes.java',32):(400,15,[(4,'core'),(1,'material')],4),('AssemblerRecipes.java',303):(300,60,[(3,'core'),(6,'material')],1),('FormingPressRecipes.java',44):(400,28,[(4,'core'),(1,'material')],4),('WiremillRecipes.java',29):(1200,960,[(1,'material')],4)}
for k,(duration,eu,inputs,outcount) in anchors_recipe.items():
 r=next(r for r in recipes['records'] if (Path(r['source_file']).name,r['source_line'])==k);assert r['duration_ticks']==duration and r['eu_per_tick']==eu and [(x['count'],x['kind']) for x in r['inputs']]==inputs and r['outputs'][0]['count']==outcount
# Real generated JS contract execution plus negative fixture checks.
for script,output in [('test_material_bridges.cjs','recipe-contract-test.json'),('test_material_runtime_audit.cjs','diagnostic-contract-test.json')]:
 p=subprocess.run(['node',str(W/'migration'/script),str(O)],capture_output=True,text=True,encoding='utf-8');assert p.returncode==0,p.stderr;(D/output).write_text(p.stdout,encoding='utf-8')
fixture=W/'migration/test-fixtures'/('material-'+str(uuid.uuid4()));fixture.mkdir(parents=True)
for rel in ['docs/material-migration/material-bridge-recipes.json','kubejs/server_scripts/14_gtnh_material_bridges.js']:
 p=fixture/rel;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(O/rel,p)
p=fixture/'kubejs/server_scripts/14_gtnh_material_bridges.js';original=p.read_text(encoding='utf-8');mutation_results=[]
for name,a,b in [('unit_count','4x gtnh_migration:micabasedsheet','3x gtnh_migration:micabasedsheet'),('tag_narrowing','#c:dusts/silicon_dioxide','gtceu:silicon_dioxide_dust'),('wrong_eu','.EUt(15, 1)','.EUt(16, 1)')]:
 assert a in original;p.write_text(original.replace(a,b,1),encoding='utf-8')
 result=subprocess.run(['node',str(W/'migration/test_material_bridges.cjs'),str(fixture)],capture_output=True,text=True,encoding='utf-8');assert result.returncode!=0,'Regression not caught '+name;mutation_results.append(name)
p.write_text(original,encoding='utf-8')
report={'passed':True,'legacy_numeric_ids_independently_parsed':len(ids),'recursive_element_composition_identities_checked':len(verified),'prefix_material_pairs_checked':len(d['pairs']),'quest_stack_mappings_checked':len(mapped),'metadata_anchors_checked':len(anchors),'source_recipe_anchors_checked':len(anchors_recipe),'material_bridge_recipes':len(recipes['records']),'negative_regressions_detected':mutation_results,'excluded_unsafe_cases':['Steel composition','SolderingAlloy composition','Neutronium nuclear counts','Plutonium isotope','screw material amount','polymer double plate generation'],'runtime_registry_verified':False,'full_material_parity':False}
(D/'independent-material-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
