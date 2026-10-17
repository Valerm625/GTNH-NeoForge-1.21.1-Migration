"""Restore omitted local inputs from an existing checkpoint without overwriting work.

No network, old-script execution, EULA acceptance or game launch is performed.
Run from any directory: python restore_local_checkpoint.py --source-root PATH
Use --check-only to validate and inventory without copying inputs.
"""
from __future__ import annotations

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
FOUNDATION = Path('outputs/GTNH-NeoForge-1.21.1-Migration-Foundation')
REPORT = Path('work/migration/local-checkpoint-restore.json')
GENERATED_COMPAT = 'gtnh-migration-compat-0.1.0.jar'
REBUILD_FILES = {
    (FOUNDATION / 'mods' / GENERATED_COMPAT).as_posix(),
    'work/neoforge-smoke/mods/' + GENERATED_COMPAT,
}
TREES = (
    'work/cache', 'work/exact-sources', 'work/runtime', 'work/neoforge-smoke',
    'work/source/GT-New-Horizons-Modpack-master', 'work/source-release-2.8.4',
    'work/migration', str(FOUNDATION),
)


class RestoreError(RuntimeError):
    pass


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def digest(path, algorithm='sha256'):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, algorithm).hexdigest()


def safe_path(root, relative):
    """Reject paths escaping a root, including junctions in existing ancestors."""
    normalized = str(relative).replace('\\', '/')
    parts = PurePosixPath(normalized)
    if parts.is_absolute() or any(x in ('..', '.') for x in parts.parts) or ':' in normalized:
        raise RestoreError(f'Unsafe relative path: {relative}')
    target = root.joinpath(*parts.parts)
    # Components cannot contain '..'; checking every existing ancestor below
    # catches redirects without repeatedly resolving a deep tree for each file.
    if not target.is_relative_to(root):
        raise RestoreError(f'Path escapes root: {target}')
    cursor = target
    while cursor != root:
        if cursor.exists() or cursor.is_symlink():
            info = cursor.lstat()
            if cursor.is_symlink() or getattr(info, 'st_file_attributes', 0) & stat.FILE_ATTRIBUTE_REPARSE_POINT:
                raise RestoreError(f'Links/junctions are not restoration inputs: {cursor}')
        cursor = cursor.parent
    return target


def require_digest(path, expected, algorithm='sha256'):
    if not path.is_file() or digest(path, algorithm) != expected.lower():
        raise RestoreError(f'{algorithm} mismatch or missing file: {path}')


def verify_inventory(root, rows):
    for entry in rows:
        path = safe_path(root, entry['path'])
        if 'bytes' in entry and (not path.is_file() or path.stat().st_size != entry['bytes']):
            raise RestoreError(f'File size mismatch: {path}')
        require_digest(path, entry['sha256'])
    return len(rows)


def verify_source_archive(archive, extracted):
    """The locked archive must match the source tree actually consumed by scripts."""
    expected = set()
    with zipfile.ZipFile(archive) as source:
        prefixes = {PurePosixPath(e.filename).parts[0] for e in source.infolist()}
        if len(prefixes) != 1:
            raise RestoreError(f'Unexpected source archive layout: {archive}')
        prefix = next(iter(prefixes)) + '/'
        for entry in source.infolist():
            if entry.is_dir():
                continue
            relative = entry.filename.removeprefix(prefix)
            target = safe_path(extracted, relative)
            if stat.S_ISLNK(entry.external_attr >> 16) or relative.casefold() in expected:
                raise RestoreError(f'Link/duplicate source archive entry: {entry.filename}')
            expected.add(relative.casefold())
            require_digest(target, hashlib.sha256(source.read(entry)).hexdigest())
    actual = {p.relative_to(extracted).as_posix().casefold() for p in extracted.rglob('*') if p.is_file()}
    if actual != expected:
        raise RestoreError(f'Extracted source tree has missing/extra files: {extracted}')
    return len(expected)


def verify_inputs(source, target):
    result = {}
    platform = read_json(target / 'locks/platform-lock.json')
    original = read_json(source / 'work/migration/platform-lock.json')
    for key in ('minecraft', 'neoforge', 'java', 'installer_url', 'sha256'):
        if original.get(key) != platform[key]:
            raise RestoreError(f'Platform lock differs: {key}')
    require_digest(source / f"work/cache/neoforge-{platform['neoforge']}-installer.jar", platform['sha256'])
    result['platform'] = {'minecraft': platform['minecraft'], 'neoforge': platform['neoforge']}
    result['source_archives'] = []
    for lockfile in sorted((target / 'locks/sources').glob('*.json')):
        lock = read_json(lockfile)
        repo = lock['repository'].split('/')[-1]
        stem = repo + '-' + lock['tag']
        archive = source / 'work/cache/exact-sources' / (stem + '.zip')
        require_digest(archive, lock['archive_sha256'])
        old_lock = read_json(source / 'work/cache/exact-sources' / (stem + '-lock.json'))
        for key in ('repository', 'tag', 'commit', 'archive_url', 'archive_sha256'):
            if old_lock.get(key) != lock[key]:
                raise RestoreError(f'Source lock differs: {lockfile.name}: {key}')
        count = verify_source_archive(archive, source / 'work/exact-sources' / repo / lock['tag'])
        result['source_archives'].append({'repository': lock['repository'], 'tag': lock['tag'], 'files_verified': count})
        print('SOURCE_LOCK_VERIFIED', repo, count, flush=True)
    artifacts = read_json(target / 'locks/artifacts.lock.json')['artifacts']
    ftb = read_json(target / 'locks/ftb-candidates.json')
    output_artifacts = read_json(source / FOUNDATION / 'docs/artifacts.lock.json')['artifacts']
    if output_artifacts != artifacts:
        raise RestoreError('The original output artifact definitions differ from the checkpoint lock')
    original_ftb = read_json(source / FOUNDATION / 'docs/ftb-candidates.json')
    without_source_path = lambda rows: [{k: v for k, v in row.items() if k != 'sources_jar'} for row in rows]
    if without_source_path(original_ftb) != without_source_path(ftb):
        raise RestoreError('The original output FTB definitions differ from the checkpoint lock')
    expected_names = {row['filename'] for row in artifacts + ftb} | {GENERATED_COMPAT}
    for folder in (source / FOUNDATION / 'mods', source / 'work/neoforge-smoke/mods'):
        if {p.name for p in folder.glob('*.jar')} != expected_names:
            raise RestoreError(f'Unexpected or missing active mod JARs: {folder}')
    for row in artifacts:
        hashes = row['hashes']
        algorithm = 'sha512' if 'sha512' in hashes else 'sha1'
        require_digest(source / FOUNDATION / 'mods' / row['filename'], hashes[algorithm], algorithm)
        require_digest(source / 'work/neoforge-smoke/mods' / row['filename'], hashes[algorithm], algorithm)
    for row in ftb:
        require_digest(source / FOUNDATION / 'mods' / row['filename'], row['sha256'])
        require_digest(source / 'work/neoforge-smoke/mods' / row['filename'], row['sha256'])
    compat = read_json(target / 'locks/compat-mod.lock.json')
    if compat['filename'] != GENERATED_COMPAT:
        raise RestoreError('Unexpected local adapter artifact; recovery policy needs review')
    original_compat = read_json(source / FOUNDATION / 'docs/compat-mod.lock.json')
    actual_compat = digest(source / FOUNDATION / 'mods' / GENERATED_COMPAT)
    if original_compat['sha256'] != actual_compat:
        raise RestoreError('The original workspace local adapter differs from its own lock')
    result['upstream_mods_verified'] = len(artifacts) + len(ftb)
    result['local_compat_jar_imported'] = False
    result['local_compat_requires_rebuild'] = {
        'filename': GENERATED_COMPAT, 'checkpoint_sha256': compat['sha256'],
        'source_workspace_sha256': actual_compat,
        'matches_checkpoint': actual_compat == compat['sha256'],
        'reason': 'Rebuild locally generated code from the preserved tracked source before any loader run.',
    }
    runtime_rows = read_json(target / 'locks/local-runtime-file-hashes.json')['files']
    runtime_rows = [row for row in runtime_rows if row['path'] != 'mods/' + GENERATED_COMPAT]
    result['runtime_files_verified'] = verify_inventory(source / FOUNDATION, runtime_rows)
    print('RUNTIME_FILES_VERIFIED', result['runtime_files_verified'], flush=True)
    for path in (source / FOUNDATION / 'eula.txt', source / 'work/neoforge-smoke/eula.txt'):
        lines = [line.strip().lower() for line in path.read_text(encoding='utf-8').splitlines()]
        if 'eula=false' not in lines or 'eula=true' in lines:
            raise RestoreError(f'Recovery expects the unaccepted checkpoint EULA: {path}')
    master = read_json(source / 'work/inventory/source_inventory.json')
    result['master_inventory_files_verified'] = verify_inventory(source / 'work/source/GT-New-Horizons-Modpack-master', master['files'])
    print('MASTER_INVENTORY_VERIFIED', result['master_inventory_files_verified'], flush=True)
    legacy = read_json(source / FOUNDATION / 'docs/reference-2.8.4/source-inventory.json')
    if legacy['sha256'] != '8fecdf562312e1c0d28812123770eee616eb420519cea4b8cbfcf24c26767828':
        raise RestoreError('Unexpected legacy reference archive identity')
    result['legacy_inventory_files_verified'] = verify_inventory(source / 'work/source-release-2.8.4', legacy['files'])
    print('LEGACY_INVENTORY_VERIFIED', result['legacy_inventory_files_verified'], flush=True)
    result['inventory_scope'] = 'Master/legacy trees checked against historical inventories; this does not independently re-read their original ZIP archives.'
    return result


def runtime_configuration(source, target):
    original = read_json(source / 'work/migration/runtime.json')
    result = dict(original)
    for key in ('java', 'java_home'):
        old = Path(original[key]).resolve()
        if not old.is_relative_to((source / 'work/runtime').resolve()) or not old.exists():
            raise RestoreError(f'Runtime path is outside the source portable runtime: {key}')
        result[key] = str(target / old.relative_to(source))
    # An earlier -version result is provenance, not proof that this restored Java ran.
    result['historical_probe'] = original.get('probe')
    result['probe'] = {'path': result['java'], 'executed_after_restore': False}
    result['restored_from'] = str(source)
    result['runtime_verified_after_restore'] = False
    return result


def tracked_paths(target):
    proc = subprocess.run(['git', 'ls-files', '-z'], cwd=target, capture_output=True, check=True)
    return {name.decode('utf-8') for name in proc.stdout.split(b'\0') if name}


def build_plan(source, target, tracked):
    rows = []
    preserved = []
    skipped = []
    for tree in TREES:
        print('PLAN_INPUT_TREE', tree, flush=True)
        base = safe_path(source, tree)
        if not base.is_dir():
            raise RestoreError(f'Missing input tree: {base}')
        for folder, dirs, files in os.walk(base, followlinks=False):
            for name in list(dirs):
                relative = (Path(folder) / name).relative_to(source)
                safe_path(source, relative)
                if name in ('__pycache__', '.git', 'java-checks'):
                    dirs.remove(name)
            for name in sorted(files):
                relative = (Path(folder) / name).relative_to(source)
                rel = relative.as_posix()
                if rel in tracked:
                    preserved.append(rel)
                    continue
                if rel in REBUILD_FILES or rel == 'work/migration/runtime.json' or name.endswith(('.part', '.pyc', '.class', '.args')):
                    skipped.append(rel)
                    continue
                src, dst = safe_path(source, relative), safe_path(target, relative)
                if dst.exists():
                    if not dst.is_file() or digest(src) != digest(dst):
                        raise RestoreError(f'Existing destination differs; preserved without changes: {dst}')
                    action = 'verified_existing'
                else:
                    action = 'copy'
                rows.append((src, dst, action))
    # Preserve the master intake inventory alongside the restored source tree.
    relative = Path('work/inventory/source_inventory.json')
    src, dst = safe_path(source, relative), safe_path(target, relative)
    if dst.exists() and digest(src) != digest(dst):
        raise RestoreError(f'Existing destination differs: {dst}')
    rows.append((src, dst, 'verified_existing' if dst.exists() else 'copy'))
    return rows, preserved, skipped


def copy_exclusive(source, target):
    """Exclusive creation protects changes made after preflight as well."""
    target.parent.mkdir(parents=True, exist_ok=True)
    expected = hashlib.sha256()
    created = False
    try:
        with source.open('rb') as src, target.open('xb') as dst:
            created = True
            for block in iter(lambda: src.read(1024 * 1024), b''):
                dst.write(block)
                expected.update(block)
        require_digest(target, expected.hexdigest())
    except BaseException:
        if created:
            target.unlink()
        raise


def restore(source, target, check_only=False):
    started = time.monotonic()
    source, target = source.resolve(), target.resolve()
    if source == target or source.is_relative_to(target) or target.is_relative_to(source):
        raise RestoreError('Source and destination must be separate, non-overlapping roots')
    print('VERIFY_LOCKED_LOCAL_INPUTS', flush=True)
    verification = verify_inputs(source, target)
    runtime = runtime_configuration(source, target)
    runtime_path = safe_path(target, 'work/migration/runtime.json')
    if runtime_path.exists() and read_json(runtime_path) != runtime:
        raise RestoreError(f'Existing runtime configuration differs; not overwritten: {runtime_path}')
    rows, preserved, skipped = build_plan(source, target, tracked_paths(target))
    report = {
        'created_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
        'source_root': str(source), 'destination_root': str(target),
        'status': 'validated_only' if check_only else 'restored',
        'verification': verification, 'tracked_files_preserved': len(preserved),
        'copied_files': 0, 'copied_bytes': 0,
        'already_identical_files': sum(action == 'verified_existing' for _, _, action in rows),
        'planned_copy_files': sum(action == 'copy' for _, _, action in rows),
        'planned_copy_bytes': sum(src.stat().st_size for src, _, action in rows if action == 'copy'),
        'skipped_generated_or_incomplete_files': skipped,
        'historical_evidence_imported': not check_only, 'fresh_rebuild_executed': False,
        'fresh_loader_probe_executed': False, 'game_runtime_verified': False,
        'full_parity': False, 'eula_changed': False, 'source_workspace_modified': False,
        'next_step': 'Probe restored Java, then run rebuild_current_phase.py; it generates and compiles adapters, runs the limited loader probe, and checks the current phase. Imported previous validation results are historical only.',
    }
    print('RESTORE_PLAN', report['planned_copy_files'], report['planned_copy_bytes'], flush=True)
    if not check_only:
        if shutil.disk_usage(target).free < report['planned_copy_bytes'] + 64 * 1024 * 1024:
            raise RestoreError('Insufficient destination disk space for the complete restore')
        for src, dst, action in rows:
            if action == 'copy':
                safe_path(target, dst.relative_to(target))
                copy_exclusive(src, dst)
                report['copied_files'] += 1
                report['copied_bytes'] += src.stat().st_size
                if report['copied_files'] % 5000 == 0:
                    print('RESTORE_PROGRESS', report['copied_files'], flush=True)
        if runtime_path.exists():
            if read_json(runtime_path) != runtime:
                raise RestoreError(f'Runtime configuration changed during restoration; preserved: {runtime_path}')
        else:
            runtime_path.parent.mkdir(parents=True, exist_ok=True)
            with runtime_path.open('x', encoding='utf-8') as stream:
                json.dump(runtime, stream, ensure_ascii=False, indent=2)
        report['runtime_configuration_rebased'] = True
    report['elapsed_seconds'] = round(time.monotonic() - started, 3)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    try:
        report = restore(args.source_root, ROOT, args.check_only)
    except (RestoreError, OSError, ValueError, subprocess.CalledProcessError) as exc:
        print('RESTORE_FAILED:', exc, file=sys.stderr)
        return 1
    if not args.check_only:
        path = safe_path(ROOT, REPORT)
        path.parent.mkdir(parents=True, exist_ok=True)
        # This file is owned by the recovery command, not a historical success record.
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
