"""Exercise versioned UUID ledger, actual NBT files and killed-JVM recovery.

A cooperating exclusive lock and same-directory atomic rename are tested locally.
This is not a world integration, inventory transaction or power-failure proof.
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, tempfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/quest-progress-store-check'
out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
classpath=';'.join(relative(path) for path in [minecraft]+libraries)
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
check=R/'work/migration/LegacyQuestProgressStoreCheck.java'
def run(executable,arguments,name,expected=0):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args')
 argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=60)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace')
 (out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==expected,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',classpath,'-d','.']+[relative(p) for p in sources+[check]],'compile')
prefix=['-cp','.;'+classpath,'org.gtnhmigration.compat.LegacyQuestProgressStoreCheck']
# Retain all test artifacts as evidence; never touch world data.
fixture=Path(tempfile.mkdtemp(prefix='run-',dir=out))
log=run(java,prefix+['test',relative(fixture)],'store')
report=json.loads(next(line for line in log.splitlines() if line.startswith('{')))
crashes=[]
for boundary in ['TEMP_OPENED','TEMP_FORCED','PUBLISHED']:
 directory=fixture/boundary
 run(java,prefix+['seed',relative(directory)],'seed-'+boundary)
 before={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in directory.glob('*.nbt')}
 run(java,prefix+['crash',relative(directory),boundary],'crash-'+boundary,71)
 committed=boundary=='PUBLISHED'
 run(java,prefix+['verify',relative(directory),'99' if committed else '1','2' if committed else '1'],'verify-'+boundary)
 for name,digest in before.items():assert hashlib.sha256((directory/name).read_bytes()).hexdigest()==digest
 pending=list(directory.glob('pending-*.tmp'))
 assert len(pending)==(0 if committed else 1)
 crashes.append({'boundary':boundary,'child_exit_code':71,'verified_revision_after_restart':2 if committed else 1,'prior_frame_unchanged':True,'pending_files_preserved':len(pending)})
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},
 'minecraft_jar_sha256':hashlib.sha256(minecraft.read_bytes()).hexdigest(),'process_crash_checks':crashes,
 'limitations':['Synthetic UUID progress, no real player save fixture or server lifecycle registration.',
 'Archive stores exact source bytes but does not assert that arbitrary bytes were imported successfully.',
 'No inventory, reward, party, network or FTB transaction was executed.',
 'Process-halt recovery on this filesystem does not prove machine-power-loss durability or detect manual removal of the entire latest tail.',
 'All immutable frames are retained; compaction and ledgers exceeding 64 MiB per frame are not supported and are never silently truncated.']})
(out/'LegacyQuestProgressStoreCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(report))
