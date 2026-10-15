from pathlib import Path
import json,re,subprocess,zipfile
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/quest-migration/ftb-export';java=Path(json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'))['java']);classes=W/'migration/java-checks';classes.mkdir(exist_ok=True)
launch=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8');libs=[O/x for x in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M).group(1).strip().split(';')]
libs=[O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar',O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar',O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']+libs+list((O/'mods').glob('*.jar'))
cp=';'.join(p.relative_to(R).as_posix() for p in libs)
arg=W/'migration/quest-tests-javac.args';arg.write_text('-proc:none\n-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\nwork/migration/java-checks\nwork/migration/LegacyRetrievalCounterCheck.java\nwork/migration/QuestSnbtCheck.java\nwork/migration/QuestAdapterShapeCheck.java\n',encoding='ascii')
r=subprocess.run([str(java.with_name('javac.exe')),'@work/migration/quest-tests-javac.args'],cwd=R,capture_output=True,timeout=60);assert r.returncode==0,r.stderr.decode(errors='replace')
for cls,args in [('QuestAdapterShapeCheck',[(O/'mods/ftb-quests-neoforge-2101.1.36.jar').relative_to(R).as_posix(),(O/'mods/gtnh-migration-compat-0.1.0.jar').relative_to(R).as_posix()]),('LegacyRetrievalCounterCheck',[]),('QuestSnbtCheck',[(O/'config/ftbquests/quests').relative_to(R).as_posix(),(D/'actual-snbt-parsed.json').relative_to(R).as_posix()])]:
 arg=W/'migration/quest-tests-java.args';arg.write_text('-cp\n"work/migration/java-checks;'+cp+'"\n'+cls+'\n'+'\n'.join('"'+a+'"' for a in args)+'\n',encoding='ascii')
 r=subprocess.run([str(java),'@work/migration/quest-tests-java.args'],cwd=R,capture_output=True,timeout=60);text=(r.stdout+r.stderr).decode('utf-8',errors='replace');(D/(cls+'.log')).write_text(text,encoding='utf-8');print(cls,r.returncode,text[-3000:]);assert r.returncode==0
 result=json.loads(next(l for l in text.splitlines() if l.startswith('{')));(D/(cls+'.json')).write_text(json.dumps(result,indent=2),encoding='utf-8')
