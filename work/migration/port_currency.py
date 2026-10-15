raise SystemExit('Superseded by pinned 2.9.84 sources. Use rebuild_current_phase.py; do not overwrite current item and recipe output with 2.8.4 data.')
"""Port actual GTNH plain coin items, never fake missing gameplay systems."""
from pathlib import Path
import json,re,zipfile,io,hashlib,collections
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[2];W=ROOT/'work';O=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs'
J=W/'source-release-2.8.4/GT New Horizons 2.8.4/.minecraft/mods/GTNewHorizonsCoreMod-2.7.268.jar'
bytecode=(W/'migration/NHItemList.bytecode.txt').read_text(encoding='utf-8')
# javap is static inspection of original classfile, not execution of legacy code.
coins=sorted(set(re.findall(r'// Field (Coin[A-Za-z0-9_]+):Lcom/dreammaster/item/NHItemList;',bytecode)))
assert coins and len(coins)==81
reportdir=O/'docs/currency-migration';reportdir.mkdir(exist_ok=True)
lang={};records=[];animations=[];previews=[]
def h(b):return hashlib.sha256(b).hexdigest()
def save(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
with zipfile.ZipFile(J) as z:
    ns=set(z.namelist());languages={}
    for loc in ['en_US','zh_CN']:
        text=z.read(f'assets/dreamcraft/lang/{loc}.lang').decode('utf-8-sig')
        d={}
        for line in text.splitlines():
            if line and not line.startswith('#') and '=' in line:
                a,b=line.split('=',1);d[a.strip()]=b
        languages[loc]=d
    for name in coins:
        path='assets/dreamcraft/textures/items/item'+name+'.png';assert path in ns,path
        b=z.read(path)
        with Image.open(io.BytesIO(b)) as im:im.verify()
        im=Image.open(io.BytesIO(b)).convert('RGBA');im.load()
        # Preserve name semantics; only normalize for 1.21 valid resource identifiers.
        suffix=name.lower();rid='gtnh_migration:'+suffix
        dst=K/f'assets/gtnh_migration/textures/item/{suffix}.png';dst.parent.mkdir(parents=True,exist_ok=True);dst.write_bytes(b)
        if path+'.mcmeta' in ns:
            mb=z.read(path+'.mcmeta');json.loads(mb);dst.with_suffix('.png.mcmeta').write_bytes(mb);animations.append(name)
        else:assert im.width==im.height,('Non-square unanimated coin',name)
        model={'parent':'minecraft:item/generated','textures':{'layer0':f'gtnh_migration:item/{suffix}'}}
        save(K/f'assets/gtnh_migration/models/item/{suffix}.json',model)
        key='item.gtnh_migration.'+suffix
        for loc in languages:
            source_key='item.'+name+'.name'
            assert source_key in languages[loc],source_key
            lang.setdefault(loc.lower(),{})[key]=languages[loc][source_key]
        records.append({'legacy_id':'dreamcraft:'+name,'legacy_damage':0,'target_id':rid,'legacy_class':'eu.usrv.yamcore.items.ModSimpleBaseItem -> ItemBase','source_jar':J.name,'source_texture':path,'source_png_sha256':h(b),'target_texture':str(dst.relative_to(K)).replace('\\','/'),'target_png_sha256':h(dst.read_bytes()),'model':'gtnh_migration:item/'+suffix,'translation_key':key,'item_registration_status':'implemented_not_runtime_verified','economy_status':'quest rewards, exchanges and machine recipes NOT YET PORTED','world_save_conversion_implemented':False})
        previews.append((name,im))
for loc,values in lang.items():save(K/f'assets/gtnh_migration/lang/{loc}.json',values)
lines=['// Generated from GTNH 2.8.4 original NHItemList plain coin items.',
       '// Registry items and original textures only; rewards/exchanges are separate work.',
       "StartupEvents.registry('item', event => {"]
for r in records:
    lines.append("  event.create("+json.dumps(r['target_id'])+").texture("+json.dumps(r['model'])+").translationKey("+json.dumps(r['translation_key'])+");")
lines.append('});')
s=K/'startup_scripts/00_gtnh_currency.js';s.parent.mkdir(parents=True,exist_ok=True);s.write_text('\n'.join(lines)+'\n',encoding='utf-8')
# Trace every source quest mention of these real currency items by stable quest ID.
root=W/'source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests'
byid={r['legacy_id']:r for r in records};uses=collections.Counter();quests_with_coins=set();unmapped=set()
def scan(x,qid):
    if isinstance(x,dict):
        item=x.get('id:8')
        if isinstance(item,str) and item.startswith('dreamcraft:Coin'):
            if item in byid:uses[item]+=1;quests_with_coins.add(qid)
            else:unmapped.add(item)
        for v in x.values():scan(v,qid)
    elif isinstance(x,list):
        for v in x:scan(v,qid)
for p in root.rglob('*.json'):
    d=json.loads(p.read_text(encoding='utf-8'));scan(d,f"{d['questIDHigh:4']}:{d['questIDLow:4']}")
for r in records:r['master_quest_occurrences']=uses[r['legacy_id']]
assert not unmapped,unmapped
save(reportdir/'coin-mapping.json',{'source_jar_sha256':h(J.read_bytes()),'records':records,'coin_items':len(records),'unique_master_quests_referencing_coins':len(quests_with_coins),'total_master_coin_references':sum(uses.values()),'unmapped_master_coin_ids':sorted(unmapped),'animations':animations,'parity_complete':False})
cols=9;cw=114;ch=78;img=Image.new('RGB',(cols*cw,((len(previews)+cols-1)//cols)*ch+35),'#1d2330');draw=ImageDraw.Draw(img)
draw.text((12,10),'GTNH original currency textures - preview only, not in-game verification',fill='white')
for i,(name,im) in enumerate(previews):
 x=(i%cols)*cw+30;y=(i//cols)*ch+32;tile=im.crop((0,0,im.width,im.width)).resize((40,40),Image.Resampling.NEAREST);img.paste(tile,(x,y),tile);draw.text((x-24,y+46),name.replace('Coin','')[:19],fill='white')
img.save(reportdir/'coin-preview.png')
print(json.dumps({'registered_coin_items':len(records),'quest_coin_references':sum(uses.values()),'unique_quests':len(quests_with_coins),'unmapped_coin_ids':sorted(unmapped),'runtime_verified':False}),flush=True)
