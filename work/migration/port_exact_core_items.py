"""Exact CoreMod item port: ordinary Items and CoinItem's real two-layer rendering."""
from pathlib import Path
import json,re,hashlib,shutil,io
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs'
S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';J=S/'src/main/java/com/dreammaster/item/NHItemList.java';text=J.read_text(encoding='utf-8');D=O/'docs/core-items-migration';D.mkdir(exist_ok=True)
def digest(b):return hashlib.sha256(b).hexdigest()
def save(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
assert 'this(name, new Item(), tab);' in text and 'GameRegistry.registerItem(entry.item, entry.name);' in text
plain=re.findall(r'^\s*(\w+)\("([^"]+)",\s*(\w+)\)[,;]',text,re.M)
coinrows=re.findall(r'^\s*(\w+)\("([^"]+)",\s*new CoinItem\((null|"[^"]+"),\s*(\d+)\),\s*(\w+)\)[,;]',text,re.M)
complex_=re.findall(r'^\s*(\w+)\("([^"]+)",\s*new\s+(?!CoinItem)([^\n]+)',text,re.M)
stack={name:int(n) for name,n in re.findall(r'(\w+)\.item\.setMaxStackSize\((\d+)\)',text)}
assert (len(plain),len(coinrows),len(complex_))==(477,80,10)
coinclass=(S/'src/main/java/com/dreammaster/item/CoinItem.java').read_text(encoding='utf-8')
assert 'return pass == 0 ? itemIcon : coinTypeIcon;' in coinclass and 'return coinType != null;' in coinclass
assert ':coins/background/big' in coinclass and ':coins/icons/' in coinclass
languages={};newlangs={'en_us':{},'zh_cn':{}}
for loc in ['en_US','zh_CN']:
    p=S/f'src/main/resources/assets/dreamcraft/lang/{loc}.lang';languages[loc]={}
    for line in p.read_text(encoding='utf-8-sig').splitlines():
        if line and not line.startswith('#') and '=' in line:
            k,v=line.split('=',1);languages[loc][k.strip()]=v
records=[];assets={};seen=set();coinold=json.loads((O/'docs/currency-migration/coin-mapping.json').read_text(encoding='utf-8'))
def copy_asset(src_rel,target_rel):
    src=S/src_rel;b=src.read_bytes()
    with Image.open(io.BytesIO(b)) as im:im.verify()
    im=Image.open(io.BytesIO(b));im.load();meta=src.with_suffix('.png.mcmeta');animation=None
    if meta.exists():
        md=json.loads(meta.read_text(encoding='utf-8'));a=md['animation'];fw=a.get('width',min(im.size));fh=a.get('height',min(im.size));assert im.width%fw==0 and im.height%fh==0,(src_rel,im.size,a)
        count=(im.width//fw)*(im.height//fh);assert a.get('frametime',1)>0
        for fr in a.get('frames',list(range(count))):
            idx=fr if isinstance(fr,int) else fr['index'];assert 0<=idx<count,(src_rel,idx,count)
            if isinstance(fr,dict):assert fr.get('time',a.get('frametime',1))>0
        animation={'frame_width':fw,'frame_height':fh,'frame_count':count,'metadata':md,'sha256':digest(meta.read_bytes())}
    else:assert im.width==im.height,(src_rel,im.size)
    dest=K/target_rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(b)
    if meta.exists():shutil.copy2(meta,dest.with_suffix('.png.mcmeta'))
    rec={'source_texture':src_rel,'source_png_sha256':digest(b),'target_texture':target_rel,'dimensions':list(im.size),'animation':animation}
    assets[target_rel]=rec;return rec
allrows=[(e,n,t,'plain',None,None) for e,n,t in plain]+[(e,n,t,'coin',json.loads(c),int(i)) for e,n,c,i,t in coinrows]
for enum,name,tab,kind,ctype,tier in allrows:
    id='gtnh_migration:'+name.lower();assert id not in seen;seen.add(id)
    key='item.gtnh_migration.'+name.lower();layers={};itemassets=[]
    if kind=='plain':
        src=f'src/main/resources/assets/dreamcraft/textures/items/item{name}.png';dest=f'assets/gtnh_migration/textures/item/{name.lower()}.png'
        itemassets.append(copy_asset(src,dest));layers['layer0']='gtnh_migration:item/'+name.lower()
    else:
        loc=f'coins/background/big{tier}';itemassets.append(copy_asset('src/main/resources/assets/dreamcraft/textures/items/'+loc+'.png','assets/gtnh_migration/textures/item/'+loc+'.png'));layers['layer0']='gtnh_migration:item/'+loc
        if ctype is not None:
            loc=f'coins/icons/{ctype}{tier}';itemassets.append(copy_asset('src/main/resources/assets/dreamcraft/textures/items/'+loc+'.png','assets/gtnh_migration/textures/item/'+loc+'.png'));layers['layer1']='gtnh_migration:item/'+loc
    save(K/f'assets/gtnh_migration/models/item/{name.lower()}.json',{'parent':'minecraft:item/generated','textures':layers})
    sourcekey='item.'+name+'.name';assert sourcekey in languages['en_US'];missing_cn=sourcekey not in languages['zh_CN']
    newlangs['en_us'][key]=languages['en_US'][sourcekey];newlangs['zh_cn'][key]=languages['zh_CN'].get(sourcekey,languages['en_US'][sourcekey])
    r={'enum_name':enum,'legacy_id':'dreamcraft:'+name,'legacy_damage':0,'target_id':id,'max_stack_size':stack.get(enum,64),'source_creative_tab':tab,'source_item_kind':kind,'source_definition':'new Item()' if kind=='plain' else 'CoinItem two-pass icon renderer; no other item behavior','coin_type':ctype,'coin_tier':tier,'source_version':'NewHorizonsCoreMod 2.9.84','assets':itemassets,'textures':layers,'source_texture':itemassets[0]['source_texture'],'source_png_sha256':itemassets[0]['source_png_sha256'],'target_texture':itemassets[0]['target_texture'],'translation_key':key,'model':'gtnh_migration:item/'+name.lower(),'zh_cn_fallback_to_source_english':missing_cn,'registration_status':'implemented_not_runtime_verified','recipes_and_integrations_complete':False}
    records.append(r)
# Old 2.8.4 flat coin images are superseded by exact-version layered assets. Quarantine only known stale generated files.
quarantine=W/'quarantine/currency-2.8.4-flat';quarantine.mkdir(parents=True,exist_ok=True)
for r in records:
    if r['source_item_kind']=='coin':
        oldpath=K/f"assets/gtnh_migration/textures/item/{r['target_id'].split(':')[1]}.png"
        if oldpath.exists():
            target=quarantine/oldpath.name
            if target.exists():assert digest(target.read_bytes())==digest(oldpath.read_bytes());oldpath.unlink()
            else:shutil.move(str(oldpath),str(target))
for loc,data in newlangs.items():save(K/f'assets/gtnh_migration/lang/{loc}.json',data)
for fname,subset in [('00_gtnh_currency.js',[r for r in records if r['enum_name'].startswith('Coin')]),('01_gtnh_core_items.js',[r for r in records if not r['enum_name'].startswith('Coin')])]:
    lines=['// Adapted from exact CoreMod 2.9.84 NHItemList/CoinItem; GPL-3.0 source and license included.', '// Plain registrations and exact coin rendering only. Specialized systems not replaced by placeholders.',"StartupEvents.registry('item', event => {"]
    for r in subset:
        lines.append('  event.create('+json.dumps(r['target_id'])+').textures('+json.dumps(r['textures'],separators=(',',':'))+').translationKey('+json.dumps(r['translation_key'])+').maxStackSize('+str(r['max_stack_size'])+');')
    lines.append('});');(K/'startup_scripts'/fname).write_text('\n'.join(lines)+'\n',encoding='utf-8')
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));ids={r['legacy_id']:r for r in records};count=0
for ref in refs:
    a=ref['identity']
    if a['id'] in ids and a['damage']==0 and not a['tag'] and not a['ore_dict']:
        ref['target_id']=ids[a['id']]['target_id'];ref['mapping_status']='exact_source_item_definition_not_runtime_verified';count+=1
save(O/'docs/quest-migration/item-registry-references.json',refs)
coverage=json.loads((O/'docs/quest-migration/coverage.json').read_text(encoding='utf-8'));coverage['mapped_core_item_identities']=count;coverage['mapped_total_item_identities']=sum(x['target_id'] is not None for x in refs);coverage['other_identities_unmapped']=sum(x['target_id'] is None for x in refs);save(O/'docs/quest-migration/coverage.json',coverage)
for old in coinold['records']:
    r=ids[old['legacy_id']];old.update({k:r[k] for k in ['source_png_sha256','max_stack_size','target_texture','translation_key','model','textures','assets']});old['target_png_sha256']=r['source_png_sha256'];old['source_version']='2.9.84';old['source_texture']=r['source_texture'];old['legacy_class']=r['source_definition'];old['source_jar']='pinned CoreMod 2.9.84 source archive';old['economy_status']='exact-version item registration/render definition implemented; full economy/runtime unverified'
coinold['source_version']='2.9.84';coinold['previous_source_jar_sha256']=coinold.pop('source_jar_sha256',coinold.get('previous_source_jar_sha256'));coinold['source_commit']=json.loads((W/'cache/exact-sources/NewHorizonsCoreMod-2.9.84-lock.json').read_text())['commit'];save(O/'docs/currency-migration/coin-mapping.json',coinold)
report={'source_commit':coinold['source_commit'],'source_file':J.relative_to(S).as_posix(),'source_file_sha256':digest(J.read_bytes()),'ordinary_items':len(plain),'coin_render_items':len(coinrows),'total_item_definitions':len(records),'coin_items':81,'additional_noncoin_items':476,'animated_texture_files':sum(a['animation'] is not None for a in assets.values()),'unique_texture_files':len(assets),'nondefault_stack_limits':stack,'zh_cn_fallbacks':[r['legacy_id'] for r in records if r['zh_cn_fallback_to_source_english']],'deferred_specialized_items':[{'enum_name':e,'name':n,'source_constructor':c,'status':'not replaced with plain placeholder'} for e,n,c in complex_],'records':records,'asset_files':list(assets.values()),'quest_stack_identities_mapped':count,'full_fidelity':False}
save(D/'core-item-mapping.json',report)
lic=O/'licenses/NewHorizonsCoreMod-2.9.84';lic.mkdir(parents=True,exist_ok=True);shutil.copy2(S/'LICENSE',lic/'LICENSE');shutil.copy2(W/'cache/exact-sources/NewHorizonsCoreMod-2.9.84-lock.json',lic/'source-lock.json');shutil.copy2(W/'cache/exact-sources/NewHorizonsCoreMod-2.9.84.zip',lic/'corresponding-source.zip')
(lic/'NOTICE.txt').write_text('Original: GTNewHorizons/NewHorizonsCoreMod tag 2.9.84. See LICENSE and source-lock.json. Corresponding source included. Adapted to NeoForge 1.21.1 KubeJS: namespace normalization, generated models/language, original stack limits and layered CoinItem textures. No claims of official GTNH endorsement or complete modpack redistribution clearance. Generated adaptations provided under the original GPL-3.0 license.\n',encoding='utf-8')
# Actual layered coin preview, not the obsolete flat textures.
cs=[r for r in records if r['enum_name'].startswith('Coin')];cols=9;cw=114;ch=78;img=Image.new('RGB',(cols*cw,9*ch+35),'#1d2330');draw=ImageDraw.Draw(img);draw.text((12,10),'CoreMod 2.9.84 actual layered coins - preview only, NOT in-game rendering',fill='white')
for i,r in enumerate(cs):
    ims=[Image.open(K/a['target_texture']).convert('RGBA') for a in r['assets']];tile=ims[0].copy()
    for im in ims[1:]:tile=Image.alpha_composite(tile,im)
    tile=tile.resize((40,40),Image.Resampling.NEAREST);x=(i%cols)*cw+30;y=(i//cols)*ch+32;img.paste(tile,(x,y),tile);draw.text((x-24,y+46),r['enum_name'].replace('Coin','')[:19],fill='white')
img.save(O/'docs/currency-migration/coin-preview.png')
print(json.dumps({k:report[k] for k in ['ordinary_items','coin_render_items','total_item_definitions','additional_noncoin_items','animated_texture_files','unique_texture_files','nondefault_stack_limits','quest_stack_identities_mapped']},ensure_ascii=False),flush=True)
