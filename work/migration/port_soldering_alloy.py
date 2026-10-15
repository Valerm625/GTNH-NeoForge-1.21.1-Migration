"""Source-backed solder alloy adjustment with matching production/decomposition recipes."""
from pathlib import Path
import json,re,hashlib,zipfile
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs';D=O/'docs/material-migration';G=W/'exact-sources/GT5-Unofficial/5.09.54.205';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84'
def save(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
source=G/'src/main/java/gregtech/loaders/materials/MaterialsInit.java';text=source.read_text(encoding='utf-8');m=re.search(r'private static Materials loadSolderingAlloy\(\)\s*\{(.*?)\n    \}',text,re.S);assert m;body=m.group(1)
components={name:int(n) for name,n in re.findall(r'\.addMaterial\(Materials\.(\w+), (\d+)\)',body)};assert components=={'Tin':9,'Antimony':1};assert '.addCentrifugeRecipe()' in body and '.setDensity(' not in body
srcmix=S/'src/main/java/com/dreammaster/gthandler/recipes/MixerRecipes.java';mixtext=srcmix.read_text(encoding='utf-8');pat=r'GTValues\.RA\.stdBuilder\(\)\s*\.itemInputs\(\s*GTOreDictUnificator.get\(OrePrefixes.dust, Materials.Tin, 9L\),\s*GTOreDictUnificator.get\(OrePrefixes.dust, Materials.Antimony, 1L\)\)\s*\.circuit\(1\)\.itemOutputs\(GTOreDictUnificator.get\(OrePrefixes.dust, Materials.SolderingAlloy, 10L\)\)\s*\.duration\(5 \* SECONDS\)\.eut\(TierEU.RECIPE_ULV\)\.addTo\(mixerRecipes\);';mixmatch=re.search(pat,mixtext,re.S);assert mixmatch
# Derive exact original mass/duration formula; no new performance assumption.
elements=(G/'src/main/java/gregtech/api/enums/Element.java').read_text(encoding='utf-8');masses={}
for element,mat in [('Sn','Tin'),('Sb','Antimony')]:
 mm=re.search(r'^\s*'+element+r'\((\d+), (\d+), (\d+),',elements,re.M);assert mm;masses[mat]=sum(map(int,mm.groups()))
material=(G/'src/main/java/gregtech/api/enums/Materials.java').read_text(encoding='utf-8');assert 'return (getDensity() * rAmount) / (tAmount * M);' in material
builder=(G/'src/main/java/gregtech/api/enums/MaterialBuilder.java').read_text(encoding='utf-8');assert 'densityMultiplier = 1;' in builder and 'densityDivider = 1;' in builder
processing=(G/'src/main/java/gregtech/loaders/oreprocessing/ProcessingDust.java').read_text(encoding='utf-8');assert 'Math.abs(aMaterial.getMass() * 4L * tItemAmount)' in processing and '.eut(Math.min(4, tList.size()) * 5)' in processing
assert '.addMaterial(Materials.Tin, 9)' in body and '.addMaterial(Materials.Antimony, 1)' in body
for file in ['gtnhlanth/util/LanthanidesRecipeOutputs.java','goodgenerator/util/NaquadahRecipeOutputs.java']:
 t=(G/'src/main/java'/file).read_text(encoding='utf-8');assert not re.search(r'Materials\.(Tin|Antimony|SolderingAlloy)\b',t)
total=sum(components.values());mass=sum(masses[k]*v for k,v in components.items())//total;duration=mass*4*total;eu=min(4,len(components))*5;assert (mass,duration,eu)==(118,4720,10)
# Verify supported modern API and hook execution order before emitting live adaptation code.
with zipfile.ZipFile(W/'cache/gtceu-1.21.1-7.0.2-sources.jar') as z:
 modern=z.read('com/gregtechceu/gtceu/data/material/FirstDegreeMaterials.java').decode();mm=re.search(r'SolderingAlloy = new Material.Builder.*?\.buildAndRegister\(\);',modern,re.S);assert '.components(Tin, 6, Lead, 3, Antimony, 1)' in mm.group()
 init=z.read('com/gregtechceu/gtceu/common/CommonInit.java').decode();assert init.index('KJSEventWrapper.materialModification();')<init.index('GTFluids.generateMaterialFluids();')
 types=z.read('com/gregtechceu/gtceu/data/recipe/serialized/chemistry/MixerRecipes.java').decode();assert 'recipeBuilder("soldering_alloy")' in types
 mclass=z.read('com/gregtechceu/gtceu/api/material/material/Material.java').decode();assert 'Material setComponents(MaterialStack... components)' in mclass and 'void addFlags(MaterialFlag... flags)' in mclass
 report_default={'Tin':6,'Lead':3,'Antimony':1}
# Modern defaults and corrected original formula are explicitly guarded rather than silently mutating an unknown version.
js='''// Source: GT5U 5.09.54.205 loadSolderingAlloy; CoreMod2.9.84 mixer.
// Composition + recipe correction only. Temperature, hazards, colors, full metallurgy remain separate.
GTCEuStartupEvents.materialModification(event => {
  const material = GTMaterials.SolderingAlloy;
  const current = material.getMaterialComponents();
  const signature = [];
  for (let i = 0; i < current.size(); i++) {
    const component = current.get(i);
    signature.push(String(component.material().getName()) + ':' + String(component.amount()));
  }
  const actual = signature.join(',');
  if (actual !== 'tin:6,lead:3,antimony:1' && actual !== 'tin:9,antimony:1') {
    throw new Error('[GTNH migration] Unreviewed soldering alloy definition: ' + actual);
  }
  material.setComponents('9x tin', '1x antimony');
  // Keep generic modern decomposition from disagreeing with the explicit original recipe below.
  material.addFlags(GTMaterialFlags.DISABLE_DECOMPOSITION);
});
'''
startup=K/'startup_scripts/08_gtnh_soldering_composition.js';startup.write_text(js,encoding='utf-8')
recipes=[{'id':'gtnh_migration:material_parity/soldering_alloy_mixer','machine':'mixer','inputs':['9x #c:dusts/tin','#c:dusts/antimony'],'outputs':['10x gtceu:soldering_alloy_dust'],'circuit':1,'duration':100,'eut':7},{'id':'gtnh_migration:material_parity/soldering_alloy_centrifuge','machine':'centrifuge','inputs':['10x #c:dusts/soldering_alloy'],'outputs':['9x gtceu:tin_dust','gtceu:antimony_dust'],'circuit':None,'duration':duration,'eut':eu}]
remove=['gtceu:mixer/soldering_alloy','gtceu:centrifuge/decomposition_centrifuging__soldering_alloy','gtceu:electrolyzer/decomposition_electrolyzing_soldering_alloy']
lines=['// Exact-source soldering alloy production/decomposition. No global material recipe removals.',"ServerEvents.recipes(event => {"]
lines.extend([
 '  const solder = GTMaterials.SolderingAlloy;',
 '  const parts = solder.getMaterialComponents();',
 '  const signature = [];',
 '  for (let i = 0; i < parts.size(); i++) signature.push(String(parts.get(i).material().getName()) + ":" + String(parts.get(i).amount()));',
 '  if (signature.join(",") !== "tin:9,antimony:1" || !solder.hasFlag(GTMaterialFlags.DISABLE_DECOMPOSITION)) {',
 '    throw new Error("[GTNH migration] Refusing solder recipe changes: startup composition adapter did not apply.");',
 '  }'
])
for id in remove:lines.append('  event.remove({id: '+json.dumps(id)+'});')
for r in recipes:
 lines.append('  event.recipes.gtceu.'+r['machine']+'('+json.dumps(r['id'])+')')
 lines.append('    .inputItems('+', '.join(map(json.dumps,r['inputs']))+').outputItems('+', '.join(map(json.dumps,r['outputs']))+')')
 if r['circuit'] is not None:lines.append('    .circuit('+str(r['circuit'])+')')
 lines.append(f"    .duration({r['duration']}).EUt({r['eut']}, 1);")
lines.append('});');recipefile=K/'server_scripts/15_gtnh_soldering_parity.js';recipefile.write_text('\n'.join(lines)+'\n',encoding='utf-8')
report={'original_material':'SolderingAlloy','target_symbol':'SolderingAlloy','target_id':'soldering_alloy','original_components':components,'modern_default_components':report_default,'configured_components':components,'source_material_file':source.relative_to(G).as_posix(),'source_material_line':text[:m.start()].count('\n')+1,'source_builder':body,'source_mixer_file':srcmix.relative_to(S).as_posix(),'source_mixer_line':mixtext[:mixmatch.start()].count('\n')+1,'source_mixer_chain':mixmatch.group(),'mass_derivation':{'elements':masses,'total_component_amount':total,'integer_weighted_mass':mass,'centrifuge_duration_formula':'max(1, abs(mass * 4 * inputAmount))','centrifuge_eu_formula':'min(4, itemOutputSlots) * 5'},'startup_script':startup.relative_to(O).as_posix(),'startup_sha256':hashlib.sha256(startup.read_bytes()).hexdigest(),'recipe_script':recipefile.relative_to(O).as_posix(),'recipe_script_sha256':hashlib.sha256(recipefile.read_bytes()).hexdigest(),'recipes':recipes,'removed_recipe_ids':remove,'runtime_event_executed':False,'full_solder_material_parity':False,'limitations':['No claim of original melting-temperature/visual/hazard behavior yet.','Generic modern decomposition disabled for this material only, replaced with the exact original centrifuge definition.','Other default recycling/casting uses corrected components but all recipes/fluids need runtime regression.']}
save(D/'soldering-alloy-adaptation.json',report);print(json.dumps({'source_composition':components,'original_mass':mass,'mixer_ticks':100,'centrifuge_ticks':duration,'centrifuge_eut':eu,'runtime_verified':False}))
