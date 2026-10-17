"""Translate only source-reviewed hunt predicates, preserving the subtype flag."""

ENTITY_IDENTITIES = {
    'Pig': 'minecraft:pig',
    'Cow': 'minecraft:cow',
    'WitherBoss': 'minecraft:wither',
    'EnderDragon': 'minecraft:ender_dragon',
}


def convert_hunt(task, task_id):
    target = task.get('target:8', '')
    if target not in ENTITY_IDENTITIES:
        raise ValueError('Hunt entity has no reviewed class/registry mapping')
    if task.get('ignoreNBT:1', 0) != 1 or task.get('damageType:8', ''):
        raise ValueError('Hunt task requires unsupported NBT/damage semantics')
    # NBTTagCompound.getBoolean returns false for an absent subtypes property.
    # With ignoreNBT=true, even a populated targetNBT must be ignored in full.
    subtypes = task.get('subtypes:1', 0)
    required = task.get('required:3', 0)
    if subtypes not in (0, 1) or type(required) is not int or not 1 <= required <= 2147483647:
        raise ValueError('Invalid hunt subtype flag or required count')
    return {
        'id': task_id, 'type': 'kill', 'entity': ENTITY_IDENTITIES[target],
        'value': required, 'custom_name': '', 'nbt_filter': '',
        'tags': ['gtnh_legacy_hunt_subtypes' if subtypes else 'gtnh_legacy_hunt_exact'],
    }
