"""Prove generated-script checks fail on known migration regressions."""
from pathlib import Path
import json,subprocess,shutil,uuid
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';testroot=W/'migration/test-fixtures'/str(uuid.uuid4());testroot.mkdir(parents=True)
for rel in ['kubejs/startup_scripts','kubejs/server_scripts','kubejs/data/gtnh_migration/recipe/early_game']:
 shutil.copytree(O/rel,testroot/rel)
for rel in ['docs/core-items-migration/core-item-mapping.json','docs/core-items-migration/item-integrations.json','docs/core-recipes-migration/exact-core-recipes.json']:
 dst=testroot/rel;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(O/rel,dst)
cases=[
 ('chunkloader_wrong_stack','kubejs/startup_scripts/00_gtnh_currency.js','.maxStackSize(4)','.maxStackSize(64)','test_exact_core_items.cjs'),
 ('coin_missing_overlay','kubejs/startup_scripts/00_gtnh_currency.js','"layer1":"gtnh_migration:item/coins/icons/chunkloader0"','"layer1":"gtnh_migration:item/coins/icons/WRONG"','test_exact_core_items.cjs'),
 ('assembler_wrong_energy','kubejs/server_scripts/10_gtnh_coin_exchange.js','.EUt(30, 1)','.EUt(32, 1)','test_exact_core_recipes.cjs'),
 ('brick_form_off_by_one','kubejs/startup_scripts/05_gtnh_wooden_brick_form.js','getDamageValue() >= 300','getDamageValue() >= 299','test_wooden_form.cjs')]
results=[]
for name,rel,old,new,test in cases:
 p=testroot/rel;original=p.read_text(encoding='utf-8');assert old in original,(name,old)
 baseline=subprocess.run(['node',str(W/'migration'/test),str(testroot)],capture_output=True,text=True,encoding='utf-8');assert baseline.returncode==0,baseline.stderr
 p.write_text(original.replace(old,new,1),encoding='utf-8')
 try:
  broken=subprocess.run(['node',str(W/'migration'/test),str(testroot)],capture_output=True,text=True,encoding='utf-8');assert broken.returncode!=0,'Test did not detect '+name
  results.append({'case':name,'baseline_passed':True,'mutation_rejected':True,'error':next((l for l in broken.stderr.splitlines() if l.startswith('Error:')),'nonzero exit')})
 finally:p.write_text(original,encoding='utf-8')
report={'cases':results,'scope':'Regression harness sensitivity, not Minecraft runtime','fixtures':str(testroot),'production_inputs_mutated':False}
(O/'docs/verification/mutation-tests.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False),flush=True)
