"""Exercise resource-proof failures using the real pinned sources and isolated outputs."""
from pathlib import Path
import hashlib
import json
import shutil
import tempfile
import unittest
import zipfile

import port_exact_ore_resources as porter
import verify_exact_ore_resources as verifier

ROOT = Path(__file__).resolve().parents[2]
REAL_OUTPUT = ROOT / porter.OUTPUT
MAPPING = porter.MAPPING
REBASE = porter.REPORT
BASE = 'assets/gtceu/textures/block/material_sets/fine/ore_small.png'
LAYER = 'assets/gtceu/textures/block/material_sets/fine/ore_small_layer2.png'


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


class OreResourceProofTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='ore-resource-proof-', dir=ROOT / 'work')
        self.output = Path(self.temp.name).resolve()
        # TemporaryDirectory removes this tree at cleanup; prove it stays in the scratch workspace.
        self.assertTrue(self.output.is_relative_to((ROOT / 'work').resolve()))
        self.addCleanup(self.temp.cleanup)
        shutil.copytree(REAL_OUTPUT / porter.PACK, self.output / porter.PACK)
        for rel in (MAPPING, REBASE):
            (self.output / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(REAL_OUTPUT / rel, self.output / rel)

    def verify(self):
        return verifier.verify(ROOT, self.output)

    def test_real_archive_to_target_and_idempotent_port(self):
        report = self.verify()
        self.assertEqual(report['primary_base_pngs_verified_from_archive_through_target'], 34)
        self.assertEqual(report['reference_byte_different_count'], 7)
        self.assertEqual(report['reference_rgba_pixel_identical_count'], 34)
        before = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
        porter.port(ROOT, self.output)
        self.assertEqual(before, {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()})
        self.verify()

    def test_missing_mapping_row_cannot_shrink_expected_scope(self):
        mapping = read_json(self.output / MAPPING)
        mapping['ore_mappings'] = [r for r in mapping['ore_mappings'] if r['target_path'] != BASE]
        mapping['ore_png_files'] -= 1
        save_json(self.output / MAPPING, mapping)
        rebase = read_json(self.output / REBASE)
        rebase['comparisons'] = [r for r in rebase['comparisons'] if r['target_path'] != BASE]
        rebase['source_base_png_files'] -= 1
        save_json(self.output / REBASE, rebase)
        with self.assertRaisesRegex(ValueError, 'distinguish original|mapping paths'):
            self.verify()

    def test_pixel_equal_2_8_4_fallback_rejected_even_with_forged_manifest(self):
        reference = ROOT / 'work/source-release-2.8.4/GT New Horizons 2.8.4/.minecraft/mods/gregtech-5.09.51.482.jar'
        with zipfile.ZipFile(reference) as archive:
            old_bytes = archive.read('assets/gregtech/textures/blocks/materialicons/FINE/oreSmall.png')
        actual = self.output / porter.PACK / BASE
        self.assertNotEqual(old_bytes, actual.read_bytes())
        self.assertEqual(verifier.rgba(old_bytes), verifier.rgba(actual.read_bytes()))
        actual.write_bytes(old_bytes)
        mapping = read_json(self.output / MAPPING)
        row = next(r for r in mapping['ore_mappings'] if r['target_path'] == BASE)
        row['source_sha256'] = row['target_sha256'] = verifier.digest(old_bytes)
        save_json(self.output / MAPPING, mapping)
        with self.assertRaisesRegex(ValueError, 'Target PNG bytes differ from primary source'):
            self.verify()

    def test_unexpected_animation_sidecar_rejected_and_rebase_restores_absence(self):
        sidecar = self.output / porter.PACK / (BASE + '.mcmeta')
        sidecar.write_text('{"animation":{"frametime":1}}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Target animation presence differs'):
            self.verify()
        porter.port(ROOT, self.output)
        self.assertFalse(sidecar.exists())
        self.verify()

    def test_visible_secondary_rejected_even_when_manifest_hash_matches(self):
        image = (self.output / porter.PACK / BASE).read_bytes()
        (self.output / porter.PACK / LAYER).write_bytes(image)
        mapping = read_json(self.output / MAPPING)
        next(r for r in mapping['ore_mappings'] if r['target_path'] == LAYER)['target_sha256'] = verifier.digest(image)
        save_json(self.output / MAPPING, mapping)
        with self.assertRaisesRegex(ValueError, 'reviewed transparent bytes'):
            self.verify()

    def test_derived_layer_cannot_claim_source_copy(self):
        mapping = read_json(self.output / MAPPING)
        row = next(r for r in mapping['ore_mappings'] if r['target_path'] == LAYER)
        row['source_bytes_copied'] = True
        row['source_sha256'] = row['target_sha256']
        save_json(self.output / MAPPING, mapping)
        with self.assertRaisesRegex(ValueError, 'Derived image must not be represented as copied source'):
            self.verify()

    def test_unknown_existing_base_aborts_before_any_output_write(self):
        (self.output / porter.PACK / BASE).write_bytes(b'unreviewed user modification')
        before = {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()}
        with self.assertRaisesRegex(ValueError, 'Unreviewed existing base modification'):
            porter.port(ROOT, self.output)
        self.assertEqual(before, {p.relative_to(self.output): p.read_bytes() for p in self.output.rglob('*') if p.is_file()})

    def test_extra_resource_is_not_hidden_by_manifest_counts(self):
        extra = self.output / porter.PACK / 'assets/gtceu/textures/block/material_sets/fine/unreviewed.png'
        extra.write_bytes((self.output / porter.PACK / BASE).read_bytes())
        with self.assertRaisesRegex(ValueError, 'Unexpected or missing resource files'):
            self.verify()


if __name__ == '__main__':
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(OreResourceProofTest)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    report = {
        'passed': result.wasSuccessful(), 'tests_run': result.testsRun,
        'production_sources_or_asset_outputs_mutated_by_tests': False,
        'test_cases': unittest.defaultTestLoader.getTestCaseNames(OreResourceProofTest),
        'test_source_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'porter_source_sha256': hashlib.sha256(Path(porter.__file__).read_bytes()).hexdigest(),
        'verifier_source_sha256': hashlib.sha256(Path(verifier.__file__).read_bytes()).hexdigest(),
        'scope': 'Real-source static verification and isolated output mutation tests; not Minecraft rendering or world validation.',
    }
    destination = REAL_OUTPUT / 'docs/verification/exact-ore-resource-tests.json'
    destination.parent.mkdir(parents=True, exist_ok=True)
    save_json(destination, report)
    print(json.dumps(report))
    raise SystemExit(0 if result.wasSuccessful() else 1)
