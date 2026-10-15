"""Independent source/property/asset tests for fuel blocks and chain conservation."""
from pathlib import Path
import json,re,hashlib,xml.etree.ElementTree as ET,zipfile,subprocess,shutil,uuid
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';D=O/'docs/fuel-block-migration'
data=json.loads((D/'fuel-blocks.json').read_text(encoding='utf-8'));source=(S/'src/main/java/com/dreammaster/block/BlockList.java').read_text(encoding='utf-8');fuelxml=W/'source/GT-New-Horizons-Modpack-master/config/GTNewHorizons/CustomFuels.xml';fuel={x.attrib['ItemName']:int(x.attrib['BurnTime']) for x in ET.parse(fuelxml).getroot()}
# Read official target tags for vanilla tool threshold behavior; do not infer all modded tool parity.
mc=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-extra.jar'
with zipfile.ZipFile(mc) as z:incorrect={tier:json.loads(z.read(f'data/minecraft/tags/block/incorrect_for_{tier}_tool.json'))['values'] for tier in ['wooden','gold','stone','iron','diamond','netherite']}
checks=[];thumbs=[]
for b in data['blocks']:
 line=next(l.strip() for l in source.splitlines() if l.strip().startswith(b['source_enum']+'('));m=re.fullmatch(r'(\w+)\("([^\"]+)", stoneLike\((\d+), ([\d.]+)f\)\),',line);assert m,line
 assert b['legacy_id']=='dreamcraft:'+m.group(2) and b['legacy_harvest_level']==int(m.group(3)) and b['hardness']==float(m.group(4)) and b['explosion_resistance']==b['hardness'];assert b['burn_ticks']==fuel[b['legacy_id']]
 assert b['max_stack']==64 and b['map_color']=='stone' and b['sound']=='stone'
 sourcepng=(S/b['source_texture']).read_bytes();target=O/'kubejs'/b['target_texture'];assert sourcepng==target.read_bytes();assert hashlib.sha256(sourcepng).hexdigest()==b['png_sha256']
 with Image.open(target) as im:im.verify()
 img=Image.open(target).convert('RGBA');thumbs.append((b['source_enum'],img))
 key=b['target_id'].split(':')[1];model=json.loads((O/f'kubejs/assets/gtnh_migration/models/block/{key}.json').read_text());state=json.loads((O/f'kubejs/assets/gtnh_migration/blockstates/{key}.json').read_text());item=json.loads((O/f'kubejs/assets/gtnh_migration/models/item/{key}.json').read_text())
 assert model=={'parent':'minecraft:block/cube_all','textures':{'all':'gtnh_migration:block/'+key}}
 assert state['variants']['']['model']=='gtnh_migration:block/'+key and item['parent']=='gtnh_migration:block/'+key
 for locale in ['en_us','zh_cn','ru_ru']:assert b['translation_key'] in json.loads((O/f'kubejs/assets/gtnh_migration/lang/{locale}.json').read_text(encoding='utf-8'))
 tags=[b['tool_tag'],b['mineable_tag']]
 for tag in tags:
  ns,rel=tag.split(':');tagfile=O/f'kubejs/data/{ns}/tags/block/{rel}.json';assert b['target_id'] in json.loads(tagfile.read_text())['values']
 toolcheck={tier:'#'+b['tool_tag'] not in values for tier,values in incorrect.items()}
 expected={tier:level>=b['legacy_harvest_level'] for tier,level in [('wooden',0),('gold',0),('stone',1),('iron',2),('diamond',3),('netherite',4)]}
 assert toolcheck==expected,(b['target_id'],toolcheck,expected);checks.append({'block':b['target_id'],'vanilla_tool_tag_check':toolcheck})
byid={b['target_id']:b for b in data['blocks']};ratios=[]
for r in data['recipes']:
 a,b=r['input'],r['output'];assert r['ticks']==300 and r['eut']==2
 if a['id'] in byid and b['id'] in byid:
  assert a['count']*byid[a['id']]['burn_ticks']==b['count']*byid[b['id']]['burn_ticks'];ratios.append(r['id'])
chaintext='\n'.join(r['source_chain'] for r in data['recipes']);assert len(data['recipes'])==28 and len(data['deferred_base_recipes'])==2
# Source has real nonuniform original tier: highest coal uses iron while charcoal/coke require diamond.
assert byid['gtnh_migration:quintuplecompressedcoal']['legacy_harvest_level']==2
stub=subprocess.run(['node',str(W/'migration/test_fuel_blocks.cjs'),str(O)],capture_output=True,text=True,encoding='utf-8');assert stub.returncode==0,stub.stderr
(D/'script-contract-test.json').write_text(stub.stdout,encoding='utf-8')
# Known-bad variants must fail. Only mutate isolated generated test fixtures.
fixture=W/'migration/test-fixtures'/('fuel-'+str(uuid.uuid4()));fixture.mkdir(parents=True)
for rel in ['docs/fuel-block-migration/fuel-blocks.json','kubejs/startup_scripts/06_gtnh_fuel_blocks.js','kubejs/startup_scripts/07_gtnh_blocks_tab.js','kubejs/server_scripts/12_gtnh_fuel_processing.js']:
 dst=fixture/rel;dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(O/rel,dst)
cases=[('short_fuel_truncation','kubejs/startup_scripts/06_gtnh_fuel_blocks.js','1889568000','1024'),('wrong_hardness','kubejs/startup_scripts/06_gtnh_fuel_blocks.js','.hardness(30.0)','.hardness(3.0)'),('lost_compression_mass','kubejs/server_scripts/12_gtnh_fuel_processing.js','9x minecraft:coal_block','8x minecraft:coal_block')];rejections=[]
for name,rel,a,b in cases:
 p=fixture/rel;s=p.read_text();assert a in s;p.write_text(s.replace(a,b,1),encoding='utf-8')
 try:
  r=subprocess.run(['node',str(W/'migration/test_fuel_blocks.cjs'),str(fixture)],capture_output=True,text=True,encoding='utf-8');assert r.returncode!=0,'Failed to catch '+name;rejections.append(name)
 finally:p.write_text(s,encoding='utf-8')
preview=Image.new('RGB',(860,420),'#1d2330');draw=ImageDraw.Draw(preview);draw.text((12,12),'Exact CoreMod fuel textures - flat preview, NOT in-game rendering',fill='white')
for i,(name,im) in enumerate(thumbs):
 x=(i%4)*210+30;y=(i//4)*90+40;im=im.resize((48,48),Image.Resampling.NEAREST);preview.paste(im,(x,y),im);draw.text((x-16,y+56),name,fill='white')
preview.save(D/'fuel-texture-preview.png')
report={'passed':True,'source_blocks_checked':len(checks),'tool_checks':checks,'fuel_conservation_recipes':len(ratios),'mutations_detected':rejections,'runtime_block_behavior_verified':False,'cross_mod_tool_tiers_verified':False,'definition_scope':'Only source-backed fuel blocks and statically mapped recipe subset; not a full pack.'};(D/'independent-block-checks.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps({k:v for k,v in report.items() if k!='tool_checks'}))
