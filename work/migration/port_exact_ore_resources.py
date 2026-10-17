"""Rebase the existing 34 ore base images onto the pinned master GT5 source.

This is the current, narrow resource entry point. port_resources.py is the
historical 2.8.4 generator and must not run after this one. No material, renderer,
world behaviour, audio, or unreviewed icon set is migrated by this script.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import zipfile

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = Path('outputs/GTNH-NeoForge-1.21.1-Migration-Foundation')
PACK = Path('resourcepacks/GTNH-2.8.4-Shared-Ore-Assets-1.21.1')
MAPPING = Path('docs/resource-migration/resource-mapping.json')
REPORT = Path('docs/resource-migration/exact-ore-rebase.json')
SOURCE_VERSION = '5.09.54.205'
SOURCE_COMMIT = 'a3e1e11241a814c9fa0dd0973d5699548428f689'
SOURCE_ARCHIVE_SHA256 = '3044b6cfeec1b1aad554739409af09e314f2f07c8138be08bd478bf183ae6b0f'
REFERENCE_SHA256 = '4ab7ce174a8f6fb7a90d8d11d56056aab2de577c36c6084b37ce890d7b1d67bf'
ICON_SETS = ('magnetic', 'diamond', 'emerald', 'gem_horizontal', 'gem_vertical',
             'ruby', 'opal', 'glass', 'netherstar', 'fine', 'sand', 'wood',
             'flint', 'lignite', 'quartz', 'lapis', 'fluid')
TRANSPARENT_SECONDARY_SHA256 = '232c2eac760eae604f9dd43352c0b272396b7cfaf220081b54583b6d0cbc2320'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def pixels(data):
    with Image.open(io.BytesIO(data)) as im:
        require(im.format == 'PNG', 'Expected PNG')
        im.load()
        require(im.size == (16, 16), 'The scoped ore bases must remain 16x16')
        return im.convert('RGBA').tobytes()


def port(root=ROOT, output=None):
    root = Path(root)
    output = Path(output) if output is not None else root / OUTPUT
    lock = load_json(root / 'locks/sources/GT5-Unofficial-5.09.54.205-lock.json')
    primary = {'repository': 'GTNewHorizons/GT5-Unofficial', 'tag': SOURCE_VERSION,
               'commit': SOURCE_COMMIT, 'archive_sha256': SOURCE_ARCHIVE_SHA256}
    require(all(lock.get(k) == v for k, v in primary.items()), 'Unexpected GT5 source lock')
    archive = root / 'work/cache/exact-sources/GT5-Unofficial-5.09.54.205.zip'
    source = root / 'work/exact-sources/GT5-Unofficial/5.09.54.205'
    reference = root / 'work/source-release-2.8.4/GT New Horizons 2.8.4/.minecraft/mods/gregtech-5.09.51.482.jar'
    require(sha(archive.read_bytes()) == SOURCE_ARCHIVE_SHA256, 'Primary archive hash mismatch')
    require(sha(reference.read_bytes()) == REFERENCE_SHA256, 'Reference-only 2.8.4 JAR hash mismatch')
    manifest = load_json(output / MAPPING)
    rows = manifest['ore_mappings']
    current = {r['target_path']: r for r in rows}
    expected_targets = {
        f'assets/gtceu/textures/block/material_sets/{icon}/{stem}{suffix}.png'
        for icon in ICON_SETS for stem in ('ore', 'ore_small') for suffix in ('', '_layer2')
    }
    require(len(rows) == len(current) == 68 and current.keys() == expected_targets,
            'Existing ore mapping must contain exactly the reviewed 34 bases and 34 derived layers')
    writes, removals, mappings, comparisons = {}, [], [], []
    with zipfile.ZipFile(archive) as original, zipfile.ZipFile(reference) as old:
        members = original.namelist()
        prefix = f'GT5-Unofficial-{SOURCE_COMMIT}/'
        for icon in ICON_SETS:
            for old_stem, target_stem in (('ore', 'ore'), ('oreSmall', 'ore_small')):
                resource = f'assets/gregtech/textures/blocks/materialicons/{icon.upper()}/{old_stem}.png'
                source_path = f'src/main/resources/{resource}'
                member = prefix + source_path
                require(members.count(member) == 1, f'Primary archive member is missing or duplicated: {member}')
                data = original.read(member)
                require((source / source_path).read_bytes() == data, f'Extracted source mismatch: {source_path}')
                original_pixels = pixels(data)
                overlay = source_path.removesuffix('.png') + '_OVERLAY.png'
                require(prefix + overlay not in members and not (source / overlay).exists(),
                        f'New source overlay needs a separate rendering review: {overlay}')
                animation_member = member + '.mcmeta'
                has_animation = animation_member in members
                require((source / (source_path + '.mcmeta')).exists() == has_animation,
                        f'Extracted animation presence mismatch: {source_path}')
                animation = None
                if has_animation:
                    require(members.count(animation_member) == 1, 'Duplicate animation metadata')
                    animation = original.read(animation_member)
                    require((source / (source_path + '.mcmeta')).read_bytes() == animation,
                            f'Extracted animation bytes mismatch: {source_path}')
                # The reference remains evidence of the version change, never the source of output bytes.
                old_data = old.read(resource)
                old_pixels = pixels(old_data)
                target = f'assets/gtceu/textures/block/material_sets/{icon}/{target_stem}.png'
                dest = output / PACK / target
                require(dest.read_bytes() in (data, old_data), f'Unreviewed existing base modification: {target}')
                writes[dest] = data
                sidecar = dest.with_suffix('.png.mcmeta')
                if has_animation:
                    writes[sidecar] = animation
                elif sidecar.exists():
                    # Only the exact companion of a scoped target is removed, to reproduce source absence.
                    removals.append(sidecar)
                base = dict(current[target])
                base.pop('source_jar', None)
                base.update({'source_repository': primary['repository'], 'source_tag': SOURCE_VERSION,
                             'source_commit': SOURCE_COMMIT, 'source_archive_sha256': SOURCE_ARCHIVE_SHA256,
                             'source_path': source_path, 'source_sha256': sha(data), 'target_sha256': sha(data),
                             'dimensions': [16, 16], 'kind': 'unchanged_png_bytes', 'derived': False,
                             'source_overlay_present': False, 'source_animation_present': has_animation,
                             'source_animation_sha256': sha(animation) if animation is not None else None,
                             'target_animation_path': target + '.mcmeta' if has_animation else None,
                             'scope': 'shared icon set asset only; material assignment/color parity unverified'})
                mappings.append(base)
                second = target.removesuffix('.png') + '_layer2.png'
                second_data = (output / PACK / second).read_bytes()
                require(sha(second_data) == TRANSPARENT_SECONDARY_SHA256,
                        f'Existing derived secondary bytes changed: {second}')
                require(all(alpha == 0 for alpha in pixels(second_data)[3::4]), f'Secondary layer is visible: {second}')
                require(not (output / PACK / (second + '.mcmeta')).exists(), f'Unexpected derived animation: {second}')
                mappings.append({'target_path': second, 'target_sha256': sha(second_data),
                                 'dimensions': [16, 16], 'kind': 'transparent_secondary_for_legacy_absent_overlay',
                                 'derived': True, 'source_bytes_copied': False,
                                 'source_overlay_path_checked': overlay, 'source_overlay_present': False,
                                 'reason': 'Synthesized transparent image retained to prevent inheriting a modern visible overlay; not an original GT5 image.'})
                comparisons.append({'source_path': source_path, 'target_path': target,
                                    'primary_sha256': sha(data), 'reference_2_8_4_sha256': sha(old_data),
                                    'byte_identical_to_reference': data == old_data,
                                    'rgba_pixels_identical_to_reference': original_pixels == old_pixels,
                                    'primary_rgba_sha256': sha(original_pixels),
                                    'reference_rgba_sha256': sha(old_pixels),
                                    'source_animation_present': has_animation})
    # Validate the whole narrow batch before writing any output file.
    require(len(comparisons) == 34, 'Incomplete source batch')
    require(all(row['rgba_pixels_identical_to_reference'] for row in comparisons),
            'A visual source change requires explicit review beyond this exact-byte rebase')
    reference_info = {'pack_version': '2.8.4', 'jar': reference.name, 'sha256': REFERENCE_SHA256,
                      'role': 'version-comparison reference only; not the primary asset baseline'}
    report = {'schema_version': 1, 'primary_source': primary, 'reference_source': reference_info,
              'source_base_png_files': 34, 'derived_transparent_secondary_png_files': 34,
              'source_animation_sidecars_present': sum(r['source_animation_present'] for r in comparisons),
              'source_animation_sidecars_absent': sum(not r['source_animation_present'] for r in comparisons),
              'reference_byte_identical_count': sum(r['byte_identical_to_reference'] for r in comparisons),
              'reference_byte_different_count': sum(not r['byte_identical_to_reference'] for r in comparisons),
              'reference_rgba_identical_count': sum(r['rgba_pixels_identical_to_reference'] for r in comparisons),
              'comparisons': comparisons, 'runtime_visual_verification': False, 'full_fidelity': False,
              'scope': 'Only 34 base PNGs rebased to pinned master GT5 bytes. Derived transparent layers are retained, not original assets. No renderer/material/color/world behaviour parity is claimed.'}
    manifest.pop('source_jar', None)
    manifest.pop('source_jar_sha256', None)
    if 'generated_at' in manifest:
        manifest['historical_report_generated_at'] = manifest.pop('generated_at')
    manifest.update({'ore_primary_source': primary, 'ore_reference_source': reference_info,
                     'ore_mapping_generator': 'work/migration/port_exact_ore_resources.py',
                     'ore_icon_sets_ported': 17, 'ore_png_files': 68, 'ore_base_png_files': 34,
                     'ore_derived_secondary_png_files': 34, 'ore_mappings': mappings,
                     'deferred_baseline': 'Historical 2.8.4 reference list, not a complete current-master missing-assets ledger.',
                     'historical_alpha_preview_baseline': '2.8.4 reference only; not an in-game rendering check.',
                     'runtime_visual_verification': False, 'full_fidelity': False})
    pack_metadata = load_json(output / PACK / 'pack.mcmeta')
    require(pack_metadata['pack']['pack_format'] == 34, 'Unexpected resource-pack format')
    pack_metadata['pack']['description'] = 'GTNH pinned GT5 5.09.54.205 ore bases: 34 exact source PNGs; derived transparent overlays; preview subset only'
    writes[output / PACK / 'pack.mcmeta'] = json_bytes(pack_metadata)
    writes[output / PACK / 'README.txt'] = (
        'Primary source: GTNewHorizons/GT5-Unofficial 5.09.54.205, commit ' + SOURCE_COMMIT + '.\n'
        'This preview subset copies 34 base PNGs byte for byte from the locked master source.\n'
        'All 34 source animation sidecars are absent; that absence is checked in source and output.\n'
        '34 transparent secondary PNGs are synthesized assets, not original source textures.\n'
        'They remain unchanged to prevent inheriting visible modern overlays absent in this source subset.\n'
        'The directory name retains 2.8.4 for existing pack references; 2.8.4 is now comparison evidence only.\n'
        '7 primary PNGs differ from 2.8.4 in encoded bytes; all 34 decode to identical RGBA pixels.\n'
        'Material identity/color, renderer behaviour, other sets, models, animations outside this subset,\n'
        'emissive rendering and gameplay/world behaviour remain unverified. This is NOT full fidelity.\n'
        'Original GTNH and GregTech attribution is retained.\n'
    ).encode('utf-8')
    writes[output / MAPPING] = json_bytes(manifest)
    writes[output / REPORT] = json_bytes(report)
    for path, data in writes.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for path in removals:
        path.unlink()
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = port(args.root, args.output)
    print(json.dumps({k: v for k, v in result.items() if k != 'comparisons'}, ensure_ascii=False))
