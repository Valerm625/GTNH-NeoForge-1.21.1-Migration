from pathlib import Path
import json,subprocess,zipfile,hashlib,re,shutil
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';P=W/'compat-mod';src=P/'src/main/java/org/gtnhmigration/compat';resources=P/'src/main/resources';src.mkdir(parents=True,exist_ok=True);(resources/'META-INF').mkdir(parents=True,exist_ok=True)
legacy=W/'source/GT-New-Horizons-Modpack-master/config/EnderStorage.cfg';text=legacy.read_text(encoding='utf-8');buckets=int(re.search(r'^enderTankSize=(\d+)$',text,re.M).group(1));assert buckets==256
es=O/'mods/EnderStorage-1.21.1-2.13.0.191.jar';rt=json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'));java=Path(rt['java']);javap=java.with_name('javap.exe')
probe=subprocess.run([str(javap),'-classpath',str(es),'-c','-p','codechicken.enderstorage.storage.EnderLiquidStorage'],capture_output=True,timeout=20).stdout.decode(errors='replace');clinit=probe[probe.index('static {};'):];assert len(re.findall(r'\bbipush\s+16\b',clinit))==1 and 'Field codechicken/lib/fluid/FluidUtils.B:I' in clinit and 'imul' in clinit
entry='''package org.gtnhmigration.compat;
import net.neoforged.fml.common.Mod;
/** Explicit GTNH migration adaptations, not a claim of complete port. */
@Mod("gtnh_migration_compat")
public final class MigrationCompat { public MigrationCompat() {} }
'''
mixin='''package org.gtnhmigration.compat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
/** Source: supplied master config/EnderStorage.cfg, enderTankSize=256.
 * Pinned against EnderStorage 2.13.0.191. Leave third-party JAR intact.
 * Changes the shared CAPACITY constant for server storage, cleared storage,
 * and client rendering/sync users consistently; do not patch getCapacity alone.
 */
@Mixin(targets="codechicken.enderstorage.storage.EnderLiquidStorage", remap=false)
public abstract class EnderTankCapacityMixin {
    @ModifyConstant(method="<clinit>", constant=@Constant(intValue=16), require=1, allow=1, remap=false)
    private static int gtnhMigration$restoreBucketCount(int original) {
        if (original != 16) throw new IllegalStateException("Unexpected EnderStorage capacity; re-audit target version");
        return 256;
    }
}
'''
(src/'MigrationCompat.java').write_text(entry,encoding='utf-8');(src/'EnderTankCapacityMixin.java').write_text(mixin,encoding='utf-8')
(resources/'META-INF/neoforge.mods.toml').write_text('''modLoader="javafml"
loaderVersion="[4,)"
license="MIT"
[[mods]]
modId="gtnh_migration_compat"
version="0.1.0"
displayName="GTNH Migration Compatibility (incomplete)"
authors="Local migration project"
description="Source-backed, version-pinned adaptations; full GTNH functionality is NOT implemented."
[[dependencies.gtnh_migration_compat]]
modId="minecraft"
type="required"
versionRange="[1.21.1]"
ordering="NONE"
side="BOTH"
[[dependencies.gtnh_migration_compat]]
modId="neoforge"
type="required"
versionRange="[21.1.262,22)"
ordering="NONE"
side="BOTH"
[[dependencies.gtnh_migration_compat]]
modId="enderstorage"
type="required"
versionRange="[2.13.0.191]"
ordering="AFTER"
side="BOTH"
[[dependencies.gtnh_migration_compat]]
modId="ftbquests"
type="required"
versionRange="[2101.1.36]"
ordering="AFTER"
side="BOTH"
[[mixins]]
config="gtnh_migration_compat.mixins.json"
''',encoding='utf-8')
(resources/'gtnh_migration_compat.mixins.json').write_text(json.dumps({'required':True,'minVersion':'0.8','package':'org.gtnhmigration.compat','compatibilityLevel':'JAVA_21','mixins':['EnderTankCapacityMixin','LegacyRetrievalItemTaskMixin','LegacyDimensionLocationTaskMixin'],'injectors':{'defaultRequire':1}},indent=2),encoding='utf-8')
(resources/'LICENSE').write_text('''MIT License
Copyright (c) 2026 Local GTNH migration project
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
''',encoding='utf-8')
args=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8');libs=[O/x for x in re.search(r'^-DlegacyClassPath=(.*)$',args,re.M).group(1).strip().split(';')];libs += [O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar', O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar', O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar'] + list((O/'mods').glob('*.jar'));cp=';'.join(p.relative_to(R).as_posix() for p in libs);classes=P/'build/classes';classes.mkdir(parents=True,exist_ok=True)
argfile=P/'javac.args';files=sorted(src.glob('*.java'));argfile.write_text('-proc:none\n--release\n21\n-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\n'+classes.relative_to(R).as_posix()+'\n'+'\n'.join(p.relative_to(R).as_posix() for p in files)+'\n',encoding='ascii')
p=subprocess.run([str(java.with_name('javac.exe')),'@'+argfile.relative_to(R).as_posix()],cwd=R,capture_output=True,timeout=45);assert p.returncode==0,p.stderr.decode(errors='replace')
jar=O/'mods/gtnh-migration-compat-0.1.0.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for base in [classes,resources]:
  for p in sorted(base.rglob('*')):
   if p.is_file():
    zi=zipfile.ZipInfo(p.relative_to(base).as_posix(),date_time=(2026,10,15,0,0,0));zi.compress_type=zipfile.ZIP_DEFLATED;z.writestr(zi,p.read_bytes())
shutil.copy2(jar,W/'neoforge-smoke/mods'/jar.name)
sourceout=O/'tools/compat-mod';shutil.copytree(P/'src',sourceout/'src',dirs_exist_ok=True)
lock={'filename':jar.name,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'version':'0.1.0','mod_id':'gtnh_migration_compat','source':'tools/compat-mod/src','original_config_sha256':hashlib.sha256(legacy.read_bytes()).hexdigest(),'target_artifact':es.name,'target_artifact_sha256':hashlib.sha256(es.read_bytes()).hexdigest(),'target_class':'codechicken.enderstorage.storage.EnderLiquidStorage','original_capacity_buckets':16,'migrated_capacity_buckets':buckets,'migrated_capacity_mb':buckets*1000,'injection_expected_matches':1,'compile_passed':True,'actual_mixin_transformation_verified':False,'runtime_storage_verified':False,'quest_adapter':'scoped retrieval + opt-in dimension-only location','quest_adapters':['scoped single-item legacy retrieval','opt-in dimension-only location'],'quest_adapter_live_verified':False}
(O/'docs/compat-mod.lock.json').write_text(json.dumps(lock,indent=2),encoding='utf-8');print(json.dumps(lock))
