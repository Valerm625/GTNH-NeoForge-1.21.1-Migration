"""Independent checks from pinned source; do not trust generated manifest counts alone."""
from pathlib import Path
import json,re,hashlib,xml.etree.ElementTree as ET
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84'
source=(S/'src/main/java/com/dreammaster/item/NHItemList.java').read_text(encoding='utf-8');enum_body=source.split('public enum NHItemList {',1)[1].split('    static {',1)[0]
expected={}
for line in enum_body.splitlines():
    m=re.match(r'\s*(\w+)\("([^"]+)"\s*,\s*(.*)\)[,;]',line)
    if not m:continue
    enum,name,args=m.groups()
    if args.startswith('new ') and not args.startswith('new CoinItem('):continue
    expected['dreamcraft:'+name]={'enum':enum,'args':args}
manifest=json.loads((O/'docs/core-items-migration/core-item-mapping.json').read_text(encoding='utf-8'));actual={r['legacy_id']:r for r in manifest['records']};assert actual.keys()==expected.keys(),(len(actual),len(expected))
for id,r in actual.items():
    e=expected[id];assert r['enum_name']==e['enum']
    stack_m=re.search(r'\b'+re.escape(e['enum'])+r'\.item\.setMaxStackSize\((\d+)\)',source)
    assert r['max_stack_size']==(int(stack_m.group(1)) if stack_m else 64)
    if e['args'].startswith('new CoinItem('):
        m=re.search(r'CoinItem\((null|"[^"]+"),\s*(\d+)\)',e['args']);typ=json.loads(m.group(1));tier=int(m.group(2))
        assert r['textures']['layer0']==f'gtnh_migration:item/coins/background/big{tier}'
        assert ('layer1' in r['textures'])==(typ is not None)
        if typ:assert r['textures']['layer1']==f'gtnh_migration:item/coins/icons/{typ}{tier}'
    for a in r['assets']:
        original=(S/a['source_texture']).read_bytes();dest=(O/'kubejs'/a['target_texture']).read_bytes();assert original==dest
        if a['animation']:assert (S/a['source_texture']).with_suffix('.png.mcmeta').read_bytes()==(O/'kubejs'/a['target_texture']).with_suffix('.png.mcmeta').read_bytes()
# Independent simple regex verifies the complete exact coin recipe method against generated output.
s=(S/'src/main/java/com/dreammaster/gthandler/recipes/AssemblerRecipes.java').read_text(encoding='utf-8');start=s.index('private void makeCoinRecipes()');end=s.index('\n    private ',start+10);body=s[start:end]
pat=r'GTValues\.RA\.stdBuilder\(\)\.itemInputs\(NHItemList\.(Coin\w+)\.get\((\d*)\)\)\.circuit\((\d+)\)\s*\.itemOutputs\(NHItemList\.(Coin\w+)\.get\((\d*)\)\)\.duration\(5 \* SECONDS\)\.eut\(TierEU\.RECIPE_(LV|MV|HV|EV)\)\s*\.addTo\(assemblerRecipes\);'
rows=re.findall(pat,body);assert len(rows)==112 and body.count('.addTo(assemblerRecipes)')==112
recipes=json.loads((O/'docs/core-recipes-migration/exact-core-recipes.json').read_text(encoding='utf-8'))['records'];coins=[r for r in recipes if r['is_coin_exchange']];by_pair={(r['inputs'][0]['source_enum'],r['outputs'][0]['source_enum']):r for r in coins}
for src,inc,circuit,dst,outc,tier in rows:
    r=by_pair[(src,dst)];assert (r['inputs'][0]['count'],r['outputs'][0]['count'],r['circuit'],r['duration_ticks'],r['eu_per_tick'])==(int(inc or 1),int(outc or 1),int(circuit),100,{'LV':30,'MV':120,'HV':480,'EV':1920}[tier])
# All original conditional XML properties remain explicitly recorded as unresolved, not hidden by plain registration.
xml=W/'source/GT-New-Horizons-Modpack-master/config/GTNewHorizons/HazardousItems.xml';pending=[]
for element in ET.parse(xml).getroot():
    if element.attrib.get('ItemName') in actual:
        pending.append({'item':element.attrib['ItemName'],'target_id':actual[element.attrib['ItemName']]['target_id'],'source_xml':ET.tostring(element,encoding='unicode'),'state':'not implemented','reason':'Requires original timing, player inventory/contact rules, hazard-protection integration and original potion-ID mapping. Item/recipe registration is not behavior parity.'})
(O/'docs/core-items-migration/pending-item-hazards.json').write_text(json.dumps(pending,ensure_ascii=False,indent=2),encoding='utf-8')
report={'passed':True,'exact_source_item_names_checked':len(expected),'stack_limits_checked':len(actual),'original_png_and_animation_bytes_equal':True,'independently_parsed_coin_recipes':len(rows),'pending_item_hazard_behaviors':len(pending),'scope':'Static exact-source reconciliation, not runtime validation'}
(O/'docs/verification/independent-source-check.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
