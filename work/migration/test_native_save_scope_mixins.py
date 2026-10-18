"""Run pinned Sponge Mixin over actual upstream classes in an isolated service.
This tests injection application, not NeoForge's complete transformer chain or
gameplay. The service loads class bytes; Minecraft main and worlds never run.
Mutation fixtures and transformed third-party bytecode stay in ignored work/.
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, zipfile

R = Path(__file__).resolve().parents[2]
O = R / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out = R / 'work/migration/native-save-scope-mixin-check'
out.mkdir(parents=True, exist_ok=True)
java = Path(json.loads((R / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch = (O / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
mc = O / 'libraries/net/minecraft/server/1.21.1-20240808.144430'
patched = O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar'
libs = [patched, mc / 'server-1.21.1-20240808.144430-srg.jar', mc / 'server-1.21.1-20240808.144430-extra.jar']
libs += [O / name for name in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
libs += sorted((O / 'mods').glob('*.jar'))
libs += [O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']
subprocess.run([os.sys.executable,str(R/'work/migration/acquire_native_client_audit.py')],cwd=R,check=True,timeout=1500)
client = R/'work/cache/native-client-audit'
server_libs=libs.copy()
libs[:0]=[client/'neoforge-21.1.262-client.jar',client/'client-srg.jar']
compat = O / 'mods/gtnh-migration-compat-0.1.0.jar'
mixin = O / 'libraries/net/fabricmc/sponge-mixin/0.16.4+mixin.0.8.7/sponge-mixin-0.16.4+mixin.0.8.7.jar'
assert mixin in libs
assert hashlib.sha256(patched.read_bytes()).hexdigest()=='f57ce3018e21459d24b50508e8c1b4fd5c16324ec1dc5f316c192aa0130e7fe0'

def relative(path): return Path(os.path.relpath(path, out)).as_posix()
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

services = out / 'META-INF/services'
services.mkdir(parents=True, exist_ok=True)
(services / 'org.spongepowered.asm.service.IMixinService').write_text('OfflineMixinService\n', encoding='ascii')
(services / 'org.spongepowered.asm.service.IGlobalPropertyService').write_text('OfflineProperties\n', encoding='ascii')
with zipfile.ZipFile(compat) as jar:
    config = json.loads(jar.read('gtnh_migration_compat.mixins.json'))
    expected = {'EnderTankCapacityMixin', 'LegacyRetrievalItemTaskMixin', 'LegacyDimensionLocationTaskMixin',
                'LegacyHuntTaskMixin', 'LegacyCraftingItemTaskMixin', 'LegacyInventoryListenerMixin', 'LegacyTaskLoginTimingMixin', 'LegacyInitialInventorySyncMixin'}
    expected |= {'LegacyServerSaveScopeMixin','LegacyPlayerListSaveScopeMixin','LegacyPlayerFileSaveScopeMixin','LegacyWorldFileSaveScopeMixin','LegacyPlayerLifecycleSaveScopeMixin','LegacyPlayerDataLoadScopeMixin'}
    assert len(config['mixins']) == 14 and set(config['mixins']) == expected
    assert config['client'] == ['LegacyOwnerCacheSaveScopeMixin']
    assert config['required'] and config['package'] == 'org.gtnhmigration.compat'
    config['mixins'].remove('LegacyPlayerFileSaveScopeMixin')
    (out / 'missing-registration.mixins.json').write_text(json.dumps(config), encoding='utf-8', newline='\n')

cp = '.;' + ';'.join(relative(path) for path in libs)
server_cp = '.;' + ';'.join(relative(path) for path in server_libs)
sources = [R / 'work/migration' / name for name in
           ['OfflineMixinService.java', 'OfflineProperties.java', 'NativeSaveScopeMixinCheck.java']]

def run(executable, args, name):
    def quote(value):
        assert not any(char in value for char in '\0\r\n')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    argfile = out / (name + '.args')
    argfile.write_text('\n'.join(quote(arg) for arg in args) + '\n', encoding='ascii')
    result = subprocess.run([str(executable), '@' + argfile.name], cwd=out, capture_output=True, timeout=60)
    log = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    (out / (name + '.log')).write_text(log, encoding='utf-8')
    assert result.returncode == 0, f'{name} failed:\n{log}'
    return log

run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', cp,
                                 '-d', '.', *[relative(source) for source in sources]], 'compile')
results = {}
for mode in ['baseline', 'dedicated', 'missing_player_file_target', 'missing_player_load_target', 'missing_registration', 'missing_stop_call']:
    log = run(java, ['-cp', server_cp if mode=='dedicated' else cp, 'NativeSaveScopeMixinCheck', mode], mode)
    results[mode] = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
    assert results[mode]['passed'], mode

report = results.pop('baseline')
dedicated = results.pop('dedicated')
assert report['injections_verified']==46 and dedicated['injections_verified']==45
assert report['targets_transformed']==7 and dedicated['targets_transformed']==6
report.update({
    'negative_controls': results,
    'client_distribution_isolated_transform_verified':True,
    'dedicated_distribution_check':dedicated,
    'compat_jar_sha256': sha(compat),
    'source_sha256': {source.relative_to(R).as_posix(): sha(source) for source in [*sources,Path(__file__),R/'work/migration/acquire_native_client_audit.py',R/'locks/native-client-audit-inputs.json']+sorted((R/'work/compat-mod/src/main/java/org/gtnhmigration/compat').glob('*SaveScope*.java'))+[R/'work/compat-mod/src/main/java/org/gtnhmigration/compat'/name for name in ['LegacyPlayerDataLoadScopeMixin.java','LegacyQuestNativeLifecycle.java','LegacyQuestPlayerAdmission.java','LegacyPlayerMarkerUpgrade.java','LegacyPlayerNbtPatch.java','LegacyOwnerTransactionRecovery.java']]},
    'dependency_sha256': {path.name: sha(path) for path in
                          [patched,client/'neoforge-21.1.262-client.jar',client/'client-srg.jar', mixin, O / 'mods/ftb-quests-neoforge-2101.1.36.jar', O / 'mods/EnderStorage-1.21.1-2.13.0.191.jar']},
    'limitations': [
        'Uses a dedicated test Mixin service, not NeoForge ModLauncher.',
        "No access transformers, other mods' Mixins, class-definition execution, game event or world is exercised.",
        'ASM BasicVerifier checks transformed method dataflow, not full JVM linkage or gameplay behavior.',
    ],
})
destination = O / 'docs/quest-migration/ftb-export/NativeSaveScopeMixinCheck.json'
destination.write_text(json.dumps(report, indent=2), encoding='utf-8', newline='\n')
print(json.dumps(report))
