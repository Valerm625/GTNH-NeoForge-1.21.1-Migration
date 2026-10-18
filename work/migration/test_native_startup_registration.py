"""Real production-distribution registry startup, without a server/world/EULA acceptance.

Unlike datagen, ordinary KubeJS startup events execute. This is not a full game
launch: CommonModLoader.begin and component registration only. Client callbacks
and configurator scanning are checked without hiding reflection diagnostics.
"""
from pathlib import Path
import hashlib, json, os, re, shutil, subprocess, uuid, zipfile

R = Path(__file__).resolve().parents[2]
O = R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
OUT = R/'work/migration/native-startup-check'
OUT.mkdir(parents=True, exist_ok=True)
JAVA = Path(json.loads((R/'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
COMPAT = O/'mods/gtnh-migration-compat-0.1.0.jar'
COREMOD = R/'work/compat-mod/src/main/resources/coremods/gtceu_client_isolation.js'
LIBRARY_COREMOD = R/'work/compat-mod/src/main/resources/coremods/library_client_callbacks.js'
LIBRARY_PIN = R/'locks/library-client-callbacks.json'
SOURCES = [R/'work/migration'/n for n in ['StartupAuditLaunchHandler.java','StartupAuditMain.java','GtceuClientIsolationCheck.java','LibraryClientCallbacksCheck.java']]
LAUNCH = O/'libraries/net/neoforged/neoforge/21.1.262/win_args.txt'

def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d): p.write_text(json.dumps(d,indent=2)+'\n',encoding='utf-8',newline='\n')
def arguments(path, values, compiler=False):
    assert all(not any(c in x for c in '\0\r\n') for x in values)
    path.write_text('\n'.join('"'+x.replace('\\','\\\\').replace('"','\\"')+'"' for x in values)+'\n',
                    encoding='utf-8' if compiler or os.name!='nt' else 'mbcs',newline='\n')
def snapshot():
    return {p.relative_to(O).as_posix():sha(p) for folder in ['mods','config','kubejs']
            for p in sorted((O/folder).rglob('*')) if p.is_file()}

initial = snapshot()
eula = (O/'eula.txt').read_bytes()
assert re.search(rb'(?m)^eula=false\s*$',eula)
pin = json.loads((R/'locks/gtceu-client-isolation.json').read_text(encoding='utf-8'))
gtjar = O/'mods'/pin['filename']
assert sha(gtjar)==pin['sha256']
with zipfile.ZipFile(COMPAT) as z:
    assert z.read('coremods/gtceu_client_isolation.js')==COREMOD.read_bytes()
    assert z.read('coremods/library_client_callbacks.js')==LIBRARY_COREMOD.read_bytes()
    assert json.loads(z.read('META-INF/coremods.json'))=={'gtnh_gtceu_client_isolation':'coremods/gtceu_client_isolation.js','gtnh_library_client_callbacks':'coremods/library_client_callbacks.js'}
launch = LAUNCH.read_text(encoding='utf-8')
legacy = [O/p for p in re.search(r'^-DlegacyClassPath=(.*)$',launch,re.M)[1].strip().split(';')]
modules = [O/p for p in re.search(r'^-p (.*)$',launch,re.M)[1].strip().split(';')]
libs = legacy+modules+[
    O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar',
    O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar',
    O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar']+sorted((O/'mods').glob('*.jar'))
platform = {
    'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar':'f57ce3018e21459d24b50508e8c1b4fd5c16324ec1dc5f316c192aa0130e7fe0',
    'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar':'26ca9c40d7e1681190b428583c38816852218e78df3f8bdb60a59a78503aec71',
    'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar':'4070305f97a08409d9b4e714f619f0ec2d97d29c9939ef43a89d927259942f98'}
for name,digest in platform.items():assert sha(O/name)==digest,name
platform_inputs = {p.relative_to(O).as_posix():sha(p) for p in set(legacy+modules+[O/n for n in platform]+[LAUNCH])}
run = OUT/str(uuid.uuid4()); run.mkdir()
classes = run/'classes'; classes.mkdir()
with zipfile.ZipFile(gtjar) as z:
    for name in z.namelist():
        if name.startswith('META-INF/jarjar/') and name.endswith('.jar'):
            p=run/Path(name).name; p.write_bytes(z.read(name)); libs.append(p)
cp = ';'.join(dict.fromkeys(p.resolve().as_posix() for p in libs))
compile_args = run/'compile.args'
arguments(compile_args,['-proc:none','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes.as_posix()]+[p.as_posix() for p in SOURCES],True)
compiled = subprocess.run([str(JAVA.with_name('javac.exe')),'@'+str(compile_args)],capture_output=True,timeout=60)
assert compiled.returncode==0,compiled.stderr.decode(errors='replace')

contract = run/'bytecode-check.json'
contract_args = run/'bytecode.args'
arguments(contract_args,['-cp',cp+';'+classes.as_posix(),'org.gtnhmigration.audit.GtceuClientIsolationCheck',gtjar.as_posix(),COREMOD.as_posix(),contract.as_posix()])
checked = subprocess.run([str(JAVA),'@'+str(contract_args)],capture_output=True,timeout=60)
assert checked.returncode==0,checked.stdout.decode(errors='replace')+checked.stderr.decode(errors='replace')
bytecode = json.loads(contract.read_text(encoding='utf-8')); assert bytecode['passed']
library_contract = run/'library-bytecode-check.json'
library_pin = json.loads(LIBRARY_PIN.read_text(encoding='utf-8'))
ldjar = run/Path(library_pin['ldlib_entry']).name
assert sha(ldjar)==library_pin['ldlib_sha256']
arguments(contract_args,['-cp',cp+';'+classes.as_posix(),'org.gtnhmigration.audit.LibraryClientCallbacksCheck',gtjar.as_posix(),ldjar.as_posix(),LIBRARY_COREMOD.as_posix(),library_contract.as_posix()])
checked = subprocess.run([str(JAVA),'@'+str(contract_args)],capture_output=True,timeout=60)
assert checked.returncode==0,checked.stdout.decode(errors='replace')+checked.stderr.decode(errors='replace')
library_bytecode=json.loads(library_contract.read_text(encoding='utf-8')); assert library_bytecode['passed']
shutil.copy2(library_contract,OUT/'library-bytecode-check.json')

launcher = run/'startup-audit-launcher.jar'
with zipfile.ZipFile(launcher,'w') as z:
    p=classes/'org/gtnhmigration/audit/launcher/StartupAuditLaunchHandler.class'
    z.write(p,p.relative_to(classes).as_posix())
    z.writestr('META-INF/services/cpw.mods.modlauncher.api.ILaunchHandlerService','org.gtnhmigration.audit.launcher.StartupAuditLaunchHandler\n')
testmod = run/'startup-audit.jar'
with zipfile.ZipFile(testmod,'w') as z:
    p=classes/'org/gtnhmigration/startupaudit/StartupAuditMain.class'
    z.write(p,p.relative_to(classes).as_posix())
    p=classes/'org/gtnhmigration/audit/LibraryClientCallbacksCheck.class'
    z.write(p,p.relative_to(classes).as_posix())
    z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nAutomatic-Module-Name: gtnh_startup_audit\n\n')
    z.writestr('META-INF/neoforge.mods.toml','modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="gtnh_startup_audit"\nversion="0.1.0"\ndisplayName="Startup registration audit"\n')

def execute(case):
    directory=run/case; directory.mkdir()
    for folder in ['mods','config','kubejs']: shutil.copytree(O/folder,directory/folder)
    shutil.copy2(testmod,directory/'mods'/testmod.name)
    shutil.copy2(O/'docs/fuel-block-migration/fuel-blocks.json',directory/'fuel-spec.json')
    shutil.copy2(library_contract,directory/'library-bytecode-check.json')
    (directory/'eula.txt').write_bytes(eula)
    if case in ['without_client_isolation','without_library_callbacks','without_fluid_callback_guard']:
        dest=directory/'mods'/COMPAT.name
        with zipfile.ZipFile(COMPAT) as source,zipfile.ZipFile(dest,'w') as target:
            for name in source.namelist():
                data=source.read(name)
                if name=='META-INF/coremods.json' and case!='without_fluid_callback_guard':
                    definition=json.loads(data)
                    del definition['gtnh_gtceu_client_isolation' if case=='without_client_isolation' else 'gtnh_library_client_callbacks']
                    data=json.dumps(definition).encode()
                if name=='coremods/library_client_callbacks.js' and case=='without_fluid_callback_guard':
                    s=data.decode(); old="if (target === 'com.gregtechceu.gtceu.api.fluid.FluidBuilder') {"; assert s.count(old)==1
                    data=s.replace(old,'if (false) {').encode()
                target.writestr(name,data)
    if case=='old_fuel_tag_signature':
        p=directory/'kubejs/startup_scripts/06_gtnh_fuel_blocks.js';s=p.read_text(encoding='utf-8')
        old='.tagBlock("minecraft:mineable/pickaxe").tagBlock(';assert old in s
        p.write_text(s.replace(old,'.tagBlock("minecraft:mineable/pickaxe", ',1),encoding='utf-8',newline='\n')
    if case=='old_loop_const':
        p=directory/'kubejs/startup_scripts/08_gtnh_soldering_composition.js';s=p.read_text(encoding='utf-8')
        assert 'var component = current.get(i);' in s
        p.write_text(s.replace('var component = current.get(i);','const component = current.get(i);'),encoding='utf-8',newline='\n')
    if case=='unqualified_material_ids':
        p=directory/'kubejs/startup_scripts/08_gtnh_soldering_composition.js';s=p.read_text(encoding='utf-8')
        p.write_text(s.replace('9x gtceu:tin','9x tin').replace('1x gtceu:antimony','1x antimony'),encoding='utf-8',newline='\n')
    args=['-Xms512M','-Xmx4G']
    for line in launch.splitlines():
        if line.startswith('-p '): args+=['-p',';'.join(p.as_posix() for p in modules)]
        elif line.startswith('-DlegacyClassPath='):
            paths=';'.join(p.as_posix() for p in legacy)+';'+launcher.as_posix()
            args+=['-DlegacyClassPath='+paths,'-cp',paths]
        elif line.startswith('-DlibraryDirectory='):args+=['-DlibraryDirectory='+(O/'libraries').as_posix()]
        elif line.startswith('--launchTarget'):args+=['--launchTarget','gtnhserverregistryaudit']
        elif line.startswith('--'):args+=line.split()
        elif line.strip():args.append(line)
    args+=['--gameDir','.'];arguments(directory/'launch.args',args)
    with (directory/'launch.log').open('wb') as log:
        result=subprocess.run([str(JAVA),'@launch.args'],cwd=directory,stdout=log,stderr=subprocess.STDOUT,timeout=180)
    assert (directory/'eula.txt').read_bytes()==eula
    assert not (directory/'world').exists()
    return result,directory,(directory/'launch.log').read_text(encoding='utf-8',errors='replace')

result,positive,log=execute('positive')
assert result.returncode==0,log[-12000:]
assert 'GTNH_NATIVE_STARTUP_AUDIT_COMPLETE' in log
assert re.search(r'Loaded 9/9 KubeJS startup scripts .* with 0 errors and 0 warnings',log)
assert 'is not a known Material' not in log
assert not re.search(r'\[KubeJS Startup/\].*(?:Error in|ERROR)',log)
assert not any('[KubeJS Startup/]' in line and '/ERROR]' in line for line in log.splitlines())
details=json.loads((positive/'native-startup-result.json').read_text(encoding='utf-8'))
assert len(details['identity_mappings'])==720 and all(r['registered'] for r in details['identity_mappings'])
assert sum(r['target'].startswith('gtnh_migration:') for r in details['identity_mappings'])==228
assert len(details['custom_items'])==574 and len(details['fuel_blocks'])==16
assert details['client_helpers_stripped_on_server'] and details['solder_components']==['tin:9','antimony:1']
# No allowlist: even a swallowed annotation/reflection failure must fail.
errors=[line for line in log.splitlines() if '/ERROR]' in line]
assert not errors,errors
assert 'Failed to load all methods' not in log and 'failed to scan annotation' not in log
assert details['library_client_callbacks']['passed']
negative=[]
for case,needle in [('without_client_isolation','net/minecraft/client/multiplayer/ClientLevel'),
                    ('without_library_callbacks','LDLib configurator inventory differs'),
                    ('without_fluid_callback_guard','NoSuchMethodError'),
                    ('old_fuel_tag_signature','BlockBuilder.tagBlock(string,string)'),
                    ('old_loop_const','Unreviewed soldering alloy definition: tin:6,tin:6,tin:6'),
                    ('unqualified_material_ids','Incorrect alloy components: [null:9, null:1]')]:
    failed,directory,failure_log=execute(case)
    assert failed.returncode!=0 and not (directory/'native-startup-result.json').exists(),case
    assert needle in failure_log,(case,failure_log[-5000:])
    negative.append({'case':case,'exit_code':failed.returncode,'expected_failure_observed':True,'log_sha256':sha(directory/'launch.log')})
assert snapshot()==initial and (O/'eula.txt').read_bytes()==eula
shutil.copy2(positive/'native-startup-result.json',OUT/'registry-details.json')
shutil.copy2(positive/'launch.log',OUT/'launch.log')
report={
    'passed':True,'test':'production_distribution_native_startup_registration',
    'source_sha256':{p.relative_to(R).as_posix():sha(p) for p in SOURCES+[Path(__file__),COREMOD,R/'locks/gtceu-client-isolation.json',LIBRARY_COREMOD,LIBRARY_PIN]},
    'compat_jar_sha256':sha(COMPAT),'gtceu_jar_sha256':sha(gtjar),'input_sha256':initial,
    'platform_inputs_sha256':platform_inputs,
    'registry_details_sha256':sha(OUT/'registry-details.json'),'startup_log_sha256':sha(OUT/'launch.log'),
    'registries':details['registries'],'registered_custom_items':len(details['custom_items']),
    'registered_mapped_identities':720,'registered_script_identity_rows':228,
    'bound_retrieval_declarations':sum(r['bound'] for r in details['retrieval_bindings']),
    'solder_components':details['solder_components'],'fuel_blocks':details['fuel_blocks'],
    'registered_lamps':details['registered_lamps'],'client_helpers_stripped_on_server':True,
    'client_instruction_preservation':bytecode,'negative_controls':negative,
    'library_callback_instruction_preservation':library_bytecode,'library_client_callbacks':details['library_client_callbacks'],
    'unresolved_upstream_errors':errors,'all_startup_errors_resolved':not errors,
    'eula_unchanged_and_false':True,'runtime_inputs_unchanged':True,
    'full_parity':False,'world_or_player_executed':False,'full_mod_lifecycle_executed':False,
    'recipes_resources_or_rendering_executed':False,
    'builtin_furnace_data_generation_executed':True,'fuel_behavior_after_resource_reload_verified':False,
    'scope':'Real CommonModLoader.begin + RegistrationEvents.modifyComponents in production dedicated-server distribution. LDLib configurators and side-specific callbacks verified. No world/server/player construction; no resources, recipes, GUI, network or full lifecycle.'}
save(OUT/'NativeStartupRegistrationCheck.json',report)
save(O/'docs/verification/NativeStartupRegistrationCheck.json',report)
print(json.dumps({k:report[k] for k in ['passed','registered_custom_items','registered_mapped_identities','bound_retrieval_declarations','registered_lamps','all_startup_errors_resolved','full_parity']}))
