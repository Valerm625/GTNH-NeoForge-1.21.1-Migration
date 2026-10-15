"""Narrow exact-source recipe translator. Reject unsupported syntax and gated behavior."""
from pathlib import Path
import json,re,hashlib,ast,collections
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84';G=W/'exact-sources/GT5-Unofficial/5.09.54.205';D=O/'docs/core-recipes-migration';D.mkdir(exist_ok=True)
items=json.loads((O/'docs/core-items-migration/core-item-mapping.json').read_text(encoding='utf-8'));mapped={r['enum_name']:r['target_id'] for r in items['records']}
# Constants are verified against exact GT5-Unofficial, not copied from the 2.8.4 reference.
values=(G/'src/main/java/gregtech/api/enums/GTValues.java').read_text(encoding='utf-8');vb=re.search(r'long\[\] V = new long\[\] \{(.*?)\};',values,re.S).group(1)
nums=[int(x.replace('_','')) for x in re.findall(r'\b([\d_]+)L',vb)]
assert nums[:6]==[8,32,128,512,2048,8192]
assert '.multiply(BigInteger.valueOf(30))' in values and '.divide(BigInteger.valueOf(32))' in values
T={tier:nums[i]*30//32 for i,tier in enumerate(['ULV','LV','MV','HV','EV','IV','LuV','ZPM','UV','UHV','UEV','UIV','UMV','UXV'])}
builder=(G/'src/main/java/gregtech/api/util/GTRecipeBuilder.java').read_text(encoding='utf-8');assert 'int SECONDS = 20;' in builder and 'int MINUTES = 20 * 60;' in builder
constants={'TICKS':1,'SECONDS':20,'MINUTES':1200,'HOURS':72000}
def number(s):
    s=re.sub(r'TierEU\.RECIPE_(\w+)',lambda m:str(T[m.group(1)]),s)
    s=re.sub(r'(?<=\d)[lL]\b','',s).replace('_','')
    for k,v in constants.items():s=re.sub(r'\b'+k+r'\b',str(v),s)
    def ev(n):
        if isinstance(n,ast.Constant) and isinstance(n.value,int):return n.value
        if isinstance(n,ast.UnaryOp) and isinstance(n.op,ast.USub):return -ev(n.operand)
        if isinstance(n,ast.BinOp):
            a,b=ev(n.left),ev(n.right)
            if isinstance(n.op,ast.Mult):return a*b
            if isinstance(n.op,ast.Add):return a+b
            if isinstance(n.op,ast.Sub):return a-b
            if isinstance(n.op,ast.Div):return a//b
        raise ValueError('Nonconstant expression: '+s)
    return ev(ast.parse(s.strip(),mode='eval').body)
def mask(s):
    # Preserve offsets while excluding comment/string braces from scope tracking.
    return re.sub(r'//[^\n]*|/\*.*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'',lambda m:' '*len(m.group()),s,flags=re.S)
def context(s,pos):
    m=mask(s[:pos]);stack=[];last=0
    for i,ch in enumerate(m):
        if ch=='{':
            label=m[last:i].strip();stack.append(label);last=i+1
        elif ch=='}':
            if stack:stack.pop()
            last=i+1
        elif ch==';':last=i+1
    return stack
MAP={'assemblerRecipes':'assembler','benderRecipes':'bender','compressorRecipes':'compressor','hammerRecipes':'forge_hammer','formingPressRecipes':'forming_press','maceratorRecipes':'macerator','vacuumFreezerRecipes':'vacuum_freezer'}
def parse_calls(chain):
    pos=len('GTValues.RA.stdBuilder()');calls=[]
    while pos<len(chain):
        m=re.match(r'\s*\.([A-Za-z]\w*)\(',chain[pos:])
        if not m:
            if chain[pos:].strip()==';':break
            raise ValueError('Unsupported chain syntax')
        name=m.group(1);start=pos+m.end();depth=1;j=start
        while j<len(chain) and depth:
            if chain[j]=='(':depth+=1
            elif chain[j]==')':depth-=1
            j+=1
        assert depth==0
        calls.append((name,chain[start:j-1].strip()));pos=j
    return calls
def stacks(s):
    pat=r'NHItemList\.(\w+)\.get\(([^()]*)\)'
    rows=[];last=0
    for m in re.finditer(pat,s):
        if s[last:m.start()].strip().strip(',').strip():raise ValueError('Contains non-CoreMod input')
        name,amount=m.groups()
        if name not in mapped:raise ValueError('Unimplemented specialized item: '+name)
        count=number(amount) if amount.strip() else 1
        if count<0:raise ValueError('Negative count')
        rows.append({'source_enum':name,'id':mapped[name],'count':count});last=m.end()
    if not rows or s[last:].strip():raise ValueError('Unsupported or empty item syntax')
    return rows
recipes=[];rejected=[];coin_count=0;all_count=0
for path in sorted((S/'src/main/java/com/dreammaster/gthandler/recipes').glob('*.java')):
    source=path.read_text(encoding='utf-8')
    for m in re.finditer(r'GTValues\.RA\.stdBuilder\(\).*?\.addTo\(\w+\);',source,re.S):
        all_count+=1;chain=m.group();scopes=context(source,m.start())
        # Only consider chains self-contained in already-ported exact item definitions.
        if chain.count('NHItemList.')<2:continue
        try:
            if len(scopes)!=2:raise ValueError('Conditional/loop/nested scope: '+str(scopes[-2:]))
            method=scopes[-1]
            if not re.search(r'void (run|makeCoinRecipes)\(\)',method):raise ValueError('Helper method requires call-site condition audit: '+method)
            calls=parse_calls(chain);keys=[x[0] for x in calls]
            if any(k not in ['itemInputs','itemOutputs','circuit','duration','eut','addTo'] for k in keys):raise ValueError('Unsupported semantics: '+','.join(k for k in keys if k not in ['itemInputs','itemOutputs','circuit','duration','eut','addTo']))
            if len(set(keys))!=len(keys):raise ValueError('Repeated builder methods require explicit audit')
            fields=dict(calls);machine=MAP[fields['addTo']];inputs=stacks(fields['itemInputs']);outputs=stacks(fields['itemOutputs'])
            assert all(x['count']>0 for x in outputs)
            duration=number(fields['duration']);eu=number(fields['eut']);circuit=number(fields['circuit']) if 'circuit' in fields else None
            assert duration>0 and eu>=0
            iscoin=all(x['source_enum'].startswith('Coin') for x in inputs+outputs)
            if iscoin:rid='gtnh_migration:coin_exchange/'+inputs[0]['source_enum'].lower()+'_to_'+outputs[0]['source_enum'].lower();coin_count+=1
            else:rid='gtnh_migration:core/'+machine+'/'+outputs[0]['source_enum'].lower()+'_'+hashlib.sha256(chain.encode()).hexdigest()[:10]
            records={'id':rid,'machine':machine,'inputs':inputs,'outputs':outputs,'duration_ticks':duration,'eu_per_tick':eu,'circuit':circuit,'source_path':path.relative_to(S).as_posix(),'source_line':source[:m.start()].count('\n')+1,'source_chain':chain,'source_chain_sha256':hashlib.sha256(chain.encode()).hexdigest(),'source_scope':scopes,'is_coin_exchange':iscoin,'runtime_verified':False}
            recipes.append(records)
        except (ValueError,KeyError,AssertionError) as e:rejected.append({'source_path':path.relative_to(S).as_posix(),'line':source[:m.start()].count('\n')+1,'reason':str(e),'source_chain':chain})
assert coin_count==112,(coin_count,[r for r in rejected if 'Coin' in r['source_chain']][:2])
assert len({r['id'] for r in recipes})==len(recipes)
old=json.loads((O/'docs/currency-migration/coin-exchange-recipes.json').read_text(encoding='utf-8'));oldby={r['id']:r for r in old['recipes']};differences=[]
for r in recipes:
    if r['is_coin_exchange']:
        a=oldby[r['id']]
        keys={'input':r['inputs'][0]['id'],'input_count':r['inputs'][0]['count'],'output':r['outputs'][0]['id'],'output_count':r['outputs'][0]['count'],'circuit':r['circuit'],'duration_ticks':r['duration_ticks'],'eu_per_tick':r['eu_per_tick']}
        if any(a[k]!=v for k,v in keys.items()):differences.append(r['id'])
        a.update(keys);a['source']='CoreMod 2.9.84 + GT5-Unofficial 5.09.54.205 pinned source';a['source_path']=r['source_path'];a['source_line']=r['source_line'];a['source_chain_sha256']=r['source_chain_sha256']
old['source_version']='2.9.84';old['source_jar']='reconciled to exact source';old['reconciliation_with_2_8_4']={'recipes_checked':112,'numerical_differences':differences};(O/'docs/currency-migration/coin-exchange-recipes.json').write_text(json.dumps(old,ensure_ascii=False,indent=2),encoding='utf-8')
def stackstr(s):return (str(s['count'])+'x ' if s['count']!=1 else '')+s['id']
for filename,selected in [('10_gtnh_coin_exchange.js',[r for r in recipes if r['is_coin_exchange']]),('11_gtnh_core_processing.js',[r for r in recipes if not r['is_coin_exchange']])]:
    lines=['// Adapted from pinned CoreMod 2.9.84 source; GPL-3.0 corresponding source included.', '// Only fully parsed unconditional core-item recipes; unsupported gates/fluids/chances stay pending.',"ServerEvents.recipes(event => {"]
    for r in selected:
        lines.append('  // '+Path(r['source_path']).name+':'+str(r['source_line']))
        lines.append('  event.recipes.gtceu.'+r['machine']+'('+json.dumps(r['id'])+')')
        consume=[x for x in r['inputs'] if x['count']>0];tools=[x for x in r['inputs'] if x['count']==0]
        if consume:lines.append('    .inputItems('+', '.join(json.dumps(stackstr(x)) for x in consume)+')')
        for tool in tools:lines.append('    .notConsumableItem('+json.dumps(tool['id'])+')')
        lines.append('    .outputItems('+', '.join(json.dumps(stackstr(x)) for x in r['outputs'])+')')
        if r['circuit'] is not None:lines.append('    .circuit('+str(r['circuit'])+')')
        lines.append(f"    .duration({r['duration_ticks']}).EUt({r['eu_per_tick']}, 1);")
    lines.append('});');(O/'kubejs/server_scripts'/filename).write_text('\n'.join(lines)+'\n',encoding='utf-8')
report={'core_source_version':'2.9.84','gt_source_version':'5.09.54.205','all_builder_chains_scanned':all_count,'exact_recipes_emitted':len(recipes),'coin_recipes':coin_count,'additional_core_recipes':len(recipes)-coin_count,'numerical_coin_differences_from_2_8_4':differences,'recipe_types':dict(collections.Counter(r['machine'] for r in recipes)),'records':recipes,'rejected_self_contained_candidates':rejected,'runtime_verified':False,'full_recipe_parity':False}
(D/'exact-core-recipes.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ['all_builder_chains_scanned','exact_recipes_emitted','coin_recipes','additional_core_recipes','numerical_coin_differences_from_2_8_4','recipe_types']},ensure_ascii=False))
