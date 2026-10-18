"""Build conservative, source-traced resource ports. Explicitly track unmigrated assets."""
from pathlib import Path
from gtceu_artifact import original_jar
import zipfile,json,hashlib,io,re,struct,datetime
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT/'work';OUT=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
SOURCE=WORK/'source-release-2.8.4/GT New Horizons 2.8.4/.minecraft/mods/gregtech-5.09.51.482.jar'
TARGET=original_jar();REPORT=OUT/'docs/resource-migration';REPORT.mkdir(exist_ok=True)
def digest(b):return hashlib.sha256(b).hexdigest()
def save_json(path,obj):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
def png(b):
    with Image.open(io.BytesIO(b)) as im:im.verify()
    im=Image.open(io.BytesIO(b)).convert('RGBA'); im.load(); return im
# Read format version from installed official Minecraft 1.21.1 jar, not a guessed constant.
version=None
for p in (WORK/'neoforge-smoke/libraries/net/minecraft/server').rglob('*.jar'):
    with zipfile.ZipFile(p) as z:
        if 'version.json' in z.namelist():version=json.loads(z.read('version.json'));break
assert version and version['id']=='1.21.1'
fmt=version['pack_version']['resource'];assert fmt==34
sets=re.findall(r'new MaterialIconSet\("([^\"]+)"',(WORK/'migration/MaterialIconSet.java').read_text(encoding='utf-8'))
pack=OUT/'resourcepacks/GTNH-2.8.4-Shared-Ore-Assets-1.21.1';pack.mkdir(parents=True,exist_ok=True)
save_json(pack/'pack.mcmeta',{'pack':{'pack_format':fmt,'description':'GTNH 2.8.4 shared ore textures: audited subset, preview only; full parity and client rendering not verified'}})
manifest=[];deferred=[];preview=[]
with zipfile.ZipFile(SOURCE) as old,zipfile.ZipFile(TARGET) as target:
    names=set(old.namelist());tn=set(target.namelist())
    for icon in sets:
        # A same-name source set is evidence for path migration, not material/color parity.
        pairs=[];block=False
        for oldstem,newstem in [('ore','ore'),('oreSmall','ore_small')]:
            n=f'assets/gregtech/textures/blocks/materialicons/{icon.upper()}/{oldstem}.png'
            ov=n.replace('.png','_OVERLAY.png')
            if n not in names:
                deferred.append({'icon_set':icon,'source_path':n,'reason':'No exact source base texture. No replacement invented.'});block=True;continue
            b=old.read(n);im=png(b)
            if im.width!=im.height or n+'.mcmeta' in names:
                deferred.append({'icon_set':icon,'source_path':n,'reason':'Animation/dimensions require additional explicit conversion'});block=True;continue
            if ov in names and png(old.read(ov)).getchannel('A').getextrema()[1]>0:
                deferred.append({'icon_set':icon,'source_path':n,'overlay':ov,'reason':'Legacy overlay renders untinted; modern secondary layer is material-tinted. Needs per-material/model adapter; not blindly copied.'});block=True;continue
            pairs.append((n,newstem,b,im,ov))
        if block:continue
        for n,stem,b,im,ov in pairs:
            targetbase=f'assets/gtceu/textures/block/material_sets/{icon}/{stem}.png'
            dest=pack/targetbase;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(b)
            # Legacy has no visible overlay: explicitly transparent layer2 prevents modern fallback overlays.
            blank=Image.new('RGBA',im.size,(0,0,0,0)); buf=io.BytesIO();blank.save(buf,format='PNG');blankbytes=buf.getvalue()
            second=targetbase.replace('.png','_layer2.png');(pack/second).write_bytes(blankbytes)
            manifest.append({'source_jar':SOURCE.name,'source_path':n,'source_sha256':digest(b),'target_path':targetbase,'target_sha256':digest(dest.read_bytes()),'dimensions':list(im.size),'kind':'unchanged_png_bytes','source_overlay_present':ov in names,'target_original_exists':targetbase in tn,'scope':'shared icon set asset only; material assignment/color parity unverified'})
            manifest.append({'source_path':ov,'target_path':second,'target_sha256':digest(blankbytes),'dimensions':list(im.size),'kind':'transparent_secondary_for_legacy_absent_overlay','reason':'prevent inheriting visible modern overlay not present in source'})
            if stem=='ore': preview.append((icon,im))
    known=set(sets)
    for n in sorted(names):
        if n.startswith('assets/gregtech/textures/blocks/materialicons/') and n.endswith('/ore.png'):
            rel=n[len('assets/gregtech/textures/blocks/materialicons/'):]
            if '/' in rel and rel.split('/')[0].lower() not in known:
                deferred.append({'source_path':n,'reason':'Legacy-only/custom icon set; target registry/material mapping not implemented.'})
readme='''Source: user-supplied GT New Horizons 2.8.4, gregtech-5.09.51.482.jar.
This PREVIEW resource pack ports only exact-name shared icon sets with no visible legacy overlay.
Base PNG bytes are unchanged. Transparent secondary textures prevent inheriting modern overlays.
Legacy dull/metallic/rough/shiny overlays are not ported: they are untinted in GTNH and tinted in GTCEu.
Material identity, per-material color, all custom sets and block registries require separate migration.
No models, emissive rendering or gameplay data are overridden. This is NOT full material fidelity.
Do not treat this as a final resource pack or enable it as proof of full migration.
Attribution: original GTNH and GregTech artists/authors retained; redistribution permissions require review.
'''
(pack/'README.txt').write_text(readme,encoding='utf-8')
# Preview pixels without applying invented material colors. Checkerboard exposes alpha.
cols=6;cellw=140;cellh=116;rows=(len(preview)+cols-1)//cols
img=Image.new('RGB',(cols*cellw,rows*cellh+45),'#1d2330');draw=ImageDraw.Draw(img)
draw.text((14,10),'GTNH 2.8.4 ore assets - alpha preview, NOT in-game rendering',fill='white')
for idx,(name,asset) in enumerate(preview):
    x=(idx%cols)*cellw+24;y=(idx//cols)*cellh+40
    for yy in range(0,64,8):
        for xx in range(0,64,8):draw.rectangle((x+xx,y+yy,x+xx+7,y+yy+7),fill='#858585' if ((xx+yy)//8)%2 else '#bbbbbb')
    resized=asset.resize((64,64),Image.Resampling.NEAREST);img.paste(resized,(x,y),resized)
    draw.text((x-12,y+73),name,fill='white')
img.save(REPORT/'ore-alpha-preview.png')
# Preserve original alarm samples under valid lowercase resource locations, no audio transcoding.
audiopack=OUT/'resourcepacks/GTNH-Original-Alarm-Audio-1.21.1';audiopack.mkdir(parents=True,exist_ok=True)
save_json(audiopack/'pack.mcmeta',{'pack':{'pack_format':fmt,'description':'GTNH original alarm audio assets; playback events only, OpenSecurity behavior not ported'}})
sounds={};sound_records=[]
source=WORK/'source/GT-New-Horizons-Modpack-master/mods/OpenSecurity/sounds/alarms'
for p in sorted(source.glob('*.ogg')):
    event=re.sub('[^a-z0-9_/.-]','_',p.stem.lower());event=event.replace('-','_');b=p.read_bytes()
    assert b.startswith(b'OggS'),p.name
    # Validate every Ogg page boundary and checksum; content decode/runtime playback remains separate.
    offset=0;pages=0
    while offset<len(b):
        assert b[offset:offset+4]==b'OggS','invalid Ogg page header'
        assert b[offset+4]==0,'unsupported Ogg bitstream version'
        segs=b[offset+26];hlen=27+segs;body=sum(b[offset+27:offset+hlen]);end=offset+hlen+body
        assert end<=len(b),'truncated Ogg page'
        page=bytearray(b[offset:end]);expected=struct.unpack_from('<I',page,22)[0];page[22:26]=b'\0'*4
        # Ogg polynomial checksum, distinct from zip CRC32.
        if 'crc_table' not in globals():
            crc_table=[]
            for v in range(256):
                c=v<<24
                for _ in range(8):c=((c<<1)^0x04c11db7)&0xffffffff if c&0x80000000 else (c<<1)&0xffffffff
                crc_table.append(c)
        crc=0
        for byte in page:crc=((crc<<8)&0xffffffff)^crc_table[((crc>>24)^byte)&0xff]
        assert crc==expected,'Ogg CRC mismatch'
        offset=end;pages+=1
    rel=f'assets/gtnh_migration/sounds/alarms/{event}.ogg';dest=audiopack/rel;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(b)
    sounds['alarms.'+event]={'sounds':[{'name':'gtnh_migration:alarms/'+event,'stream':True}]}
    sound_records.append({'source_path':str(p.relative_to(WORK/'source/GT-New-Horizons-Modpack-master')),'source_sha256':digest(b),'target_path':rel,'target_sha256':digest(dest.read_bytes()),'ogg_page_count':pages,'ogg_crc_valid':True,'playback_verified':False})
save_json(audiopack/'assets/gtnh_migration/sounds.json',sounds)
(audiopack/'README.txt').write_text('Original user-supplied OpenSecurity alarm audio, unchanged bytes. Valid Ogg page checksums and resource IDs checked. This does NOT port OpenSecurity alarms, computers, permissions or triggers. In-game audio decoding/playback not tested. Original third-party content attribution and redistribution rights require review.\n',encoding='utf-8')
report={'minecraft':'1.21.1','resource_pack_format':fmt,'source_jar':str(SOURCE),'source_jar_sha256':digest(SOURCE.read_bytes()),'target_jar_sha256':digest(TARGET.read_bytes()),'ore_icon_sets_ported':len(preview),'ore_png_files':len(manifest),'ore_mappings':manifest,'deferred':deferred,'audio_files':len(sound_records),'audio_mappings':sound_records,'runtime_visual_verification':False,'full_fidelity':False,'generated_at':datetime.datetime.now(datetime.timezone.utc).isoformat()}
save_json(REPORT/'resource-mapping.json',report)
print(json.dumps({'ore_sets':len(preview),'ore_pngs':len(manifest),'deferred_entries':len(deferred),'audio_files':len(sound_records),'preview':str(REPORT/'ore-alpha-preview.png'),'full_fidelity':False}),flush=True)
