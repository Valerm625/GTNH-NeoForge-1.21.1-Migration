"""Audit the two pinned user ZIPs as data, without extracting or executing them.

Usage: python work/migration/audit_source_archives.py --release-zip PATH --master-zip PATH
Detailed hash manifests go under ignored work/source-archive-audit/. Only the compact
evidence/source-archive-audit.json summary is suitable for committing. No task text,
resource bytes, machine paths or third-party source bodies are put in the summary.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RELEASE_PREFIX = 'GT New Horizons 2.8.4/.minecraft/'
MASTER_PREFIX = 'GT-New-Horizons-Modpack-master/'
PINNED_HASHES = {
    'release': '8fecdf562312e1c0d28812123770eee616eb420519cea4b8cbfcf24c26767828',
    'master': '334a7ed73a0c2ed3d142fa53f5d6635576d30b63ad3025fddd5f31e719849802',
}
MEDIA = {'.png', '.jpg', '.jpeg', '.gif', '.ogg', '.wav', '.mp3', '.lang', '.mcmeta', '.obj', '.mtl'}
QUEST_PREFIX = 'config/betterquesting/DefaultQuests/Quests/'


class AuditError(ValueError):
    pass


def digest(data):
    return hashlib.sha256(data).hexdigest()


def strip_types(value):
    if isinstance(value, dict):
        return {re.sub(r':\d+$', '', key): strip_types(v) for key, v in value.items()}
    if isinstance(value, list):
        return [strip_types(v) for v in value]
    return value


def safe_entry_name(info):
    name = info.filename.replace('\\', '/')
    parts = name.rstrip('/').split('/')
    if (name.startswith('/') or ':' in name or '\x00' in name
            or any(p in ('', '.', '..') for p in parts)
            or stat.S_ISLNK(info.external_attr >> 16)):
        raise AuditError('Unsafe ZIP path or symlink: ' + repr(info.filename))
    return name


def audit_archive(path, prefix, expected_sha256=None, max_expanded_bytes=16 * 1024**3, max_depth=4):
    """Fully read entries for CRC validation; hashes are evidence, not runtime tests."""
    path = Path(path)
    with path.open('rb') as stream:
        archive_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
    if expected_sha256 and archive_hash != expected_sha256:
        raise AuditError('Input SHA-256 differs from the pinned baseline: ' + path.name)
    files, assets, archives, collisions = [], [], [], []
    quests, version_evidence = {}, {}
    task_types, reward_types = Counter(), Counter()
    expanded = 0
    mod_rows = []

    def record_asset(container, name, payload):
        ext = PurePosixPath(name).suffix.lower()
        if not (name.startswith('assets/') or '/assets/' in name or ext in MEDIA):
            return
        record = {'container': container, 'path': name, 'bytes': len(payload), 'sha256': digest(payload)}
        if payload[:8] == b'\x89PNG\r\n\x1a\n' and len(payload) >= 24:
            record['png_header_dimensions'] = list(struct.unpack('>II', payload[16:24]))
        assets.append(record)

    def read_zip(z, container, depth):
        nonlocal expanded, mod_rows
        seen, file_count, texture_count, class_count, asset_count = {}, 0, 0, 0, 0
        namespaces = Counter()
        for info in z.infolist():
            name = safe_entry_name(info)
            # Reject decompression bombs before materializing each payload.
            expanded += info.file_size
            if expanded > max_expanded_bytes:
                raise AuditError('Expanded byte budget exceeded')
            payload = b'' if info.is_dir() else z.read(info)
            hash_value = digest(payload)
            key = name.rstrip('/').casefold()
            if key in seen:
                prior = seen[key]
                equal = prior['sha256'] == hash_value and prior['directory'] == info.is_dir()
                collisions.append({'container': container, 'path': name, 'prior': prior['path'],
                                   'directory': info.is_dir(), 'identical_bytes': equal})
                if not equal:
                    raise AuditError('Conflicting case-insensitive ZIP paths: ' + prior['path'] + ' / ' + name)
            else:
                seen[key] = {'path': name, 'sha256': hash_value, 'directory': info.is_dir()}
            if info.is_dir():
                continue
            file_count += 1
            class_count += name.endswith('.class')
            texture_count += name.lower().endswith('.png') and '/textures/' in name
            if name.startswith('assets/'):
                asset_count += 1
                namespaces[name.split('/')[1]] += 1
            relative = name[len(prefix):] if depth == 0 and name.startswith(prefix) else name
            record_asset(container, relative, payload)
            if depth == 0:
                files.append({'path': name, 'relative_path': relative, 'bytes': len(payload), 'sha256': hash_value})
                if relative.startswith(QUEST_PREFIX) and relative.endswith('.json'):
                    quest = strip_types(json.loads(payload))
                    qid = str(quest['questIDHigh']) + ':' + str(quest['questIDLow'])
                    if qid in quests:
                        raise AuditError('Duplicate quest identity: ' + qid)
                    canonical = json.dumps(quest, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')
                    quests[qid] = {'path': relative, 'sha256': hash_value, 'canonical_sha256': digest(canonical)}
                    task_types.update(t.get('taskID', '<missing>') for t in quest.get('tasks', {}).values())
                    reward_types.update(r.get('rewardID', '<missing>') for r in quest.get('rewards', {}).values())
                if relative == 'README.md':
                    readme = payload.decode('utf-8-sig')
                    version_evidence['readme_version_line'] = next((s for s in readme.splitlines() if s.startswith('Version ')), None)
                    mod_rows = re.findall(r'^\| \[([^\]]+)\]\(([^)]+)\) \| ([^|]+) \|$', readme, re.M)
                if relative == 'config/txloader/load/mainmenu/version.txt':
                    version_evidence['mainmenu_version_line'] = payload.decode('utf-8-sig').strip()
                if relative == 'config/betterquesting/DefaultQuests/QuestSettings.json':
                    settings = strip_types(json.loads(payload))
                    version_evidence['questbook_format'] = settings.get('format')
                    version_evidence['questbook_pack_version'] = settings.get('betterquesting', {}).get('pack_version')
            if name.lower().endswith(('.jar', '.zip')):
                if not zipfile.is_zipfile(io.BytesIO(payload)):
                    raise AuditError('Invalid nested archive: ' + container + '!/' + name)
                if depth >= max_depth:
                    raise AuditError('Nested archive depth limit exceeded')
                child_name = (container + '!/' if container else '') + relative
                with zipfile.ZipFile(io.BytesIO(payload)) as child:
                    counts = read_zip(child, child_name, depth + 1)
                archives.append({'path': child_name, 'bytes': len(payload), 'sha256': hash_value,
                                 'crc_validated': True, **counts})
        return {'file_count': file_count, 'texture_png_count': texture_count, 'class_count': class_count,
                'assets_directory_file_count': asset_count, 'asset_namespaces': dict(namespaces)}

    with zipfile.ZipFile(path) as z:
        entry_count = len(z.infolist())
        read_zip(z, '', 0)
    if not any(f['path'].startswith(prefix) for f in files):
        raise AuditError('Expected archive layout is missing: ' + prefix)
    extensions = Counter(PurePosixPath(a['path']).suffix.lower() for a in assets)
    top_jars = [a for a in archives if a['path'].endswith('.jar') and '!/' not in a['path']]
    top_mod_jars = [a for a in top_jars if a['path'].startswith('mods/')]
    summary = {
        'archive_filename': path.name, 'archive_sha256': archive_hash, 'archive_bytes': path.stat().st_size,
        'archive_file_entries': len(files), 'archive_entries_including_directories': entry_count,
        'outer_expanded_bytes': sum(f['bytes'] for f in files), 'total_read_bytes_including_nested': expanded,
        'outer_and_contained_archives_crc_validated': True, 'contained_archive_count': len(archives),
        'outer_jar_count': len(top_jars), 'mod_jar_count': len(top_mod_jars),
        'readme_mod_version_rows': len(mod_rows), 'java_source_file_count': sum(f['path'].endswith('.java') for f in files),
        'root_category_file_counts': dict(Counter(f['relative_path'].split('/')[0] for f in files)),
        'gameplay_script_file_count': sum(f['relative_path'].startswith('scripts/') or f['relative_path'].endswith('.zs') for f in files),
        'quest_count': len(quests), 'task_type_counts': dict(task_types), 'reward_type_counts': dict(reward_types),
        'asset_manifest_entries': len(assets), 'asset_extensions': dict(sorted(extensions.items())),
        'png_files_in_asset_manifest': extensions['.png'], 'animation_metadata_files_in_asset_manifest': extensions['.mcmeta'],
        'audio_files_in_asset_manifest': sum(extensions[e] for e in ('.ogg', '.wav', '.mp3')),
        'texture_pngs_in_top_level_jars': sum(a['texture_png_count'] for a in top_jars),
        'assets_directory_files_in_top_level_mod_jars': sum(a['assets_directory_file_count'] for a in top_mod_jars),
        'case_insensitive_collisions': collisions, 'version_evidence': version_evidence,
    }
    return {'summary': summary, 'files': files, 'assets': assets, 'archives': archives, 'quests': quests,
            'readme_mod_versions': [{'name': name, 'source_url': url, 'version': version.strip()} for name, url, version in mod_rows]}


def compare(release, master):
    old = {f['relative_path']: f for f in release['files']}
    new = {f['relative_path']: f for f in master['files']}
    common = old.keys() & new.keys()
    same = sorted(p for p in common if old[p]['sha256'] == new[p]['sha256'])
    changed = sorted(common - set(same))
    old_q, new_q = release['quests'], master['quests']
    common_q = old_q.keys() & new_q.keys()
    return {
        'same_path_identical_files': len(same), 'same_path_changed_files': len(changed),
        'release_only_file_paths': len(old.keys() - new.keys()), 'master_only_file_paths': len(new.keys() - old.keys()),
        'config_same_path_identical': sum(p.startswith('config/') for p in same),
        'config_same_path_changed': sum(p.startswith('config/') for p in changed),
        'config_release_only_paths': sum(p.startswith('config/') for p in old.keys() - new.keys()),
        'config_master_only_paths': sum(p.startswith('config/') for p in new.keys() - old.keys()),
        'shared_quest_id_count': len(common_q),
        'shared_quest_id_identical_bytes': sum(old_q[q]['sha256'] == new_q[q]['sha256'] for q in common_q),
        'shared_quest_id_identical_canonical_json': sum(old_q[q]['canonical_sha256'] == new_q[q]['canonical_sha256'] for q in common_q),
        'release_only_quest_id_count': len(old_q.keys() - new_q.keys()),
        'master_only_quest_id_count': len(new_q.keys() - old_q.keys()),
    }


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release-zip', type=Path, required=True)
    parser.add_argument('--master-zip', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path, default=ROOT / 'work/source-archive-audit')
    parser.add_argument('--summary', type=Path, default=ROOT / 'evidence/source-archive-audit.json')
    args = parser.parse_args(argv)
    results = {}
    for label, path, prefix in [('release', args.release_zip, RELEASE_PREFIX), ('master', args.master_zip, MASTER_PREFIX)]:
        print('Read-only audit: ' + label, flush=True)
        results[label] = audit_archive(path, prefix, PINNED_HASHES[label])
        for kind, records in results[label].items():
            save(args.output_dir / (label + '-' + kind + '.json'), records)
    differences = compare(results['release'], results['master'])
    save(args.output_dir / 'comparison.json', differences)
    mod_versions = {m['name']: m['version'] for m in results['master']['readme_mod_versions']}
    implementation_versions = []
    for repository, jar_prefix in [('GT5-Unofficial', 'gregtech-'), ('NewHorizonsCoreMod', 'GTNewHorizonsCoreMod-'),
                                   ('BetterQuesting', 'BetterQuesting-'), ('Railcraft', 'Railcraft-')]:
        version = mod_versions[repository]
        lock = json.loads((ROOT / 'locks/sources' / (repository + '-' + version + '-lock.json')).read_text(encoding='utf-8'))
        if lock['tag'] != version:
            raise AuditError('Repository source lock does not match master mod list: ' + repository)
        implementation_versions.append({
            'repository': repository, 'master_readme_version': version, 'repository_lock_tag': lock['tag'],
            'repository_lock_commit': lock['commit'], 'repository_lock_archive_sha256': lock['archive_sha256'],
            'release_2_8_4_jars': [f['relative_path'] for f in results['release']['files']
                                 if f['relative_path'].startswith('mods/' + jar_prefix) and f['path'].endswith('.jar')],
            'master_mod_list_matches_repository_lock_tag': True,
            'upstream_source_archive_reverified_by_this_tool': False,
        })
    report = {
        'schema': 1, 'scope': 'Read-only source inventory; no migration or runtime execution',
        'release': results['release']['summary'], 'master': results['master']['summary'], 'differences': differences,
        'implementation_version_evidence': implementation_versions,
        'version_differences': [
            'Master README says 2.9.0-RC-2 (2026-10-04), while its main-menu version says 2.9.0-beta-3 (2026-09-06). Preserve both source facts; this snapshot is not a proven RC-2 release artifact.',
            'The 2.8.4 archive contains 245 quest IDs absent from master; master has 320 IDs absent from 2.8.4. An ID difference alone does not prove removed content because quests may be replaced or renumbered. Reconcile semantics before choosing a full-parity baseline.',
            'Master contains configurations and a 242-row mod version list, but no mod JARs or Java source files. The 2.8.4 JAR versions do not implement the master baseline.',
        ],
        'count_boundaries': [
            'ZIP entry counts retain identical case aliases. Extracting to a case-insensitive filesystem merges four identical file pairs in the 2.8.4 input.',
            'Asset entries count archive occurrences, including overrides, raw files, nested archives and resources under assets/. They are not unique effective game resources or migration coverage.',
            'PNG dimensions come from the file header only. SHA-256 equality and ZIP CRC do not prove image decoding, animation timing, rendering or gameplay equivalence.',
            'No scripts/ or .zs gameplay files exist in either outer ZIP. This does not imply that recipes are absent: many are implemented inside mod code.',
        ],
        'lossless_validation_requirements': [
            'Track source archive hash, container, path, bytes and SHA-256 for every migrated asset; resolve resource-pack/txloader override order explicitly.',
            'Preserve PNG, animation metadata and audio bytes where formats permit; audit every format conversion and resource-ID translation, with no untracked omissions or modern fallback textures.',
            'Validate model geometry, UVs, texture layers, tint, transparency, animation timing and font/localization keys in deterministic source/target client scenes.',
            'Verify sound-event mappings, volume, pitch, looping and attenuation; hashing audio alone is insufficient.',
            'Compare task identities, prerequisites, rewards, conditions and text across versions before accepting a union or replacement.',
        ],
        'runtime_executed': False, 'assets_migrated_by_this_audit': 0, 'full_functional_or_visual_parity_verified': False,
    }
    save(args.summary, report)
    print(json.dumps({'summary_written': True, 'release_files': report['release']['archive_file_entries'],
                      'master_files': report['master']['archive_file_entries'], 'differences': differences}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
