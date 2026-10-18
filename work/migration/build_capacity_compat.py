from pathlib import Path
import json,subprocess,zipfile,hashlib,re,shutil,io
from gtceu_artifact import runtime_jar, original_jar
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';P=W/'compat-mod';src=P/'src/main/java/org/gtnhmigration/compat';resources=P/'src/main/resources';(src/'mixin').mkdir(parents=True,exist_ok=True);src.mkdir(parents=True,exist_ok=True);(resources/'META-INF').mkdir(parents=True,exist_ok=True)
legacy=W/'source/GT-New-Horizons-Modpack-master/config/EnderStorage.cfg';text=legacy.read_text(encoding='utf-8');buckets=int(re.search(r'^enderTankSize=(\d+)$',text,re.M).group(1));assert buckets==256
gtpin=json.loads((R/'locks/gtceu-client-isolation.json').read_text(encoding='utf-8'));gtjar=runtime_jar(O)
assert hashlib.sha256(original_jar().read_bytes()).hexdigest()==gtpin['sha256'],'Re-audit GTCEu client isolation for changed upstream artifact'
with zipfile.ZipFile(gtjar) as z:
 for name,digest in gtpin['classes'].items():assert hashlib.sha256(z.read(name)).hexdigest()==digest,name
 librarypin=json.loads((R/'locks/library-client-callbacks.json').read_text(encoding='utf-8'))
 assert original_jar().name==librarypin['gtceu_filename'] and gtpin['sha256']==librarypin['gtceu_sha256']
 ldbytes=z.read(librarypin['ldlib_entry'])
 assert hashlib.sha256(ldbytes).hexdigest()==librarypin['ldlib_sha256'],'Changed nested LDLib; re-audit client callbacks'
 with zipfile.ZipFile(io.BytesIO(ldbytes)) as ld:
  for name,expected in librarypin['classes'].items():
   assert expected['artifact'] in ['gtceu','ldlib']
   assert hashlib.sha256((z if expected['artifact']=='gtceu' else ld).read(name)).hexdigest()==expected['sha256'],name
es=O/'mods/EnderStorage-1.21.1-2.13.0.191.jar';rt=json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'));java=Path(rt['java']);javap=java.with_name('javap.exe')
probe=subprocess.run([str(javap),'-classpath',str(es),'-c','-p','codechicken.enderstorage.storage.EnderLiquidStorage'],capture_output=True,timeout=20).stdout.decode(errors='replace');clinit=probe[probe.index('static {};'):];assert len(re.findall(r'\bbipush\s+16\b',clinit))==1 and 'Field codechicken/lib/fluid/FluidUtils.B:I' in clinit and 'imul' in clinit
entry='''package org.gtnhmigration.compat;
import net.neoforged.fml.common.Mod;
/** Explicit GTNH migration adaptations, not a claim of complete port. */
@Mod("gtnh_migration_compat")
public final class MigrationCompat { public MigrationCompat(net.neoforged.bus.api.IEventBus bus) { LegacyShapedRecipe.register(bus); LegacyItemTag.register(bus); } }
'''
mixin='''package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;
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
(src/'MigrationCompat.java').write_text(entry,encoding='utf-8',newline='\n');(src/'mixin/EnderTankCapacityMixin.java').write_text(mixin,encoding='utf-8',newline='\n')
(resources/'META-INF/neoforge.mods.toml').write_text('''modLoader="javafml"
loaderVersion="[4,)"
license="MIT; Minecraft Forge Public Licence 1.0; LGPL-3.0 (see NOTICE)"
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
[[dependencies.gtnh_migration_compat]]
modId="gtceu"
type="required"
versionRange="[7.0.2]"
ordering="AFTER"
side="BOTH"
[[dependencies.gtnh_migration_compat]]
modId="ldlib"
type="required"
versionRange="[1.0.35.a]"
ordering="AFTER"
side="BOTH"
[[dependencies.gtnh_migration_compat]]
modId="kubejs"
type="required"
versionRange="[2101.7.2-build.377]"
ordering="AFTER"
side="BOTH"
''',encoding='utf-8',newline='\n')
(resources/'gtnh_migration_compat.mixins.json').write_text(json.dumps({'required':True,'minVersion':'0.8','package':'org.gtnhmigration.compat.mixin','compatibilityLevel':'JAVA_21','mixins':['EnderTankCapacityMixin','LegacyRetrievalItemTaskMixin','LegacyDimensionLocationTaskMixin','LegacyHuntTaskMixin','LegacyCraftingItemTaskMixin','LegacyInventoryListenerMixin','LegacyTaskLoginTimingMixin','LegacyInitialInventorySyncMixin','LegacyServerSaveScopeMixin','LegacyPlayerListSaveScopeMixin','LegacyPlayerFileSaveScopeMixin','LegacyWorldFileSaveScopeMixin','LegacyPlayerLifecycleSaveScopeMixin','LegacyPlayerDataLoadScopeMixin','LegacyCraftingGridMixin','LegacyCraftingCacheMixin'],'client':['LegacyOwnerCacheSaveScopeMixin'],'injectors':{'defaultRequire':1}},indent=2),encoding='utf-8',newline='\n')
(resources/'LICENSE').write_text('''Local adapter code is MIT except LegacyShapedRecipe.java and its compiled classes,
which are under the Minecraft Forge Public Licence 1.0. See NOTICE and
MinecraftForge-License.txt for the source attribution and applicable licence.
LegacyCraftingEnergy.java and LegacyCraftingNbt.java and their compiled classes are under LGPL-3.0;
see LGPL-3.0.txt, GPL-3.0.txt and NOTICE.

MIT License
Copyright (c) 2026 Local GTNH migration project
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
''',encoding='utf-8',newline='\n')
assert hashlib.sha256((resources/'MinecraftForge-License.txt').read_bytes()).hexdigest() == '5d5b5983827c197ac2f352b8580e38b182389985cf0a5b5650c35de81fde9026'
for name,digest in {'LGPL-3.0.txt':'e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118','GPL-3.0.txt':'12ac5047f2af0522f06798b1589ffc4599bc29c91f954d7874e0320634e777c0'}.items():
 assert hashlib.sha256((resources/name).read_bytes()).hexdigest()==digest,name
# IGTTool extends an LDLib interface; compile against the already hash-pinned nested dependency.
ldcompile=P/'build/dependencies'/Path(librarypin['ldlib_entry']).name
ldcompile.parent.mkdir(parents=True,exist_ok=True)
if ldcompile.exists():
 assert hashlib.sha256(ldcompile.read_bytes()).hexdigest()==librarypin['ldlib_sha256'],'Changed compiler LDLib dependency'
else:
 ldcompile.write_bytes(ldbytes)
args=(O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8');libs=[O/x for x in re.search(r'^-DlegacyClassPath=(.*)$',args,re.M).group(1).strip().split(';')];libs += [O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar', O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar', O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar'] + list((O/'mods').glob('*.jar')) + [ldcompile];cp=';'.join(p.relative_to(R).as_posix() for p in libs);classes=P/'build/classes';classes.mkdir(parents=True,exist_ok=True)
# Remove only stale compiled classes inside this known build output.
assert classes.resolve().is_relative_to((P/'build').resolve())
for stale in classes.rglob('*.class'):
 assert stale.resolve().is_relative_to(classes.resolve())
 stale.unlink()
argfile=P/'javac.args';files=sorted(src.rglob('*.java'));argfile.write_text('-proc:none\n--release\n21\n-encoding\nUTF-8\n-cp\n"'+cp+'"\n-d\n'+classes.relative_to(R).as_posix()+'\n'+'\n'.join(p.relative_to(R).as_posix() for p in files)+'\n',encoding='ascii')
p=subprocess.run([str(java.with_name('javac.exe')),'-J-Duser.language=en','-J-Duser.country=US','@'+argfile.relative_to(R).as_posix()],cwd=R,capture_output=True,timeout=45);assert p.returncode==0,p.stderr.decode(errors='replace')
jar=O/'mods/gtnh-migration-compat-0.1.0.jar'
with zipfile.ZipFile(jar,'w',zipfile.ZIP_DEFLATED) as z:
 for base in [classes,resources]:
  for p in sorted(base.rglob('*')):
   if p.is_file():
    zi=zipfile.ZipInfo(p.relative_to(base).as_posix(),date_time=(2026,10,15,0,0,0));zi.compress_type=zipfile.ZIP_DEFLATED;z.writestr(zi,p.read_bytes())
shutil.copy2(jar,W/'neoforge-smoke/mods'/jar.name)
sourceout=O/'tools/compat-mod'
# Remove only obsolete copies of the moved Mixin sources in this generated tree.
for moved in (src/'mixin').glob('*Mixin.java'):
 stale=sourceout/'src/main/java/org/gtnhmigration/compat'/moved.name
 assert stale.resolve().is_relative_to(sourceout.resolve())
 if stale.is_file():stale.unlink()
shutil.copytree(P/'src',sourceout/'src',dirs_exist_ok=True)
lock={'filename':jar.name,'sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'version':'0.1.0','mod_id':'gtnh_migration_compat','source':'tools/compat-mod/src','original_config_sha256':hashlib.sha256(legacy.read_bytes()).hexdigest(),'target_artifact':es.name,'target_artifact_sha256':hashlib.sha256(es.read_bytes()).hexdigest(),'target_class':'codechicken.enderstorage.storage.EnderLiquidStorage','original_capacity_buckets':16,'migrated_capacity_buckets':buckets,'migrated_capacity_mb':buckets*1000,'injection_expected_matches':1,'compile_passed':True,'actual_mixin_transformation_verified':False,'runtime_storage_verified':False,'quest_adapter':'scoped retrieval/crafting matching; opt-in dimension and hunt predicates','quest_adapters':['scoped single-item legacy retrieval','scoped crafting matching','opt-in dimension-only location','opt-in hunt exact/subtype predicate'],'unintegrated_helpers':['LegacyRetrievalEngine; differential-test prototype, not exported or connected to game events'],'quest_adapter_live_verified':False}
lock['quest_adapters'].append('marked single-retrieval initial sync and all main-slot changes deferred to ServerTickEvent.Pre')
lock['inventory_event_world_verified']=False
lock['unintegrated_helpers'] += [
 'LegacyRetrievalProgressCodec; source-compared NBT model, not connected to FTB save storage',
 'LegacyQuestRetrievalPlanner; ordered calculation model, no inventory transaction or quest export',
 'LegacyTypedNbtJsonReader; strict typed legacy JSON decoder, retains raw bytes; no world/save import',
 'LegacyQuestProgressEnvelope; exact identity and local merge candidate, no file store or task activation',
 'LegacyQuestProgressStoreCodec and LegacyQuestProgressFileStore; independent append-only UUID ledger, process restart verified, no world lifecycle or inventory transaction',
 'LegacyRetrievalInventoryBridge; actual Inventory snapshots/count writes and rollback, no registered submit handler or durable inventory/progress transaction',
 'LegacyRetrievalTransactionJournal; expected-head intent/decision records, clone-persisted marker proposals and synthetic player-file recovery, no native player save',
 'LegacyRetrievalTransactionProposal; actual inventory/player-NBT/quest-progress composition, no world coordinator',
 'LegacyPlayerDataFileReader; explicit UUID.dat or level.dat/Data/Player integrity/readback; journal binds source authority, synthetic files only; native lifecycle hooks installed but game execution unverified',
 'LegacyQuestWorldStore and LegacyQuestPlayerAdmission and LegacyQuestNativeLifecycle; world binding and guarded startup/load call sites installed; production receipts exercised with synthetic files, native game execution unverified',
 'LegacyPlayerMarkerUpgrade; world-bound maintenance intents, immutable gzip archives, binary-preserving marker relocation and process-halt recovery; installed before native admission, synthetic file/cache execution only',
 'LegacyMaintenanceInitialization; world-bound construction intent, independent completion witness and retained partial files; auxiliary genesis-only restart, synthetic process-halt fixtures, no main-world initialization repair',
        'LegacyOwnerTransactionRecovery and LegacyPlayerNbtPatch; known pending owner transaction mirror/cache reconciliation with archived raw files and unchanged world authority; native startup call site installed, process-halt fixtures only',
 'LegacyRetrievalSaveScope and LegacyRetrievalNativeSaveScope; one-use native save permits and process-local quarantine; 46 selected save/load/lifecycle guards; startup and source-receipt admission call sites installed, native lifecycle execution unverified',
 'LegacyRetrievalCommitCoordinator; explicit UUID-file and integrated-owner cache/mirror/world save/readback and conditional rollback protocols; native adapters compiled and acquire server/storage save permits; required save/lifecycle mixins are registered and isolated-transformed, fixture execution only; no persistent admission recovery or gameplay submit handler',
]
(O/'docs/compat-mod.lock.json').write_text(json.dumps(lock,indent=2),encoding='utf-8',newline='\n');print(json.dumps(lock))
