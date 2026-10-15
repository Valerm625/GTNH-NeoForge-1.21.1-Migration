from pathlib import Path
import json,hashlib,re,io
from PIL import Image
R=Path(__file__).resolve().parents[2];W=R/'work';O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';K=O/'kubejs';S=W/'exact-sources/NewHorizonsCoreMod/2.9.84'
D=O/'docs/early-game-migration';D.mkdir(exist_ok=True)
source=S/'src/main/java/com/dreammaster/item/WoodenBrickForm.java';text=source.read_text(encoding='utf-8')
assert 'maxDurability = 300' in text and 'return false;' in text and 'ret.getItemDamage() < getMaxDamage()' in text and 'ret.getItemDamage() + 1' in text
recipe_source=S/'src/main/java/com/dreammaster/gthandler/GT_CraftingRecipeLoader.java';rs=recipe_source.read_text(encoding='utf-8')
assert 'NHItemList.UnfiredClayBrick.get(8)' in rs and '"CCC", "CFC", "CCC"' in rs and 'new ItemStack(NHItemList.WoodenBrickForm.item, 1, WILDCARD)' in rs
smelt=S/'src/main/java/com/dreammaster/scripts/ScriptMinecraft.java';ss=smelt.read_text(encoding='utf-8');assert 'GTModHandler.addSmeltingRecipe(NHItemList.UnfiredClayBrick.get(), getModItem(Minecraft.ID, "brick", 1, 0));' in ss
src=S/'src/main/resources/assets/dreamcraft/textures/items/itemWoodenBrickForm.png';b=src.read_bytes()
with Image.open(io.BytesIO(b)) as im:im.verify()
rel='assets/gtnh_migration/textures/item/woodenbrickform.png';dest=K/rel;dest.write_bytes(b)
model={'parent':'minecraft:item/generated','textures':{'layer0':'gtnh_migration:item/woodenbrickform'}}
(K/'assets/gtnh_migration/models/item/woodenbrickform.json').write_text(json.dumps(model,indent=2),encoding='utf-8')
for loc in ['en_US','zh_CN']:
 s=(S/f'src/main/resources/assets/dreamcraft/lang/{loc}.lang').read_text(encoding='utf-8');m=re.search(r'^item.WoodenBrickForm.name=(.*)$',s,re.M);assert m
 p=K/f'assets/gtnh_migration/lang/{loc.lower()}.json';d=json.loads(p.read_text(encoding='utf-8'));d['item.gtnh_migration.woodenbrickform']=m.group(1);p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
js='''// Adapted from CoreMod 2.9.84 WoodenBrickForm.java; GPL-3.0 corresponding source included.
// Preserve exact boundary: damage 299 -> 300, damage 300 -> empty remainder.
StartupEvents.registry('item', event => {
  event.create('gtnh_migration:woodenbrickform')
    .textures({layer0: 'gtnh_migration:item/woodenbrickform'})
    .translationKey('item.gtnh_migration.woodenbrickform')
    .maxStackSize(1).maxDamage(300).disableRepair()
    .craftingRemainingItem(stack => {
      if (stack.getDamageValue() >= 300) return Item.empty;
      const remaining = stack.copy();
      remaining.setCount(1);
      remaining.setDamageValue(stack.getDamageValue() + 1);
      return remaining;
    });
});
'''
(K/'startup_scripts/05_gtnh_wooden_brick_form.js').write_text(js,encoding='utf-8')
recipes={
'unfired_bricks_bulk':{'type':'minecraft:crafting_shaped','category':'misc','pattern':['CCC','CFC','CCC'],'key':{'C':{'item':'minecraft:clay_ball'},'F':{'item':'gtnh_migration:woodenbrickform'}},'result':{'id':'gtnh_migration:unfiredclaybrick','count':8}},
'unfired_brick_single':{'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:clay_ball'},{'item':'gtnh_migration:woodenbrickform'}],'result':{'id':'gtnh_migration:unfiredclaybrick','count':1}},
'fire_unfired_clay_brick':{'type':'minecraft:smelting','category':'misc','ingredient':{'item':'gtnh_migration:unfiredclaybrick'},'result':{'id':'minecraft:brick','count':1},'experience':0.0,'cookingtime':200}}
for id,recipe in recipes.items():
 p=K/f'data/gtnh_migration/recipe/early_game/{id}.json';p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(recipe,indent=2),encoding='utf-8')
report={'source_version':'2.9.84','item':{'legacy_id':'dreamcraft:WoodenBrickForm','target_id':'gtnh_migration:woodenbrickform','max_stack_size':1,'max_damage':300,'repairable':False,'source_path':source.relative_to(S).as_posix(),'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'source_texture':src.relative_to(S).as_posix(),'target_texture':rel,'png_sha256':hashlib.sha256(b).hexdigest()},'recipes':recipes,'source_crafting_file':recipe_source.relative_to(S).as_posix(),'source_smelting_file':smelt.relative_to(S).as_posix(),'smelting_xp_evidence':'GTModHandler.addSmeltingRecipe default delegates xp 0.0F','smelting_ticks_evidence':'Minecraft 1.7.10 vanilla furnace cook cycle 200 ticks; 1.21.1 explicitly retained','remaining_blocker':'Original acquisition of the form depends on Tinkers Construct blankPattern and crafting knife; no replacement acquisition recipe fabricated. Specialized behavior not runtime tested.','creative_tab':'not yet added; available through registry once initialized','runtime_verified':False,'full_early_game_parity':False}
(D/'wooden-brick-form.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
# Mark this specialized item implemented without claiming the remaining nine are implemented.
p=O/'docs/core-items-migration/core-item-mapping.json';d=json.loads(p.read_text(encoding='utf-8'))
for r in d['deferred_specialized_items']:
 if r['enum_name']=='WoodenBrickForm':r['status']='separate source-backed KubeJS behavior and 3 recipes implemented; runtime and acquisition pending';r['report']='docs/early-game-migration/wooden-brick-form.json'
d['additional_specialized_item_definitions']=1;d['all_item_definitions_including_specialized']=558;p.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
# Preserve original GENERIC creative tab membership without changing the 557 simple-item test contract.
integrations=O/'docs/core-items-migration/item-integrations.json';data=json.loads(integrations.read_text(encoding='utf-8'))
for tab in data['creative_tabs']:
    if tab['source_tab']=='GENERIC' and 'gtnh_migration:woodenbrickform' not in tab['items']:tab['items'].append('gtnh_migration:woodenbrickform')
integrations.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
lines=['// Original creative tab membership including implemented specialized brick mold.',"StartupEvents.registry('creative_mode_tab', event => {"]
for tab in data['creative_tabs']:
    lines.extend(['  event.create('+json.dumps(tab['target_id'])+').translationKey('+json.dumps(tab['translation_key'])+')','    .icon(() => Item.of('+json.dumps(tab['icon'])+'))','    .content(() => '+json.dumps(tab['items'],separators=(',',':'))+');'])
lines.append('});');(K/'startup_scripts/02_gtnh_creative_tabs.js').write_text('\n'.join(lines)+'\n',encoding='utf-8')
p=O/'docs/quest-migration/item-registry-references.json';refs=json.loads(p.read_text(encoding='utf-8'))
for ref in refs:
    x=ref['identity']
    if x['id']=='dreamcraft:WoodenBrickForm' and x['damage']==0 and not x['tag'] and not x['ore_dict']:
        ref['target_id']='gtnh_migration:woodenbrickform';ref['mapping_status']='specialized_behavior_implemented_not_game_verified'
p.write_text(json.dumps(refs,ensure_ascii=False,indent=2),encoding='utf-8')
p=O/'docs/quest-migration/coverage.json';cov=json.loads(p.read_text(encoding='utf-8'));cov['mapped_total_item_identities']=sum(x['target_id'] is not None for x in refs);cov['other_identities_unmapped']=sum(x['target_id'] is None for x in refs);p.write_text(json.dumps(cov,ensure_ascii=False,indent=2),encoding='utf-8')
report['creative_tab']='GENERIC membership included';(D/'wooden-brick-form.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')

print('WOODEN_BRICK_FORM_BEHAVIOR_AND_THREE_RECIPES_WRITTEN')
