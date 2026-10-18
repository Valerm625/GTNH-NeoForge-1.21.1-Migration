"""Actual inventory -> player NBT proposal -> independent durable journal composition."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/retrieval-proposal-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
extra=minecraft.with_name('server-1.21.1-20240808.144430-extra.jar')
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
cp=';'.join(relative(path) for path in [minecraft,extra]+libraries)
names=['LegacyRetrievalEngine','LegacyQuestRetrievalPlanner','LegacyRetrievalInventoryBridge','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyRetrievalTransactionProposal']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
check=R/'work/migration/LegacyRetrievalTransactionProposalCheck.java'
def run(executable,arguments,name):
 def quote(value):
  assert not any(c in value for c in '\x00\r\n')
  return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in arguments)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=60)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',cp,'-d','.']+[relative(p) for p in sources+[check]],'compile')
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyRetrievalTransactionProposalCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'proposal')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},'minecraft_jar_sha256':hashlib.sha256(minecraft.read_bytes()).hexdigest(),
 'limitations':['Actual vanilla Inventory(null), ItemStack codecs and real ledger files; synthetic player NBT and file writer, no world or native PlayerDataStorage.',
 'Inventory fixture encoding follows inspected Inventory.save slot order; the actual Inventory.save method needs a live Player and is not invoked.',
 'Exact vanilla predicates and actor/party authorization remain fixtures; oak logs are not a verified legacy logWood mapping.',
 'The proposal binds progress and inventory snapshots to an expected ledger SHA; a runtime coordinator must additionally enforce server thread, quiescence, baseline file persistence, save/readback and recovery admission.',
 'No game completion, reward, synchronization, death/clone or full NeoForge transformation is executed.']})
(out/'LegacyRetrievalTransactionProposalCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))
