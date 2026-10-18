from pathlib import Path
import json,hashlib,shutil,re,datetime,zipfile
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
# Package the already installed test runtime; do not change launcher accounts or user's system Java.
runtime=json.loads((W/'migration/runtime.json').read_text(encoding='utf-8'));jhome=Path(runtime['java_home']);dest=O/'runtime'/jhome.name
dest.parent.mkdir(exist_ok=True);shutil.copytree(jhome,dest,dirs_exist_ok=True)
shutil.copytree(W/'neoforge-smoke/libraries',O/'libraries',dirs_exist_ok=True)
shutil.copy2(W/'neoforge-smoke/user_jvm_args.txt',O/'user_jvm_args.txt')
# EULA remains false. The user's own choice is required before full server startup.
eula=O/'eula.txt'
if not eula.exists():shutil.copy2(W/'neoforge-smoke/eula.txt',eula)
assert 'eula=false' in eula.read_text(encoding='utf-8')
# Loopback-only test server prevents unintentionally publishing an unfinished server.
props=O/'server.properties'
if not props.exists():props.write_text('server-ip=127.0.0.1\nserver-port=25565\nonline-mode=true\nenable-rcon=false\nenable-query=false\nwhite-list=true\nmax-players=2\nlevel-name=migration-test-world\nmotd=GTNH migration test - incomplete, not a release\n',encoding='ascii')
v=json.loads((W/'migration/platform-lock.json').read_text(encoding='utf-8'))['neoforge']
script=r'''param([switch]$AllowIncompleteMigration)
$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
if (-not $AllowIncompleteMigration) {
  Write-Host 'This is an INCOMPLETE migration test, NOT a finished GTNH pack.'
  Write-Host 'Read STATUS.zh-CN.md, then run this script with -AllowIncompleteMigration if you want to test.'
  exit 2
}
$EulaPath = Join-Path $Root 'eula.txt'
if (-not (Test-Path -LiteralPath $EulaPath) -or -not (Select-String -LiteralPath $EulaPath -Pattern '^\s*eula\s*=\s*true\s*$' -Quiet)) {
  Write-Host 'Minecraft EULA acceptance is required. This script will NOT accept it for you.'
  Write-Host 'Read https://aka.ms/MinecraftEULA and edit eula.txt yourself if you agree.'
  exit 3
}
$Manifest = Get-Content -LiteralPath (Join-Path $Root 'docs\verification\test-runtime-files.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($Entry in $Manifest.files) {
  $Path = Join-Path $Root $Entry.path
  if (-not (Test-Path -LiteralPath $Path)) { throw "Missing runtime file: $($Entry.path)" }
  $Hash = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($Hash -ne $Entry.sha256) { throw "Changed runtime file: $($Entry.path)" }
}
$Java = Join-Path $Root 'RUNTIME_JAVA'
Push-Location -LiteralPath $Root
try {
  & $Java '-Xms1G' '-Xmx4G' '-Djava.net.preferIPv4Stack=true' '@user_jvm_args.txt' '@libraries/net/neoforged/neoforge/NEO_VERSION/win_args.txt' 'nogui'
  exit $LASTEXITCODE
} finally { Pop-Location }
'''.replace('RUNTIME_JAVA',(Path('runtime')/jhome.name/'bin/java.exe').as_posix().replace('/','\\')).replace('NEO_VERSION',v)
(O/'start-server-test.ps1').write_text(script,encoding='utf-8-sig')
if not (O/'start-server-test.cmd').exists(): (O/'start-server-test.cmd').write_text('@echo off\r\ncd /d "%~dp0"\r\npowershell.exe -NoProfile -File "%~dp0start-server-test.ps1" %*\r\npause\r\n',encoding='ascii')
files=[]
for folder in ['runtime','libraries','mods','kubejs','resourcepacks']:
 for p in sorted((O/folder).rglob('*')):
  if p.is_file():
   with p.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
   files.append({'path':p.relative_to(O).as_posix(),'bytes':p.stat().st_size,'sha256':digest})
# Include only the authored, manifest-owned quest configuration in integrity checks.
quest_manifest=O/'docs/quest-migration/ftb-export/export-manifest.json'
if quest_manifest.exists():
 for name in sorted(json.loads(quest_manifest.read_text(encoding='utf-8'))['files']):
  p=O/'config/ftbquests/quests'/name
  assert p.is_file(),f'Missing authored quest file: {name}'
  with p.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
  files.append({'path':p.relative_to(O).as_posix(),'bytes':p.stat().st_size,'sha256':digest})
# Include the exact archive required by the packaged compatibility JAR. Older
# content-addressed archives remain on disk without becoming current inputs.
with zipfile.ZipFile(O/'mods/gtnh-migration-compat-0.1.0.jar') as z:
 pin=z.read('gtnh_migration/quest-definitions.pin').decode('ascii').splitlines()
assert len(pin)==4 and pin[0]=='GTNHQDP1' and all(re.fullmatch('[0-9a-f]{64}',v) for v in pin[1:])
p=O/'config/gtnh_migration/quest-definitions'/(pin[1]+'.bin')
assert p.is_file(),'Missing pinned definition archive'
with p.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
assert digest==pin[1],'Changed pinned definition archive'
files.append({'path':p.relative_to(O).as_posix(),'bytes':p.stat().st_size,'sha256':digest})
# Pin the authored EnderStorage configuration; user EULA and generated runtime logs are deliberately excluded.
p=O/'config/EnderStorage.cfg'
if p.exists():
 with p.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
 files.append({'path':p.relative_to(O).as_posix(),'bytes':p.stat().st_size,'sha256':digest})
report={'source_sha256':{Path(__file__).relative_to(R).as_posix():hashlib.sha256(Path(__file__).read_bytes()).hexdigest()},'created_at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files':files,'scope':'Installed test runtime and migration inputs; no world, EULA consent, or gameplay validation implied.'}
(O/'docs/verification/test-runtime-files.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(O/'docs/verification/test-runtime.sha256.tsv').write_text(''.join(x['sha256']+'\t'+x['path']+'\n' for x in files),encoding='utf-8')
# A relocatable test output must not depend on the work folder or source absolute paths.
args=(O/f'libraries/net/neoforged/neoforge/{v}/win_args.txt').read_text(encoding='utf-8')
assert str(W).replace('\\','/') not in args and str(W) not in args
print(json.dumps({'test_runtime_files':len(files),'bytes':sum(x['bytes'] for x in files),'java_relative':str((dest/'bin/java.exe').relative_to(O)),'eula_accepted':False,'loopback_only':True,'full_migration':False}),flush=True)
