"""Run pinned Sponge Mixin over actual upstream classes in an isolated service.
This tests injection application, not NeoForge's complete transformer chain or
gameplay. The service loads class bytes; Minecraft main and worlds never run.
Mutation fixtures and transformed third-party bytecode stay in ignored work/.
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, zipfile

R = Path(__file__).resolve().parents[2]
O = R / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out = R / 'work/migration/isolated-mixin-check'
out.mkdir(parents=True, exist_ok=True)
java = Path(json.loads((R / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
launch = (O / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
mc = O / 'libraries/net/minecraft/server/1.21.1-20240808.144430'
libs = [mc / 'server-1.21.1-20240808.144430-srg.jar', mc / 'server-1.21.1-20240808.144430-extra.jar']
libs += [O / name for name in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
libs += sorted((O / 'mods').glob('*.jar'))
libs += [O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']
compat = O / 'mods/gtnh-migration-compat-0.1.0.jar'
mixin = O / 'libraries/net/fabricmc/sponge-mixin/0.16.4+mixin.0.8.7/sponge-mixin-0.16.4+mixin.0.8.7.jar'
assert mixin in libs

def relative(path): return Path(os.path.relpath(path, out)).as_posix()
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

services = out / 'META-INF/services'
services.mkdir(parents=True, exist_ok=True)
(services / 'org.spongepowered.asm.service.IMixinService').write_text('OfflineMixinService\n', encoding='ascii')
(services / 'org.spongepowered.asm.service.IGlobalPropertyService').write_text('OfflineProperties\n', encoding='ascii')
with zipfile.ZipFile(compat) as jar:
    config = json.loads(jar.read('gtnh_migration_compat.mixins.json'))
    expected = {'EnderTankCapacityMixin', 'LegacyRetrievalItemTaskMixin', 'LegacyDimensionLocationTaskMixin',
                'LegacyHuntTaskMixin', 'LegacyCraftingItemTaskMixin'}
    assert len(config['mixins']) == 5 and set(config['mixins']) == expected
    assert config['required'] and config['package'] == 'org.gtnhmigration.compat'
    config['mixins'].remove('LegacyHuntTaskMixin')
    (out / 'missing-registration.mixins.json').write_text(json.dumps(config), encoding='utf-8', newline='\n')

cp = '.;' + ';'.join(relative(path) for path in libs)
sources = [R / 'work/migration' / name for name in
           ['OfflineMixinService.java', 'OfflineProperties.java', 'IsolatedMixinTransformationCheck.java']]

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
for mode in ['baseline', 'missing_hunt_target', 'changed_capacity_constant', 'missing_registration']:
    log = run(java, ['-cp', cp, 'IsolatedMixinTransformationCheck', mode], mode)
    results[mode] = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
    assert results[mode]['passed'], mode

report = results.pop('baseline')
report.update({
    'negative_controls': results,
    'compat_jar_sha256': sha(compat),
    'source_sha256': {source.relative_to(R).as_posix(): sha(source) for source in [*sources, Path(__file__)]},
    'dependency_sha256': {path.name: sha(path) for path in
                          [mixin, O / 'mods/ftb-quests-neoforge-2101.1.36.jar', O / 'mods/EnderStorage-1.21.1-2.13.0.191.jar']},
    'limitations': [
        'Uses a dedicated test Mixin service, not NeoForge ModLauncher.',
        "No access transformers, other mods' Mixins, class-definition execution, game event or world is exercised.",
        'ASM BasicVerifier checks transformed method dataflow, not full JVM linkage or gameplay behavior.',
    ],
})
destination = O / 'docs/quest-migration/ftb-export/IsolatedMixinTransformationCheck.json'
destination.write_text(json.dumps(report, indent=2), encoding='utf-8', newline='\n')
print(json.dumps(report))
