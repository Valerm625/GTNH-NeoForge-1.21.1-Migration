const fs=require('fs'),vm=require('vm'),path=require('path');const root=path.resolve(process.argv[2]);const spec=JSON.parse(fs.readFileSync(path.join(root,'docs/material-migration/soldering-alloy-adaptation.json'),'utf8'));const startup=fs.readFileSync(path.join(root,spec.startup_script),'utf8'),recipes=fs.readFileSync(path.join(root,spec.recipe_script),'utf8');
function material(initial){let state=initial.map(x=>[...x]),flags=new Set();return {getMaterialComponents(){const arr=state.map(([name,n])=>({material:()=>({getName:()=>name}),amount:()=>n}));return{size:()=>arr.length,get:i=>arr[i]};},setComponents(...args){state=args.map(s=>{const m=/^(\d+)x gtceu:(\w+)$/.exec(s);if(!m)throw Error('Bad or unqualified material stack');return[m[2],Number(m[1])];});},addFlags(f){flags.add(f);},hasFlag(f){return flags.has(f);},inspect:()=>state};}
const alloy=material([['tin',6],['lead',3],['antimony',1]]);const definitions=new Map();let removed=[];
const gtceu = new Proxy({}, {
 get(_, machine) {
  return id => {
   if (definitions.has(id)) throw Error('Duplicate recipe');
   const a={id,machine,circuit:null}; definitions.set(id,a);
   const b={inputItems(...x){a.inputs=x;return b;},outputItems(...x){a.outputs=x;return b;},circuit(x){a.circuit=x;return b;},duration(x){a.duration=x;return b;},EUt(x,amp){a.eut=x;a.amps=amp;return b;}};
   return b;
  };
 }
});
const context={
 GTMaterials:{SolderingAlloy:alloy},
 GTMaterialFlags:{DISABLE_DECOMPOSITION:'disable'},
 GTCEuStartupEvents:{materialModification(fn){fn({});}},
 ServerEvents:{recipes(fn){fn({
  remove(v){if(Object.keys(v).join()!=='id')throw Error('Unscoped removal');removed.push(v.id);},
  recipes:{gtceu}
 });}}
};
vm.runInNewContext(startup,context,{timeout:1000});if(JSON.stringify(alloy.inspect())!==JSON.stringify([['tin',9],['antimony',1]])||!alloy.hasFlag('disable'))throw Error('Composition not corrected');
vm.runInNewContext(startup,context,{timeout:1000}); // idempotent intended state
vm.runInNewContext(recipes,context,{timeout:1000});if(definitions.size!==2||JSON.stringify(removed)!==JSON.stringify(spec.removed_recipe_ids))throw Error('Recipe/removal coverage');
for(const e of spec.recipes){const a=definitions.get(e.id);if(a.machine!==e.machine||JSON.stringify(a.inputs)!==JSON.stringify(e.inputs)||JSON.stringify(a.outputs)!==JSON.stringify(e.outputs)||a.circuit!==e.circuit||a.duration!==e.duration||a.eut!==e.eut||a.amps!==1)throw Error('Recipe mismatch '+e.id);}
// Unknown future mod definition must not silently receive the patch.
let bad=material([['tin',1],['copper',1]]),rejected=false;
try{vm.runInNewContext(startup,{...context,GTMaterials:{SolderingAlloy:bad}},{timeout:1000});}catch(e){rejected=true;}if(!rejected)throw Error('Unknown composition accepted');
// Missing startup adapter must leave recipes untouched.
let untouched=0;rejected=false;try{vm.runInNewContext(recipes,{GTMaterials:{SolderingAlloy:material([['tin',6],['lead',3],['antimony',1]])},GTMaterialFlags:context.GTMaterialFlags,ServerEvents:{recipes(fn){fn({remove(){untouched++;}});}}},{timeout:1000});}catch(e){rejected=true;}if(!rejected||untouched!==0)throw Error('Missing startup did not fail before mutation');
console.log(JSON.stringify({test:'soldering_alloy_source_correction',passed:true,components:{tin:9,antimony:1},recipes:2,unknown_source_rejected:true,missing_startup_stops_before_recipe_changes:true,scope:'Real generated JS with modelled GT API; no game material/recipe initialization has run'}));
