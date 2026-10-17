"""Explicit 1.7.10 vanilla item identity translations for reviewed quest stacks.

No fuzzy name matching is used. Metadata entries are exact old damage values;
unsupported NBT, ore-dictionary, potion and entity-specific identities remain
unresolved by design.
"""
from __future__ import annotations

# Exact same-name item IDs are admitted only after the modern Items registry
# verifier confirms that the corresponding 1.21.1 item field exists.
DIRECT_SAME_NAME = {
    'stick','string','redstone','ender_pearl','leather','piston','glass',
    'nether_star','glowstone_dust','flint','diamond','iron_ingot','sugar',
    'feather','ender_eye','chest','bucket','bookshelf','paper','ghast_tear',
    'book','obsidian','lever','flint_and_steel','stone','rail','gunpowder',
    'egg','cobblestone','netherrack','iron_block','glass_pane','glass_bottle',
    'tnt','nether_wart','lava_bucket','blaze_powder','beacon','apple',
    'pumpkin','hopper','gravel','furnace','cooked_beef','clay_ball',
    'wheat_seeds','wheat','torch','spider_eye','sand','potato','porkchop',
    'golden_carrot','dragon_egg','cookie','carrot','bread','writable_book',
    'snowball','redstone_torch','redstone_block','item_frame','gold_ingot',
    'fishing_rod','crafting_table','bone','blaze_rod','water_bucket','minecart',
    'lapis_block','iron_ore','iron_door','glowstone','experience_bottle',
    'end_stone','enchanting_table','emerald','dispenser','coal_block','cactus',
    'anvil','trapped_chest','stone_pressure_plate','stone_button','slime_ball',
    'pumpkin_pie','leather_boots','iron_chestplate','golden_rail','gold_nugget',
    'gold_block','ender_chest','dirt','clock','clay','cake','bow','beef','baked_potato',
    'arrow','wooden_sword','wooden_shovel','wooden_pickaxe','wooden_axe',
    'soul_sand','snow','shears','saddle','rotten_flesh','redstone_ore','noteblock',
    'milk_bucket','melon','magma_cream','ladder','iron_leggings','iron_helmet',
    'iron_boots','iron_bars','ice','gold_ore','furnace_minecart','fireworks',
    'detector_rail','cooked_chicken','comparator','chicken','chainmail_chestplate',
    'carpet','brick','bowl','wooden_hoe','web','stone_stairs','sticky_piston',
    'repeater','pumpkin_seeds','netherbrick','name_tag','mushroom_stew','melon_seeds',
    'lapis_ore','iron_sword','iron_pickaxe','grass','golden_sword','golden_pickaxe',
    'golden_leggings','golden_hoe','golden_helmet','golden_boots','flower_pot',
    'firework_charge','filled_map','end_portal_frame','emerald_ore','emerald_block',
    'diamond_sword','diamond_block','diamond_ore','diamond_leggings','diamond_hoe',
    'diamond_helmet','diamond_boots','cooked_chicken','command_block','coal_ore',
    'chest_minecart','chainmail_leggings','chainmail_helmet','chainmail_boots',
    'carrot','cauldron','cooked_porkchop','golden_apple','iron_chestplate',
    'leather_chestplate','leather_leggings','leather_helmet','diamond_chestplate',
    'waterlily', # replaced below by explicit modern rename
}

# Old names whose modern IDs are not the same. Values are modern item IDs.
RENAMED_META0 = {
    'bed':'red_bed','boat':'oak_boat','brick_block':'bricks','cooked_fished':'cooked_cod',
    'fence':'oak_fence','firework_charge':'firework_star','fireworks':'firework_rocket',
    'grass':'grass_block','golden_rail':'powered_rail','hardened_clay':'terracotta',
    'netherbrick':'nether_brick','noteblock':'note_block','quartz_ore':'nether_quartz_ore',
    'reeds':'sugar_cane','stonebrick':'stone_bricks','trapdoor':'oak_trapdoor',
    'waterlily':'lily_pad','web':'cobweb','wooden_button':'oak_button',
    'wooden_door':'oak_door','wooden_pressure_plate':'oak_pressure_plate',
    'wooden_slab':'oak_slab','yellow_flower':'dandelion','red_flower':'poppy',
    'melon':'melon_slice','snow':'snow_block','stone_stairs':'cobblestone_stairs',
    'pumpkin':'carved_pumpkin',
}

# Exact old metadata -> modern item variant. Values are namespace-free IDs.
META_VARIANTS = {
    ('coal',0):'coal',('coal',1):'charcoal',
    ('dye',0):'black_dye',('dye',1):'red_dye',('dye',2):'green_dye',
    ('dye',3):'cocoa_beans',('dye',4):'lapis_lazuli',('dye',5):'purple_dye',
    ('dye',6):'cyan_dye',('dye',7):'light_gray_dye',('dye',8):'gray_dye',
    ('dye',9):'pink_dye',('dye',10):'lime_dye',('dye',11):'yellow_dye',
    ('dye',12):'light_blue_dye',('dye',13):'magenta_dye',('dye',14):'orange_dye',
    ('dye',15):'bone_meal',
    ('fish',0):'cod',('fish',1):'salmon',('fish',2):'tropical_fish',('fish',3):'pufferfish',
    ('sand',0):'sand',('sand',1):'red_sand',
    ('stone',0):'stone',('stone',1):'granite',('stone',2):'polished_granite',
    ('stone',3):'diorite',('stone',4):'polished_diorite',('stone',5):'andesite',
    ('stone',6):'polished_andesite',
    ('stonebrick',0):'stone_bricks',('stonebrick',1):'mossy_stone_bricks',
    ('stonebrick',2):'cracked_stone_bricks',('stonebrick',3):'chiseled_stone_bricks',
    ('quartz_block',0):'quartz_block',('quartz_block',1):'chiseled_quartz_block',
    ('quartz_block',2):'quartz_pillar',
    ('planks',0):'oak_planks',('planks',1):'spruce_planks',('planks',2):'birch_planks',
    ('planks',3):'jungle_planks',('planks',4):'acacia_planks',('planks',5):'dark_oak_planks',
    ('log',0):'oak_log',('log',1):'spruce_log',('log',2):'birch_log',('log',3):'jungle_log',
    ('leaves',0):'oak_leaves',('leaves',1):'spruce_leaves',('leaves',2):'birch_leaves',('leaves',3):'jungle_leaves',
    ('sapling',0):'oak_sapling',('sapling',1):'spruce_sapling',('sapling',2):'birch_sapling',
    ('sapling',3):'jungle_sapling',('sapling',4):'acacia_sapling',('sapling',5):'dark_oak_sapling',
    ('tallgrass',1):'short_grass',('tallgrass',2):'fern',
    ('red_flower',0):'poppy',('red_flower',1):'blue_orchid',('red_flower',2):'allium',
    ('red_flower',3):'azure_bluet',('red_flower',4):'red_tulip',('red_flower',5):'orange_tulip',
    ('red_flower',6):'white_tulip',('red_flower',7):'pink_tulip',('red_flower',8):'oxeye_daisy',
    ('yellow_flower',0):'dandelion',
    ('wool',0):'white_wool',('wool',1):'orange_wool',('wool',2):'magenta_wool',
    ('wool',3):'light_blue_wool',('wool',4):'yellow_wool',('wool',5):'lime_wool',
    ('wool',6):'pink_wool',('wool',7):'gray_wool',('wool',8):'light_gray_wool',
    ('wool',9):'cyan_wool',('wool',10):'purple_wool',('wool',11):'blue_wool',
    ('wool',12):'brown_wool',('wool',13):'green_wool',('wool',14):'red_wool',('wool',15):'black_wool',
    ('carpet',0):'white_carpet',('carpet',1):'orange_carpet',('carpet',2):'magenta_carpet',
    ('carpet',3):'light_blue_carpet',('carpet',4):'yellow_carpet',('carpet',5):'lime_carpet',
    ('carpet',6):'pink_carpet',('carpet',7):'gray_carpet',('carpet',8):'light_gray_carpet',
    ('carpet',9):'cyan_carpet',('carpet',10):'purple_carpet',('carpet',11):'blue_carpet',
    ('carpet',12):'brown_carpet',('carpet',13):'green_carpet',('carpet',14):'red_carpet',('carpet',15):'black_carpet',
    ('wooden_slab',0):'oak_slab',('wooden_slab',1):'spruce_slab',('wooden_slab',2):'birch_slab',
    ('wooden_slab',3):'jungle_slab',('wooden_slab',4):'acacia_slab',('wooden_slab',5):'dark_oak_slab',
    ('skull',0):'skeleton_skull',('skull',1):'wither_skeleton_skull',('skull',2):'zombie_head',
    ('skull',3):'player_head',('skull',4):'creeper_head',('skull',5):'dragon_head',
}

# These damage values were introduced after 1.7.10 and are not valid legacy
# vanilla item variants. Keep them unresolved instead of translating invented
# or malformed old stacks.
for _damage in range(1, 7):
    META_VARIANTS.pop(('stone', _damage), None)
# Metadata is the authoritative path for these variant families at damage 0.
for _name, _damage in list(META_VARIANTS):
    if _damage == 0:
        RENAMED_META0.pop(_name, None)
# Filled maps carry a map-id component in modern Minecraft; the old damage
# value is not enough to reconstruct that component safely.
DIRECT_SAME_NAME.discard('filled_map')
# A name may be accepted either by the direct modern registry field or by an
# explicit rename/metadata table, never by more than one route.
DIRECT_SAME_NAME -= set(RENAMED_META0)
DIRECT_SAME_NAME -= {name for name, _damage in META_VARIANTS}

# 1.7.10 music disc IDs are distinct registry entries; no metadata is used.
for old, modern in {
    'record_13':'music_disc_13','record_cat':'music_disc_cat','record_blocks':'music_disc_blocks',
    'record_chirp':'music_disc_chirp','record_far':'music_disc_far','record_mall':'music_disc_mall',
    'record_mellohi':'music_disc_mellohi','record_stal':'music_disc_stal','record_strad':'music_disc_strad',
    'record_ward':'music_disc_ward','record_11':'music_disc_11','record_wait':'music_disc_wait',
}.items(): RENAMED_META0[old]=modern


def resolve_legacy_vanilla(legacy_id: str, damage: int, ore_dict: str = '', tag=None) -> str | None:
    """Return a modern minecraft item ID only for an explicitly reviewed stack."""
    if not legacy_id.startswith('minecraft:') or ore_dict or tag:
        return None
    name = legacy_id.split(':', 1)[1]
    target = META_VARIANTS.get((name, damage))
    if target is not None:
        return 'minecraft:' + target
    if damage != 0:
        return None
    target = RENAMED_META0.get(name)
    if target is not None:
        return 'minecraft:' + target
    if name in DIRECT_SAME_NAME:
        return 'minecraft:' + name
    return None
