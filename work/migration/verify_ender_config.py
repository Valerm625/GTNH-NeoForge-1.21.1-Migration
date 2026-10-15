from pathlib import Path
import json,re,subprocess,zipfile
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';java=Path(json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'))['java']);classes=W/'migration/java-checks';classes.mkdir(exist_ok=True)
with zipfile.ZipFile(O/'mods/CodeChickenLib-1.21.1-4.6.1.529.jar') as z:
 p='META-INF/jarjar/Quack-0.4.10.115.jar';(W/'cache/Quack-0.4.10.115.jar').write_bytes(z.read(p))
args=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8');libs=[O/x for x in re.search(r'^-DlegacyClassPath=(.*)$',args,re.M).group(1).strip().split(';')]
libs=[O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar',O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar',O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']+libs+[O/'mods/CodeChickenLib-1.21.1-4.6.1.529.jar',W/'cache/Quack-0.4.10.115.jar'];cp=';'.join(p.relative_to(R).as_posix() for p in libs)
arg=W/'migration/config-javac.args';arg.write_text('-proc:none\n-cp\n"'+cp+'"\n-d\nwork/migration/java-checks\nwork/migration/EnderConfigRoundTrip.java\n',encoding='ascii');r=subprocess.run([str(java.with_name('javac.exe')),'@work/migration/config-javac.args'],cwd=R,capture_output=True,timeout=40);assert r.returncode==0,r.stderr.decode(errors='replace')
# Test a copy to avoid re-overwriting user changes to actual config on a routine validation run.
import shutil
live=O/'config/EnderStorage.cfg';target=W/'migration/ender-config-validation.cfg'
if live.exists():shutil.copy2(live,target)
arg=W/'migration/config-java.args';arg.write_text('-cp\n"work/migration/java-checks;'+cp+'"\nEnderConfigRoundTrip\n"'+target.relative_to(R).as_posix()+'"\n--read-only\n',encoding='ascii');r=subprocess.run([str(java),'@work/migration/config-java.args'],cwd=R,capture_output=True,timeout=40);text=(r.stdout+r.stderr).decode('utf-8',errors='replace');assert r.returncode==0,text
if not live.exists():shutil.copy2(target,live)
report=json.loads(next(l for l in text.splitlines() if l.startswith('{')));(O/'docs/verification/ender-config-roundtrip.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
