"""Compose actual definition decoding, progress identity and ordered pure planning.

An independent ledger is saved and reopened; no game/world import, modern ore membership or FTB submission is tested.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import tempfile

R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/progress-import-pipeline-check'
out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
fixture=R/'work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/AndSoItBegins-AAAAAAAAAAAAAAAAAAAAAA==/SticksnStones-AAAAAAAAAAAAAAAAAAAAAg==.json'
fixture_sha='b10a26b4012b0b407dd97bb5d486fbf4d819ee85c37248ea8ab61946e2d05f6b'
assert hashlib.sha256(fixture.read_bytes()).hexdigest()==fixture_sha,'Changed actual definition'
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
classpath=';'.join(relative(path) for path in [minecraft]+libraries)
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestRetrievalPlanner','LegacyQuestProgressEnvelope','LegacyTypedNbtJsonReader','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
check=R/'work/migration/LegacyProgressImportPipelineCheck.java'
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args')
 argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=60)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace')
 (out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',classpath,'-d','.']+[relative(p) for p in sources+[check]],'compile')
store_directory=Path(tempfile.mkdtemp(prefix='ledger-',dir=out))
log=run(java,['-cp','.;'+classpath,'LegacyProgressImportPipelineCheck',relative(fixture),relative(store_directory)],'composition')
report=json.loads(next(line for line in log.splitlines() if line.startswith('{')))
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},
 'definition_fixture_sha256':fixture_sha,'minecraft_jar_sha256':hashlib.sha256(minecraft.read_bytes()).hexdigest(),
 'limitations':['Uses a real mainline quest definition but synthetic player progress, authorization and immutable symbolic item predicates.',
 'logWood is a source-symbol fixture, not a mapped modern item tag or a verified registry membership set.',
 'Imported and planned UUID states survive independent ledger close/reopen; no world save, real inventory, quest completion, reward, party lifecycle or FTB operation runs.',
 'Individual fixed-source differential checks remain separate; this is a composition check, not a new original runtime oracle.']})
(out/'LegacyProgressImportPipelineCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n')
print(json.dumps(report))
