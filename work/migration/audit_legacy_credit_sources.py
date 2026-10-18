"""Inventory fixed GT5 credit definitions/assets/recipes without activating substitutes."""
from pathlib import Path
import hashlib
import json
import re
import struct
import zipfile

R = Path(__file__).resolve().parents[2]
S = R / 'work/exact-sources/GT5-Unofficial/5.09.54.205'
files = {}
source_lock = R / 'locks/sources/GT5-Unofficial-5.09.54.205-lock.json'
pin = json.loads(source_lock.read_text(encoding='utf-8'))
archive = R / 'work/cache/exact-sources/GT5-Unofficial-5.09.54.205.zip'
assert hashlib.sha256(archive.read_bytes()).hexdigest() == pin['archive_sha256']
source_zip = zipfile.ZipFile(archive)


def read(relative):
    path = S / relative
    raw = path.read_bytes()
    assert raw == source_zip.read('GT5-Unofficial-' + pin['commit'] + '/' + relative), relative
    files[path.relative_to(R).as_posix()] = hashlib.sha256(raw).hexdigest()
    return raw


def text(relative):
    return read(relative).decode('utf-8')


item_file = 'src/main/java/gregtech/common/items/MetaGeneratedItem01.java'
items = text(item_file)
ids = text('src/main/java/gregtech/common/items/IDMetaItem01.java')
proxy = text('src/main/java/gregtech/common/GTProxy.java')
parent = text('src/main/java/gregtech/api/items/MetaGeneratedItemX32.java')
base = text('src/main/java/gregtech/api/items/MetaGeneratedItem.java')
generic = text('src/main/java/gregtech/api/items/GTGenericItem.java')
meta_base = text('src/main/java/gregtech/api/items/MetaBaseItem.java')
assert 'mName = "gt." + aUnlocalized;' in generic and 'GameRegistry.registerItem(this, mName, GregTech.ID);' in generic
assert 'super(aUnlocalized, null, null);' in meta_base and 'super(aUnlocalized);' in base
assert 'super(aUnlocalized, (short) 32000, (short) 766);' in parent
assert 'short meta = (short) (mOffset + aID);' in base
assert 'getUnlocalizedName() + "/" + i' in base
assert 'ItemList.Credit_Iron.set(GTModHandler.getIC2Item("coin", 1L));' in proxy
languages = {}
for locale in ('en_US', 'zh_CN', 'ru_RU'):
    languages[locale] = {}
    for line in text(f'src/main/resources/assets/gregtech/lang/{locale}.lang').splitlines():
        if line and not line.startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            languages[locale][key] = value

definitions = []
pattern = r'ItemList\.(Credit_\w+)\.set\(\s*addItemWithLocalizationKeys\(\s*IDMetaItem01\.(Credit_\w+)\.ID,\s*"([^"]+)",\s*"([^"]+)"(.*?)\)\);'
for match in re.finditer(pattern, items, re.S):
    name, enum, name_key, tip_key, extra = match.groups()
    assert name == enum
    identifier = int(re.search(r'\b' + re.escape(enum) + r'\((\d+)\)', ids).group(1))
    texture = f'src/main/resources/assets/gregtech/textures/items/gt.metaitem.01/{identifier}.png'
    png = read(texture)
    assert png[:8] == b'\x89PNG\r\n\x1a\n'
    dimensions = list(struct.unpack('>II', png[16:24]))
    assert dimensions[0] > 0 and dimensions[1] > 0
    animation = None
    if (S / (texture + '.mcmeta')).exists():
        animation = json.loads(text(texture + '.mcmeta'))
    material = re.findall(r'new ItemData\(Materials\.(\w+),\s*(\d+)L\)', extra)
    aspects = re.findall(r'new TCAspects.TC_AspectStack\(TCAspects\.(\w+),\s*(\d+)L\)', extra)
    remainder = re.sub(r'new ItemData\(Materials\.\w+,\s*\d+L\)|new TCAspects.TC_AspectStack\(TCAspects\.\w+,\s*\d+L\)|[\s,]', '', extra)
    assert not remainder, (name, 'Unclassified constructor arguments')
    assert name_key in languages['en_US'] and tip_key in languages['en_US']
    definitions.append({'source_enum': name, 'legacy_id': 'gregtech:gt.metaitem.01', 'damage': 32000 + identifier,
                        'definition_line': items[:match.start()].count('\n') + 1,
                        'name_key': name_key, 'tooltip_key': tip_key,
                        'translations': {loc: {'name': lang.get(name_key), 'tooltip': lang.get(tip_key)} for loc, lang in languages.items()},
                        'texture': texture, 'png_sha256': hashlib.sha256(png).hexdigest(), 'dimensions': dimensions,
                        'animation': animation, 'material_data': [{'material': n, 'units': int(amount)} for n, amount in material],
                        'thaumcraft_aspects': [{'aspect': n, 'amount': int(amount)} for n, amount in aspects]})
assert len(definitions) == 13
recipes = []
pattern = r'GTModHandler\.addShapelessCraftingRecipe\(\s*ItemList\.(Credit_\w+)\.get\((\d+)L\),\s*([^,]+),\s*new Object\[\]\s*\{([^}]+)\}\);'
for match in re.finditer(pattern, items, re.S):
    output, count, flags, inputs = match.groups()
    flags = [part.strip() for part in flags.split('|')]
    assert set(flags) == {'GTModHandler.RecipeBits.KEEPNBT', 'GTModHandler.RecipeBits.NOT_REMOVABLE'}
    names = re.findall(r'ItemList\.(Credit_\w+)', inputs)
    assert not re.sub(r'ItemList\.Credit_\w+|[\s,]', '', inputs)
    assert len(set(names)) == 1 and (int(count), len(names)) in ((8, 1), (1, 8))
    recipes.append({'output_enum': output, 'output_count': int(count), 'input_enums': names,
                    'keep_nbt': True, 'removable_by_gt': False, 'source_line': items[:match.start()].count('\n') + 1,
                    'source_fragment_sha256': hashlib.sha256(match.group().encode()).hexdigest()})
assert len(recipes) == 24
known = {row['source_enum'] for row in definitions} | {'Credit_Iron'}
assert {row['output_enum'] for row in recipes} == known
for row in recipes:
    assert set(row['input_enums']) <= known
    assert any(other['output_enum'] == row['input_enums'][0] and other['input_enums'][0] == row['output_enum']
               and other['output_count'] == len(row['input_enums']) and len(other['input_enums']) == row['output_count'] for other in recipes)
assert sum(bool(row['material_data']) for row in definitions) == 1
assert sum(bool(row['thaumcraft_aspects']) for row in definitions) == 2
source_zip.close()
files[source_lock.relative_to(R).as_posix()] = hashlib.sha256(source_lock.read_bytes()).hexdigest()
files[Path(__file__).relative_to(R).as_posix()] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
report = {'passed': True, 'scope': 'Source inventory only; no credit item or recipe activated',
          'source_commit': json.loads(source_lock.read_text(encoding='utf-8'))['commit'],
          'gt_item_definitions': len(definitions), 'referenced_external_items': 1, 'source_conversion_recipes': len(recipes),
          'external_items': [{'source_enum': 'Credit_Iron', 'source_binding': 'GTModHandler.getIC2Item("coin", 1L)',
                              'source_file': 'src/main/java/gregtech/common/GTProxy.java', 'primary_ic2_identity_verified': False}],
          'definitions': definitions, 'recipes': recipes, 'source_sha256': files,
          'activated_credit_items': 0, 'activated_credit_recipes': 0, 'native_item_behavior_complete': False,
          'material_recycling_complete': False, 'thaumcraft_integration_complete': False,
          'client_visuals_verified': False, 'full_parity': False}
(R / 'evidence/LegacyCreditSourceAudit.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps({key: report[key] for key in ('passed', 'gt_item_definitions', 'referenced_external_items', 'source_conversion_recipes', 'activated_credit_items', 'activated_credit_recipes')}))
