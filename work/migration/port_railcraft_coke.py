"""Preserve original coke packing progression while using an actual Railcraft port."""
from pathlib import Path
import json,re,zipfile,hashlib,shutil,io
from PIL import Image
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';OLD=W/'exact-sources/Railcraft/9.17.31';D=O/'docs/railcraft-migration';D.mkdir(exist_ok=True)
def save(p,x):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,ensure_ascii=False,indent=2),encoding='utf-8')
comp=S/'src/main/java/com/dreammaster/gthandler/recipes/CompressorRecipes.java';text=comp.read_text(encoding='utf-8')
pat=r'GTValues\.RA\.stdBuilder\(\)\.itemInputs\(getModItem\(Railcraft.ID, "fuel.coke", 9, 0\)\)\s*\.itemOutputs\(getModItem\(Railcraft.ID, "cube", 1, 0\)\)\.duration\(15 \* SECONDS\)\.eut\(2\)\s*\.addTo\(compressorRecipes\);';match=re.search(pat,text,re.S);assert match
packing={'id':'gtnh_migration:railcraft/pack_coal_coke','machine':'compressor','input':'railcraft:coal_coke','input_count':9,'output':'railcraft:coal_coke_block','output_count':1,'ticks':300,'eut':2,'source_file':comp.relative_to(S).as_posix(),'source_line':text[:match.start()].count('\n')+1,'source_chain':match.group()}
p=S/'src/main/java/com/dreammaster/scripts/ScriptRailcraft.java';text=p.read_text(encoding='utf-8')
pat=r'GTValues\.RA\.stdBuilder\(\)\.itemInputs\(getModItem\(Railcraft.ID, "cube", 1, 0\)\)\s*\.itemOutputs\(getModItem\(Railcraft.ID, "fuel.coke", 9, 0\)\)\.duration\(5 \* SECONDS\)\.eut\(24\)\s*\.addTo\(hammerRecipes\);';m=re.search(pat,text,re.S);assert m
unpacking={'id':'gtnh_migration:railcraft/unpack_coal_coke','machine':'forge_hammer','input':'railcraft:coal_coke_block','input_count':1,'output':'railcraft:coal_coke','output_count':9,'ticks':100,'eut':24,'source_file':p.relative_to(S).as_posix(),'source_line':text[:m.start()].count('\n')+1,'source_chain':m.group(),'dependency_note':'Original ScriptRailcraft is loaded with cross-mod script dependencies; whole-mod integration remains incomplete.'}
remover=(S/'src/main/java/com/dreammaster/recipes/RecipeRemover.java').read_text(encoding='utf-8');assert 'removeRecipeByOutputDelayed(getModItem(Railcraft.ID, "cube", 1, 0));' in remover and 'removeRecipeByOutputDelayed(getModItem(Railcraft.ID, "fuel.coke", 1, 0));' in remover
enum=(OLD/'src/main/java/mods/railcraft/common/blocks/aesthetics/cube/EnumCube.java').read_text(encoding='utf-8');assert re.search(r'public enum EnumCube\s*\{\s*COKE_BLOCK\(',enum) and 'new ItemStack(BlockCube.getBlock(), qty, ordinal())' in enum
jar=O/'mods/railcraft-reborn-1.21.1-1.2.12.jar';removals=['railcraft:coal_coke','railcraft:coal_coke_block_from_coal_coke'];originals={}
with zipfile.ZipFile(jar) as z:
 for id in removals:
  path='data/railcraft/recipe/'+id.split(':')[1]+'.json';d=json.loads(z.read(path));assert d['type'] in ['minecraft:crafting_shaped','minecraft:crafting_shapeless'];originals[id]=d
 assert json.loads(z.read('data/c/tags/item/storage_blocks/coal_coke.json'))['values']==['railcraft:coal_coke_block']
lines=['// Original GTNH replaces free crafting coke packing/unpacking with machines.', '// Remove only these two verified default crafting recipe IDs, not coke-oven production.',"ServerEvents.recipes(event => {"]
for id in removals:lines.append('  event.remove({id: '+json.dumps(id)+'});')
for r in [packing,unpacking]:
 stack=lambda id,n:(str(n)+'x ' if n!=1 else '')+id
 lines.append('  event.recipes.gtceu.'+r['machine']+'('+json.dumps(r['id'])+').inputItems('+json.dumps(stack(r['input'],r['input_count']))+').outputItems('+json.dumps(stack(r['output'],r['output_count']))+f").duration({r['ticks']}).EUt({r['eut']}, 1);")
lines.append('});');(K/'server_scripts/13_gtnh_railcraft_coke.js').write_text('\n'.join(lines)+'\n',encoding='utf-8')
# Matching original imagery only; preserve byte content and publish required attribution for texture-pack use.
pack=O/'resourcepacks/GTNH-Railcraft-Coke-Assets-1.21.1';pack.mkdir(exist_ok=True);save(pack/'pack.mcmeta',{'pack':{'pack_format':34,'description':'Original Railcraft 9.17.31 coke assets for Railcraft Reborn; preview, in-game render unverified'}})
assets=[]
for src,tgt in [('src/main/resources/assets/railcraft/textures/blocks/cube.coke.png','assets/railcraft/textures/block/coal_coke_block.png'),('src/main/resources/assets/railcraft/textures/items/fuel.coke.png','assets/railcraft/textures/item/coal_coke.png')]:
 b=(OLD/src).read_bytes()
 with Image.open(io.BytesIO(b)) as im:im.verify()
 dst=pack/tgt;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(b)
 assets.append({'source':src,'target':tgt,'sha256':hashlib.sha256(b).hexdigest()})
shutil.copy2(OLD/'LICENSE.md',pack/'LICENSE-Railcraft.md')
(pack/'ATTRIBUTION.txt').write_text('Original Railcraft assets by CovertJaguar and Railcraft contributors. Railcraft Blog: http://railcraft.info/\nOriginal source: https://github.com/GTNewHorizons/Railcraft/tree/9.17.31\nThis resource pack is intended solely for use alongside Railcraft Reborn. It contains unchanged original PNGs mapped to modern texture paths; no Railcraft code or modified mod JAR is redistributed here. See LICENSE-Railcraft.md, Derivatives/Source sections. Public distribution of the complete modpack requires separate license review.\n',encoding='utf-8')
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));mapping={('Railcraft:cube',0):'railcraft:coal_coke_block',('Railcraft:fuel.coke',0):'railcraft:coal_coke'}
for ref in refs:
 x=ref['identity'];key=(x['id'],x['damage'])
 if key in mapping and not x['tag'] and not x['ore_dict']:ref['target_id']=mapping[key];ref['mapping_status']='exact_legacy_coke_identity_to_modern_registry_not_full_gameplay_verified'
save(O/'docs/quest-migration/item-registry-references.json',refs);p=O/'docs/quest-migration/coverage.json';c=json.loads(p.read_text(encoding='utf-8'));c['mapped_total_item_identities']=sum(x['target_id'] is not None for x in refs);c['other_identities_unmapped']=sum(x['target_id'] is None for x in refs);save(p,c)
# Don't silently assert default coking production parameters are equivalent.
module=(OLD/'src/main/java/mods/railcraft/common/modules/ModuleFactory.java').read_text(encoding='utf-8');assert 'COKE_COOK_TIME = 1800;' in module and 'COKE_COOK_CREOSOTE = 500;' in module
with zipfile.ZipFile(jar) as z:modern_coking={n:json.loads(z.read(n)) for n in ['data/railcraft/recipe/coke_oven/charcoal.json','data/railcraft/recipe/coke_oven/coal_coke_block.json']}
report={'source_railcraft_version':'9.17.31','modern_railcraft_version':'1.2.12','legacy_coke_block_metadata':0,'verified_item_mappings':[{'legacy':id,'damage':damage,'modern':target} for (id,damage),target in mapping.items()],'recipes':[packing,unpacking],'removed_modern_crafting_recipes':originals,'texture_assets':assets,'full_parity':False,'runtime_verified':False,'known_unresolved_coking_differences':{'original_base_time':1800,'original_block_time_expression':'1800 * 9','original_block_creosote':4500,'modern_recipe_json':modern_coking,'status':'Not patched yet; original/modern timer units, original GTNH replacement coke oven and processor speed require separate audit.'},'other_pending':['Steam/boilers, tracks/signals, cart fluids/items, GTNH recipe gates, modern extra-content progression, source/source-license differences.']}
save(D/'coke-bridge.json',report);print(json.dumps({'coke_machine_recipes':2,'default_crafting_bypasses_removed':2,'source_textures':2,'quest_mapping_added':2,'remaining_coking_differences_recorded':True}))
