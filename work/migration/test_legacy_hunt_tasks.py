"""Boundary tests against the separate hunt-task conversion entry point."""
import unittest
from legacy_hunt_tasks import convert_hunt


class HuntConversionTest(unittest.TestCase):
    def source(self, **overrides):
        return {'target:8': 'Cow', 'required:3': 6, 'ignoreNBT:1': 1,
                'subtypes:1': 1, 'damageType:8': '', **overrides}

    def test_preserves_both_matching_modes(self):
        for flag, marker in [(0, 'exact'), (1, 'subtypes')]:
            result=convert_hunt(self.source(**{'subtypes:1': flag}), 'task')
            self.assertEqual(result['tags'], ['gtnh_legacy_hunt_'+marker])
            self.assertEqual(result['entity'], 'minecraft:cow')
            self.assertEqual(result['value'], 6)

    def test_absent_subtypes_has_legacy_false_default(self):
        task=self.source(); del task['subtypes:1']
        self.assertEqual(convert_hunt(task,'task')['tags'], ['gtnh_legacy_hunt_exact'])

    def test_ignored_nbt_is_not_accidentally_reintroduced(self):
        task=self.source(**{'targetNBT:10': {'CustomName:8': 'named', 'Health:4': 1}})
        result=convert_hunt(task,'task')
        self.assertEqual(result['custom_name'], '')
        self.assertEqual(result['nbt_filter'], '')

    def test_unimplemented_predicates_rejected(self):
        for change in [{'ignoreNBT:1':0},{'damageType:8':'fire'}, {'target:8':'TwilightForest.Naga'}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                convert_hunt(self.source(**change),'task')

    def test_invalid_counts_and_flags_rejected(self):
        for change in [{'required:3':0},{'required:3':-1},{'required:3':2147483648},
                       {'required:3':True},{'required:3':1.5},{'subtypes:1':2}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                convert_hunt(self.source(**change),'task')


if __name__=='__main__':
    unittest.main()
