"""Adversarial fixtures for the read-only source resource collision ledger."""
from pathlib import Path
import hashlib
import json
import tempfile
import unittest
import warnings
import zipfile
import audit_exact_resource_collisions as audit


class ResourceCollisionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / 'source.zip'
        self.tree = self.root / 'source'
        self.relative = 'src/main/resources/assets/example/textures/ore.png'
        self.resource = self.tree / self.relative
        self.resource.parent.mkdir(parents=True)
        self.resource.write_bytes(b'archive-original')
        with zipfile.ZipFile(self.archive, 'w') as z:
            z.writestr('repo-commit/' + self.relative, b'archive-original')
        self.expected = hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def scan(self):
        return audit.scan_archive(self.archive, self.tree, 'example/1', self.expected)

    def test_same_path_candidates_remain_even_when_bytes_equal(self):
        one = self.scan()[0]
        two = dict(one, source='second/2')
        conflict, aliases = audit.group_collisions([two, one])
        self.assertEqual(len(conflict), 1)
        self.assertEqual(len(conflict[0]['candidates']), 2)
        self.assertEqual(conflict[0]['distinct_content_hashes'], 1)
        self.assertIsNone(conflict[0]['selected_source'])
        self.assertEqual(conflict[0]['precedence'], 'unknown')
        self.assertEqual(aliases, [])
        different = dict(two, sha256='f'*64)
        self.assertEqual(audit.group_collisions([one, different])[0][0]['distinct_content_hashes'], 2)

    def test_missing_or_changed_extraction_does_not_pass_archive_hash_alone(self):
        self.resource.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing extracted'):
            self.scan()
        self.resource.write_bytes(b'local-edit')
        with self.assertRaisesRegex(ValueError, 'differs'):
            self.scan()

    def test_unlisted_extracted_asset_is_not_silently_omitted(self):
        self.resource.with_name('extra.png').write_bytes(b'extra')
        with self.assertRaisesRegex(ValueError, 'Unlisted extracted'):
            self.scan()

    def test_changed_archive_rejected_before_resource_reads(self):
        with self.archive.open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(ValueError, 'Archive hash mismatch'):
            self.scan()

    def test_duplicate_archive_entry_not_last_writer_wins(self):
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(self.archive, 'a') as z:
                z.writestr('repo-commit/' + self.relative, b'archive-original')
        self.expected = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'Duplicate archive entry'):
            self.scan()

    def test_case_aliases_not_collapsed_into_exact_path_group(self):
        one = self.scan()[0]
        two = dict(one, source='second/2', resource_path=one['resource_path'].replace('ore', 'Ore'))
        conflicts, aliases = audit.group_collisions([two, one])
        self.assertEqual(conflicts, [])
        self.assertEqual(len(aliases), 1)
        self.assertEqual(len(aliases[0]['exact_spellings']), 2)
        self.assertEqual(len(aliases[0]['candidates']), 2)

    def test_path_traversal_entry_rejected_without_extraction(self):
        with zipfile.ZipFile(self.archive, 'a') as z:
            z.writestr('repo-commit/src/main/resources/assets/../../escape.txt', b'data')
        self.expected = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'Noncanonical archive path'):
            self.scan()
        self.assertFalse((self.root / 'escape.txt').exists())


if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(ResourceCollisionTests))
    report = {'passed': result.wasSuccessful(), 'tests_run': result.testsRun,
              'test_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'auditor_source_sha256': hashlib.sha256(Path(audit.__file__).read_bytes()).hexdigest(),
              'scope': 'Synthetic temporary archives and extracted-tree mutations; real-source inventory is separately hash-bound.',
              'production_assets_modified': False}
    (audit.ROOT / 'evidence/exact-resource-collision-tests.json').write_text(
        json.dumps(report, indent=2) + '\n', encoding='utf-8', newline='\n')
    print(json.dumps(report))
    raise SystemExit(0 if result.wasSuccessful() else 1)
