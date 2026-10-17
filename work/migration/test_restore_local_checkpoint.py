"""Recovery invariants: preserve edits, reject unsafe paths, verify locked bytes."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import restore_local_checkpoint as recovery


class RestoreSafetyTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.source = self.base / 'source'
        self.target = self.base / 'target'
        self.source.mkdir()
        self.target.mkdir()

    def file(self, root, name, data=b'original'):
        p = root / name
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(data)
        return p

    def test_changed_destination_is_not_overwritten_or_partially_restored(self):
        self.file(self.source, 'work/data/new.txt')
        self.file(self.source, 'work/data/edited.txt')
        changed = self.file(self.target, 'work/data/edited.txt', b'user edit')
        with patch.object(recovery, 'TREES', ('work/data',)):
            with self.assertRaisesRegex(recovery.RestoreError, 'differs'):
                recovery.build_plan(self.source, self.target, set())
        self.assertEqual(changed.read_bytes(), b'user edit')
        self.assertFalse((self.target / 'work/data/new.txt').exists())

    def test_tracked_script_is_preserved_even_when_source_differs(self):
        self.file(self.source, 'work/data/current.py', b'old script')
        changed = self.file(self.target, 'work/data/current.py', b'new script')
        self.file(self.source, 'work/inventory/source_inventory.json', b'{}')
        with patch.object(recovery, 'TREES', ('work/data',)):
            rows, preserved, _ = recovery.build_plan(self.source, self.target, {'work/data/current.py'})
        self.assertEqual(preserved, ['work/data/current.py'])
        self.assertFalse(any(dst == changed for _, dst, _ in rows))
        self.assertEqual(changed.read_bytes(), b'new script')

    def test_exclusive_copy_handles_a_destination_created_after_preflight(self):
        src = self.file(self.source, 'input', b'checkpoint')
        dst = self.file(self.target, 'output', b'concurrent user edit')
        with self.assertRaises(FileExistsError):
            recovery.copy_exclusive(src, dst)
        self.assertEqual(dst.read_bytes(), b'concurrent user edit')

    def test_generated_local_adapter_is_excluded_for_a_fresh_source_build(self):
        relative = 'work/neoforge-smoke/mods/' + recovery.GENERATED_COMPAT
        self.file(self.source, relative, b'historical build')
        self.file(self.source, 'work/inventory/source_inventory.json', b'{}')
        with patch.object(recovery, 'TREES', ('work/neoforge-smoke',)):
            rows, _, skipped = recovery.build_plan(self.source, self.target, set())
        self.assertIn(relative, skipped)
        self.assertFalse(any(dst.name == recovery.GENERATED_COMPAT for _, dst, _ in rows))

    def test_tampered_lock_input_is_rejected(self):
        file = self.file(self.source, 'mod.jar')
        locked = recovery.digest(file)
        file.write_bytes(b'tampered')
        with self.assertRaisesRegex(recovery.RestoreError, 'mismatch'):
            recovery.require_digest(file, locked)

    def test_traversal_and_absolute_destinations_are_rejected(self):
        for value in ('../outside', 'nested/../../outside', 'C:/outside', '/outside'):
            with self.subTest(value=value), self.assertRaises(recovery.RestoreError):
                recovery.safe_path(self.target, value)

    def test_link_destination_is_rejected(self):
        link = self.target / 'redirect'
        try:
            link.symlink_to(self.source, target_is_directory=True)
        except OSError:
            self.skipTest('Creating symlinks requires an unavailable Windows privilege')
        with self.assertRaises(recovery.RestoreError):
            recovery.safe_path(self.target, 'redirect/file')

    def test_source_archive_is_checked_against_consumed_extracted_tree(self):
        archive = self.base / 'source.zip'
        with zipfile.ZipFile(archive, 'w') as stream:
            stream.writestr('project-commit/src/Main.java', b'locked source')
        tree = self.source / 'extracted'
        main = self.file(tree, 'src/Main.java', b'locked source')
        self.assertEqual(recovery.verify_source_archive(archive, tree), 1)
        main.write_bytes(b'edited source')
        with self.assertRaises(recovery.RestoreError):
            recovery.verify_source_archive(archive, tree)

    def test_runtime_rebase_does_not_preserve_old_success_as_a_new_probe(self):
        java = self.file(self.source, 'work/runtime/jdk/bin/java.exe')
        old = {'java': str(java), 'java_home': str(java.parent.parent), 'probe': {'returncode': 0}}
        config = self.file(self.source, 'work/migration/runtime.json', json.dumps(old).encode())
        original = config.read_bytes()
        new = recovery.runtime_configuration(self.source, self.target)
        self.assertEqual(new['java'], str(self.target / 'work/runtime/jdk/bin/java.exe'))
        self.assertFalse(new['probe']['executed_after_restore'])
        self.assertFalse(new['runtime_verified_after_restore'])
        self.assertEqual(config.read_bytes(), original)

    def test_overlapping_roots_fail_before_any_validation_or_copy(self):
        with self.assertRaisesRegex(recovery.RestoreError, 'non-overlapping'):
            recovery.restore(self.source, self.source / 'nested')


if __name__ == '__main__':
    unittest.main()
