"""Independently verify the reviewed ore subset against both pinned source archives.

Expected paths are defined here, not imported from the porter or trusted from its
manifest. Pixel equality is reported separately from required encoded-byte equality.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = Path('outputs/GTNH-NeoForge-1.21.1-Migration-Foundation')
PACK = Path('resourcepacks/GTNH-2.8.4-Shared-Ore-Assets-1.21.1')
SOURCE_ID = {'repository': 'GTNewHorizons/GT5-Unofficial', 'tag': '5.09.54.205',
             'commit': 'a3e1e11241a814c9fa0dd0973d5699548428f689',
             'archive_sha256': '3044b6cfeec1b1aad554739409af09e314f2f07c8138be08bd478bf183ae6b0f'}
REFERENCE_SHA = '4ab7ce174a8f6fb7a90d8d11d56056aab2de577c36c6084b37ce890d7b1d67bf'
TRANSPARENT_SHA = '232c2eac760eae604f9dd43352c0b272396b7cfaf220081b54583b6d0cbc2320'
# Independent fixed review scope. A generator accidentally dropping a set must fail.
REVIEWED_SETS = ('diamond', 'emerald', 'fine', 'flint', 'fluid', 'gem_horizontal',
                 'gem_vertical', 'glass', 'lapis', 'lignite', 'magnetic',
                 'netherstar', 'opal', 'quartz', 'ruby', 'sand', 'wood')
DIFFERENT_REFERENCE_PATHS = {
    'FINE/oreSmall.png', 'SAND/oreSmall.png', 'FLINT/ore.png', 'FLINT/oreSmall.png',
    'LIGNITE/oreSmall.png', 'QUARTZ/oreSmall.png', 'LAPIS/ore.png',
}


def check(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def rgba(data):
    with Image.open(io.BytesIO(data)) as image:
        check(image.format == 'PNG' and image.size == (16, 16), 'Expected scoped 16x16 PNG')
        image.load()
        return image.convert('RGBA').tobytes()


def verify(root=ROOT, output=None):
    root = Path(root)
    output = Path(output) if output is not None else root / OUT
    lock_path = root / 'locks/sources/GT5-Unofficial-5.09.54.205-lock.json'
    lock = read_json(lock_path)
    check(all(lock.get(k) == v for k, v in SOURCE_ID.items()), 'Unexpected primary source lock')
    archive = root / 'work/cache/exact-sources/GT5-Unofficial-5.09.54.205.zip'
    extracted = root / 'work/exact-sources/GT5-Unofficial/5.09.54.205/src/main/resources'
    old_jar = root / 'work/source-release-2.8.4/GT New Horizons 2.8.4/.minecraft/mods/gregtech-5.09.51.482.jar'
    check(digest(archive.read_bytes()) == SOURCE_ID['archive_sha256'], 'Primary archive hash mismatch')
    check(digest(old_jar.read_bytes()) == REFERENCE_SHA, 'Reference archive hash mismatch')
    mapping_path = output / 'docs/resource-migration/resource-mapping.json'
    rebase_path = output / 'docs/resource-migration/exact-ore-rebase.json'
    mapping, rebase = read_json(mapping_path), read_json(rebase_path)
    check(mapping.get('ore_primary_source') == SOURCE_ID and rebase.get('primary_source') == SOURCE_ID,
          'Manifest primary source identity mismatch')
    expected_reference = {'pack_version': '2.8.4', 'jar': 'gregtech-5.09.51.482.jar',
                          'sha256': REFERENCE_SHA,
                          'role': 'version-comparison reference only; not the primary asset baseline'}
    check(mapping.get('ore_reference_source') == expected_reference
          and rebase.get('reference_source') == expected_reference,
          'The 2.8.4 input must remain identified as reference-only')
    for report in (mapping, rebase):
        check(report.get('full_fidelity') is False and report.get('runtime_visual_verification') is False,
              'An asset-only report must not claim full or runtime parity')
    check(mapping.get('ore_base_png_files') == 34 and mapping.get('ore_derived_secondary_png_files') == 34
          and mapping.get('ore_png_files') == 68 and mapping.get('ore_icon_sets_ported') == 17,
          'Manifest must distinguish original and derived images')
    mapped = {r['target_path']: r for r in mapping['ore_mappings']}
    compared = {r['target_path']: r for r in rebase['comparisons']}
    expected_bases = {f'assets/gtceu/textures/block/material_sets/{icon}/{stem}.png'
                      for icon in REVIEWED_SETS for stem in ('ore', 'ore_small')}
    expected_layers = {p.removesuffix('.png') + '_layer2.png' for p in expected_bases}
    check(len(mapping['ore_mappings']) == len(mapped) == 68 and mapped.keys() == expected_bases | expected_layers,
          'Incomplete, duplicated or unreviewed ore mapping paths')
    check(len(rebase['comparisons']) == len(compared) == 34 and compared.keys() == expected_bases,
          'Incomplete, duplicated or unreviewed source comparisons')
    pack = output / PACK
    expected_files, differences, animation_count = set(expected_bases | expected_layers), [], 0
    with zipfile.ZipFile(archive) as source_zip, zipfile.ZipFile(old_jar) as reference_zip:
        names = source_zip.namelist()
        prefix = f"GT5-Unofficial-{SOURCE_ID['commit']}/src/main/resources/"
        for icon in REVIEWED_SETS:
            for legacy, modern in (('ore', 'ore'), ('oreSmall', 'ore_small')):
                resource = f'assets/gregtech/textures/blocks/materialicons/{icon.upper()}/{legacy}.png'
                source_rel = 'src/main/resources/' + resource
                target = f'assets/gtceu/textures/block/material_sets/{icon}/{modern}.png'
                check(names.count(prefix + resource) == 1, 'Missing or duplicate source member: ' + resource)
                source_bytes = source_zip.read(prefix + resource)
                check((extracted / resource).read_bytes() == source_bytes, 'Extracted source bytes differ: ' + resource)
                check((pack / target).read_bytes() == source_bytes, 'Target PNG bytes differ from primary source: ' + target)
                source_pixels = rgba(source_bytes)
                old_bytes = reference_zip.read(resource)
                old_pixels = rgba(old_bytes)
                check(source_pixels == old_pixels, 'Primary/reference RGBA pixels differ: ' + resource)
                animation_present = prefix + resource + '.mcmeta' in names
                check((extracted / (resource + '.mcmeta')).exists() == animation_present,
                      'Extracted animation presence differs: ' + resource)
                check((pack / (target + '.mcmeta')).exists() == animation_present,
                      'Target animation presence differs: ' + target)
                animation_hash = None
                if animation_present:
                    check(names.count(prefix + resource + '.mcmeta') == 1, 'Duplicate source animation')
                    animation_bytes = source_zip.read(prefix + resource + '.mcmeta')
                    check((extracted / (resource + '.mcmeta')).read_bytes() == animation_bytes,
                          'Extracted animation bytes differ: ' + resource)
                    check((pack / (target + '.mcmeta')).read_bytes() == animation_bytes,
                          'Target animation bytes differ: ' + target)
                    animation_hash = digest(animation_bytes)
                    expected_files.add(target + '.mcmeta')
                    animation_count += 1
                overlay = resource.removesuffix('.png') + '_OVERLAY.png'
                check(prefix + overlay not in names and not (extracted / overlay).exists(),
                      'Source now has an overlay requiring a new review: ' + overlay)
                base = mapped[target]
                for key, expected in {
                    'source_path': source_rel, 'source_repository': SOURCE_ID['repository'],
                    'source_tag': SOURCE_ID['tag'], 'source_commit': SOURCE_ID['commit'],
                    'source_archive_sha256': SOURCE_ID['archive_sha256'], 'source_sha256': digest(source_bytes),
                    'target_sha256': digest(source_bytes), 'dimensions': [16, 16], 'kind': 'unchanged_png_bytes',
                    'derived': False, 'source_overlay_present': False, 'source_animation_present': animation_present,
                    'source_animation_sha256': animation_hash,
                    'target_animation_path': target + '.mcmeta' if animation_present else None,
                }.items():
                    check(key in base and base[key] == expected, f'Incorrect base provenance field {key}: {target}')
                second = target.removesuffix('.png') + '_layer2.png'
                second_bytes = (pack / second).read_bytes()
                check(digest(second_bytes) == TRANSPARENT_SHA and all(a == 0 for a in rgba(second_bytes)[3::4]),
                      'Derived secondary must retain its reviewed transparent bytes: ' + second)
                layer = mapped[second]
                check(layer.get('derived') is True and layer.get('source_bytes_copied') is False
                      and layer.get('kind') == 'transparent_secondary_for_legacy_absent_overlay'
                      and layer.get('source_overlay_path_checked') == 'src/main/resources/' + overlay
                      and layer.get('source_overlay_present') is False
                      and layer.get('target_sha256') == digest(second_bytes) and layer.get('dimensions') == [16, 16]
                      and 'source_sha256' not in layer and 'source_path' not in layer,
                      'Derived image must not be represented as copied source: ' + second)
                expected_comparison = {
                    'source_path': source_rel, 'target_path': target, 'primary_sha256': digest(source_bytes),
                    'reference_2_8_4_sha256': digest(old_bytes), 'byte_identical_to_reference': source_bytes == old_bytes,
                    'rgba_pixels_identical_to_reference': True, 'primary_rgba_sha256': digest(source_pixels),
                    'reference_rgba_sha256': digest(old_pixels), 'source_animation_present': animation_present,
                }
                check(compared[target] == expected_comparison, 'Source comparison was not independently reproducible: ' + target)
                if source_bytes != old_bytes:
                    differences.append(expected_comparison)
    check({r['source_path'].split('materialicons/', 1)[1] for r in differences} == DIFFERENT_REFERENCE_PATHS,
          'Version-difference set changed from the seven reviewed paths')
    check(animation_count == 0, 'Pinned reviewed bases unexpectedly have animation sidecars')
    actual_files = {p.relative_to(pack).as_posix() for p in (pack / 'assets').rglob('*') if p.is_file()}
    check(actual_files == expected_files, 'Unexpected or missing resource files in the scoped ore pack')
    for key, expected in {
        'source_base_png_files': 34, 'derived_transparent_secondary_png_files': 34,
        'source_animation_sidecars_present': 0, 'source_animation_sidecars_absent': 34,
        'reference_byte_identical_count': 27, 'reference_byte_different_count': 7,
        'reference_rgba_identical_count': 34,
    }.items():
        check(rebase.get(key) == expected, 'Incorrect rebase summary: ' + key)
    metadata = read_json(pack / 'pack.mcmeta')
    check(metadata['pack']['pack_format'] == 34 and '5.09.54.205' in metadata['pack']['description'],
          'Pack metadata must identify the current primary source')
    return {
        'passed': True, 'primary_source': SOURCE_ID, 'reference_only_jar_sha256': REFERENCE_SHA,
        'primary_base_pngs_verified_from_archive_through_target': 34,
        'primary_animation_sidecars_present': 0, 'primary_animation_sidecars_absent_verified': 34,
        'derived_secondary_pngs_verified_transparent': 34,
        'reference_byte_identical_count': 27, 'reference_byte_different_count': 7,
        'reference_rgba_pixel_identical_count': 34, 'reference_differences': differences,
        'input_sha256': {
            'source_lock': digest(lock_path.read_bytes()), 'source_archive': SOURCE_ID['archive_sha256'],
            'reference_jar': REFERENCE_SHA, 'resource_mapping': digest(mapping_path.read_bytes()),
            'rebase_report': digest(rebase_path.read_bytes()), 'pack_mcmeta': digest((pack / 'pack.mcmeta').read_bytes()),
            'porter': digest((root / 'work/migration/port_exact_ore_resources.py').read_bytes()),
            'verifier': digest(Path(__file__).read_bytes()),
        },
        'runtime_visual_verification': False, 'full_fidelity': False,
        'scope': 'Independent static byte and decoded-RGBA checks of this 34-base-image subset only. Derived layers are not source assets; rendering, material/color assignment, pack precedence and world behaviour remain unverified.',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = verify(args.root, args.output)
    output = args.output if args.output is not None else args.root / OUT
    report = args.report if args.report is not None else output / 'docs/verification/exact-ore-resource-check.json'
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({k: v for k, v in result.items() if k not in ('reference_differences', 'input_sha256')}, ensure_ascii=False))
