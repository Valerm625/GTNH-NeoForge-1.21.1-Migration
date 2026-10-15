from pathlib import Path
import json,re
R=Path(__file__).resolve().parents[2];S=R/'work/exact-sources/NewHorizonsCoreMod/2.9.84';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs'
def readlang(p):
 d={}
 for line in p.read_text(encoding='utf-8-sig').splitlines():
  if line and not line.startswith('#') and '=' in line:k,v=line.split('=',1);d[k.strip()]=v
 return d
ru=readlang(S/'src/main/resources/assets/dreamcraft/lang/ru_RU.lang');en=readlang(S/'src/main/resources/assets/dreamcraft/lang/en_US.lang');data=json.loads((O/'docs/core-items-migration/core-item-mapping.json').read_text(encoding='utf-8'));out={};fallback=[]
for r in data['records']:
 k='item.'+r['legacy_id'].split(':')[1]+'.name';out[r['translation_key']]=ru.get(k,en[k])
 if k not in ru:fallback.append(k)
k='item.WoodenBrickForm.name';out['item.gtnh_migration.woodenbrickform']=ru.get(k,en[k])
tabsource=(S/'src/main/java/com/dreammaster/creativetab/ModTabList.java').read_text();chunks=re.split(r'(?=    public static final CreativeTabs )',tabsource)[1:]
for c in chunks:
 tab=re.search(r'CreativeTabs (\w+)',c).group(1)
 if tab=='BLOCKS':continue
 name=re.search(r'new CreativeTabs\("([^"]+)"',c).group(1);key='itemGroup.'+name;out['itemGroup.gtnh_migration.'+tab.lower()]=ru.get(key,en[key])
integrations=json.loads((O/'docs/core-items-migration/item-integrations.json').read_text(encoding='utf-8'))
for tip in integrations['tooltip_lines']:out[tip['translation_key']]=tip['text']
(K/'assets/gtnh_migration/lang/ru_ru.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
(O/'docs/core-items-migration/ru-localization.json').write_text(json.dumps({'items':558,'tabs':7,'tooltips':24,'item_source_english_fallback_keys':fallback,'note':'Preserved supplied exact-version Russian where available; no invented translations. Runtime font rendering unverified.'},ensure_ascii=False,indent=2),encoding='utf-8')
print('RU_SOURCE_LOCALIZATION',len(out),'ITEM_FALLBACKS',len(fallback))
