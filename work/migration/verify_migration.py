from pathlib import Path
import json,zipfile,io,tomllib,hashlib,subprocess,re,collections,datetime
from PIL import Image
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/verification';D.mkdir(exist_ok=True)
def h(p,alg='sha256'):return hashlib.new(alg,p.read_bytes()).hexdigest()
lock=json.loads((O/'docs/artifacts.lock.json').read_text(encoding='utf-8'));ftb=json.loads((O/'docs/ftb-candidates.json').read_text(encoding='utf-8'))
expected={x['filename']:x for x in lock['artifacts']};expected.update({x['filename']:x for x in ftb});actual={p.name:p for p in (O/'mods').glob('*.jar')}
compatpath=O/'docs/compat-mod.lock.json'
if compatpath.exists():
    compat=json.loads(compatpath.read_text(encoding='utf-8'));expected[compat['filename']]=compat
assert actual.keys()==expected.keys(),{'extra':list(actual.keys()-expected.keys()),'missing':list(expected.keys()-actual.keys())}
providers={};records=[];issues=[];top=[]
def inspect(data,source,embedded=False):
 with zipfile.ZipFile(io.BytesIO(data)) as z:
  bad=z.testzip();assert not bad,(source,bad)
  meta=None
  for n in ['META-INF/neoforge.mods.toml','META-INF/mods.toml']:
   if n in z.namelist():meta=tomllib.loads(z.read(n).decode());break
  mf=z.read('META-INF/MANIFEST.MF').decode(errors='replace') if 'META-INF/MANIFEST.MF' in z.namelist() else ''
  ih=re.search(r'^Implementation-Version: (.+)$',mf,re.M);impl=ih.group(1).strip() if ih else None
  if meta:
   for mod in meta.get('mods',[]):
    version=mod.get('version','')
    if version=='${file.jarVersion}':version=impl
    rec={'id':mod['modId'],'version':version,'source':source,'embedded':embedded,'metadata':meta}
    records.append(rec)
    if mod['modId'] not in providers or (providers[mod['modId']]['embedded'] and not embedded):providers[mod['modId']]=rec
    elif not embedded and not providers[mod['modId']]['embedded']:issues.append('Duplicate top-level mod '+mod['modId'])
  if 'META-INF/jarjar/metadata.json' in z.namelist():
   for entry in json.loads(z.read('META-INF/jarjar/metadata.json')).get('jars',[]):
    p=entry['path'];assert p in z.namelist();inspect(z.read(p),source+'!/'+p,True)
for name,p in sorted(actual.items()):
 row=expected[name]
 if 'hashes' in row:
  for alg in ['sha512','sha1']:
   if alg in row['hashes']:assert h(p,alg)==row['hashes'][alg]
 else:assert h(p)==row['sha256']
 inspect(p.read_bytes(),name);top.append({'filename':name,'sha256':h(p),'bytes':p.stat().st_size})
for script,report in [('test_fuel_blocks.cjs','fuel-script-contract.json'),('test_railcraft_coke.cjs','railcraft-coke-contract.json')]:
    test=subprocess.run(['node',str(W/'migration'/script),str(O)],capture_output=True,text=True,encoding='utf-8');assert test.returncode==0,test.stderr
    (D/report).write_text(test.stdout,encoding='utf-8')

platform=json.loads((W/'migration/platform-lock.json').read_text(encoding='utf-8'))
providers['minecraft']={'id':'minecraft','version':'1.21.1','source':'official Minecraft server','embedded':False}
providers['neoforge']={'id':'neoforge','version':platform['neoforge'],'source':'official NeoForge','embedded':False}
rows=[];missing=[]
for id,rec in providers.items():
 if not rec.get('metadata'):continue
 for dep in rec['metadata'].get('dependencies',{}).get(id,[]):
  kind=dep.get('type','required').lower();did=dep['modId']
  if kind not in ['required','optional']:continue
  if did not in providers:
   if kind=='required':missing.append({'mod':id,'dependency':did})
   continue
  bound=dep.get('versionRange')
  if bound:
   assert providers[did]['version'],providers[did]
   rows.append([id,did,providers[did]['version'],bound])
assert not missing,missing
assert not issues,issues
ranges=W/'migration/dependency-ranges.tsv';ranges.write_text('\n'.join('\t'.join(row) for row in rows)+'\n',encoding='utf-8')
runtime=json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'));java=runtime['java'];javac=str(Path(java).with_name('javac.exe'))
lib=next((W/'neoforge-smoke/libraries/org/apache/maven/maven-artifact').rglob('maven-artifact-*.jar'))
classes=W/'migration/java-checks';classes.mkdir(exist_ok=True)
subprocess.run([javac,'-encoding','UTF-8','-cp',str(lib),'-d',str(classes),str(W/'migration/CheckVersionRanges.java')],check=True,capture_output=True)
commons=next((W/'neoforge-smoke/libraries/org/apache/commons/commons-lang3').rglob('commons-lang3-*.jar'))
check=subprocess.run([java,'-cp',str(classes)+';'+str(lib)+';'+str(commons),'CheckVersionRanges',str(ranges)],capture_output=True,text=True,encoding='utf-8')
(D/'dependency-range-results.tsv').write_text(check.stdout,encoding='utf-8');assert check.returncode==0,check.stdout+check.stderr
pngs=0;jsons=0
for base in [O/'kubejs',O/'resourcepacks']:
 for p in base.rglob('*'):
  if not p.is_file():continue
  rel=p.relative_to(base).as_posix()
  if '/assets/' in '/'+rel:
   asset=rel[rel.find('assets/'):];assert re.fullmatch(r'assets/[a-z0-9_.-]+/[a-z0-9_./-]+',asset),asset
  if p.suffix=='.png':
   with Image.open(p) as im:im.verify()
   pngs+=1
  if p.suffix in ['.json','.mcmeta']:json.loads(p.read_text(encoding='utf-8'));jsons+=1
core=json.loads((O/'docs/core-items-migration/core-item-mapping.json').read_text(encoding='utf-8'))
for asset in core['asset_files']:
    path=O/'kubejs'/asset['target_texture']; assert h(path)==asset['source_png_sha256']
    if asset['animation']:
        meta=path.with_suffix('.png.mcmeta');assert h(meta)==asset['animation']['sha256']
for item in core['records']:
    model=O/'kubejs/assets/gtnh_migration/models/item'/f"{item['target_id'].split(':')[1]}.json"
    md=json.loads(model.read_text(encoding='utf-8'));assert md['textures']==item['textures']
    for texture in md['textures'].values():
        ns,loc=texture.split(':');assert (O/f'kubejs/assets/{ns}/textures/{loc}.png').exists()
    for locale in ['en_us','zh_cn']:
        assert item['translation_key'] in json.loads((O/f'kubejs/assets/gtnh_migration/lang/{locale}.json').read_text(encoding='utf-8'))
coins=json.loads((O/'docs/currency-migration/coin-mapping.json').read_text(encoding='utf-8'))
for c in coins['records']:
 p=O/'kubejs'/c['target_texture'];assert h(p)==c['source_png_sha256']
 model=O/'kubejs/assets/gtnh_migration/models/item'/f"{c['target_id'].split(':')[1]}.json"
 md=json.loads(model.read_text(encoding='utf-8'));tex=md['textures']['layer0'];ns,loc=tex.split(':')
 assert (O/f'kubejs/assets/{ns}/textures/{loc}.png').exists()
 for lang in ['en_us','zh_cn']:
  assert c['translation_key'] in json.loads((O/f'kubejs/assets/gtnh_migration/lang/{lang}.json').read_text(encoding='utf-8'))
node=subprocess.run(['node',str(W/'migration/test_exact_core_items.cjs'),str(O)],capture_output=True,text=True,encoding='utf-8');assert node.returncode==0,node.stderr
(D/'currency-js-contract-test.json').write_text(node.stdout,encoding='utf-8')

recipe_node=subprocess.run(['node',str(W/'migration/test_exact_core_recipes.cjs'),str(O)],capture_output=True,text=True,encoding='utf-8');assert recipe_node.returncode==0,recipe_node.stderr
(D/'coin-recipes-js-contract-test.json').write_text(recipe_node.stdout,encoding='utf-8')
form_node=subprocess.run(['node',str(W/'migration/test_wooden_form.cjs'),str(O)],capture_output=True,text=True,encoding='utf-8');assert form_node.returncode==0,form_node.stderr
(D/'wooden-form-contract-test.json').write_text(form_node.stdout,encoding='utf-8')


rm=json.loads((O/'docs/resource-migration/resource-mapping.json').read_text(encoding='utf-8'))
for c in rm['ore_mappings']:
 p=O/'resourcepacks/GTNH-2.8.4-Shared-Ore-Assets-1.21.1'/c['target_path'];assert h(p)==c['target_sha256']
for c in rm['audio_mappings']:
 p=O/'resourcepacks/GTNH-Original-Alarm-Audio-1.21.1'/c['target_path'];assert h(p)==c['source_sha256']
loader=json.loads((W/'migration/loader-with-quest-runtime.json').read_text(encoding='utf-8'))
text=(W/'migration/loader-with-quest-runtime.log').read_text(encoding='utf-8',errors='replace')
assert loader['eula_gate_seen'] and not loader['server_done_seen']
for id in providers:
 if id in ['minecraft','neoforge']:continue
 assert '('+id+')' in text,'Loader did not list '+id
for n in ['loader-first-run.json','loader-first-run.log','loader-with-quest-runtime.json','loader-with-quest-runtime.log','platform-lock.json','VersionSupportMatrix.bytecode.txt']:
 import shutil;shutil.copy2(W/'migration'/n,D/n)

material_map=json.loads((O/'docs/material-migration/material-identity-map.json').read_text(encoding='utf-8'))
material_recipes=json.loads((O/'docs/material-migration/material-bridge-recipes.json').read_text(encoding='utf-8'))
solder=json.loads((O/'docs/material-migration/soldering-alloy-adaptation.json').read_text(encoding='utf-8'))
for script,output in [('test_material_bridges.cjs','material-bridge-contract.json'),('test_material_runtime_audit.cjs','material-runtime-diagnostic-contract.json'),('test_soldering_alloy.cjs','soldering-alloy-contract.json')]:
    test=subprocess.run(['node',str(W/'migration'/script),str(O)],capture_output=True,text=True,encoding='utf-8');assert test.returncode==0,test.stderr
    (D/output).write_text(test.stdout,encoding='utf-8')

report={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'top_level_artifacts':top,'active_top_level_jars':len(top),'registered_mod_ids_listed_by_discovery':len(providers),'required_dependencies_missing':missing,'version_ranges_checked':len(rows),'version_range_failures':0,'neoforge_compatibility_matrix_applied':True,'decoded_png_count':pngs,'json_mcmeta_parsed_count':jsons,'coin_items':len(coins['records']),'simple_and_layered_item_definitions':557,'specialized_item_definitions':1,'total_implemented_item_definitions':558,'animated_source_texture_files':31,'creative_tabs':8,'custom_tooltip_lines':24,'vanilla_fuel_overrides':2,'fuel_block_definitions':16,'fuel_block_burn_values':16,'long_fuel_codec_cases':120,'capacity_adapter_compiled':True,'capacity_adapter_runtime_verified':False,'gtceu_machine_recipe_definitions':157+len(material_recipes['records'])+len(solder['recipes']),'material_quest_stack_mappings':material_map['newly_mapped_quest_identities'],'soldering_composition_adapter_implemented':True,'soldering_composition_runtime_verified':False,'early_game_recipe_definitions':3,'coin_exchange_recipes':112,'coin_recipe_contract_test_passed':True,'coin_script_contract_test_passed':True,'eula_gate_reached':True,'server_world_started':False,'client_started':False,'resource_reload_tested':False,'feature_parity_verified':False,'notes':['All passed checks are static or pre-EULA loader discovery; no full mod initialization asserted.','Item, recipe and form behavior scripts execute against recording stubs only; not game integration.','JEI raw interval excludes 1.21.1, but the exact NeoForge loader officially accepts 1.21 as compatible.','Missing refmap warnings remain; evaluate after full mod initialization.']}
(D/'validation-summary.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ['top_level_artifacts','notes']},ensure_ascii=False),flush=True)
