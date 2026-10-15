raise SystemExit('Superseded by pinned 2.9.84 sources. Use rebuild_current_phase.py; do not overwrite current item and recipe output with 2.8.4 data.')
"""Translate all 112 coin-exchange assembler recipes from source JAR bytecode."""
from pathlib import Path
import json,re,hashlib,collections
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';D=O/'docs/currency-migration'
source=(W/'migration/coin-recipes.bytecode.txt').read_text(encoding='utf-8')
parts=source.split('// Method gregtech/api/util/GTRecipeBuilder.addTo:')[:-1]
assert len(parts)==112
mapping=json.loads((D/'coin-mapping.json').read_text(encoding='utf-8'));ids={r['legacy_id'].split(':')[1]:r['target_id'] for r in mapping['records']}
# Derive voltage constants from the actual original GTValues static bytecode.
gtv=(W/'migration/GTValues.bytecode.txt').read_text(encoding='utf-8')
static=gtv[gtv.index('static {};'):];vbody=static[:static.index('// Field V:[J')]
volts=[int(v) for v in re.findall(r'// long (\d+)l',vbody)];assert volts[:5]==[8,32,128,512,2048]
lam=gtv[gtv.index('private static long lambda$static$0'):gtv.index('static {};')]
assert re.findall(r'// long (\d+)l',lam)==['30','32']
assert 'BigInteger.multiply' in lam and 'BigInteger.divide' in lam
teu=(W/'migration/TierEU.bytecode.txt').read_text(encoding='utf-8')
for tier,index in [('LV',1),('MV',2),('HV',3),('EV',4)]:
    assert re.search(r'GTValues.VP:\[J\s*\d+: iconst_'+str(index)+r'\s*\d+: laload\s*\d+: putstatic[^\n]*RECIPE_'+tier+r':J',teu)
eut={tier:volts[index]*30//32 for tier,index in [('LV',1),('MV',2),('HV',3),('EV',4)]}
def lines_in(p):
 return [x.strip() for x in p.splitlines() if re.match(r'^\s*\d+:',x)]
def constant(line):
 m=re.search(r'// long (-?\d+)l',line)
 if m:return int(m.group(1))
 m=re.search(r'(?:[il]const_)(\d+)',line)
 if m:return int(m.group(1))
 m=re.search(r'(?:bipush|sipush)\s+(-?\d+)',line)
 if m:return int(m.group(1))
 raise ValueError('Unsupported bytecode constant: '+line)
records=[]
for idx,p in enumerate(parts):
 lines=lines_in(p)
 ci=[(i,re.search(r'CustomItemList\.(Coin\w+):',l).group(1)) for i,l in enumerate(lines) if re.search(r'// Field com/dreammaster/gthandler/CustomItemList\.(Coin\w+):',l)]
 assert len(ci)==2,(idx,ci)
 ((ii,src),(oi,dst))=ci;countin=constant(lines[ii+1]);countout=constant(lines[oi+1])
 circuitlines=[i for i,l in enumerate(lines) if '// Method gregtech/api/util/GTUtility.getIntegratedCircuit:' in l];assert len(circuitlines)==1
 circuit=constant(lines[circuitlines[0]-1])
 di=[i for i,l in enumerate(lines) if '// Method gregtech/api/util/GTRecipeBuilder.duration:' in l];assert len(di)==1
 duration=constant(lines[di[0]-1]);tier=re.findall(r'TierEU.RECIPE_(\w+):',p);assert len(tier)==1
 assert '// Field gregtech/api/recipe/RecipeMaps.assemblerRecipes:' in p
 assert all(x not in p for x in ['fluidInputs','metadata:','outputChances'])
 assert countin in [1,10] and countout in [1,10] and countin!=countout
 assert circuit==(10 if countin==10 else 1)
 rec={'id':'gtnh_migration:coin_exchange/'+src.lower()+'_to_'+dst.lower(),'source_method':'com.dreammaster.gthandler.recipes.AssemblerRecipes.makeCoinRecipes','source_recipe_index':idx,'source_instruction_offset':int(lines[ii].split(':')[0]),'source_input':src,'source_output':dst,'input':ids[src],'input_count':countin,'output':ids[dst],'output_count':countout,'circuit':circuit,'circuit_consumed':False,'duration_ticks':duration,'original_tier':'RECIPE_'+tier[0],'eu_per_tick':eut[tier[0]],'source':'GTNH 2.8.4, not yet reconciled against missing 2.9.0-RC-2 CoreMod implementation','runtime_verified':False}
 records.append(rec)
assert len({r['id'] for r in records})==112
# Reciprocal conversions must not introduce value creation or asymmetric recipe data.
lookup={(r['input'],r['output']):r for r in records}
for r in records:
 rev=lookup[(r['output'],r['input'])]
 assert r['input_count']==rev['output_count'] and r['output_count']==rev['input_count']
 assert r['duration_ticks']==rev['duration_ticks'] and r['eu_per_tick']==rev['eu_per_tick']
js=['// Source: GTNH 2.8.4 AssemblerRecipes.makeCoinRecipes, 112 bytecode-audited recipes.',
    '// Do NOT treat this as proof that all 2.9.0 recipes/economy are ported.',
    "ServerEvents.recipes(event => {"]
for r in records:
 inp=(str(r['input_count'])+'x ' if r['input_count']!=1 else '')+r['input'];out=(str(r['output_count'])+'x ' if r['output_count']!=1 else '')+r['output']
 js.append('  event.recipes.gtceu.assembler('+json.dumps(r['id'])+')')
 js.append('    .inputItems('+json.dumps(inp)+').outputItems('+json.dumps(out)+')')
 js.append(f"    .circuit({r['circuit']}).duration({r['duration_ticks']}).EUt({r['eu_per_tick']}, 1);")
js.append('});')
p=O/'kubejs/server_scripts/10_gtnh_coin_exchange.js';p.parent.mkdir(parents=True,exist_ok=True);p.write_text('\n'.join(js)+'\n',encoding='utf-8')
report={'source_jar':'GTNewHorizonsCoreMod-2.7.268.jar','source_method':'AssemblerRecipes.makeCoinRecipes','source_bytecode_sha256':hashlib.sha256(source.encode()).hexdigest(),'recipes':records,'recipe_count':len(records),'reciprocal_pairs':len(records)//2,'durations':sorted({r['duration_ticks'] for r in records}),'eu_per_tick_values':eut,'checks':['112 complete stdBuilder/addTo recipe blocks consumed','all item references map to original coin items','all reciprocal count ratios and energy/durations match','original integrated circuits are non-consumable (stack count 0), mapped to GTCEu circuit selector'],'runtime_verified':False,'full_economy_parity':False}
(D/'coin-exchange-recipes.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:report[k] for k in ['recipe_count','reciprocal_pairs','durations','eu_per_tick_values','runtime_verified']}))
