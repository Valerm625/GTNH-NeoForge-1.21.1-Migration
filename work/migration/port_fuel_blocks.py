"""Exact-source fuel block port. Does not invent missing Railcraft/ore-unifier mappings."""
from pathlib import Path
import json,re,hashlib,io,xml.etree.ElementTree as ET
from PIL import Image
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-Neoforge-1.21.1-Migration-Foundation'
# Resolve exact existing path, preserving established folder case.
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';G=W/'exact-sources/GT5-Unofficial/5.09.54.205';D=O/'docs/fuel-block-migration';D.mkdir(exist_ok=True)
def save(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
def sha(b):return hashlib.sha256(b).hexdigest()
def lang(p):return {a.strip():b for l in p.read_text(encoding='utf-8-sig').splitlines() if l and not l.startswith('#') and '=' in l for a,b in [l.split('=',1)]}
source=S/'src/main/java/com/dreammaster/block/BlockList.java';s=source.read_text(encoding='utf-8');start=s.index('// ===== Compressed Coal Blocks');end=s.index('// ===== Blood Magic',start);rows=re.findall(r'(\w+)\("([^\"]+)", stoneLike\((\d+), ([\d.]+)f\)\)',s[start:end]);assert len(rows)==16
assert 'new Block(Material.rock).setHardness(hardness).setStepSound(Block.soundTypeStone)' in s
fuelsrc=W/'source/GT-New-Horizons-Modpack-master/config/GTNewHorizons/CustomFuels.xml';fuels={x.attrib['ItemName']:int(x.attrib['BurnTime']) for x in ET.parse(fuelsrc).getroot()}
records=[];tags={};languages={loc:json.loads((K/f'assets/gtnh_migration/lang/{loc.lower()}.json').read_text(encoding='utf-8')) for loc in ['en_US','zh_CN','ru_RU']}
lines=['// Adapted from exact CoreMod 2.9.84 BlockList stoneLike and master CustomFuels.xml.', '// Explicit original hardness/resistance/tool requirements. Long-burn save/sync require compat validation.',"StartupEvents.registry('block', event => {"]
for enum,name,level,hardness in rows:
 level=int(level);hardness=float(hardness);ticks=fuels['dreamcraft:'+name];assert 0<ticks<2**31
 id='gtnh_migration:'+name.lower();key='block.gtnh_migration.'+name.lower();tex='gtnh_migration:block/'+name.lower()
 src=S/f'src/main/resources/assets/dreamcraft/textures/blocks/block{name}.png';b=src.read_bytes()
 with Image.open(io.BytesIO(b)) as im:im.verify()
 im=Image.open(io.BytesIO(b));assert im.size==(16,16),im.size
 dst=K/f'assets/gtnh_migration/textures/block/{name.lower()}.png';dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(b)
 tooltag={1:'minecraft:needs_stone_tool',2:'minecraft:needs_iron_tool',3:'minecraft:needs_diamond_tool'}[level]
 # 1.7.10 setHardness raises internal resistance to hardness*5; getExplosionResistance returns /5.
 resistance=hardness
 lines.extend(['  event.create('+json.dumps(id)+').translationKey('+json.dumps(key)+')','    .texture('+json.dumps(tex)+').stoneSoundType().mapColor("stone")',f'    .hardness({hardness}).resistance({resistance}).requiresTool()', '    .tagBlock("minecraft:mineable/pickaxe", '+json.dumps(tooltag)+')',f'    .item(item => {{ item.burnTime({ticks}); item.maxStackSize(64); }});'])
 for tag in ['minecraft:mineable/pickaxe',tooltag]:tags.setdefault(tag,[]).append(id)
 save(K/f'assets/gtnh_migration/blockstates/{name.lower()}.json',{'variants':{'':{'model':'gtnh_migration:block/'+name.lower()}}})
 save(K/f'assets/gtnh_migration/models/block/{name.lower()}.json',{'parent':'minecraft:block/cube_all','textures':{'all':tex}})
 save(K/f'assets/gtnh_migration/models/item/{name.lower()}.json',{'parent':'gtnh_migration:block/'+name.lower()})
 for loc,values in languages.items():
  orig=lang(S/f'src/main/resources/assets/dreamcraft/lang/{loc}.lang');values[key]=orig.get('tile.'+name+'.name',lang(S/'src/main/resources/assets/dreamcraft/lang/en_US.lang')['tile.'+name+'.name'])
 records.append({'source_enum':enum,'legacy_id':'dreamcraft:'+name,'target_id':id,'hardness':hardness,'explosion_resistance':resistance,'legacy_harvest_level':level,'tool_tag':tooltag,'mineable_tag':'minecraft:mineable/pickaxe','sound':'stone','map_color':'stone','max_stack':64,'burn_ticks':ticks,'source_texture':src.relative_to(S).as_posix(),'target_texture':dst.relative_to(K).as_posix(),'png_sha256':sha(b),'translation_key':key,'runtime_verified':False})
lines.append('});');(K/'startup_scripts/06_gtnh_fuel_blocks.js').write_text('\n'.join(lines)+'\n',encoding='utf-8')
for tag,values in tags.items():ns,path=tag.split(':');save(K/f'data/{ns}/tags/block/{path}.json',{'replace':False,'values':values})
# Original BLOCKS tab icon is stone. Include only actually implemented blocks, never fake missing special blocks.
for loc,d in languages.items():d['itemGroup.gtnh_migration.blocks']=lang(S/f'src/main/resources/assets/dreamcraft/lang/{loc}.lang')['itemGroup.tabDreamCraftBlocks'];save(K/f'assets/gtnh_migration/lang/{loc.lower()}.json',d)
(K/'startup_scripts/07_gtnh_blocks_tab.js').write_text("// Original CoreMod BLOCKS icon and grouping for implemented blocks only.\nStartupEvents.registry('creative_mode_tab', event => {\n  event.create('gtnh_migration:blocks').translationKey('itemGroup.gtnh_migration.blocks').icon(() => Item.of('minecraft:stone')).content(() => "+json.dumps([r['target_id'] for r in records])+');\n});\n',encoding='utf-8')
byenum={r['source_enum']:r['target_id'] for r in records}
# Original Coal unification has explicit vanilla assignment; do not assume charcoal maps to similarly named NH block.
coalmap=(G/'src/main/java/gregtech/loaders/preload/LoaderGTOreDictionary.java').read_text(encoding='utf-8');assert 'GTOreDictUnificator.set(OrePrefixes.block, Materials.Coal, new ItemStack(Blocks.coal_block, 1, 0));' in coalmap
recipes=[];deferred=[]
def item(expr):
 expr=expr.strip();m=re.fullmatch(r'BlockList\.(\w+)\.get\((\d+)\)',expr)
 if m and m.group(1) in byenum:return {'id':byenum[m.group(1)],'count':int(m.group(2)),'source':expr}
 m=re.fullmatch(r'GTOreDictUnificator.get\(OrePrefixes.block, Materials.Coal, (\d+)\)',expr) or re.fullmatch(r'Materials.Coal.getBlocks\((\d+)\)',expr)
 if m:return {'id':'minecraft:coal_block','count':int(m.group(1)),'source':expr}

 m=re.fullmatch(r'getModItem\(Railcraft.ID, "cube", (\d+), 0\)',expr)
 if m:return {'id':'railcraft:coal_coke_block','count':int(m.group(1)),'source':expr,'evidence':'Exact Railcraft 9.17.31 EnumCube.COKE_BLOCK ordinal 0; modern RailcraftBlocks COAL_COKE_BLOCK registration and coal_coke storage tag.'}
 raise ValueError('Source registry mapping not proven: '+expr)
for fn,machine,mapname in [('CompressorRecipes.java','compressor','compressorRecipes'),('ForgeHammerRecipes.java','forge_hammer','hammerRecipes')]:
 p=S/'src/main/java/com/dreammaster/gthandler/recipes'/fn;text=p.read_text(encoding='utf-8')
 for m in re.finditer(r'GTValues\.RA\.stdBuilder\(\).*?\.addTo\(\w+\);',text,re.S):
  chain=m.group()
  if not any(x in chain for x in ['CompressedCharcoal','CompressedCoal']):continue
  pat=r'GTValues\.RA\.stdBuilder\(\)\.itemInputs\((.*?)\)\s*\.itemOutputs\((.*?)\)\s*\.duration\(15 \* SECONDS\)\.eut\(2\)\s*\.addTo\('+mapname+r'\);'
  parsed=re.fullmatch(pat,chain,re.S);assert parsed,chain
  try:a=item(parsed.group(1));b=item(parsed.group(2))
  except ValueError as e:deferred.append({'source_file':fn,'line':text[:m.start()].count('\n')+1,'chain':chain,'reason':str(e)});continue
  rid='gtnh_migration:fuel/'+machine+'/'+a['id'].split(':')[1]+'_to_'+b['id'].split(':')[1]
  recipes.append({'id':rid,'machine':machine,'input':a,'output':b,'ticks':300,'eut':2,'source_file':p.relative_to(S).as_posix(),'source_line':text[:m.start()].count('\n')+1,'source_chain':chain})
assert len(recipes)==28 and len(deferred)==2,(len(recipes),len(deferred))
lookup={(r['input']['id'],r['output']['id']):r for r in recipes}
for r in recipes:
 rev=lookup[(r['output']['id'],r['input']['id'])];assert r['input']['count']==rev['output']['count'] and r['output']['count']==rev['input']['count']
script=['// Exact original 15-second 2 EU/t compression/hammer chains; unresolved charcoal base excluded; Railcraft coke base mapping verified.',"ServerEvents.recipes(event => {"]
for r in recipes:
 stack=lambda a:(str(a['count'])+'x ' if a['count']!=1 else '')+a['id']
 script.append('  event.recipes.gtceu.'+r['machine']+'('+json.dumps(r['id'])+').inputItems('+json.dumps(stack(r['input']))+').outputItems('+json.dumps(stack(r['output']))+').duration(300).EUt(2, 1);')
script.append('});');(K/'server_scripts/12_gtnh_fuel_processing.js').write_text('\n'.join(script)+'\n',encoding='utf-8')
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));ids={r['legacy_id']:r['target_id'] for r in records};mapped=0
for ref in refs:
 a=ref['identity']
 if a['id'] in ids and a['damage']==0 and not a['tag'] and not a['ore_dict']:ref['target_id']=ids[a['id']];ref['mapping_status']='source_fuel_block_defined_not_runtime_verified';mapped+=1
save(O/'docs/quest-migration/item-registry-references.json',refs)
p=O/'docs/quest-migration/coverage.json';cov=json.loads(p.read_text(encoding='utf-8'));cov['mapped_total_item_identities']=sum(x['target_id'] is not None for x in refs);cov['other_identities_unmapped']=sum(x['target_id'] is None for x in refs);cov['mapped_fuel_block_identities']=mapped;save(p,cov)
report={'source_core_version':'2.9.84','blocks':records,'recipes':recipes,'deferred_base_recipes':deferred,'registered_blocks':16,'exact_machine_recipes':28,'max_burn_ticks':max(r['burn_ticks'] for r in records),'mapped_quest_identities':mapped,'vanilla_short_save_risk':True,'full_fuel_parity':False,'risks':['Long furnace burn save and client menu synchronization need explicit compatibility fix and runtime testing.','Source charcoal unification base recipes unresolved; Railcraft cube:0 coke mapping is source-verified.','Cross-mod GT harvesting level behavior may differ despite vanilla tool tags; requires runtime tier tests.']}
save(D/'fuel-blocks.json',report)
# Keep existing integration coverage honest as fuel blocks become implemented.
ip=O/'docs/core-items-migration/item-integrations.json';integr=json.loads(ip.read_text(encoding='utf-8'));integr['fuel_block_definitions']=[{'legacy_id':b['legacy_id'],'id':b['target_id'],'ticks':b['burn_ticks']} for b in records];integr['fuel_entries_deferred']=[x for x in integr.get('fuel_entries_deferred',[]) if x['source']['ItemName'] not in ids];save(ip,integr)
print(json.dumps({k:report[k] for k in ['registered_blocks','exact_machine_recipes','max_burn_ticks','mapped_quest_identities','full_fuel_parity']}))
