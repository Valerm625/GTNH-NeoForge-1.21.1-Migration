from pathlib import Path
import json,urllib.request,subprocess,hashlib,zipfile
R=Path(__file__).resolve().parents[2];C=R/'work/cache/legacy-platform';C.mkdir(parents=True,exist_ok=True)
def get(url,path):
 if path.exists():return
 r=subprocess.run(['curl.exe','-fL','--connect-timeout','12','--max-time','90','--retry','1','-sS','-o',str(path)+'.part',url],capture_output=True,timeout=200)
 if r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:400])
 Path(str(path)+'.part').replace(path)
get('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',C/'mojang-versions.json');v=next(x for x in json.loads((C/'mojang-versions.json').read_text())['versions'] if x['id']=='1.7.10');get(v['url'],C/'1.7.10.json')
meta=json.loads((C/'1.7.10.json').read_text());server=meta['downloads']['server'];p=C/'minecraft-server-1.7.10.jar';get(server['url'],p);assert hashlib.sha1(p.read_bytes()).hexdigest()==server['sha1']
u='https://maven.minecraftforge.net/de/oceanlabs/mcp/mcp/1.7.10/mcp-1.7.10-srg.zip';get(u,C/'mcp-1.7.10-srg.zip')
with zipfile.ZipFile(C/'mcp-1.7.10-srg.zip') as z:
 names=z.namelist();print('MCP_ENTRIES',names[:10]);n=next(n for n in names if n.endswith('joined.srg'));s=z.read(n).decode();(C/'joined.srg').write_text(s,encoding='utf-8')
 lines=[l for l in s.splitlines() if 'net/minecraft/block/Block/' in l and any(x in l for x in ['func_149711_c','func_149638_a','func_149752_b','func_149772_a'])];print('LEGACY_METHODS',lines);print('BLOCK_CLASS',[l for l in s.splitlines() if l.startswith('CL:') and l.endswith(' net/minecraft/block/Block')])
print('LEGACY_SERVER_VERIFIED',p.stat().st_size,server['sha1'])
