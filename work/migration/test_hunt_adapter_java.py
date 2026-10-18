"""Check compiled hunt predicate and both games' class ancestry without a world."""
from pathlib import Path
import hashlib, json, os, re, subprocess

R=Path(__file__).resolve().parents[2]
W=R/'work'; O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
I=Path(os.environ.get('GTNH_TEST_INPUT_ROOT',str(R))).resolve()
INPUT_O=I/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
# An explicitly separate dependency root is read-only. Its old compat JAR is
# never used implicitly; an independently compiled current-source JAR is needed.
D=(W/'hunt-test-review') if I != R else O/'docs/quest-migration/ftb-export'
D.mkdir(parents=True,exist_ok=True)
java=Path(json.loads((I/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch=(INPUT_O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
mc=INPUT_O/'libraries/net/minecraft/server/1.21.1-20240808.144430'
libs=[mc/'server-1.21.1-20240808.144430-srg.jar',mc/'server-1.21.1-20240808.144430-extra.jar']
libs += [INPUT_O/x for x in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
compat=Path(os.environ.get('GTNH_TEST_COMPAT_JAR',str(O/'mods/gtnh-migration-compat-0.1.0.jar'))).resolve()
def relative(path): return os.path.relpath(path,R).replace('\\','/')
cp=';'.join(relative(p) for p in [compat,*libs])
classes=W/'migration/hunt-java-checks'; classes.mkdir(exist_ok=True)
arg=W/'migration/hunt-javac.args'
arg.write_text('-proc:none\n-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\nwork/migration/hunt-java-checks\nwork/migration/LegacyHuntAdapterCheck.java\n',encoding='utf-8')
p=subprocess.run([str(java.with_name('javac.exe')),'@work/migration/hunt-javac.args'],cwd=R,capture_output=True,timeout=60)
assert p.returncode==0,p.stderr.decode(errors='replace')
inputs=[I/'work/cache/legacy-platform/minecraft-server-1.7.10.jar', I/'work/cache/legacy-platform/joined.srg',
        INPUT_O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar',
        INPUT_O/'mods/ftb-quests-neoforge-2101.1.36.jar',compat]
arg=W/'migration/hunt-java.args'
arg.write_text('-cp\n"work/migration/hunt-java-checks;'+cp+'"\nLegacyHuntAdapterCheck\n'+
               '\n'.join('"'+relative(p)+'"' for p in inputs)+'\n',encoding='utf-8')
p=subprocess.run([str(java),'@work/migration/hunt-java.args'],cwd=R,capture_output=True,timeout=60)
text=(p.stdout+p.stderr).decode('utf-8',errors='replace')
(D/'LegacyHuntAdapterCheck.log').write_text(text,encoding='utf-8')
assert p.returncode==0,text
report=json.loads(next(line for line in text.splitlines() if line.startswith('{')))
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
report['compat_jar_sha256']=sha(compat)
report['source_sha256']={relative(path):sha(path) for path in [
    W/'compat-mod/src/main/java/org/gtnhmigration/compat/mixin/LegacyHuntTaskMixin.java',
    W/'compat-mod/src/main/java/org/gtnhmigration/compat/LegacyHuntEntityMatcher.java',
    W/'migration/LegacyHuntAdapterCheck.java',Path(__file__).resolve(),
]}
report['dependency_sha256']={path.name:sha(path) for path in inputs[:-1]}
report['isolated_dependency_root']=I!=R
(D/'LegacyHuntAdapterCheck.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report))
