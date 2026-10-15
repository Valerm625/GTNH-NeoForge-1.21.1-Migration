from pathlib import Path
import json,subprocess,hashlib,re,uuid,shutil,zipfile
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/material-migration';spec=json.loads((D/'soldering-alloy-adaptation.json').read_text(encoding='utf-8'))
G=W/'exact-sources/GT5-Unofficial/5.09.54.205';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84'
body=(G/spec['source_material_file']).read_text(encoding='utf-8');start=body.index('private static Materials loadSolderingAlloy()');end=body.index('\n    }',start);chunk=body[start:end]
assert re.findall(r'\.addMaterial\(Materials\.(\w+), (\d+)\)',chunk)==[('Tin','9'),('Antimony','1')]
assert '.addCentrifugeRecipe()' in chunk and '.setDensity(' not in chunk
mixer=(S/spec['source_mixer_file']).read_text(encoding='utf-8');assert spec['source_mixer_chain'] in mixer
assert mixer[:mixer.index(spec['source_mixer_chain'])].count('\n')+1==spec['source_mixer_line']
assert 'Materials.Tin, 9L' in spec['source_mixer_chain'] and 'Materials.Antimony, 1L' in spec['source_mixer_chain'] and '.circuit(1)' in spec['source_mixer_chain']
masses=spec['mass_derivation'];assert masses['elements']=={'Tin':118,'Antimony':121}
assert (9*118+121)//10==118
centrifuge=next(r for r in spec['recipes'] if r['machine']=='centrifuge');assert centrifuge['duration']==118*4*10 and centrifuge['eut']==2*5
assert centrifuge['outputs']==['9x gtceu:tin_dust','gtceu:antimony_dust']
# Source-material API order and whitelist removal IDs are independently rechecked against actual modern source.
with zipfile.ZipFile(W/'cache/gtceu-1.21.1-7.0.2-sources.jar') as z:
 types=z.read('com/gregtechceu/gtceu/data/recipe/GTRecipeTypes.java').decode();assert 'CENTRIFUGE_RECIPES = register("centrifuge"' in types and 'MIXER_RECIPES = register("mixer"' in types
 r=z.read('com/gregtechceu/gtceu/common/recipe/builder/GTRecipeBuilder.java').decode();assert 'id.withPrefix(recipeType.registryName.getPath() + "/")' in r
 decomp=z.read('com/gregtechceu/gtceu/data/recipe/generated/DecompositionRecipeHandler.java').decode();assert 'recipeBuilder("decomposition_centrifuging_", material.getName())' in decomp and 'material.hasFlag(DISABLE_DECOMPOSITION)' in decomp
for p,h in [(spec['startup_script'],spec['startup_sha256']),(spec['recipe_script'],spec['recipe_script_sha256'])]:assert hashlib.sha256((O/p).read_bytes()).hexdigest()==h
result=subprocess.run(['node',str(W/'migration/test_soldering_alloy.cjs'),str(O)],capture_output=True,text=True,encoding='utf-8');assert result.returncode==0,result.stderr;(D/'soldering-contract-test.json').write_text(result.stdout,encoding='utf-8')
fixture=W/'migration/test-fixtures'/('solder-'+str(uuid.uuid4()));fixture.mkdir(parents=True)
for rel in ['docs/material-migration/soldering-alloy-adaptation.json',spec['startup_script'],spec['recipe_script']]:
 p=fixture/rel;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(O/rel,p)
mutations=[]
for name,rel,old,new in [('wrong_tin_ratio',spec['startup_script'],"setComponents('9x tin'","setComponents('8x tin'"),('wrong_decomposition_time',spec['recipe_script'],'.duration(4720)','.duration(1770)'),('missing_mixer_removal',spec['recipe_script'],'event.remove({id: "gtceu:mixer/soldering_alloy"});','// removal accidentally omitted')]:
 p=fixture/rel;s=p.read_text(encoding='utf-8');assert old in s;p.write_text(s.replace(old,new,1),encoding='utf-8')
 try:
  r=subprocess.run(['node',str(W/'migration/test_soldering_alloy.cjs'),str(fixture)],capture_output=True,text=True,encoding='utf-8');assert r.returncode!=0,'missed '+name;mutations.append(name)
 finally:p.write_text(s,encoding='utf-8')
report={'passed':True,'independent_source_ratio':{'Tin':9,'Antimony':1},'original_integer_mass':118,'centrifuge_ticks':4720,'mixer_ticks':100,'recipes_checked':2,'negative_regressions_detected':mutations,'actual_material_initialization_verified':False,'all_physical_and_render_properties_ported':False}
(D/'soldering-independent-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
