"""Verify explicit old vanilla identities against the actual 1.21.1 Items class."""
from pathlib import Path
import hashlib,json,re
from legacy_vanilla_items import DIRECT_SAME_NAME,RENAMED_META0,META_VARIANTS,resolve_legacy_vanilla
R=Path(__file__).resolve().parents[2];O=R/'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation';J=R/'work/migration/modern-Items.javap.txt';text=J.read_text(encoding='utf-8');fields={m.group(1).lower() for m in re.finditer(r'public static final [^ ]+ ([A-Z0-9_]+);',text)}
assert len(fields)>1000,'Modern Items registry evidence missing or unexpectedly small'
assert not DIRECT_SAME_NAME & set(RENAMED_META0)
assert not DIRECT_SAME_NAME & {name for name, _damage in META_VARIANTS}
for name in DIRECT_SAME_NAME:
    assert name in fields and resolve_legacy_vanilla('minecraft:'+name,0)=='minecraft:'+name,(name,resolve_legacy_vanilla('minecraft:'+name,0))
resolved={}
for name in DIRECT_SAME_NAME:
 target=resolve_legacy_vanilla('minecraft:'+name,0);assert target and target.rsplit(':',1)[1] in fields,(name,target);resolved['minecraft:'+name+':0']=target
for (name,damage),target in META_VARIANTS.items():
 assert target in fields,(name,damage,target);resolved[f'minecraft:{name}:{damage}']=resolve_legacy_vanilla('minecraft:'+name,damage);assert resolved[f'minecraft:{name}:{damage}']=='minecraft:'+target
for name,target in RENAMED_META0.items():
 assert target in fields,(name,target);assert resolve_legacy_vanilla('minecraft:'+name,0)=='minecraft:'+target;resolved[f'minecraft:{name}:0']='minecraft:'+target
assert resolve_legacy_vanilla('minecraft:skull',3,tag={'display':'named'}) is None
assert resolve_legacy_vanilla('minecraft:dye',15,ore_dict='dyeWhite') is None
assert resolve_legacy_vanilla('minecraft:spawn_egg',54) is None
assert resolve_legacy_vanilla('minecraft:potion',8198) is None
assert resolve_legacy_vanilla('minecraft:filled_map',0) is None
assert resolve_legacy_vanilla('minecraft:pumpkin',0)=='minecraft:carved_pumpkin'
assert resolve_legacy_vanilla('minecraft:grass',0)=='minecraft:grass_block'
assert resolve_legacy_vanilla('minecraft:netherbrick',0)=='minecraft:nether_brick'
assert resolve_legacy_vanilla('minecraft:melon',0)=='minecraft:melon_slice'
assert resolve_legacy_vanilla('minecraft:snow',0)=='minecraft:snow_block'
assert resolve_legacy_vanilla('minecraft:stone_stairs',0)=='minecraft:cobblestone_stairs'
for _damage in range(1,7): assert resolve_legacy_vanilla('minecraft:stone',_damage) is None
source_hash=hashlib.sha256(J.read_bytes()).hexdigest()
refs=json.loads((O/'docs/quest-migration/item-registry-references.json').read_text(encoding='utf-8'))
conflicts=[(x['identity'],x.get('target_id'),resolve_legacy_vanilla(x['identity']['id'],x['identity']['damage'],x['identity']['ore_dict'],x['identity']['tag'])) for x in refs if x.get('target_id') and resolve_legacy_vanilla(x['identity']['id'],x['identity']['damage'],x['identity']['ore_dict'],x['identity']['tag']) and x['target_id'] != resolve_legacy_vanilla(x['identity']['id'],x['identity']['damage'],x['identity']['ore_dict'],x['identity']['tag'])]
assert not conflicts,conflicts[:5]
new_identity_rows=[x for x in refs if not x.get('target_id') and resolve_legacy_vanilla(x['identity']['id'],x['identity']['damage'],x['identity']['ore_dict'],x['identity']['tag'])]
report={'passed':True,'modern_items_field_count':len(fields),'modern_items_evidence_sha256':source_hash,'direct_same_name_identities':len(DIRECT_SAME_NAME),'renamed_meta0_identities':len(RENAMED_META0),'metadata_variant_identities':len(META_VARIANTS),'resolved_identity_count':len(resolved),'newly_mapped_source_identity_count':len(new_identity_rows),'newly_mapped_source_occurrences':sum(x['occurrences'] for x in new_identity_rows),'overridden_existing_target_count':len(conflicts),'unsupported_nbt_ore_potion_spawn_egg_rejected':True,'fuzzy_matching':False,'source': 'Minecraft 1.21.1 server Items class, locally installed pinned server artifact'}
D=O/'docs/quest-migration';D.mkdir(parents=True,exist_ok=True);(D/'vanilla-identity-mapping.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(report,ensure_ascii=False))
