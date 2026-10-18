"""Real NeoForge data generation, native registry audit and Mixin package regression.

Runs the official forgedatauserdev target with exact installer-produced bytecode.
No server entry point, world or player is executed. KubeJS intentionally skips
startup scripts during datagen; its custom registrations are outside this audit.
"""
from pathlib import Path
import hashlib, json, os, re, shutil, subprocess, uuid, zipfile
from gtceu_artifact import runtime_jar, original_jar

R = Path(__file__).resolve().parents[2]
O = R / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
C = R / 'work/cache/native-client-audit'
OUT = R / 'work/migration/native-registry-check'
OUT.mkdir(parents=True, exist_ok=True)
JAVA = Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
SOURCE = R/'work/migration/NativeRegistryAudit.java'
CALLBACK_SOURCE = R/'work/migration/LibraryClientCallbacksCheck.java'
LIBRARY_COREMOD = R/'work/compat-mod/src/main/resources/coremods/library_client_callbacks.js'
LIBRARY_PIN = R/'locks/library-client-callbacks.json'
COMPAT = O/'mods/gtnh-migration-compat-0.1.0.jar'
LAUNCH = O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt'

def sha(path, algorithm='sha256'):
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()

def write_json(path, value):
    path.write_text(json.dumps(value, indent=2)+'\n', encoding='utf-8', newline='\n')

def arguments(path, values, compiler=False):
    # java.exe decodes its @file with the Windows system code page. javac's
    # Java-side @file parser uses UTF-8 on this JDK. Absolute classpaths are
    # required by the FML resource path filter, including in non-ASCII workspaces.
    def quote(value):
        assert not any(c in value for c in '\0\r\n')
        return '"'+value.replace('\\','\\\\').replace('"','\\"')+'"'
    path.write_text('\n'.join(quote(x) for x in values)+'\n',
                    encoding='utf-8' if compiler or os.name != 'nt' else 'mbcs', newline='\n')

subprocess.run([os.sys.executable, str(R/'work/migration/acquire_native_client_audit.py')],
               cwd=R, check=True, timeout=1500)
launch = LAUNCH.read_text(encoding='utf-8')
classpath = [O/p for p in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M)[1].strip().split(';')]
# Retain the pinned installer versions. Native binaries are not required for
# this headless data-generator run and are not loaded or supplied.
def coordinate(path): return '/'.join(path.parts[-4:-2])
present = {coordinate(p) for p in classpath}
client_version = json.loads((C/'version-1.21.1.json').read_text(encoding='utf-8'))
supplement = {}
for library in client_version['libraries']:
    if ':natives-' in library['name']: continue
    rules = library.get('rules', [])
    allowed = not rules
    for rule in rules:
        if rule.get('os',{}).get('name','windows') == 'windows':
            allowed = rule['action'] == 'allow'
    if not allowed: continue
    artifact = library['downloads']['artifact']
    path = O/'libraries'/artifact['path']
    if coordinate(path) in present: continue
    if not path.exists():
        path = R/'work/cache/native-registry-client-libraries'/artifact['path']
        path.parent.mkdir(parents=True, exist_ok=True)
        if not path.exists():
            temporary = path.with_suffix('.download')
            assert artifact['url'].startswith('https://libraries.minecraft.net/')
            subprocess.run(['curl.exe','-fLsS','--connect-timeout','15','--max-time','180',
                            '-o',str(temporary),artifact['url']], check=True, timeout=190)
            assert sha(temporary,'sha1') == artifact['sha1']
            temporary.replace(path)
    assert path.stat().st_size == artifact['size'] and sha(path,'sha1') == artifact['sha1']
    supplement[library['name']] = {'sha256':sha(path), 'sha1':artifact['sha1'], 'url':artifact['url']}
    classpath.append(path); present.add(coordinate(path))

# NeoForgeDevProvider splits Minecraft and NeoForge by their package prefixes.
# Overlay only unchanged entries from the three pinned distribution artifacts.
overlay_inputs = [C/'neoforge-21.1.262-client.jar', C/'client-srg.jar',
                  O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']
assert [sha(p) for p in overlay_inputs] == [
    'aff67d0c3bbd015ecd881bb37416b740ada2e1e854433501ec5bb9381dbdd1c2',
    'a649bd3ce77f122d0f83ae1b37b4b303e662470f928b9bee5fbc76580805ffd4',
    '4070305f97a08409d9b4e714f619f0ec2d97d29c9939ef43a89d927259942f98']
merged = OUT/'neoforge-development.jar'
seen = set()
with zipfile.ZipFile(merged,'w',compression=zipfile.ZIP_DEFLATED) as out:
    for source in overlay_inputs:
        with zipfile.ZipFile(source) as archive:
            for name in sorted(archive.namelist()):
                if name in seen or name.endswith('/') or name.upper().endswith(('.SF','.RSA','.DSA')) or name=='META-INF/MANIFEST.MF': continue
                entry = zipfile.ZipInfo(name, date_time=(2026,10,15,0,0,0))
                entry.compress_type=zipfile.ZIP_DEFLATED
                out.writestr(entry,archive.read(name)); seen.add(name)
classpath += [merged,C/'client-extra.jar']

classes = OUT/('classes-'+uuid.uuid4().hex)
classes.mkdir()
compile_args = OUT/'compile.args'
arguments(compile_args, ['-proc:none','--release','21','-encoding','UTF-8','-cp',
    ';'.join(p.resolve().as_posix() for p in classpath+sorted((O/'mods').glob('*.jar'))),
    '-d',classes.resolve().as_posix(),SOURCE.resolve().as_posix(),CALLBACK_SOURCE.resolve().as_posix()], compiler=True)
with (OUT/'compile.log').open('wb') as log:
    result = subprocess.run([str(JAVA.with_name('javac.exe')),'@'+str(compile_args)],
                            stdout=log,stderr=subprocess.STDOUT,timeout=60)
assert result.returncode==0, 'Audit compilation failed; inspect native-registry-check/compile.log'
pin=json.loads(LIBRARY_PIN.read_text(encoding='utf-8'));gtjar=runtime_jar(O)
assert sha(original_jar())==pin['gtceu_sha256']
ldjar=classes/'ldlib.jar'
with zipfile.ZipFile(gtjar) as z:ldjar.write_bytes(z.read(pin['ldlib_entry']))
assert sha(ldjar)==pin['ldlib_sha256']
contract=classes/'library-bytecode-check.json';contract_args=classes/'contract.args'
arguments(contract_args,['-cp',';'.join(p.resolve().as_posix() for p in classpath+[classes]),
    'org.gtnhmigration.audit.LibraryClientCallbacksCheck',gtjar.as_posix(),ldjar.as_posix(),LIBRARY_COREMOD.as_posix(),contract.as_posix()])
checked=subprocess.run([str(JAVA),'@'+str(contract_args)],capture_output=True,timeout=60)
assert checked.returncode==0,checked.stdout.decode(errors='replace')+checked.stderr.decode(errors='replace')
audit = OUT/'gtnh-registry-audit.jar'
with zipfile.ZipFile(audit,'w') as jar:
    for p in sorted(classes.rglob('*.class')):
        jar.writestr(zipfile.ZipInfo(p.relative_to(classes).as_posix(),date_time=(2026,10,15,0,0,0)),p.read_bytes())
    jar.writestr(zipfile.ZipInfo('META-INF/neoforge.mods.toml',date_time=(2026,10,15,0,0,0)),
        'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\n'
        'modId="gtnh_registry_audit"\nversion="0.1.0"\ndisplayName="Test-only registry audit"\n')

inputs = {}
for folder in ['mods','config','kubejs']:
    for p in sorted((O/folder).rglob('*')):
        if p.is_file(): inputs[p.relative_to(O).as_posix()] = sha(p)
eulas = {p: p.read_bytes() for p in [O/'eula.txt',R/'work/neoforge-smoke/eula.txt'] if p.exists()}
assert all(b'eula=false' in value for value in eulas.values())

def execute(mode):
    run = OUT/(mode+'-'+uuid.uuid4().hex)
    run.mkdir()
    for folder in ['mods','config','kubejs']:
        shutil.copytree(O/folder,run/folder)
    shutil.copy2(audit,run/'mods'/audit.name)
    shutil.copy2(contract,run/'library-bytecode-check.json')
    (run/'eula.txt').write_text('eula=false\n',encoding='ascii',newline='\n')
    if mode=='negative':
        # Exact reported regression: ordinary classes inside the reserved Mixin
        # package. The renamed Mixin declarations still target the same classes.
        with zipfile.ZipFile(COMPAT) as original, zipfile.ZipFile(run/'mods'/COMPAT.name,'w') as mutated:
            for entry in original.infolist():
                data=original.read(entry.filename)
                if entry.filename=='gtnh_migration_compat.mixins.json':
                    config=json.loads(data);config['package']='org.gtnhmigration.compat'
                    for key in ['mixins','client']:config[key]=['mixin.'+n for n in config[key]]
                    data=json.dumps(config).encode()
                mutated.writestr(entry,data)
    args=['-Xms512M','-Xmx4G']
    for line in launch.splitlines():
        if line.startswith('-p '):args+=['-p',';'.join((O/p).resolve().as_posix() for p in line[3:].split(';'))]
        elif line.startswith('-DlegacyClassPath='):
            cp=';'.join(p.resolve().as_posix() for p in classpath)
            args+=['-DlegacyClassPath='+cp,'-cp',cp]
        elif line.startswith('-DignoreList='):args+=[line+',neoforge-development.jar,client-extra.jar']
        elif line.startswith('-DlibraryDirectory='):args+=['-DlibraryDirectory='+(O/'libraries').resolve().as_posix()]
        elif line.startswith('--launchTarget'):args+=['--launchTarget','forgedatauserdev']
        elif line.startswith('--'):args+=line.split()
        else:args.append(line)
    args+=['--gameDir','.', '--mod','gtnh_registry_audit','--output','generated','--reports']
    arguments(run/'datagen.args',args)
    with (run/'datagen.log').open('wb') as log:
        result=subprocess.run([str(JAVA),'@datagen.args'],cwd=run,stdout=log,stderr=subprocess.STDOUT,timeout=240)
    log=(run/'datagen.log').read_text(encoding='utf-8',errors='replace')
    shutil.copy2(run/'datagen.log',OUT/(mode+'.log'))
    assert (run/'eula.txt').read_bytes()==b'eula=false\n'
    assert not (run/'world').exists() and not (run/'saves').exists()
    assert all(p.read_bytes()==value for p,value in eulas.items())
    assert all(sha(O/name)==digest for name,digest in inputs.items()),'Foundation inputs changed during audit'
    if mode=='negative':
        assert result.returncode!=0 and 'IllegalClassLoadError' in log
        assert 'LegacyRetrievalNativeSaveScope$PlayerDataLoadHooks is in a defined mixin package' in log
        assert not (run/'native-registry-result.json').exists()
        return {'passed':True,'illegal_mixin_package_rejected':True,'exit_code':result.returncode}
    assert result.returncode==0 and 'GTNH_NATIVE_REGISTRY_AUDIT_COMPLETE' in log, 'Datagen failed; inspect positive.log'
    assert '/ERROR]' not in log, 'Datagen logged an error; inspect positive.log'
    report=json.loads((run/'native-registry-result.json').read_text(encoding='utf-8'))
    assert report['passed'] and not report['world_or_player_executed']
    assert report['native_hook_interfaces_verified']==7 and len(report['defined_classes'])==17
    assert report['ender_storage_capacity_mb']==256000 and report['kubejs_data_generation_mode']
    assert report['client_helpers_retained']==2 and report['client_lamp_renderers_constructed']==512
    assert report['library_client_callbacks']['passed'] and report['library_client_callbacks']['client_distribution']
    assert len(report['identity_mappings'])==720 and len(report['retrieval_bindings'])==3814
    upstream=[row for row in report['identity_mappings'] if not row['target'].startswith('gtnh_migration:')]
    scripted=[row for row in report['identity_mappings'] if row['target'].startswith('gtnh_migration:')]
    assert len(upstream)==492 and all(row['registered'] for row in upstream)
    assert len(scripted)==228 and not any(row['registered'] for row in scripted)
    assert sum(row['bound'] for row in report['retrieval_bindings'])==68
    write_json(OUT/'registry-details.json',report)
    return report

negative=execute('negative')
details=execute('positive')
report={
    'test':details['test'],'passed':True,'native_mod_registration_executed':True,
    'world_or_player_executed':False,'eula_changed':False,
    'registries':{key:len(value) for key,value in details['registries'].items()},
    'mods':details['mods'],'upstream_identity_mappings_verified':492,
    'scripted_identity_mappings_not_exercised':228,'bound_retrieval_declarations':68,
    'defined_classes':details['defined_classes'],'native_hook_interfaces_verified':7,
    'ender_storage_capacity_mb':details['ender_storage_capacity_mb'],
    'kubejs_data_generation_mode':details['kubejs_data_generation_mode'],
    'client_helpers_retained':details['client_helpers_retained'],
    'client_lamp_renderers_constructed':details['client_lamp_renderers_constructed'],
    'library_client_callbacks':details['library_client_callbacks'],
    'negative_control':negative,'compat_jar_sha256':sha(COMPAT),
    'crafting_grid_origin_verified':details['crafting_grid_origin_verified'],
    'archive_sha256':details['archive_sha256'],'registry_details_sha256':sha(OUT/'registry-details.json'),
    'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in [SOURCE,CALLBACK_SOURCE,LIBRARY_COREMOD,LIBRARY_PIN,Path(__file__),R/'work/migration/acquire_native_client_audit.py']},
    'overlay_inputs_sha256':{p.name:sha(p) for p in overlay_inputs},
    'client_library_supplement':supplement,'runtime_inputs_sha256':inputs,
    'limitations':[
        'Official data-generator CLIENT distribution only; no server/world/player, rendering or gameplay execution.',
        'Pinned KubeJS skips startup reload while PlatformWrapper.isGeneratingData() is true. The 228 scripted identities are not validated here.',
        'Registry identity and retrieval binding do not prove legacy behavior, quest completion, rewards, recipes or texture parity.',
        'Seven native Mixin marker interfaces are checked on actual transformed classes; hook bodies are not exercised.',
    ],
}
write_json(OUT/'NativeRegistryDatagenCheck.json',report)
destination=O/'docs/quest-migration/ftb-export/NativeRegistryDatagenCheck.json'
write_json(destination,report)
print(json.dumps({key:report[key] for key in ['test','passed','registries','upstream_identity_mappings_verified','scripted_identity_mappings_not_exercised','bound_retrieval_declarations','compat_jar_sha256']}))
