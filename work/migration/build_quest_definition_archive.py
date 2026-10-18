"""Build a content-addressed runtime definition archive from the pinned user's master ZIP."""
from pathlib import Path
import hashlib,io,json,os,struct,zipfile
from legacy_vanilla_items import resolve_legacy_vanilla
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
B=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests'
archive=Path(os.environ.get('GTNH_MASTER_ARCHIVE',str(R/'inputs/GT-New-Horizons-Modpack-master.zip')))
if not archive.exists():
 fallback=Path('D:/格雷科技迁移相关/GT-New-Horizons-Modpack-master.zip')
 if fallback.exists():archive=fallback
pin=json.loads((R/'evidence/source-archive-audit.json').read_text(encoding='utf-8'))['master']['archive_sha256']
def sha(b):return hashlib.sha256(b).hexdigest()
assert sha(archive.read_bytes())==pin,'Master archive changed'
refs_path=O/'docs/quest-migration/item-registry-references.json'
references=refs_path.read_bytes();refs=json.loads(references)
mappings={}
for row in refs:
 identity=row['identity']
 if identity['tag'] or identity['ore_dict']:continue
 old,damage=identity['id'],identity['damage']
 target=row.get('target_id');vanilla=resolve_legacy_vanilla(old,damage)
 assert not (target and vanilla and target!=vanilla),(old,damage)
 target=target or vanilla
 if target:
  previous=mappings.setdefault((old,damage),target);assert previous==target
buffer=io.BytesIO();buffer.write(b'GTNHQDA1')
def number(value):buffer.write(struct.pack('>i',value))
def blob(value):number(len(value));buffer.write(value)
def text(value):blob(value.encode('utf-8'))
# Explicit portable ordering matches the pinned Windows source tree.
paths=sorted((p for p in B.rglob('*') if p.is_file()),key=lambda p:(p.relative_to(B).as_posix().lower(),p.relative_to(B).as_posix()));assert len(paths)==7806
number(len(paths));entries=[];quest_ids=set();quest_count=0
prefix='GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/'
with zipfile.ZipFile(archive) as z:
 original={n[len(prefix):] for n in z.namelist() if n.startswith(prefix) and not n.endswith('/')}
 assert original=={p.relative_to(B).as_posix() for p in paths},'Definition tree is incomplete or has extra files'
 for p in paths:
  relative=p.relative_to(B).as_posix();raw=p.read_bytes();assert z.read(prefix+relative)==raw,relative
  text(relative);is_quest=relative.startswith('Quests/') and relative.endswith('.json');buffer.write(bytes([is_quest]))
  if is_quest:
   obj=json.loads(raw);high=obj['questIDHigh:4'];low=obj['questIDLow:4'];qid=(high,low);assert qid not in quest_ids;quest_ids.add(qid);quest_count+=1;buffer.write(struct.pack('>qq',high,low))
  blob(raw);buffer.write(hashlib.sha256(raw).digest());entries.append({'path':relative,'bytes':len(raw),'sha256':sha(raw)})
assert quest_count==3814
split=buffer.tell();blob(references);number(len(mappings))
for (old,damage),target in sorted(mappings.items()):text(old);number(damage);text(target)
raw=buffer.getvalue();digest=sha(raw);definition_sha=sha(raw[:split]);identity_sha=sha(raw[split:])
out=O/'config/gtnh_migration/quest-definitions';out.mkdir(parents=True,exist_ok=True);target=out/(digest+'.bin')
if target.exists():assert target.read_bytes()==raw
else:
 temporary=out/(digest+'.new');assert not temporary.exists(),'Unpublished archive exists; inspect it before retrying'
 temporary.write_bytes(raw);temporary.replace(target)
resource=R/'work/compat-mod/src/main/resources/gtnh_migration/quest-definitions.pin';resource.parent.mkdir(parents=True,exist_ok=True)
resource.write_text('GTNHQDP1\n'+digest+'\n'+definition_sha+'\n'+identity_sha+'\n',encoding='ascii',newline='\n')
report={'passed':True,'archive_sha256':digest,'definition_sha256':definition_sha,'identity_sha256':identity_sha,'archive_bytes':len(raw),'source_document_count':len(entries),'source_quest_count':quest_count,'mapped_plain_identities':len(mappings),'master_archive_sha256':pin,'identity_reference_sha256':sha(references),'documents':entries,'source_sha256':{p.relative_to(R).as_posix():sha(p.read_bytes()) for p in [Path(__file__),R/'work/migration/legacy_vanilla_items.py']},'game_runtime_verified':False,'full_parity':False}
destination=O/'docs/quest-migration/definition-archive.json';destination.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps({k:v for k,v in report.items() if k not in ['documents','source_sha256']}))
