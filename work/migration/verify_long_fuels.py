"""Repeatable real Java codec test using the installed launcher's exact dependency classpath."""
from pathlib import Path
import json,re,subprocess,hashlib,shutil
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/fuel-block-migration'
java=Path(json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'))['java']);argsfile=O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt';args=argsfile.read_text(encoding='utf-8')
libs=[O/x for x in re.search(r'^-DlegacyClassPath=(.*)$',args,re.M).group(1).strip().split(';')]
priority=[O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar',O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar',O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']
cp=';'.join(p.relative_to(R).as_posix() for p in priority+libs);classes=W/'migration/java-checks';classes.mkdir(exist_ok=True)
compileargs=W/'migration/codec-javac.args';compileargs.write_text('-proc:none\n-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\nwork/migration/java-checks\nwork/migration/LongFuelRoundTrip.java\n',encoding='ascii')
r=subprocess.run([str(java.with_name('javac.exe')),'@work/migration/codec-javac.args'],cwd=R,capture_output=True,timeout=60);assert r.returncode==0,r.stderr.decode(errors='replace')
runargs=W/'migration/codec-java.args';runargs.write_text('-cp\n"work/migration/java-checks;'+cp+'"\nLongFuelRoundTrip\n',encoding='ascii')
r=subprocess.run([str(java),'@work/migration/codec-java.args'],cwd=R,capture_output=True,timeout=60);t=(r.stdout+r.stderr).decode('utf-8',errors='replace');(D/'actual-codec-test.log').write_text(t,encoding='utf-8');assert r.returncode==0,t
result=json.loads(next(l for l in t.splitlines() if l.startswith('{')))
patched=(W/'migration/patched-AbstractFurnaceBlockEntity.txt').read_text(encoding='utf-8');sync=(W/'migration/patched-ServerPlayer$1.txt').read_text(encoding='utf-8');codec=(W/'migration/patched-AdvancedContainerSetDataPayload.txt').read_text(encoding='utf-8')
assert re.search(r'String BurnTime\s*\d+: invokevirtual[^\n]+CompoundTag.getInt:',patched)
assert re.search(r'String BurnTime\s*\d+: aload_0\s*\d+: getfield[^\n]+litTime:I\s*\d+: invokevirtual[^\n]+CompoundTag.putInt:',patched)
assert 'hasChannel:' in sync and 'AdvancedContainerSetDataPayload' in sync and 'ByteBufCodecs.VAR_INT' in codec
result['patched_furnace_save_uses_int']=True;result['server_uses_advanced_channel_when_available']=True;result['custom_patch_added']=False;result['exact_launcher_classpath_used']=True
result['limitations']=['No world save/reload or connected client was exercised.','Vanilla clients without the NeoForge advanced data channel fall back to short packets.','Behavior after consuming last fuel item and restarting still needs full runtime validation.']
result['source_jar_sha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in priority}
(D/'long-fuel-verification.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
p=D/'fuel-blocks.json';d=json.loads(p.read_text(encoding='utf-8'));d['vanilla_short_save_risk']=False;d['long_burn_platform_status']='Installed NeoForge patches use int NBT save and VAR_INT advanced sync; 120 actual codec roundtrips passed, full gameplay remains untested.';d['risks'][0]='Long burn codecs validated against installed NeoForge; actual furnace save/reload and connected-menu sync still untested.';p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
for name in ['legacy-Block.bytecode.txt','patched-AbstractFurnaceBlockEntity.txt','patched-ServerPlayer$1.txt','patched-AdvancedContainerSetDataPayload.txt']:
 shutil.copy2(W/'migration'/name,D/name)
print(json.dumps(result,ensure_ascii=False))
