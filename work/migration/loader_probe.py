from pathlib import Path
import json,subprocess,datetime,hashlib,shutil
ROOT=Path(__file__).resolve().parents[2];WORK=ROOT/'work';OUT=ROOT/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';SERVER=WORK/'neoforge-smoke'
java=json.loads((WORK/'migration/runtime.json').read_text(encoding='utf-8'))['java']
v=json.loads((WORK/'migration/platform-lock.json').read_text(encoding='utf-8'))['neoforge']
assert 'eula=false' in (SERVER/'eula.txt').read_text(encoding='utf-8'), 'Loader probe only allowed with unaccepted EULA'
args=SERVER/f'libraries/net/neoforged/neoforge/{v}/win_args.txt'
assert args.exists()
cmd=[java,'-Xms512M','-Xmx4G','-Djava.net.preferIPv4Stack=true','@'+str(args),'nogui']
log=WORK/'migration/loader-with-quest-runtime.log'; print('RUNNING_LOADER_CHECK_NO_EULA_ACCEPTANCE',flush=True)
with log.open('wb') as f:
    proc=subprocess.Popen(cmd,cwd=SERVER,stdin=subprocess.PIPE,stdout=f,stderr=subprocess.STDOUT)
    try: code=proc.wait(timeout=240)
    except subprocess.TimeoutExpired:
        proc.stdin.write(b'stop\n');proc.stdin.flush()
        try:code=proc.wait(timeout=30)
        except subprocess.TimeoutExpired:proc.terminate();code=proc.wait(timeout=20)
text=log.read_text(encoding='utf-8',errors='replace')
result={'returncode':code,'eula_changed_by_agent':False,'log':str(log),'timestamp':datetime.datetime.now(datetime.timezone.utc).isoformat(),'lines':len(text.splitlines()),'eula_gate_seen':any(s in text.lower() for s in ['agree to the eula','agree to our eula']),'server_done_seen':'Done (' in text}
(WORK/'migration/loader-with-quest-runtime.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
print(json.dumps(result),flush=True)
assert code==0 and result['eula_gate_seen'] and not result['server_done_seen'], 'Unexpected loader probe result; inspect saved log'
