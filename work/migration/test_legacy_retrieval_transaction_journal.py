"""Independent ledger + synthetic player-file recovery, not native PlayerDataStorage."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/retrieval-transaction-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libs=[O/p for p in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
mc=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
def rel(p):return Path(os.path.relpath(p,out)).as_posix()
cp=';'.join(rel(p) for p in [mc]+libs)
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(n+'.java') for n in names]
check=R/'work/migration/LegacyRetrievalTransactionJournalCheck.java'
def run(executable,args,name,expected=0):
 def quote(v):
  assert not any(c in v for c in '\x00\r\n')
  return '"'+v.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in args)+'\n',encoding='utf-8',newline='\n')
 p=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=60)
 log=(p.stdout+p.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert p.returncode==expected,name+' failed:\n'+log
 return log
run(java.with_name('javac.exe'),['-proc:none','--release','21','-encoding','UTF-8','-cp',cp,'-d','.']+[rel(p) for p in sources+[check]],'compile')
prefix=['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyRetrievalTransactionJournalCheck']
fixture=Path(tempfile.mkdtemp(prefix='run-',dir=out))
log=run(java,prefix+['test',rel(fixture)],'journal')
report=json.loads(next(line for line in log.splitlines() if line.startswith('{')))
cuts=[]
for boundary in ['PREPARE_TEMP','PREPARED','PLAYER_TEMP','PLAYER_PUBLISHED','RESOLVE_TEMP','RESOLVE_PUBLISHED','RESOLVED']:
 base=fixture/boundary;run(java,prefix+['seed',rel(base)],'seed-'+boundary)
 original={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (base/'ledger').glob('*.nbt')}
 run(java,prefix+['crash',rel(base),boundary],'crash-'+boundary,72)
 player=hashlib.sha256((base/'player.dat').read_bytes()).hexdigest()
 committed=boundary in ['PLAYER_PUBLISHED','RESOLVE_TEMP','RESOLVE_PUBLISHED','RESOLVED']
 run(java,prefix+['recover',rel(base),str(committed).lower()],'recover-'+boundary)
 run(java,prefix+['recover',rel(base),str(committed).lower()],'replay-'+boundary)
 assert hashlib.sha256((base/'player.dat').read_bytes()).hexdigest()==player,'Recovery rewrote player data'
 for name,digest in original.items():assert hashlib.sha256((base/'ledger'/name).read_bytes()).hexdigest()==digest
 cuts.append({'boundary':boundary,'child_exit_code':72,'committed':committed,'player_file_unchanged_by_recovery':True,'prior_ledger_frames_retained':True,'second_recovery_idempotent':True})
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},'minecraft_jar_sha256':hashlib.sha256(mc.read_bytes()).hexdigest(),'process_crash_checks':cuts,
 'limitations':['Synthetic full player NBT and a fixture atomic file writer, not native PlayerDataStorage or a world.',
 'The journal never writes player inventory; an actual coordinator must persist marker+inventory together, read back the authoritative file without dat_old fallback, and gate normal play until resolved.',
 'Accepted before/after player inventories and progress states must come from independently verified runtime snapshots and planning; this journal is not item matching, authorization or inventory validation.',
 'Aborting a pending transaction does not undo a live in-memory inventory; a future coordinator must restore/reload it before play resumes.',
 'Death/clone/logout, native-save failure/readback, power loss, reward events, party and networking remain unverified.',
 'The existing append-only ledger retains source and transaction bytes; limits/compaction and manual removal of a complete history tail remain unresolved.']})
(out/'LegacyRetrievalTransactionJournalCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))
