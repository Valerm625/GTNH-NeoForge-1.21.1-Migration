from pathlib import Path
import json,re,collections,xml.etree.ElementTree as ET,hashlib
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';MASTER=W/'source/GT-New-Horizons-Modpack-master'
D=O/'docs/core-items-migration';data=json.loads((D/'core-item-mapping.json').read_text(encoding='utf-8'));items=data['records'];byenum={r['enum_name']:r for r in items};byid={r['legacy_id']:r for r in items}
def lang(p):
 d={}
 for line in p.read_text(encoding='utf-8-sig').splitlines():
  if line and not line.startswith('#') and '=' in line:k,v=line.split('=',1);d[k.strip()]=v
 return d
oldlangs={loc:lang(S/f'src/main/resources/assets/dreamcraft/lang/{loc}.lang') for loc in ['en_US','zh_CN']};langs={loc:json.loads((K/f'assets/gtnh_migration/lang/{loc}.json').read_text(encoding='utf-8')) for loc in ['en_us','zh_cn']}
s=(S/'src/main/java/com/dreammaster/creativetab/ModTabList.java').read_text(encoding='utf-8');chunks=re.split(r'(?=    public static final CreativeTabs )',s)[1:];tabs=[]
for ch in chunks:
 tab=re.search(r'CreativeTabs (\w+)',ch).group(1);icons=re.findall(r'return NHItemList\.(\w+)\.item;',ch)
 selected=[r['target_id'] for r in items if r['source_creative_tab']==tab]
 if not selected:continue
 assert len(icons)==1 and icons[0] in byenum
 label=re.search(r'new CreativeTabs\("([^"]+)"',ch).group(1);key='itemGroup.gtnh_migration.'+tab.lower()
 for loc in ['en_US','zh_CN']:langs[loc.lower()][key]=oldlangs[loc].get('itemGroup.'+label,oldlangs['en_US']['itemGroup.'+label])
 tabs.append({'source_tab':tab,'target_id':'gtnh_migration:'+tab.lower(),'icon':byenum[icons[0]]['target_id'],'translation_key':key,'items':selected,'scope':'Only implemented original items, specialized item and block entries not invented.'})
assert len(tabs)==7 and sum(len(t['items']) for t in tabs)==557
lines=['// Original NHItemList creative group membership; missing special items remain excluded, not replaced.',"StartupEvents.registry('creative_mode_tab', event => {"]
for t in tabs:
 lines+=['  event.create('+json.dumps(t['target_id'])+').translationKey('+json.dumps(t['translation_key'])+')','    .icon(() => Item.of('+json.dumps(t['icon'])+'))','    .content(() => '+json.dumps(t['items'],separators=(',',':'))+');']
lines.append('});');(K/'startup_scripts/02_gtnh_creative_tabs.js').write_text('\n'.join(lines)+'\n',encoding='utf-8')
source_tips=MASTER/'config/GTNewHorizons/CustomToolTips.xml';en=lang(MASTER/'config/txloader/load/customtooltips/lang/en_US.lang');tips=[];pending=[]
for el in ET.parse(source_tips).getroot():
 a=el.attrib;id=a.get('ItemName')
 if id not in byid:continue
 if a.get('NBT'):pending.append({'source':a,'reason':'NBT-matching tooltip unsupported'});continue
 key=a['ToolTip'];assert key in en,key;value=en[key];args=a.get('FormatArgs');arguments=args.split(',') if args else []
 if arguments:
  assert value.count('%s')==len(arguments) and not re.search(r'%(?!s)',value)
  for arg in arguments:value=value.replace('%s',arg,1)
 # Original custom tooltip handler splits literal backslash-n and translates legacy color codes.
 parts=value.split('\\n')
 for i,part in enumerate(parts):
  tkey='tooltip.gtnh_migration.'+id.split(':')[1].lower()+'.'+str(i);langs['en_us'][tkey]=part;langs['zh_cn'][tkey]=part
  tips.append({'legacy_id':id,'target_id':byid[id]['target_id'],'source_key':key,'source_format_args':arguments,'text':part,'translation_key':tkey,'zh_cn_source_missing':True})
lines=['// Exact master CustomToolTips.xml entries for implemented items; source strings preserved.',"ItemEvents.modification(event => {"]
for t in tips:lines.append('  event.modify('+json.dumps(t['target_id'])+', item => { item.tooltip(Text.translatable('+json.dumps(t['translation_key'])+')); });')
lines.append('});');(K/'startup_scripts/03_gtnh_tooltips.js').write_text('\n'.join(lines)+'\n',encoding='utf-8')
fuels=[];deferred=[]
for el in ET.parse(MASTER/'config/GTNewHorizons/CustomFuels.xml').getroot():
 a=el.attrib;id=a['ItemName'];ticks=int(a['BurnTime'])
 if id in ['minecraft:diamond','minecraft:diamond_block']:fuels.append({'id':id,'ticks':ticks,'source':a})
 else:deferred.append({'source':a,'reason':'Original block/mod/metadata-specific fuel not yet registered; not silently remapped.'})
lines=['// Master CustomFuels.xml vanilla item overrides; values are ticks, not seconds.',"ItemEvents.modification(event => {"]
for f in fuels:lines.append('  event.modify('+json.dumps(f['id'])+', item => { item.burnTime = '+str(f['ticks'])+'; });')
lines.append('});');(K/'startup_scripts/04_gtnh_fuel_overrides.js').write_text('\n'.join(lines)+'\n',encoding='utf-8')
for loc,d in langs.items():(K/f'assets/gtnh_migration/lang/{loc}.json').write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
report={'creative_tabs':tabs,'tooltip_lines':tips,'tooltip_pending':pending,'fuel_overrides':fuels,'fuel_entries_deferred':deferred,'runtime_verified':False}
(D/'item-integrations.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'creative_tabs':len(tabs),'tab_item_memberships':557,'tooltip_lines':len(tips),'fuel_overrides':len(fuels),'deferred_fuels':len(deferred),'runtime_verified':False}))
