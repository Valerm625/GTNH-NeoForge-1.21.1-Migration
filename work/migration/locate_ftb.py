from pathlib import Path
import urllib.request,concurrent.futures,json,xml.etree.ElementTree as ET,datetime
ROOT=Path(__file__).resolve().parents[2];C=ROOT/'work/cache/ftb';C.mkdir(exist_ok=True)
urls={
'ftb-quests':'https://maven.ftb.dev/releases/dev/ftb/mods/ftb-quests-neoforge/maven-metadata.xml',
'ftb-library':'https://maven.ftb.dev/releases/dev/ftb/mods/ftb-library-neoforge/maven-metadata.xml',
'ftb-teams':'https://maven.ftb.dev/releases/dev/ftb/mods/ftb-teams-neoforge/maven-metadata.xml',
}
def run(k,url):
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'GTNH-Migration/0.4'})
  with urllib.request.urlopen(req,timeout=30) as r:b=r.read()
  (C/(k+'-metadata.xml')).write_bytes(b);tree=ET.fromstring(b);vs=[x.text for x in tree.findall('./versioning/versions/version')]
  wanted=[v for v in vs if v.startswith('2101.')]
  return {'project':k,'url':url,'matches':wanted[-12:]}
 except Exception as e:return {'project':k,'error':str(e)}
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as p: results=list(p.map(lambda kv:run(*kv),urls.items()))
print(json.dumps(results,indent=2));(C/'discovery.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
