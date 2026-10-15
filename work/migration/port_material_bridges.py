"""Translate source-core/material processing only when identity and generation are proved."""
from pathlib import Path
import json,re,hashlib,ast,collections,zipfile
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';G=W/'exact-sources/GT5-Unofficial/5.09.54.205';D=O/'docs/material-migration'
# Reuse explicitly inspected parser functions without executing the old generator's top-level edits.
code=ast.parse((W/'migration/port_exact_core_recipes.py').read_text(encoding='utf-8'))
selected=[n for n in code.body if isinstance(n,ast.FunctionDef) and n.name in ['number','mask','context','parse_calls']];ns={'re':re,'ast':ast,'T':{'ULV':7,'LV':30,'MV':120,'HV':480,'EV':1920,'IV':7680,'LuV':30720,'ZPM':122880,'UV':491520,'UHV':1966080,'UEV':7864320,'UIV':31457280,'UMV':125829120,'UXV':503316480},'constants':{'TICKS':1,'SECONDS':20,'MINUTES':1200,'HOURS':72000}}
exec(compile(ast.Module(body=selected,type_ignores=[]),'<audited-source-parser>','exec'),ns)
number,mask,context,parse_calls=[ns[x] for x in ['number','mask','context','parse_calls']]
catalog=json.loads((D/'material-identity-map.json').read_text(encoding='utf-8'));pairs=catalog['pairs'];core=json.loads((O/'docs/core-items-migration/core-item-mapping.json').read_text(encoding='utf-8'));coremap={r['enum_name']:r['target_id'] for r in core['records']}
existing=json.loads((O/'docs/core-recipes-migration/exact-core-recipes.json').read_text(encoding='utf-8'));existing_chains={r['source_chain_sha256'] for r in existing['records']}
MAP={'assemblerRecipes':'assembler','benderRecipes':'bender','compressorRecipes':'compressor','hammerRecipes':'forge_hammer','formingPressRecipes':'forming_press','maceratorRecipes':'macerator','vacuumFreezerRecipes':'vacuum_freezer','wiremillRecipes':'wiremill','latheRecipes':'lathe','extractorRecipes':'extractor','cutterRecipes':'cutter','extruderRecipes':'extruder','alloySmelterRecipes':'alloy_smelter','mixerRecipes':'mixer','sifterRecipes':'sifter','polarizerRecipes':'polarizer'}
# Machine max item slots extracted from actual target source, not guessed generic limits.
with zipfile.ZipFile(W/'cache/gtceu-1.21.1-7.0.2-sources.jar') as z:
 targettypes=z.read('com/gregtechceu/gtceu/data/recipe/GTRecipeTypes.java').decode()
limits={m.group(1):(int(m.group(2)),int(m.group(3))) for m in re.finditer(r'register\("([a-z_]+)",[^;]*?\.setMaxIOSize\((\d+), (\d+),',targettypes)}
getters={}
materialssource=(G/'src/main/java/gregtech/api/enums/Materials.java').read_text(encoding='utf-8')
for method,pref in re.findall(r'public ItemStack (get\w+)\(int amount\)\s*\{\s*return GTOreDictUnificator.get\(OrePrefixes\.(\w+), this, amount\);',materialssource):getters[method]=pref
vanilla_items={'clay_ball':'clay_ball','brick':'brick','paper':'paper','string':'string','leather':'leather','coal':'coal','redstone':'redstone','diamond':'diamond','stick':'stick','iron_ingot':'iron_ingot','gold_ingot':'gold_ingot','glass_bottle':'glass_bottle','blaze_powder':'blaze_powder','blaze_rod':'blaze_rod','bone':'bone','flint':'flint','slime_ball':'slime_ball','ender_pearl':'ender_pearl','ender_eye':'ender_eye','nether_star':'nether_star'}
vanilla_blocks={'glass':'glass','stone':'stone','cobblestone':'cobblestone','sand':'sand','obsidian':'obsidian','coal_block':'coal_block','diamond_block':'diamond_block','redstone_block':'redstone_block','iron_block':'iron_block','gold_block':'gold_block','wool':'white_wool'}
def splitargs(s):
 out=[];depth=0;start=0;quote=False;escape=False
 for i,c in enumerate(s):
  if c=='"' and not escape:quote=not quote
  if not quote:
   if c in '([{':depth+=1
   elif c in ')]}':depth-=1
   elif c==',' and depth==0:out.append(s[start:i].strip());start=i+1
  escape=c=='\\' and not escape
 out.append(s[start:].strip());return out

def stack(expr,output=False):
 expr=expr.strip();m=re.fullmatch(r'NHItemList\.(\w+)\.get\(([^()]*)\)',expr)
 if m:
  if m.group(1) not in coremap:raise ValueError('Unported specialized CoreMod item')
  return {'id':coremap[m.group(1)],'count':number(m.group(2)) if m.group(2).strip() else 1,'kind':'core','source':expr,'source_enum':m.group(1)}
 m=re.fullmatch(r'GTOreDictUnificator.get\(\s*OrePrefixes\.(\w+),\s*Materials\.(\w+),\s*([^()]+)\)',expr)
 if m:prefix,material,num=m.groups()
 else:
  m=re.fullmatch(r'Materials\.(\w+)\.(get\w+)\(([^()]+)\)',expr)
  if m:
   material,getter,num=m.groups()
   if getter not in getters:raise ValueError('Unknown Materials stack getter')
   prefix=getters[getter]
  else:prefix=material=num=None
 if prefix:
  key=prefix+'|'+material
  if key not in pairs:raise ValueError('Unproved material generation/identity/amount: '+key)
  pair=pairs[key]
  if not output and not pair['target_tag']:raise ValueError('No exact native unification tag')
  return {'id':pair['target_id'],'ingredient':pair['target_id'] if output else '#'+pair['target_tag'],'count':number(num),'kind':'material','pair_key':key,'source':expr,'identity_evidence':pair['identity_evidence']}
 m=re.fullmatch(r'new ItemStack\((Items|Blocks)\.(\w+)(?:,\s*([^,()]+))?(?:,\s*([^,()]+))?\)',expr)
 if m:
  typ,name,n,meta=m.groups();count=number(n) if n else 1;damage=number(meta) if meta else 0
  if damage!=0:raise ValueError('Vanilla metadata conversion needs explicit proof')
  table=vanilla_items if typ=='Items' else vanilla_blocks
  if name not in table:raise ValueError('Vanilla name mapping not whitelisted')
  return {'id':'minecraft:'+table[name],'count':count,'kind':'vanilla','source':expr}
 raise ValueError('Unsupported input grammar or registry source: '+expr[:150])
recipes=[];rejections=[];candidates=0
for path in sorted((S/'src/main/java/com/dreammaster/gthandler/recipes').glob('*.java')):
 raw=path.read_text(encoding='utf-8')
 for m in re.finditer(r'GTValues\.RA\.stdBuilder\(\).*?\.addTo\(\w+\);',raw,re.S):
  chain=m.group()
  if 'NHItemList.' not in chain or 'Materials.' not in chain:continue
  if hashlib.sha256(chain.encode()).hexdigest() in existing_chains:continue
  candidates+=1
  try:
   scopes=context(raw,m.start())
   if len(scopes)!=2 or not re.search(r'void run\(\)',scopes[-1]):raise ValueError('Nontrivial method/conditional scope requires manual gating audit')
   methodstart=raw.rfind('public void run()',0,m.start())
   if 'return' in mask(raw[methodstart:m.start()]):raise ValueError('Early-return path ahead of recipe')
   calls=parse_calls(chain);keys=[k for k,a in calls]
   if len(keys)!=len(set(keys)) or any(k not in ['itemInputs','itemOutputs','circuit','duration','eut','addTo'] for k in keys):raise ValueError('Unsupported fluids/chances/repeated methods/metadata')
   fields=dict(calls)
   if fields['addTo'] not in MAP:raise ValueError('Unknown modern machine type')
   machine=MAP[fields['addTo']];ins=[stack(x) for x in splitargs(fields['itemInputs'])];outs=[stack(x,True) for x in splitargs(fields['itemOutputs'])]
   if not any(x['kind']=='material' for x in ins+outs):raise ValueError('No material bridge needed')
   if not any(x['kind']=='core' for x in ins+outs):raise ValueError('Existing generic processing not overridden blindly')
   if any(not 0<=x['count']<=64 for x in ins) or any(not 1<=x['count']<=64 for x in outs):raise ValueError('Unsafe/custom stack size')
   circuit=number(fields['circuit']) if 'circuit' in fields else None;duration=number(fields['duration']);eu=number(fields['eut'])
   assert duration>0 and eu>=0
   if machine not in limits or len(ins)+(circuit is not None)>limits[machine][0] or len(outs)>limits[machine][1]:raise ValueError('Exceeds target IO-slot capacity')
   rid='gtnh_migration:material_bridge/'+machine+'/'+outs[0]['id'].split(':')[1]+'_'+hashlib.sha256(chain.encode()).hexdigest()[:10]
   recipes.append({'id':rid,'machine':machine,'inputs':ins,'outputs':outs,'circuit':circuit,'duration_ticks':duration,'eu_per_tick':eu,'source_file':path.relative_to(S).as_posix(),'source_line':raw[:m.start()].count('\n')+1,'source_chain':chain,'source_sha256':hashlib.sha256(chain.encode()).hexdigest(),'source_scope':scopes,'runtime_verified':False})
  except (ValueError,KeyError,AssertionError) as e:rejections.append({'source_file':path.relative_to(S).as_posix(),'source_line':raw[:m.start()].count('\n')+1,'reason':str(e),'source_chain':chain})
assert len({r['id'] for r in recipes})==len(recipes)
lines=['// Exact CoreMod 2.9.84 bridges with source-verified GT5U/GTCEu material identity and amount.', '// Native c: material tags retain unification for inputs. Never maps mismatched alloy ratios.', '// Runtime registry/tag resolution, conflicts and whole-progression parity remain unverified.',"ServerEvents.recipes(event => {"]
for r in recipes:
 lines+=['  // '+Path(r['source_file']).name+':'+str(r['source_line']),'  event.recipes.gtceu.'+r['machine']+'('+json.dumps(r['id'])+')']
 consume=[x for x in r['inputs'] if x['count']>0];tools=[x for x in r['inputs'] if x['count']==0]
 def st(x,inp=False):return (str(x['count'])+'x ' if x['count']!=1 else '')+(x.get('ingredient',x['id']) if inp else x['id'])
 if consume:lines.append('    .inputItems('+', '.join(json.dumps(st(x,True)) for x in consume)+')')
 for x in tools:lines.append('    .notConsumableItem('+json.dumps(x.get('ingredient',x['id']))+')')
 lines.append('    .outputItems('+', '.join(json.dumps(st(x)) for x in r['outputs'])+')')
 if r['circuit'] is not None:lines.append('    .circuit('+str(r['circuit'])+')')
 lines.append(f"    .duration({r['duration_ticks']}).EUt({r['eu_per_tick']}, 1);")
lines.append('});');out=O/'kubejs/server_scripts/14_gtnh_material_bridges.js';out.write_text('\n'.join(lines)+'\n',encoding='utf-8')
report={'candidate_chains':candidates,'implemented_recipes':len(recipes),'records':recipes,'rejections':rejections,'rejection_counts':dict(collections.Counter(r['reason'] for r in rejections)),'input_unification':'GTRecipe input stacks are unified; modern native material tags retained rather than narrowing to a single mod item','runtime_tags_verified':False,'full_material_recipe_parity':False}
(D/'material-bridge-recipes.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'candidate_chains':candidates,'implemented_recipes':len(recipes),'machines':dict(collections.Counter(r['machine'] for r in recipes)),'rejected':len(rejections)},ensure_ascii=False))
