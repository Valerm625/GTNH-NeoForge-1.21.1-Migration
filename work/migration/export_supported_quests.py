"""Conservative FTB export. Unsupported quests retain source + explicit blockers, never placeholders."""
from pathlib import Path
import json,re,hashlib,collections,shutil
from legacy_vanilla_items import resolve_legacy_vanilla
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';Q=W/'source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests';D=O/'docs/quest-migration/ftb-export';D.mkdir(parents=True,exist_ok=True);DEST=O/'config/ftbquests/quests'

refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));registry={(x['identity']['id'],x['identity']['damage']):x['target_id'] for x in refs if x.get('target_id') and not x['identity']['tag'] and not x['identity']['ore_dict']}
# Keep the complete old ID space stable through deterministic 64-bit collision-checked FTB IDs.
ids={}
def new_id(kind,source):
 h=hashlib.sha256((kind+'\0'+source).encode()).hexdigest()[:16].upper();assert h not in ['0000000000000000','0000000000000001']
 key=kind+'|'+source;assert h not in ids or ids[h]==key;ids[h]=key;return h

def raw_id(d,pre='questID'):return str(d[pre+'High:4'])+':'+str(d[pre+'Low:4'])
def stack(x):
 if x.get('tag:10') or x.get('OreDict:8'):raise ValueError('NBT/ore dictionary predicate not yet adapted')
 id=x['id:8'];meta=x.get('Damage:2',0);count=x.get('Count:3',1)
 if not 1<=count<=8192:raise ValueError('Invalid/custom count')
 reg=registry.get((id,meta))
 van=resolve_legacy_vanilla(id,meta,x.get('OreDict:8',''),x.get('tag:10'))
 if reg and van and reg != van: raise ValueError(f'Conflicting identity mapping {id}:{meta}: {reg} vs {van}')
 target=reg or van
 if not target:raise ValueError('Unmapped item/icon/reward: '+id+':'+str(meta))
 return target,count

# Preserve rich text and exact visible strings. Default light theme colors verified in BQ FormattingTag.java.
colors={'0':'black','1':'dark_blue','2':'dark_green','3':'dark_aqua','4':'dark_red','5':'dark_purple','6':'gold','7':'gray','8':'dark_gray','9':'blue','a':'green','b':'aqua','c':'red','d':'light_purple','e':'yellow','f':'white'}
style_tags={'note':{'color':'dark_aqua'},'warn':{'color':'dark_red'},'quest':{'color':'dark_green','underlined':True},'bold':{'bold':True},'italic':{'italic':True},'underline':{'underlined':True},'strikethrough':{'strikethrough':True},'obfuscated':{'obfuscated':True}}
def rich(line):
 nodes=[];style={};stack_=[];pos=0
 token=re.compile(r'§[0-9a-fklmnor]|\[/?[a-z]+(?: [^\]]*)?\]',re.I)
 for m in token.finditer(line):
  if m.start()>pos:nodes.append({'text':line[pos:m.start()],**style})
  t=m.group();pos=m.end()
  if t.startswith('§'):
   code=t[1].lower()
   if code in colors:style={'color':colors[code]}
   elif code=='r':style={}
   else:style={**style,{'k':'obfuscated','l':'bold','m':'strikethrough','n':'underlined','o':'italic'}[code]:True}
   continue
  closing=t.startswith('[/');name=t[2:-1] if closing else t[1:-1].split(' ',1)[0]
  if closing:
   if not stack_ or stack_[-1][0]!=name:raise ValueError('Unbalanced markup '+name)
   _,style=stack_.pop();continue
  if name=='url':
   end=line.find('[/url]',pos)
   if end<0:raise ValueError('Unterminated URL')
   params=t[1:-1].split(' ',1);url=params[1][5:] if len(params)>1 and params[1].startswith('link=') else line[pos:end]
   if not re.match(r'https?://[^\s]+$',url):raise ValueError('Unsafe/unsupported URL')
   stack_.append((name,style));style={**style,'color':'dark_blue','underlined':True,'clickEvent':{'action':'open_url','value':url}}
  elif name in style_tags:stack_.append((name,style));style={**style,**style_tags[name]}
  else:raise ValueError('Unsupported markup tag: '+name)
 if pos<len(line):nodes.append({'text':line[pos:],**style})
 if stack_:raise ValueError('Markup crosses line boundary; requires explicit conversion')
 return json.dumps({'text':'','extra':nodes},ensure_ascii=False,separators=(',',':'))

def snbt(v):
 if isinstance(v,bool):return '1b' if v else '0b'
 if isinstance(v,str):return json.dumps(v,ensure_ascii=False)
 if isinstance(v,int):return str(v)
 if isinstance(v,float):return repr(v)+'d'
 if isinstance(v,list):return '[ '+', '.join(snbt(x) for x in v)+' ]'
 if isinstance(v,dict):return '{\n'+',\n'.join(json.dumps(k,ensure_ascii=False)+': '+snbt(x) for k,x in v.items())+'\n}'
 raise TypeError(type(v))

sources={};candidates={};blocked={}
for p in sorted((Q/'Quests').rglob('*.json')):
 d=json.loads(p.read_text(encoding='utf-8'));qid=raw_id(d);sources[qid]={'data':d,'path':p.relative_to(Q).as_posix()};new_id('quest',qid)
for qid,src in sources.items():
 d=src['data'];p=d['properties:10']['betterquesting:10'];tasks=[];rewards=[]
 try:
  if p.get('isGlobal:1',0) or p.get('repeatTime:3',-1)>=0 or p.get('simultaneous:1',0):raise ValueError('Global/repeat/simultaneous quest semantics unresolved')
  if p.get('taskLogic:8','AND')!='AND' or p.get('questLogic:8','AND')!='AND':raise ValueError('Non-AND logic unsupported')
  if p.get('autoClaim:1',0) or p.get('partySingleReward:1',0):raise ValueError('Reward-sharing/autoclaim mode not adapted')
  if p.get('visibility:8','NORMAL') not in ['NORMAL','UNLOCKED']:raise ValueError('Visibility mode not adapted')
  icon,_=stack(p['icon:10'])
  desc=[rich(x) for x in p.get('desc:8','').split('\n')]
  for key,t in d.get('tasks:9',{}).items():
   tid=new_id('task',qid+'/'+key);typ=t['taskID:8']
   if typ=='bq_standard:checkbox':tasks.append({'id':tid,'type':'checkmark'});continue
   if typ=='bq_standard:crafting':
    # BQ crafting detects craft and smelt events, but not anvil events in this
    # reviewed subset. Statistics-only completion and ore/NBT predicates need
    # separate adapters, so fail closed instead of using inventory detection.
    if t.get('allowCraftedFromStatistics:1',0) or not t.get('allowCraft:1',1) or not t.get('allowSmelt:1',1) or t.get('allowAnvil:1',0):
     raise ValueError('Crafting task allows unsupported statistics/anvil/event semantics')
    req=list(t.get('requiredItems:9',{}).values())
    if not req: raise ValueError('Empty crafting requirement list')
    component='none' if t.get('ignoreNBT:1',0) else ('fuzzy' if t.get('partialMatch:1',0) else 'strict')
    for index,required in enumerate(req):
     item,count=stack(required);tasks.append({'id':new_id('task',qid+'/'+key+'/'+str(index)),'type':'item','item':{'id':item,'count':1},'count':count,'consume_items':False,'only_from_crafting':True,'match_components':component,'optional_task':False,'tags':['gtnh_legacy_crafting']})
    continue
   if typ not in ['bq_standard:retrieval','bq_standard:optional_retrieval']:raise ValueError('Task type not implemented: '+typ)
   req=list(t.get('requiredItems:9',{}).values())
   if len(req)!=1 or t.get('consume:1',0) or t.get('autoConsume:1',0) or t.get('groupDetect:1',0) or t.get('requireOnlyOneItem:1',0):raise ValueError('Grouped/consuming/multi-item retrieval requires further adapter')
   item,count=stack(req[0]);task={'id':tid,'type':'item','item':{'id':item,'count':1},'count':count,'consume_items':False,'only_from_crafting':False,'match_components':'none' if t.get('ignoreNBT:1',0) else 'strict','optional_task':typ.endswith('optional_retrieval'),'tags':['gtnh_legacy_single_retrieval']};tasks.append(task)
  if not tasks:raise ValueError('Empty task list not auto-completed by export')
  if all(t.get('optional_task',False) for t in tasks):raise ValueError('All-optional completion differs between engines')
  for key,r in d.get('rewards:9',{}).items():
   if r['rewardID:8']=='bq_standard:choice':
    choices=list(r.get('choices:9',{}).items())
    if not choices: raise ValueError('Empty choice reward')
    table_rewards=[]
    for sub,st in choices:
     item,count=stack(st);table_rewards.append({'id':new_id('choice_item',qid+'/'+key+'/'+sub),'item':{'id':item,'count':1},'count':count})
    rewards.append({'id':new_id('reward',qid+'/'+key),'type':'choice','table_id':-1,'table_data':{'loot_size':1,'rewards':table_rewards},'team_reward':False,'auto':'disabled'})
    continue
   if r['rewardID:8']!='bq_standard:item':raise ValueError('Reward type not implemented: '+r['rewardID:8'])
   for sub,st in r.get('rewards:9',{}).items():
    item,count=stack(st);rewards.append({'id':new_id('reward',qid+'/'+key+'/'+sub),'type':'item','item':{'id':item,'count':1},'count':count,'team_reward':False,'auto':'disabled'})
  deps=[raw_id(x) for x in d.get('preRequisites:9',{}).values()]
  candidates[qid]={'id':new_id('quest',qid),'icon':{'id':icon},'tasks':tasks,'rewards':rewards,'dependencies':[new_id('quest',dep) for dep in deps],'dependency_requirement':'all_completed','progression_mode':'linear' if p.get('lockedProgress:1',0) else 'flexible','can_repeat':False,'hide_until_deps_complete':p.get('visibility:8')=='UNLOCKED','hide_until_deps_visible':False,'hide_text_until_complete':False,'require_sequential_tasks':False,'disable_toast':bool(p.get('isSilent:1',0)),'tags':['gtnh_imported_partial'],'_source_id':qid,'_source_deps':deps,'_title':p.get('name:8',''),'_description':desc}
 except (ValueError,KeyError) as e:blocked[qid]=str(e)
# Dependency closure: no omitted prerequisites silently unlock a quest.
active=set();changed=True
while changed:
 changed=False
 for qid,c in candidates.items():
  if qid not in active and all(d in active for d in c['_source_deps']):active.add(qid);changed=True
for qid in candidates.keys()-active:blocked[qid]='Dependency not exported: '+','.join(x for x in candidates[qid]['_source_deps'] if x not in active)
# Only real source chapters/placements; quests present in multiple chapters become links, not duplicate progress.
chapterdata={};placements={};chaporder=[]
for directory in sorted((Q/'QuestLines').iterdir()):
 if not directory.is_dir():continue
 meta=json.loads((directory/'QuestLine.json').read_text(encoding='utf-8'));cid=raw_id(meta,'questLineID');chapterdata[cid]=(meta,directory)
 for p in directory.glob('*.json'):
  if p.name=='QuestLine.json':continue
  place=json.loads(p.read_text(encoding='utf-8'));qid=raw_id(place)
  if qid in active:placements.setdefault(qid,[]).append((cid,place))
# Don't export unplaced trigger quests without a reviewed hidden chapter model.
for qid in list(active):
 if qid not in placements:active.remove(qid);blocked[qid]='Unplaced trigger quest needs hidden chapter semantics'
for line in (Q/'QuestLinesOrder.txt').read_text(encoding='utf-8').splitlines():
 if ': ' in line:
  encoded=line.split(': ',1)[0]
  for cid,(_,p) in chapterdata.items():
   if p.name.endswith(encoded):chaporder.append(cid)
langs={};chapters=[];assigned=set();exported=[]
for order,cid in enumerate(chaporder):
 meta,directory=chapterdata[cid];props=meta['properties:10']['betterquesting:10'];chosen=[qid for qid in active if any(c==cid for c,p in placements[qid])]
 if not chosen:continue
 ch_id=new_id('chapter',cid);filename='gtnh_'+cid.replace(':','_').replace('-','n');ch={'id':ch_id,'filename':filename,'group':'','order_index':order,'icon':{'id':stack(props['icon:10'])[0]},'default_quest_shape':'square','default_quest_size':1.0,'progression_mode':'flexible','consume_items':False,'hide_quest_until_deps_complete':False,'default_repeatable_quest':False,'quests':[],'quest_links':[]}
 langs['chapter.'+ch_id+'.title']=props['name:8'];langs['chapter.'+ch_id+'.chapter_subtitle']=[props.get('desc:8',''),'[Migration status] This is a partial, unverified import. Remaining original quests/systems are not yet available.']
 for qid in sorted(chosen):
  c=candidates[qid];place=next(p for cc,p in placements[qid] if cc==cid);x=(place['x:3']+place['sizeX:3']/2)/24;y=(place['y:3']+place['sizeY:3']/2)/24
  if qid in assigned:
   ch['quest_links'].append({'id':new_id('quest_link',qid+'/'+cid),'linked_quest':c['id'],'x':x,'y':y,'size':place['sizeX:3']/24});continue
  target={k:v for k,v in c.items() if not k.startswith('_')};target.update({'x':x,'y':y,'size':place['sizeX:3']/24,'icon_scale':1.0});ch['quests'].append(target);assigned.add(qid);exported.append({'source_id':qid,'target_id':c['id'],'source_path':sources[qid]['path'],'source_sha256':hashlib.sha256((Q/sources[qid]['path']).read_bytes()).hexdigest(),'chapter_id':ch_id,'tasks':len(c['tasks']),'rewards':len(c['rewards']),'dependency_ids':c['dependencies']})
  langs['quest.'+c['id']+'.title']=c['_title'];langs['quest.'+c['id']+'.quest_desc']=[json.dumps({'text':'[Migration preview] Original text follows. Mentioned mods, controls and progression may not yet be implemented.','color':'gold'},separators=(',',':'))]+c['_description']
 chapters.append(ch)
assert '0:0' in assigned and len(assigned)==len(active)
root={'version':13,'default_reward_team':False,'default_consume_items':False,'default_autoclaim_rewards':'disabled','default_quest_shape':'square','progression_mode':'flexible','fallback_locale':'en_us','detection_delay':0,'show_lock_icons':True,'verify_on_load':False,'drop_loot_crates':False,'pause_game':False}
files={'data.snbt':root,'chapter_groups.snbt':{'chapter_groups':[]},'lang/en_us.snbt':langs}
for ch in chapters:files['chapters/'+ch['filename']+'.snbt']=ch
# Preserve unrelated configs; stale previously generated files are only removed if owned by prior export manifest.
prior=D/'export-manifest.json'
if prior.exists():
 for rel in json.loads(prior.read_text(encoding='utf-8')).get('files',{}):
  if rel not in files:
   p=DEST/rel
   if p.exists():p.unlink()
for rel,value in files.items():p=DEST/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(snbt(value)+'\n',encoding='utf-8')
report={'engine':'FTB Quests 2101.1.36','source_quests':len(sources),'structurally_supported_candidates':len(candidates),'crafting_task_candidates':sum(1 for c in candidates.values() for t in c['tasks'] if 'gtnh_legacy_crafting' in t.get('tags',[])),'choice_reward_candidates':sum(1 for c in candidates.values() for r in c['rewards'] if r.get('type')=='choice'),'choice_reward_candidate_source_ids':sorted(c['_source_id'] for c in candidates.values() if any(r.get('type')=='choice' for r in c['rewards'])),'crafting_candidate_source_ids':sorted(c['_source_id'] for c in candidates.values() if any('gtnh_legacy_crafting' in t.get('tags',[]) for t in c['tasks'])),'dependency_closed_active_quests':len(exported),'active_quest_ids':sorted(assigned),'chapters':len(chapters),'exported':exported,'files':{k:{'sha256':hashlib.sha256((DEST/k).read_bytes()).hexdigest(),'data':v} for k,v in files.items()},'blocked_quests':[{'source_id':qid,'name':sources[qid]['data']['properties:10']['betterquesting:10'].get('name:8'),'reason':reason} for qid,reason in blocked.items()],'unsupported_not_replaced':True,'original_text_preserved_as_rich_components':True,'active_trial_import':True,'game_runtime_verified':False,'full_quest_parity':False,'limitations':['Only exact single-item non-consuming retrieval plus original checkbox tasks; custom adapter required.','Full party join/leave/history semantics are not equivalent yet.','Original text mentions unported systems; conspicuous preview note added, not removed.','Coordinates scaled from top-left pixel geometry to node centers; visual overlap not yet game-tested.','Theme-specific visual effects/notifications still not ported.']}
prior.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');(D/'stable-id-map.json').write_text(json.dumps(ids,indent=2),encoding='utf-8')
# Copy source losslessly for this bounded active surface, not as substitute quests.
for rec in exported:
 p=Q/rec['source_path'];dst=D/'source-quests'/p.name;dst.parent.mkdir(exist_ok=True);shutil.copy2(p,dst)
print(json.dumps({'source_quests':len(sources),'candidate_quests':len(candidates),'active_closed_quests':len(exported),'chapters':len(chapters),'ids':sorted(assigned),'files':list(files),'game_verified':False},ensure_ascii=False))
