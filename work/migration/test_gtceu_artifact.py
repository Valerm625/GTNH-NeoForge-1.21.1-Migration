"""Exercise replacement/recovery with real pinned JARs in private fixture packs."""
import json
import shutil
import uuid
from restore_local_checkpoint import verify_mod_inputs, build_plan, TREES, REBUILD_FILES
from pathlib import Path
from gtceu_artifact import (ROOT, FOUNDATION, RECEIPT, pins, original_path, cache_original,
                            runtime_jar, install, sha, read, verify_derived)


def snapshot(folder):
    return {p.relative_to(folder).as_posix(): sha(p) for p in folder.rglob('*') if p.is_file()}


def main():
    run = ROOT / 'work/migration/gtceu-artifact-check' / uuid.uuid4().hex
    run.mkdir(parents=True)
    pin = pins()
    original = cache_original()
    built = read(ROOT / 'work/migration/gtceu-kubejs-bridge/build.json')
    artifact = ROOT / built['jar']
    verify_derived(artifact)
    pack = run / 'pack'
    (pack / 'mods').mkdir(parents=True)
    shutil.copy2(original, pack / 'mods' / original.name)
    (pack / 'eula.txt').write_text('eula=false\n', encoding='ascii')
    (pack / 'config').mkdir()
    (pack / 'config/retained.txt').write_bytes(b'User configuration retained\n')
    before = snapshot(pack)
    original_lock = sha(ROOT / 'locks/artifacts.lock.json')
    cases = []

    def rejects(name, action, message, unchanged=None):
        state = snapshot(unchanged) if unchanged else None
        try:
            action()
        except ValueError as error:
            assert message in str(error), (name, error)
        else:
            raise AssertionError('Expected rejection: ' + name)
        if unchanged:
            assert snapshot(unchanged) == state, name
        cases.append(name)

    rejects('original_requires_install', lambda: runtime_jar(pack), 'must be built', pack)
    install(pack, artifact)
    assert sha(runtime_jar(pack)) == pin['sha256']
    assert not (pack / 'mods' / pin['original_filename']).exists()
    assert {p.name for p in (pack / 'mods').glob('*.jar')} == {pin['filename']}
    state = snapshot(pack)
    install(pack, artifact)
    assert snapshot(pack) == state
    cases += ['verified_replacement', 'idempotent_install']

    # Interruption after publishing the derivative but before removing the base.
    shutil.copy2(original, pack / 'mods' / original.name)
    rejects('dual_identity_load_rejected', lambda: runtime_jar(pack), 'Interrupted', pack)
    install(pack, artifact)
    assert snapshot(pack) == state
    cases.append('dual_identity_install_resumed')

    # Interruption after replacing the JAR but before writing the receipt.
    (pack / RECEIPT).unlink()
    rejects('missing_receipt_load_rejected', lambda: runtime_jar(pack), 'receipt', pack)
    install(pack, artifact)
    assert snapshot(pack) == state
    cases.append('missing_receipt_install_resumed')

    (pack / RECEIPT).write_text('{}', encoding='utf-8')
    rejects('changed_receipt_load_rejected', lambda: runtime_jar(pack), 'receipt', pack)
    install(pack, artifact)
    assert snapshot(pack) == state
    cases.append('receipt_regenerated_from_verified_artifact')

    bad = run / 'unverified.jar'
    bad.write_bytes(b'not the pinned artifact')
    rejects('unverified_build_preserved_pack', lambda: install(pack, bad), 'SHA256 differs', pack)
    installed = pack / 'mods' / pin['filename']
    installed.write_bytes(b'unknown user modified artifact')
    rejects('modified_installed_jar_preserved', lambda: install(pack, artifact), 'SHA256 differs', pack)
    installed.write_bytes(artifact.read_bytes())
    (pack / 'mods' / original.name).write_bytes(b'unknown original artifact')
    rejects('modified_original_jar_preserved', lambda: install(pack, artifact), 'Original GTCEu SHA256', pack)
    (pack / 'mods' / original.name).unlink()

    # Separate source root tests cannot damage the live cache or source patch.
    root = run / 'root'
    for name in ('gtceu-derived-artifact.json', 'gtceu-kubejs72-build.json', 'artifacts.lock.json'):
        (root / 'locks').mkdir(exist_ok=True, parents=True)
        shutil.copy2(ROOT / 'locks' / name, root / 'locks' / name)
    patch = Path('work/compat-mod/patches/gtceu-kubejs72.json')
    (root / patch).parent.mkdir(parents=True)
    shutil.copy2(ROOT / patch, root / patch)
    cache = original_path(root)
    cache.parent.mkdir(parents=True)
    cache.write_bytes(b'changed cache')
    rejects('modified_original_cache_rejected', lambda: verify_derived(artifact, root), 'Original GTCEu SHA256', root)
    (root / patch).write_bytes(b'changed patch')
    rejects('modified_patch_rejected', lambda: pins(root), 'Changed GTCEu source patch', root)

    restored = verify_mod_inputs(ROOT, ROOT)
    assert restored['derived_gtceu_imported'] is False
    assert restored['derived_gtceu_requires_source_rebuild'] is True
    assert restored['upstream_mods_verified'] == 18
    cases.append('restore_mod_identity_verified')
    source = run / 'restore-source'
    destination = run / 'restore-target'
    destination.mkdir()
    for tree in TREES:
        (source / tree).mkdir(parents=True, exist_ok=True)
    for name in REBUILD_FILES | {'work/migration/gtceu-kubejs-bridge/test/derived.jar',
                                'work/cache/original-artifacts/retained.jar',
                                'work/inventory/source_inventory.json'}:
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'planner fixture; never executed')
    rows, preserved, skipped = build_plan(source.resolve(), destination.resolve(), set())
    copied = {src.relative_to(source.resolve()).as_posix() for src, _, _ in rows}
    assert copied == {'work/cache/original-artifacts/retained.jar', 'work/inventory/source_inventory.json'}
    assert REBUILD_FILES <= set(skipped)
    cases.append('restore_rebuild_outputs_skipped_original_cache_retained')

    after = snapshot(pack)
    for name, digest in before.items():
        if not name.startswith('mods/'):
            assert after[name] == digest
    assert sha(ROOT / 'locks/artifacts.lock.json') == original_lock
    assert sha(original) == pin['original_sha256']
    report = {'passed': True, 'cases': cases, 'case_count': len(cases),
              'original_download_lock_unchanged': True, 'original_jar_retained': True,
              'configuration_and_eula_unchanged': True, 'fixture_only': True,
              'derived_jar_sha256': pin['sha256'], 'full_parity': False,
              'source_sha256': {p: sha(ROOT / p) for p in (
                  'work/migration/test_gtceu_artifact.py', 'work/migration/gtceu_artifact.py',
                  'work/migration/install_gtceu_bridge.py', 'work/migration/restore_local_checkpoint.py',
                  'locks/gtceu-derived-artifact.json')}}
    encoded = json.dumps(report, indent=2) + '\n'
    (run / 'report.json').write_text(encoded, encoding='utf-8', newline='\n')
    (ROOT / 'evidence/GTCEuArtifactCheck.json').write_text(encoded, encoding='utf-8', newline='\n')
    print(json.dumps(report))


if __name__ == '__main__':
    main()
