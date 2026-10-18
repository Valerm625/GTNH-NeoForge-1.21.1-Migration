"""Read all pinned source definitions through the production archive reader."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile,zipfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/quest-definition-archive-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
extra=minecraft.with_name('server-1.21.1-20240808.144430-extra.jar')
patched=O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar'
universal=patched.with_name('neoforge-21.1.262-universal.jar')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
assert sha(patched)=='f57ce3018e21459d24b50508e8c1b4fd5c16324ec1dc5f316c192aa0130e7fe0'
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
cp=';'.join(relative(path) for path in [minecraft,extra]+libraries)
compile_cp=';'.join(relative(path) for path in [patched,universal])+';'+cp
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader','LegacyQuestWorldStore','LegacyQuestDefinitionArchive','LegacyTypedNbtJsonReader']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
checks=[R/'work/migration/LegacyQuestDefinitionArchiveCheck.java']
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=90)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',compile_cp,'-d','.']+[relative(p) for p in sources+checks],'compile')
pin=R/'work/compat-mod/src/main/resources/gtnh_migration/quest-definitions.pin'
log=run(java,['-Xmx1g','-cp','.;'+relative(pin.parent.parent)+';'+cp,'org.gtnhmigration.compat.LegacyQuestDefinitionArchiveCheck',relative(O),relative(R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests'),relative(Path(tempfile.mkdtemp(prefix='run-',dir=out))),relative(pin)],'definition-archive')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in sources+checks+[Path(__file__),pin]},'minecraft_jar_sha256':sha(minecraft),'archive_sha256':pin.read_text(encoding='ascii').splitlines()[1],
 'limitations':['Reads actual pinned definitions and synthetic world ledgers; no native server/world/player execution.',
 'Catalog counts retrieval progress payloads only; no unsupported task, reward, ore equivalence, party or UI is activated.',
 'Digest and bounded file read checks do not establish malicious-writer or power-loss protection.']})
(out/'LegacyQuestDefinitionArchiveCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))
