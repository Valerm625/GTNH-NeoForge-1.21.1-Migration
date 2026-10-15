from pathlib import Path
import json,re,zipfile,hashlib,shutil
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/enderstorage-migration';D.mkdir(exist_ok=True)
old=W/'source/GT-New-Horizons-Modpack-master/config/EnderStorage.cfg';vals={}
for line in old.read_text(encoding='utf-8').splitlines():
 if line and not line.startswith('#') and '=' in line:k,v=line.split('=',1);vals[k]=v
S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';p=S/'src/main/java/com/dreammaster/scripts/ScriptTCCoreMod.java';text=p.read_text(encoding='utf-8');gates=[]
for key,meta in [('ENDERCHEST',0),('ENDERTANK',4096),('ENDERPOUCHE',0)]:
 pattern=r'arcaneRecipe = ThaumcraftApi.addArcaneCraftingRecipe\(\s*"'+key+r'",(.*?)\);'
 m=re.search(pattern,text,re.S);assert m;gates.append({'research':key,'source_line':text[:m.start()].count('\n')+1,'source_recipe':m.group(),'state':'not ported; cannot replace research/vis gate by ordinary shaped recipe'})
jar=O/'mods/EnderStorage-1.21.1-2.13.0.191.jar'
with zipfile.ZipFile(jar) as z:modern={n:json.loads(z.read(n)) for n in z.namelist() if n.startswith('data/enderstorage/recipe/') and n.endswith('.json')}
report={'legacy_config':str(old),'legacy_values':vals,'modern_config': 'config/EnderStorage.cfg','migrated_supported_settings':{'personalItem':'minecraft:diamond','anarchyMode':False,'item_storage_size':1},'capacity':{'legacy_buckets':256,'modern_unpatched_buckets':16,'compatibility_module':'gtnh-migration-compat-0.1.0.jar','pinned_target':'2.13.0.191','compiled_test_passed':True,'actual_game_verified':False},'unsupported_setting_semantics':['Legacy disable-vanilla_recipe=true is a progression rule, not present in modern configuration; must coordinate original replacement recipe before claiming parity.','Legacy disableFXChest/disableFXTank flags are not equivalent to modern disableCreatorVisuals; no blind translation.','Modern chest/tank/pouch ordinary creation recipes conflict with original Thaumcraft research/vis gated progression; exact replacements remain unimplemented.'],'unimplemented_research_gates':gates,'modern_recipe_evidence':modern,'full_feature_parity':False,'not_changed':'Third-party mod JAR unchanged. New module contains only local adaptation code. Original proprietary sources not repackaged as a modified mod.'}
(D/'compatibility-differences.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(D/'README.md').write_text('''# Ender Storage compatibility — incomplete

Modern EnderStorage 2.13.0.191 is an actual NeoForge 1.21.1 artifact, but not a drop-in GTNH-equivalent.

Implemented: original diamond privacy key, anarchy=false, 3x9 item frequency storage config using actual CodeChickenLib serializer/readback; a separately compiled version-pinned mixin changes the shared EnderLiquidStorage bucket capacity from 16 to original 256. Target constant shape and compiled handler/boundary checks pass, but actual mixin transformation and live storage fill/drain/save/sync are untested.

Not implemented: original Thaumcraft research/vis gates and custom recipes, vanilla chest recipe disable/replacement, color/frequency ownership/multiplayer behavior regression, effects setting parity. Default modern recipes are not counted as migrated GTNH progression. This is a test candidate, not a gameplay-complete subsystem.

No patch is applied directly to third-party JARs. See compatibility-differences.json and ../compat-mod.lock.json.
''',encoding='utf-8')
# Include the independently authored adapter license/source; original source references remain separate.
lic=O/'licenses/GTNH-Migration-Compat';lic.mkdir(exist_ok=True);shutil.copy2(W/'compat-mod/src/main/resources/LICENSE',lic/'LICENSE')
(O/'tools/compat-mod/README.md').write_text('''# Local compatibility module

Source and resources are here. Build and test entry points are in tools/migration-sources/build_capacity_compat.py and verify_capacity_compat.py; these scripts expect the original work/ and outputs/ directory layout, not this copied source folder as project root.

The original EnderStorage JAR remains unchanged. Adapter pins 2.13.0.191 and restores 256 buckets from supplied config via ModifyConstant in static initialization (exactly one match required). Handler and target bytecode checks passed. Mod discovery passed; target-class transformation and gameplay not yet verified. Do not call it a fully tested port.
''',encoding='utf-8')
print('ENDER_DIFFERENCES_AND_SOURCE_PROVENANCE_WRITTEN')
