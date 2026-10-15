"""Read-only source intake. Never execute archive scripts or load JAR code."""
from pathlib import Path, PurePosixPath
import zipfile, hashlib, json, collections, struct, stat, re, datetime, shutil, os
ROOT=Path(__file__).resolve().parents[2]
ARCHIVE=Path(os.environ.get('GTNH_LEGACY_ARCHIVE', str(ROOT/'inputs/GT_New_Horizons_2.8.4_Java_17-25.zip')))
if not ARCHIVE.is_file(): raise FileNotFoundError('Set GTNH_LEGACY_ARCHIVE to your locally supplied reference archive; no archive is included in Git.')
DEST=ROOT/'work/source-release-2.8.4'
REPORT=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation/docs/reference-2.8.4'
REPORT.mkdir(parents=True,exist_ok=True)
def write(name,obj):
    path=REPORT/name; path.write_text(json.dumps(obj,ensure_ascii=False,indent=2),encoding='utf-8')
def sha(path):
    with path.open('rb') as f: return hashlib.file_digest(f,'sha256').hexdigest()
def typed_strip(x):
    if isinstance(x,dict): return {re.sub(r':\d+$','',k):typed_strip(v) for k,v in x.items()}
    if isinstance(x,list): return [typed_strip(v) for v in x]
    return x
print('HASH_SOURCE',flush=True)
archive_hash=sha(ARCHIVE)
files=[]; seen={}; total=0; duplicates=[]; skip_entries=set()
with zipfile.ZipFile(ARCHIVE) as z:
    for e in z.infolist():
        n=e.filename.replace('\\','/'); p=PurePosixPath(n)
        if n.startswith('/') or any(x in ('.','..') for x in p.parts) or ':' in n or '\x00' in n: raise ValueError('Unsafe archive path: '+n)
        if stat.S_ISLNK(e.external_attr >> 16): raise ValueError('Symlink in archive: '+n)
        target=(DEST/Path(*p.parts)).resolve()
        if not target.is_relative_to(DEST.resolve()): raise ValueError('Path traversal: '+n)
        key=str(target).casefold()
        if key in seen:
            prev=seen[key]
            if prev.is_dir()!=e.is_dir() or (not e.is_dir() and z.read(prev)!=z.read(e)):
                raise ValueError('Conflicting duplicate path: '+n)
            duplicates.append({'canonical':prev.filename,'alias':e.filename,'bytes':e.file_size,'sha256':hashlib.sha256(z.read(e)).hexdigest() if not e.is_dir() else None,'action':'deduplicate identical bytes on Windows; original archive retained'})
            skip_entries.add(e.filename)
        else: seen[key]=e
        total+=e.file_size
    if total>8*1024**3: raise ValueError('Unexpected expanded size >8GiB')
    print('EXTRACT',len(z.infolist()),'entries;',total,'expanded bytes',flush=True)
    for i,e in enumerate(z.infolist()):
        if e.filename in skip_entries: continue
        target=DEST/Path(*PurePosixPath(e.filename.replace('\\','/')).parts)
        if e.is_dir(): target.mkdir(parents=True,exist_ok=True); continue
        target.parent.mkdir(parents=True,exist_ok=True)
        # Read every entry to EOF: Python ZIP reader also validates its CRC.
        with z.open(e) as src,target.open('wb') as out:
            h=hashlib.sha256()
            while b:=src.read(1024*1024): out.write(b); h.update(b)
        files.append({'path':e.filename,'size':e.file_size,'sha256':h.hexdigest()})
        if i and i%2500==0: print('EXTRACT_PROGRESS',i,flush=True)
root=DEST/'GT New Horizons 2.8.4'; mc=root/'.minecraft'
manifest=json.loads((root/'mmc-pack.json').read_text(encoding='utf-8-sig'))
write('launcher-manifest.json',manifest)
write('case-collision-audit.json',duplicates)
write('source-inventory.json',{'archive':str(ARCHIVE),'sha256':archive_hash,'file_count':len(files),'files':files})
jar_records=[]; namespaces=collections.Counter(); errors=[]
for i,p in enumerate(sorted(root.rglob('*.jar'))):
    rel=p.relative_to(root).as_posix()
    r={'path':rel,'filename':p.name,'size':p.stat().st_size,'sha256':sha(p),'metadata':None,'metadata_parse_error':None,'namespaces':{},'textures':0,'classes':0}
    try:
        with zipfile.ZipFile(p) as z:
            names=z.namelist(); metadata_names=[n for n in names if n.lower()=='mcmod.info']
            if metadata_names:
                text=z.read(metadata_names[0]).decode('utf-8-sig',errors='replace')
                try: r['metadata']=json.loads(text)
                except Exception as e: r['metadata_parse_error']=str(e); r['metadata_raw']=text[:24000]
            if 'META-INF/MANIFEST.MF' in names:
                text=z.read('META-INF/MANIFEST.MF').decode('utf-8',errors='replace')
                r['manifest_headers']={}
                for line in text.splitlines():
                    if ': ' in line:
                        k,v=line.split(': ',1)
                        if k in ['Implementation-Version','Implementation-Title','FMLCorePlugin','FMLCorePluginContainsFMLMod','FMLAT','TweakClass','Main-Class','Specification-Version']: r['manifest_headers'][k]=v
            counts=collections.Counter()
            for n in names:
                ps=n.split('/')
                if len(ps)>2 and ps[0]=='assets' and not n.endswith('/'): counts[ps[1]]+=1
            r['namespaces']=dict(counts); namespaces.update(counts)
            r['textures']=sum(n.endswith('.png') and '/textures/' in n for n in names)
            r['classes']=sum(n.endswith('.class') for n in names)
            r['embedded_jars']=[n for n in names if n.endswith('.jar')]
            bad=z.testzip()
            if bad: raise ValueError('CRC failure: '+bad)
            r['zip_crc_validated']=True
    except Exception as e: r['archive_error']=str(e); errors.append({'path':rel,'error':str(e)})
    jar_records.append(r)
    if (i+1)%50==0: print('JAR_INDEX',i+1,flush=True)
write('jar-inventory.json',jar_records)
write('asset-namespaces.json',dict(namespaces.most_common()))
config_files=[f for f in files if '/.minecraft/config/' in f['path']]
script_files=[f for f in files if '/.minecraft/scripts/' in f['path']]
jar_locations=collections.Counter(r['path'].rsplit('/',1)[0] for r in jar_records)
master=ROOT/'work/source/GT-New-Horizons-Modpack-master'
def configs_at(base):
    return {p.relative_to(base).as_posix():p for p in (base/'config').rglob('*') if p.is_file()}
old=configs_at(mc); new=configs_at(master)
changed=[]; equal=0
for n in sorted(old.keys()&new.keys()):
    a,b=sha(old[n]),sha(new[n])
    if a!=b: changed.append({'path':n,'release_2_8_4_sha256':a,'master_sha256':b})
    else: equal+=1
comparison={'release':'2.8.4','master_readme_version':'2.9.0-RC-2','same_path_identical':equal,'same_path_different':len(changed),'only_in_release':sorted(old.keys()-new.keys()),'only_in_master':sorted(new.keys()-old.keys()),'changed':changed,'interpretation':'Different snapshots, not a patch set. Same-name files are not interchangeable. Raw path differences include renamed quest files.'}
write('config-comparison.json',comparison)
summary={'checked_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'archive':str(ARCHIVE),'archive_sha256':archive_hash,'archive_bytes':ARCHIVE.stat().st_size,'expanded_bytes':total,'files':len(files),'jar_count':len(jar_records),'jar_locations':dict(jar_locations),'jar_crc_errors':errors,'identical_case_duplicate_files':len(duplicates),'configuration_files':len(config_files),'script_files':len(script_files),'jar_texture_pngs':sum(r['textures'] for r in jar_records),'asset_namespaces':len(namespaces),'runtime_executed':False,'destination':str(root),'components':manifest.get('components',[]),'config_comparison':{k:v for k,v in comparison.items() if k not in ['changed','only_in_release','only_in_master']},'only_release_config_paths':len(comparison['only_in_release']),'only_master_config_paths':len(comparison['only_in_master']),'porting_status':'reference-source-only; legacy jars are NOT valid NeoForge 1.21.1 mods'}
write('summary.json',summary)
print('INTAKE_SUMMARY',json.dumps(summary,ensure_ascii=False),flush=True)
