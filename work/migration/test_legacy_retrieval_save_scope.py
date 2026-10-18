"""Execute actual save-permit state machine independently of native game classes."""
from pathlib import Path
import hashlib,json,os,subprocess,tempfile
R=Path(__file__).resolve().parents[2];out=R/'work/migration/retrieval-save-scope-check';out.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
sources=[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalSaveScope.java',R/'work/migration/LegacyRetrievalSaveScopeCheck.java']
def relative(p):return Path(os.path.relpath(p,out)).as_posix()
def run(executable,args,name):
 def quote(value):return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
 argfile=out/(name+'.args');argfile.write_text('\n'.join(quote(v) for v in args)+'\n',encoding='utf-8',newline='\n')
 result=subprocess.run([str(executable),'@'+argfile.name],cwd=out,capture_output=True,timeout=60)
 log=(result.stdout+result.stderr).decode('utf-8',errors='replace');(out/(name+'.log')).write_text(log,encoding='utf-8',newline='\n')
 assert result.returncode==0,log
 return log
run(java.with_name('javac.exe'),['--release','21','-encoding','UTF-8','-d','.']+[relative(p) for p in sources],'compile')
log=run(java,['-cp','.','org.gtnhmigration.compat.LegacyRetrievalSaveScopeCheck',relative(Path(tempfile.mkdtemp(prefix='run-',dir=out)))],'scope')
report=json.loads(next(line for line in log.splitlines() if line.startswith('{')))
report['source_sha256']={p.relative_to(R).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources+[Path(__file__)]}
report['limitations']=['Executes the production permit state machine and real Java threads; native save bodies are synthetic callbacks.',
 'Separate Sponge tests verify actual injections on pinned bytecode, without native class definition or full NeoForge launch.',
 'Scope quarantine is process-local. No persistent world admission binding, arbitrary mod inventory writes, external filesystem lock, power-loss recovery or complete lifecycle isolation is proven.']
(out/'LegacyRetrievalSaveScopeCheck.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8',newline='\n');print(json.dumps(report))
