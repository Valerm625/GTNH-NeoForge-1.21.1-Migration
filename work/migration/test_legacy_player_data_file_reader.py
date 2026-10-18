"""Authoritative UUID.dat readback, integrity failures and file-backed journal resolution."""
from pathlib import Path
import hashlib,json,os,re,subprocess,tempfile
R=Path(__file__).resolve().parents[2]
O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out=R/'work/migration/player-file-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries=[O/path for path in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
minecraft=O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
extra=minecraft.with_name('server-1.21.1-20240808.144430-extra.jar')
def relative(path):return Path(os.path.relpath(path,out)).as_posix()
cp=';'.join(relative(path) for path in [minecraft,extra]+libraries)
names=['LegacyRetrievalEngine','LegacyRetrievalProgressCodec','LegacyQuestProgressEnvelope','LegacyQuestProgressStoreCodec','LegacyQuestProgressFileStore','LegacyRetrievalTransactionJournal','LegacyPlayerDataFileReader']
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/(name+'.java') for name in names]
check=R/'work/migration/LegacyPlayerDataFileReaderCheck.java'
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
log=run(java,['-cp','.;'+cp,'org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'reader')
report=json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
report.update({'source_sha256':{p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[check,Path(__file__)]},'minecraft_jar_sha256':hashlib.sha256(minecraft.read_bytes()).hexdigest(),
 'limitations':['Synthetic player data written with actual Minecraft NBT codecs; no PlayerDataStorage.save, live Player, world or data fixer executes.',
 'Only UUID.dat is read. Corrupt or absent primary blocks journal recovery even when dat_old is valid; no file is repaired, overwritten or created by the reader.',
 'Raw gzip bytes and decoded NBT snapshots are retained; bounds are 64 MiB compressed/decompressed and 256 MiB NBT accounting. Oversized files are preserved and rejected, not truncated.',
 'File attributes and a second digest detect changes but do not lock native saves or external writers. The caller must keep the player lifecycle and file writers quiescent through resolution.',
 'The passed directory must be the trusted world player directory. This is not hostile filesystem containment, player authentication or full item/schema validation.',
 'Symlink rejection is exercised only if the current filesystem permits creating the test link; see symbolic_link_checked.']})
(out/'LegacyPlayerDataFileReaderCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))
