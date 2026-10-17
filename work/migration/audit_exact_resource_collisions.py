"""Inventory exact legacy source assets and retain all same-path candidates.

This is source evidence only. No target file is selected or changed, and archive
order is explicitly NOT a Minecraft resource-pack precedence determination.
"""
from pathlib import Path, PurePosixPath
from collections import defaultdict, Counter
import argparse
import hashlib
import json
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCES = (
    ('NewHorizonsCoreMod', '2.9.84', '927b1cfb5e2686ec6398dd8a6d94927767ed69ed0ea54fb2df934784e9a09071'),
    ('GT5-Unofficial', '5.09.54.205', '3044b6cfeec1b1aad554739409af09e314f2f07c8138be08bd478bf183ae6b0f'),
    ('Railcraft', '9.17.31', '13e11049a7b7cd6be2ba3ca8026dbbb6c8b36470d41905f3b96b5c8380834c76'),
    ('BetterQuesting', '3.8.89-GTNH', '7717db6ce9d8b70f6ef9601b3b23b9e339e7a33000a792d7ac145b3eb0183d10'),
)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def check(condition, message):
    if not condition:
        raise ValueError(message)


def scan_archive(archive, extracted, source, expected_archive_hash):
    check(sha(archive.read_bytes()) == expected_archive_hash, 'Archive hash mismatch: ' + source)
    rows = []
    seen = set()
    prefix = 'src/main/resources/assets/'
    with zipfile.ZipFile(archive) as zipped:
        roots = {i.filename.split('/')[0] for i in zipped.infolist() if i.filename}
        check(len(roots) == 1, 'Expected exactly one archive root: ' + source)
        archive_root = next(iter(roots)) + '/'
        for entry in zipped.infolist():
            name = entry.filename
            check(name not in seen, 'Duplicate archive entry: ' + name)
            seen.add(name)
            check('\\' not in name and not PurePosixPath(name).is_absolute()
                  and '..' not in PurePosixPath(name).parts, 'Noncanonical archive path: ' + name)
            if entry.is_dir() or not name.startswith(archive_root + prefix):
                continue
            relative = name[len(archive_root):]
            resource = relative[len('src/main/resources/'):]
            data = zipped.read(entry)
            local = extracted / relative
            check(local.is_file(), 'Missing extracted resource: ' + source + '/' + relative)
            check(local.read_bytes() == data, 'Extracted resource differs: ' + source + '/' + relative)
            rows.append({'resource_path': resource, 'source': source, 'archive_entry': name,
                         'bytes': len(data), 'sha256': sha(data)})
    local_assets = extracted / 'src/main/resources/assets'
    local_paths = {'assets/' + p.relative_to(local_assets).as_posix()
                   for p in local_assets.rglob('*') if p.is_file()}
    check(local_paths == {r['resource_path'] for r in rows}, 'Unlisted extracted resources: ' + source)
    check(rows, 'No source assets: ' + source)
    return sorted(rows, key=lambda row: row['resource_path'])


def group_collisions(rows):
    exact = defaultdict(list)
    folded = defaultdict(list)
    for row in rows:
        exact[row['resource_path']].append(row)
        folded[row['resource_path'].casefold()].append(row)
    def candidates(values):
        return sorted(values, key=lambda row: (row['source'], row['resource_path']))
    collisions = [
        {'resource_path': path, 'candidates': candidates(values),
         'distinct_content_hashes': len({row['sha256'] for row in values}),
         'precedence': 'unknown', 'selected_source': None}
        for path, values in sorted(exact.items()) if len(values) > 1
    ]
    aliases = [
        {'casefold_path': path, 'candidates': candidates(values),
         'exact_spellings': sorted({row['resource_path'] for row in values}),
         'resolution': 'unresolved; do not rename or collapse'}
        for path, values in sorted(folded.items())
        if len({row['resource_path'] for row in values}) > 1
    ]
    return collisions, aliases


def audit(root=ROOT):
    rows, inputs = [], []
    for repository, tag, expected in SOURCES:
        source = repository + '/' + tag
        lock_file = root / 'locks/sources' / (repository + '-' + tag + '-lock.json')
        lock = json.loads(lock_file.read_text(encoding='utf-8'))
        check(lock['repository'] == 'GTNewHorizons/' + repository and lock['tag'] == tag
              and lock['archive_sha256'] == expected, 'Source lock identity changed: ' + source)
        archive = root / 'work/cache/exact-sources' / (repository + '-' + tag + '.zip')
        batch = scan_archive(archive, root / 'work/exact-sources' / source, source, expected)
        rows.extend(batch)
        inputs.append({'source': source, 'commit': lock['commit'], 'archive_sha256': expected,
                       'lock_sha256': sha(lock_file.read_bytes()), 'resource_files': len(batch),
                       'resource_bytes': sum(r['bytes'] for r in batch)})
    collisions, aliases = group_collisions(rows)
    manifest_bytes = (json.dumps(rows, ensure_ascii=False, sort_keys=True, separators=(',', ':')) + '\n').encode('utf-8')
    report = {
        'passed': True, 'sources': inputs, 'source_resource_occurrences': len(rows),
        'unique_exact_resource_paths': len({r['resource_path'] for r in rows}),
        'same_path_groups': len(collisions),
        'same_path_different_content_groups': sum(c['distinct_content_hashes'] > 1 for c in collisions),
        'casefold_alias_groups': len(aliases),
        'extensions': dict(sorted(Counter(Path(r['resource_path']).suffix.lower() for r in rows).items())),
        'collisions': collisions, 'casefold_aliases': aliases,
        'full_manifest_sha256': sha(manifest_bytes), 'auditor_source_sha256': sha(Path(__file__).read_bytes()),
        'all_enumerated_asset_bytes_match_archive_and_tree': True,
        'resource_precedence_verified': False, 'runtime_visual_verified': False,
        'target_resources_modified': False, 'full_resource_migration': False,
        'scope': 'Four pinned legacy source archives only, not all GTNH mods or assembled runtime JARs. All candidate bytes retained; enumeration order is not load precedence. Generated build resources and external resource packs remain outside this inventory.',
    }
    return report, manifest_bytes


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    result, manifest = audit(args.root)
    local = args.root / 'work/migration/resource-collision-check'
    local.mkdir(parents=True, exist_ok=True)
    (local / 'asset-manifest.json').write_bytes(manifest)
    (args.root / 'evidence/exact-resource-collisions.json').write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ('collisions', 'casefold_aliases')}, ensure_ascii=False))
