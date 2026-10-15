const fs=require('fs'),vm=require('vm'),path=require('path');
const root=path.resolve(process.argv[2]);
const spec=JSON.parse(fs.readFileSync(path.join(root,'docs/core-recipes-migration/exact-core-recipes.json'),'utf8'));
const expected=new Map(spec.records.map(r=>[r.id,r]));const actual=new Map();let calls=0;
const gtceu=new Proxy({}, {get(_,machine){return id=>{
 if(actual.has(id)||!expected.has(id))throw Error('Duplicate/unexpected recipe '+id);
 const row={id,machine,inputs:[],outputs:[],tools:[],circuit:null};actual.set(id,row);
 return {inputItems(...v){row.inputs.push(...v);return this;},outputItems(...v){row.outputs.push(...v);return this;},notConsumableItem(v){row.tools.push(v);return this;},circuit(v){row.circuit=v;return this;},duration(v){row.duration=v;return this;},EUt(v,a){row.eu=v;row.amps=a;return this;}};
}}});
const context={ServerEvents:{recipes(fn){calls++;fn({recipes:{gtceu}});}}};
for(const name of ['10_gtnh_coin_exchange.js','11_gtnh_core_processing.js']) vm.runInNewContext(fs.readFileSync(path.join(root,'kubejs/server_scripts',name),'utf8'),context,{timeout:1000});
if(calls!==2||actual.size!==expected.size)throw Error('Incomplete recipe script');
const stack=x=>(x.count===1?'':x.count+'x ')+x.id;
for(const [id,a] of actual){const e=expected.get(id);const inp=e.inputs.filter(x=>x.count>0).map(stack),tools=e.inputs.filter(x=>x.count===0).map(x=>x.id),out=e.outputs.map(stack);
 if(a.machine!==e.machine||JSON.stringify(a.inputs)!==JSON.stringify(inp)||JSON.stringify(a.outputs)!==JSON.stringify(out)||JSON.stringify(a.tools)!==JSON.stringify(tools)||a.circuit!==e.circuit||a.duration!==e.duration_ticks||a.eu!==e.eu_per_tick||a.amps!==1)throw Error('Source value mismatch '+id);
}
console.log(JSON.stringify({test:'exact_source_core_recipes',recipes:actual.size,coin_recipes:112,additional_core_recipes:15,passed:true,scope:'JS API recording stubs and source-transcribed values; NOT GTCEu runtime integration'}));
