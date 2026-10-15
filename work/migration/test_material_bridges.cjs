const fs=require('fs'),vm=require('vm'),path=require('path');
const root=path.resolve(process.argv[2]);const spec=JSON.parse(fs.readFileSync(path.join(root,'docs/material-migration/material-bridge-recipes.json'),'utf8'));const expected=new Map(spec.records.map(r=>[r.id,r]));const actual=new Map();
const gtceu=new Proxy({},{get(_,machine){return id=>{
 if(!expected.has(id)||actual.has(id))throw Error('Unexpected recipe '+id);
 const r={machine,inputs:[],outputs:[],tools:[],circuit:null};actual.set(id,r);
 const c={inputItems(...v){r.inputs.push(...v);return c;},outputItems(...v){r.outputs.push(...v);return c;},notConsumableItem(v){r.tools.push(v);return c;},circuit(v){r.circuit=v;return c;},duration(v){r.duration=v;return c;},EUt(v,a){r.eut=v;r.amps=a;return c;}};return c;
}}});
const context={ServerEvents:{recipes(fn){fn({recipes:{gtceu}});}}};
vm.runInNewContext(fs.readFileSync(path.join(root,'kubejs/server_scripts/14_gtnh_material_bridges.js'),'utf8'),context,{timeout:1500});
if(actual.size!==expected.size)throw Error('Incomplete material bridge script');
const stack=(x,input)=>(x.count===1?'':x.count+'x ')+(input?(x.ingredient||x.id):x.id);
for(const [id,a] of actual){const e=expected.get(id);
 if(a.machine!==e.machine||a.duration!==e.duration_ticks||a.eut!==e.eu_per_tick||a.amps!==1||a.circuit!==e.circuit)throw Error('Recipe numeric mismatch '+id);
 if(JSON.stringify(a.inputs)!==JSON.stringify(e.inputs.filter(x=>x.count>0).map(x=>stack(x,true))))throw Error('Inputs/unification narrowed '+id);
 if(JSON.stringify(a.outputs)!==JSON.stringify(e.outputs.map(x=>stack(x,false))))throw Error('Output mismatch '+id);
 if(JSON.stringify(a.tools)!==JSON.stringify(e.inputs.filter(x=>x.count===0).map(x=>x.ingredient||x.id)))throw Error('Tool consumption mismatch '+id);
 for(const x of e.inputs)if(x.kind==='material'&&!x.ingredient.startsWith('#c:'))throw Error('Material unification lost');
}
console.log(JSON.stringify({test:'material_bridge_recipe_contract',passed:true,recipes:actual.size,unification_tags_preserved:true,scope:'Actual generated JS against recording API; no Minecraft recipe reload or GT machine test'}));
