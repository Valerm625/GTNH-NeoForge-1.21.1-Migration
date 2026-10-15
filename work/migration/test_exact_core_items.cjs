const fs=require('fs'),vm=require('vm'),path=require('path');
const root=path.resolve(process.argv[2]);
const spec=JSON.parse(fs.readFileSync(path.join(root,'docs/core-items-migration/core-item-mapping.json'),'utf8'));
const integration=JSON.parse(fs.readFileSync(path.join(root,'docs/core-items-migration/item-integrations.json'),'utf8'));
const want=new Map(spec.records.map(r=>[r.target_id,r]));const got=new Map(),tabs=new Map(),modifications=new Map();
function fail(s){throw Error(s)}
const context={
  Item:{of:id=>({item:id})},Text:{translatable:key=>({translate:key})},
  StartupEvents:{registry(type,fn){
    if(type==='item') fn({create(id){
      if(got.has(id)||!want.has(id))fail('Unexpected duplicate item '+id);const r={id};got.set(id,r);
      return {textures(v){r.textures=JSON.parse(JSON.stringify(v));return this;},translationKey(v){r.key=v;return this;},maxStackSize(v){r.stack=v;return this;}};
    }});
    else if(type==='creative_mode_tab')fn({create(id){
      if(tabs.has(id))fail('Duplicate tab '+id);const r={id};tabs.set(id,r);
      return {translationKey(v){r.key=v;return this;},icon(fn){r.icon=fn();return this;},content(fn){r.content=Array.from(fn(false));return this;}};
    }});else fail('Unsupported registry '+type);
  }},
  ItemEvents:{modification(fn){fn({modify(id,callback){
      const r=modifications.get(id)||{tips:[]};modifications.set(id,r);
      const item={tooltip(t){r.tips.push(t.translate);return this;}};
      Object.defineProperty(item,'burnTime',{set(v){r.fuel=v;}});callback(item);
  }});}}
};
for(const name of ['00_gtnh_currency.js','01_gtnh_core_items.js','02_gtnh_creative_tabs.js','03_gtnh_tooltips.js','04_gtnh_fuel_overrides.js']) {
  vm.runInNewContext(fs.readFileSync(path.join(root,'kubejs/startup_scripts',name),'utf8'),context,{timeout:1500});
}
if(got.size!==557||tabs.size!==7)fail('Incomplete output');
for(const [id,r] of got){const e=want.get(id);if(JSON.stringify(r.textures)!==JSON.stringify(e.textures)||r.key!==e.translation_key||r.stack!==e.max_stack_size)fail('Item mismatch '+id);}
for(const e of integration.creative_tabs){const r=tabs.get(e.target_id);if(!r||r.key!==e.translation_key||r.icon.item!==e.icon||JSON.stringify(r.content)!==JSON.stringify(e.items))fail('Tab mismatch '+e.target_id);}
for(const t of integration.tooltip_lines){if(!modifications.get(t.target_id)?.tips.includes(t.translation_key))fail('Missing tip '+t.target_id);}
for(const f of integration.fuel_overrides){if(modifications.get(f.id)?.fuel!==f.ticks)fail('Fuel mismatch '+f.id);}
if(got.get('gtnh_migration:coinchunkloadertieri').stack!==4)fail('Regression: chunkloader coin stack limit');
if(got.get('gtnh_migration:medalderp').stack!==1)fail('Regression: medal stack limit');
if(!got.get('gtnh_migration:coinadventure').textures.layer1)fail('Regression: coin lost second render layer');
console.log(JSON.stringify({test:'exact_core_item_scripts',passed:true,items:got.size,creative_tabs:tabs.size,tooltip_lines:integration.tooltip_lines.length,fuels:integration.fuel_overrides.length,scope:'actual generated scripts executed against recording API stubs, NOT game initialization/render verification'}));
