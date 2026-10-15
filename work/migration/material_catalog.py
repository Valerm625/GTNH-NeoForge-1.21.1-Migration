"""Conservative exact-source GregTech material identity catalog; NOT a save converter."""
from pathlib import Path
import json,re,zipfile,hashlib,math,collections,ast
from fractions import Fraction
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';G=W/'exact-sources/GT5-Unofficial/5.09.54.205';D=O/'docs/material-migration';D.mkdir(exist_ok=True)
def sha(b):return hashlib.sha256(b).hexdigest()
def save(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
def clean(s):return re.sub(r'//[^\n]*|/\*.*?\*/',lambda m:' '*len(m.group()),s,flags=re.S)
def callarg(s,name):
 m=re.search(r'\.'+re.escape(name)+r'\(',s)
 if not m:return None
 depth=1;i=m.end();start=i
 while i<len(s) and depth:
  if s[i]=='(':depth+=1
  elif s[i]==')':depth-=1
  i+=1
 return s[start:i-1]
def amount(s):
 s=s.replace('GTValues.','').replace('M','1').replace('L','').replace('_','')
 def f(n):
  if isinstance(n,ast.Constant) and isinstance(n.value,int):return Fraction(n.value)
  if isinstance(n,ast.UnaryOp) and isinstance(n.op,ast.USub):return -f(n.operand)
  if isinstance(n,ast.BinOp):
   a,b=f(n.left),f(n.right)
   if isinstance(n.op,ast.Mult):return a*b
   if isinstance(n.op,ast.Div):return a/b
   if isinstance(n.op,ast.Add):return a+b
   if isinstance(n.op,ast.Sub):return a-b
  raise ValueError(s)
 return str(f(ast.parse(s,mode='eval').body))
old_ids_src=G/'src/main/java/gregtech/api/enums/MaterialsIDMap.java';ids={int(n):m for n,m in re.findall(r'\br\(\s*(\d+),\s*Materials\.(\w+)\);',old_ids_src.read_text(encoding='utf-8'))}
initpath=G/'src/main/java/gregtech/loaders/materials/MaterialsInit.java';initraw=initpath.read_text(encoding='utf-8');init=clean(initraw)
# Fetch return bodies and references, respecting aliases in the source initializer.
methods={m.group(1):{'body':m.group(2),'line':init[:m.start()].count('\n')+1} for m in re.finditer(r'private static Materials (load\w+)\(\)\s*\{(.*?)\n    \}',init,re.S)}
assign={}
for m in re.finditer(r'((?:Materials\.\w+\s*=\s*)+)(load\w+)\(\);',init):
 for symbol in re.findall(r'Materials\.(\w+)',m.group(1)):assign[symbol]=m.group(2)
old={}
for symbol,method in assign.items():
 if method not in methods:continue
 body=methods[method]['body'];name=re.search(r'\.setName\("([^"]+)"\)',body)
 if not name:continue
 elements=re.findall(r'\.setElement\(Element\.(\w+)\)',body);comp=re.findall(r'\.addMaterial\((?:Materials\.)?(\w+),\s*(\d+)\)',body)
 old[symbol]={'symbol':symbol,'name':name.group(1),'method':method,'line':methods[method]['line'],'element':elements[0] if len(elements)==1 else None,'components':{m:int(n) for m,n in comp},'raw_body':body,'source_file':initpath.relative_to(G).as_posix(),'body_sha256':sha(body.encode())}
oldprefixsrc=G/'src/main/java/gregtech/api/enums/OrePrefixes.java';oldprefixtext=clean(oldprefixsrc.read_text(encoding='utf-8'));oldprefix={}
for m in re.finditer(r'public static final OrePrefixes (\w+)\s*=\s*new OrePrefixBuilder\("[^\"]+"\)(.*?)\.build\(\);',oldprefixtext,re.S):
 a=callarg(m.group(2),'materialAmount');oldprefix[m.group(1)]={'amount':amount(a) if a else None,'source_line':oldprefixtext[:m.start()].count('\n')+1,'body':m.group(2)}
prefixslots={}
for suffix in ['01','02']:
 p=G/f'src/main/java/gregtech/common/items/MetaGeneratedItem{suffix}.java';raw=p.read_text(encoding='utf-8');m=re.search(r'super\(\s*"metaitem\.'+suffix+r'",(.*?)\);',raw,re.S);assert m
 tokens=[t.strip() for t in m.group(1).split(',')];assert len(tokens)==32
 prefixslots['gregtech:gt.metaitem.'+suffix]=[None if t=='null' else t.removeprefix('OrePrefixes.') for t in tokens]
zipfile_path=W/'cache/gtceu-1.21.1-7.0.2-sources.jar'
with zipfile.ZipFile(zipfile_path) as z:
 files={n:z.read(n).decode('utf-8') for n in z.namelist() if n.endswith('.java')};target={}
 for path,raw in files.items():
  if '/data/material/' not in path:continue
  text=clean(raw)
  for m in re.finditer(r'\b(\w+)\s*=\s*new Material.Builder\(GTCEu.id\("([a-z0-9_]+)"\)\)(.*?)\.buildAndRegister\(\);',text,re.S):
   sym,name,body=m.groups();props=set()
   for method in ['dust','ingot','gem','ore','polymer']:
    if re.search(r'\.'+method+r'\(',body):props.add(method)
   if props&{'ingot','gem','polymer','ore'}:props.add('dust')
   if 'polymer' in props:props.add('ingot')
   els=re.findall(r'\.element\(GTElements\.(\w+)\)',body);args=callarg(body,'components');comp={}
   if args is not None:
    ts=[x.strip().removeprefix('GTMaterials.') for x in args.split(',')]
    if len(ts)%2==0 and all(re.fullmatch(r'\w+',ts[i]) and ts[i+1].isdigit() for i in range(0,len(ts),2)):comp={ts[i]:int(ts[i+1]) for i in range(0,len(ts),2)}
   flags=set()
   for fm in re.finditer(r'\.(?:flags|appendFlags)\(([^()]*)\)',body):flags.update(re.findall(r'\b[A-Z][A-Z0-9_]+\b',fm.group(1)))
   if 'polymer' in props:flags.update({'FLAMMABLE','NO_SMASHING','DISABLE_DECOMPOSITION'})
   target[sym]={'symbol':sym,'id':name,'props':sorted(props),'flags':sorted(flags),'element':els[0] if len(els)==1 else None,'components':comp,'has_unparsed_components':args is not None and not comp,'ignored_prefixes':re.findall(r'\b\w+\b',callarg(body,'ignoredTagPrefixes') or ''),'source_file':path,'source_line':text[:m.start()].count('\n')+1,'raw_body':body}
 # Propagate only explicit required flag dependencies and documented preset bundles.
 presets={'STD_METAL':{'GENERATE_PLATE'},'EXT_METAL':{'GENERATE_PLATE','GENERATE_ROD'},'EXT2_METAL':{'GENERATE_PLATE','GENERATE_ROD','GENERATE_LONG_ROD','GENERATE_BOLT_SCREW'}}
 flagsource=clean(files['com/gregtechceu/gtceu/api/material/material/info/MaterialFlags.java']);deps={}
 for m in re.finditer(r'public static final MaterialFlag (\w+)\s*=.*?\.build\(\);',flagsource,re.S):deps[m.group(1)]=set(re.findall(r'\b[A-Z][A-Z_]+\b',callarg(m.group(),'requireFlags') or ''))
 for t in target.values():
  f=set(t['flags']);oldlen=-1
  while oldlen!=len(f):
   oldlen=len(f)
   for x in list(f):f.update(presets.get(x,set()));f.update(deps.get(x,set()))
  t['flags']=sorted(f)
 prefixsource=clean(files['com/gregtechceu/gtceu/api/tag/TagPrefix.java']);prefixes={}
 for m in re.finditer(r'public static final TagPrefix (\w+)\s*=\s*new TagPrefix\("([^\"]+)"\)(.*?);',prefixsource,re.S):
  var,name,body=m.groups();a=callarg(body,'materialAmount');pat=re.search(r'\.idPattern\("([^\"]+)"\)',body)
  lower=re.sub(r'(?<!^)(?=[A-Z])','_',name).lower();prefixes[var]={'var':var,'pattern':pat.group(1) if pat else '%s_'+lower,'amount':amount(a) if a else None,'condition':callarg(body,'generationCondition'),'generates':'.generateItem(true)' in body,'source_line':prefixsource[:m.start()].count('\n')+1,'body':body}
 ignored={};gtsource=clean(files['com/gregtechceu/gtceu/data/material/GTMaterials.java'])
 for m in re.finditer(r'(\w+)\.setIgnored\((\w+)(?:,\s*([^;]+?))?\);',gtsource):
  prefix,mat,expr=m.groups();van=re.fullmatch(r'Items\.(\w+)',expr or '');ignored[(prefix,mat)]={'target':'minecraft:'+van.group(1).lower() if van else None,'source':m.group()}
 for m in re.finditer(r'excludeAllGems\((\w+), Items\.(\w+)\);',gtsource):
  for prefix in ['gem','gemChipped','gemFlawed','gemFlawless','gemExquisite']:
   ignored[(prefix,m.group(1))]={'target':'minecraft:'+m.group(2).lower() if prefix=='gem' else None,'source':m.group()}
 for m in re.finditer(r'excludeAllGemsButNormal\((\w+)\);',gtsource):
  for prefix in ['gemChipped','gemFlawed','gemFlawless','gemExquisite']:ignored[(prefix,m.group(1))]={'target':None,'source':m.group()}
# Nuclear identity uses explicit proton/neutron counts; equal spelling alone is not enough.
old_elements_text=(G/'src/main/java/gregtech/api/enums/Element.java').read_text(encoding='utf-8')
old_elements={m.group(1):(int(m.group(2)),int(m.group(3))) for m in re.finditer(r'^\s*(\w+)\(\s*(\d+),\s*(\d+),',old_elements_text,re.M)}
new_elements={m.group(1):(int(m.group(2)),int(m.group(3))) for m in re.finditer(r'Element (\w+) = createAndRegister\(\s*(\d+),\s*(\d+),',files['com/gregtechceu/gtceu/data/material/GTElements.java'])}

# Match identity recursively by element/composition, never label alone. Unit scale not recipe semantics.
aliases={}
for symbol,mat in old.items():
 if symbol not in target and mat['element']:
  candidates=[name for name,t in target.items() if mat['element'] in old_elements and t['element'] in new_elements and old_elements[mat['element']]==new_elements[t['element']] and old_elements[mat['element']][0]>0]
  if len(candidates)==1:aliases[symbol]=candidates[0]
verified={};fail={}
def identity(sym,vis=None):
 if sym in verified:return True
 if sym in fail:return False
 if sym not in old or aliases.get(sym,sym) not in target:fail[sym]='No exact symbol builder on both source sides';return False
 vis=set() if vis is None else set(vis)
 if sym in vis:fail[sym]='Cyclic composition';return False
 vis.add(sym);a,b=old[sym],target[aliases.get(sym,sym)]
 if a['element'] or b['element']:
  ok=a['element'] in old_elements and b['element'] in new_elements and old_elements[a['element']]==new_elements[b['element']] and old_elements[a['element']][0]>0;why='Same explicit proton and neutron counts; nonzero atomic number' if ok else 'Element nuclear identity mismatch or insufficient atomic data'
 elif a['components'] and b['components'] and not b['has_unparsed_components']:
  ac={aliases.get(k,k):v for k,v in a['components'].items()};bc=b['components'];ga=math.gcd(*ac.values());gb=math.gcd(*bc.values());ok=({k:v//ga for k,v in ac.items()}=={k:v//gb for k,v in bc.items()})
  why='Exact normalized component ratios with recursively matched ingredients'
  if ok:ok=all(identity(k,vis) for k in a['components'])
  if not ok:why='Component ratios or nested component identities differ'
 else:ok=False;why='Insufficient explicit element/composition evidence'
 if ok:verified[sym]=why
 else:fail[sym]=why
 return ok
for sym in old:identity(sym)
translations={'stick':'rod','stickLong':'rodLong','gearGt':'gear','gearGtSmall':'gearSmall','crushedCentrifuged':'crushedRefined'}
# Exact supported predicate interpreter. Unknown cases are withheld, not approximated.
def condition(t,prefix):
 p=prefixes[prefix];c=p['condition'];props=set(t['props']);flags=set(t['flags'])
 if c in ['hasDustProperty','hasIngotProperty','hasGemProperty','hasOreProperty']:return c[3:-8].lower() in props
 m=re.fullmatch(r'mat -> mat.hasFlag\(MaterialFlags\.(\w+)\)',c or '')
 if m:return m.group(1) in flags
 if c=='hasIngotProperty.and(mat -> mat.hasFlag(MaterialFlags.GENERATE_PLATE) && !mat.hasFlag(MaterialFlags.NO_SMASHING))':return 'ingot' in props and 'GENERATE_PLATE' in flags and 'NO_SMASHING' not in flags
 return False
pairmap={};withheld=[]
for sym in sorted(verified):
 for op,info in oldprefix.items():
  np=translations.get(op,op)
  if np not in prefixes or not prefixes[np]['generates']:continue
  p=prefixes[np];t=target[aliases.get(sym,sym)]
  if not info['amount'] or not p['amount'] or info['amount']!=p['amount']:continue
  if not condition(t,np):continue
  override=ignored.get((np,aliases.get(sym,sym)))
  if np in t['ignored_prefixes'] and override is None:continue
  if override is not None:
   if override['target'] is None:continue
   id=override['target']
  else:id='gtceu:'+p['pattern'].replace('%s',t['id'])
  pairmap[op+'|'+sym]={'legacy_prefix':op,'legacy_material':sym,'target_prefix':np,'target_material':t['id'],'target_symbol':aliases.get(sym,sym),'target_id':id,'material_amount':p['amount'],'identity_evidence':verified[sym],'legacy_material_line':old[sym]['line'],'legacy_material_source':old[sym]['source_file'],'target_source':t['source_file'],'target_line':t['source_line'],'generation_condition':p['condition'],'target_flags':t['flags'],'target_tag':('c:'+re.search(r'\.defaultTagPath\("([^"]*%s[^"]*)"',p['body']).group(1).replace('%s',t['id'])) if re.search(r'\.defaultTagPath\("([^"]*%s[^"]*)"',p['body']) else None,'vanilla_override':override,'status':'static_source_identity_not_runtime_or_gameplay_equivalence','limitations':['No claim of source tool stats, hazard, material color, recipe properties or metadata/NBT conversion parity.']}
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'));decoded=[];mapped=0;reasons=collections.Counter()
for r in refs:
 a=r['identity'];item=a['id'];meta=a['damage']
 if item not in prefixslots:continue
 if r.get('mapping_status')=='source_verified_material_identity_runtime_and_properties_pending':r['target_id']=None;r['mapping_status']='unmapped'
 if not isinstance(meta,int) or not 0<=meta<32000:reasons['Nonmaterial/custom/wildcard metadata']+=1;continue
 prefix=prefixslots[item][meta//1000];symbol=ids.get(meta%1000);pair=pairmap.get(str(prefix)+'|'+str(symbol))
 record={'legacy_stack':a,'decoded_prefix':prefix,'decoded_material_id':meta%1000,'decoded_material':symbol,'mapping':pair}
 if pair and not a['tag'] and not a['ore_dict']:
  r['target_id']=pair['target_id'];r['mapping_status']='source_verified_material_identity_runtime_and_properties_pending';mapped+=1
 else:reasons[fail.get(symbol,'Unproven generation/amount or stack-specific semantics')]+=1
 decoded.append(record)
save(O/'docs/quest-migration/item-registry-references.json',refs)
p=O/'docs/quest-migration/coverage.json';coverage=json.loads(p.read_text(encoding='utf-8'));coverage['mapped_material_item_identities']=mapped;coverage['mapped_total_item_identities']=sum(x['target_id'] is not None for x in refs);coverage['other_identities_unmapped']=sum(x['target_id'] is None for x in refs);save(p,coverage)
report={'legacy_numeric_materials':len(ids),'legacy_parsed_builders':len(old),'modern_parsed_builders':len(target),'identity_matched_materials':len(verified),'supported_prefix_material_pairs':len(pairmap),'newly_mapped_quest_identities':mapped,'withheld_reasons':dict(reasons),'source_modern_sources_sha256':sha(zipfile_path.read_bytes()),'legacy_id_table_sha256':sha(old_ids_src.read_bytes()),'id_table':{str(k):v for k,v in ids.items()},'prefix_slots':prefixslots,'legacy_elements':{k:list(v) for k,v in old_elements.items()},'modern_elements':{k:list(v) for k,v in new_elements.items()},'element_proven_symbol_aliases':aliases,'identity_verified':verified,'identity_rejected':fail,'pairs':pairmap,'quest_stack_decoding':decoded,'runtime_registry_verified':False,'save_conversion_implemented':False,'full_material_parity':False}
save(D/'material-identity-map.json',report)
# Source snippets are auditable; no huge stdout matrices.
save(D/'material-definitions.json',{'legacy':old,'modern':target,'prefixes':prefixes,'legacy_prefixes':oldprefix})

color_differences=[]
for symbol in sorted(verified):
    a=old[symbol];b=target[aliases.get(symbol,symbol)]
    x=re.search(r'\.setARGB\((0x[0-9A-Fa-f]+)\)',a['raw_body']);y=re.search(r'\.color\((0x[0-9A-Fa-f]+)\)',b['raw_body'])
    if x and y and (int(x.group(1),16)&0xffffff)!=(int(y.group(1),16)&0xffffff):
        color_differences.append({'material':symbol,'target_id':b['id'],'original_rgb':f"{int(x.group(1),16)&0xffffff:06x}",'modern_rgb':f"{int(y.group(1),16)&0xffffff:06x}",'status':'unmodified; material identity does not imply rendering equivalence'})
save(D/'known-material-color-differences.json',color_differences)

print(json.dumps({k:report[k] for k in ['legacy_numeric_materials','legacy_parsed_builders','modern_parsed_builders','identity_matched_materials','supported_prefix_material_pairs','newly_mapped_quest_identities','withheld_reasons']},ensure_ascii=False))
