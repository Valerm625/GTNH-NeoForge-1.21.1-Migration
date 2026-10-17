"""Exercise real ZIP integrity and extraction-risk boundaries using synthetic data."""
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

from audit_source_archives import AuditError, ROOT, audit_archive


def make_zip(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_STORED) as z:
        for name, payload in entries:
            z.writestr(name, payload)
    return output.getvalue()


def corrupt_entry_payload(data, filename):
    result = bytearray(data)
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        info = z.getinfo(filename)
        name_len, extra_len = struct.unpack_from('<HH', data, info.header_offset + 26)
        payload_offset = info.header_offset + 30 + name_len + extra_len
        result[payload_offset] ^= 1
    return bytes(result)


class SourceArchiveAuditBoundaries(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='audit-tests-', dir=ROOT / 'work')
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def run_audit(self, payload, **kwargs):
        path = self.root / 'synthetic.zip'
        path.write_bytes(payload)
        return audit_archive(path, 'pack/', **kwargs)

    def test_identical_case_alias_is_reported_without_merging_source_entries(self):
        report = self.run_audit(make_zip([('pack/config/Name.lang', b'value'), ('pack/config/name.lang', b'value')]))
        self.assertEqual(report['summary']['archive_file_entries'], 2)
        self.assertEqual(len(report['summary']['case_insensitive_collisions']), 1)
        self.assertTrue(report['summary']['case_insensitive_collisions'][0]['identical_bytes'])
        self.assertEqual(len(report['assets']), 2)

    def test_different_bytes_under_case_alias_are_rejected(self):
        with self.assertRaisesRegex(AuditError, 'Conflicting case-insensitive'):
            self.run_audit(make_zip([('pack/a.txt', b'one'), ('pack/A.txt', b'two')]))

    def test_outer_crc_corruption_is_rejected(self):
        corrupted = corrupt_entry_payload(make_zip([('pack/config/data.txt', b'original')]), 'pack/config/data.txt')
        with self.assertRaisesRegex(zipfile.BadZipFile, 'CRC'):
            self.run_audit(corrupted)

    def test_nested_crc_corruption_is_rejected_even_when_outer_crc_is_valid(self):
        nested = corrupt_entry_payload(make_zip([('assets/test/example.png', b'original')]), 'assets/test/example.png')
        with self.assertRaisesRegex(zipfile.BadZipFile, 'CRC'):
            self.run_audit(make_zip([('pack/mods/example.jar', nested)]))

    def test_unsafe_paths_are_rejected_without_extraction(self):
        for name in ('pack/../escape.txt', '/absolute.txt', 'C:/drive.txt', 'pack\\..\\escape.txt'):
            with self.subTest(name=name), self.assertRaisesRegex(AuditError, 'Unsafe ZIP path'):
                self.run_audit(make_zip([(name, b'value')]))

    def test_hash_pinning_and_expansion_budget_are_enforced(self):
        data = make_zip([('pack/config/test.txt', b'12345')])
        with self.assertRaisesRegex(AuditError, 'SHA-256'):
            self.run_audit(data, expected_sha256='0' * 64)
        with self.assertRaisesRegex(AuditError, 'Expanded byte budget'):
            self.run_audit(data, max_expanded_bytes=4)

    def test_duplicate_quest_ids_do_not_silently_reduce_task_count(self):
        quest = json.dumps({'questIDHigh:4': 0, 'questIDLow:4': 1, 'tasks:9': {}, 'rewards:9': {}}).encode()
        with self.assertRaisesRegex(AuditError, 'Duplicate quest identity'):
            self.run_audit(make_zip([
                ('pack/config/betterquesting/DefaultQuests/Quests/one.json', quest),
                ('pack/config/betterquesting/DefaultQuests/Quests/two.json', quest),
            ]))

    def test_nested_archive_depth_limit_fails_instead_of_silently_skipping_assets(self):
        nested = make_zip([('assets/test/example.png', b'original')])
        with self.assertRaisesRegex(AuditError, 'depth limit'):
            self.run_audit(make_zip([('pack/mods/example.jar', nested)]), max_depth=0)


if __name__ == '__main__':
    unittest.main()
