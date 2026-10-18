"""Rebuild current authored migration outputs, preserve EULA and avoid stale 2.8.4 generators."""
from pathlib import Path
import subprocess,sys,json
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).parent
steps=['port_exact_core_items.py','port_exact_core_recipes.py','port_core_integrations.py','port_wooden_form.py','port_extra_localization.py','port_fuel_blocks.py','port_railcraft_coke.py','material_catalog.py','port_material_bridges.py','port_soldering_alloy.py','generate_material_runtime_audit.py','verify_material_mapping.py','verify_soldering_alloy.py','verify_vanilla_identity_mapping.py','add_quest_parity_adapter.py','build_capacity_compat.py','loader_probe.py','test_legacy_retrieval_matcher.py','test_legacy_hunt_tasks.py','test_hunt_adapter_java.py','export_supported_quests.py','test_quest_export_java.py','verify_crafting_adapter_contract.py','verify_choice_reward_contract.py','verify_hunt_adapter_contract.py','verify_location_task_boundary.py','verify_quest_checkpoint.py','verify_capacity_compat.py','verify_ender_config.py','verify_long_fuels.py','verify_fuel_blocks.py','verify_exact_source.py','verify_migration.py','test_generated_regressions.py','document_ender_differences.py','stage_test_runtime.py','write_material_phase_status.py','write_quest_checkpoint_status.py','write_task_semantics_status.py']
steps.insert(steps.index('test_legacy_retrieval_matcher.py'), 'test_legacy_retrieval_engine.py')
steps.insert(steps.index('test_legacy_retrieval_engine.py'), 'test_isolated_mixin_transformation.py')
steps.insert(steps.index('test_legacy_retrieval_engine.py'), 'test_native_save_scope_mixins.py')
steps.insert(steps.index('test_legacy_retrieval_engine.py'), 'test_legacy_retrieval_save_scope.py')
steps.insert(steps.index('material_catalog.py'), 'port_exact_ore_resources.py')
for step in ['audit_exact_resource_collisions.py', 'test_exact_resource_collisions.py']:
    steps.insert(steps.index('material_catalog.py'), step)
for step in ['test_legacy_retrieval_progress_codec.py', 'test_legacy_quest_retrieval_planner.py', 'test_legacy_inventory_observer.py']:
    steps.insert(steps.index('test_legacy_retrieval_matcher.py'), step)
for step in ['test_legacy_typed_nbt_json_reader.py', 'test_legacy_quest_progress_envelope.py', 'test_legacy_quest_progress_store.py', 'test_legacy_progress_import_pipeline.py']:
    steps.insert(steps.index('test_legacy_retrieval_matcher.py'), step)
steps.insert(steps.index('test_legacy_retrieval_matcher.py'), 'test_legacy_retrieval_inventory_bridge.py')
for step in ['test_legacy_retrieval_transaction_journal.py', 'test_legacy_retrieval_transaction_proposal.py', 'test_legacy_player_data_file_reader.py', 'test_legacy_retrieval_commit_coordinator.py', 'test_legacy_world_player_authority.py', 'test_legacy_integrated_owner_coordinator.py', 'test_legacy_quest_world_store.py', 'test_legacy_quest_player_admission.py', 'test_legacy_player_marker_upgrade.py', 'test_legacy_owner_transaction_recovery.py', 'test_legacy_startup_shutdown.py', 'test_legacy_maintenance_initialization.py']:
    steps.insert(steps.index('test_legacy_retrieval_matcher.py'), step)
for step in ['verify_exact_ore_resources.py', 'test_exact_ore_resources.py']:
    steps.insert(steps.index('verify_migration.py'), step)
steps.insert(steps.index('build_capacity_compat.py'), 'build_quest_definition_archive.py')
steps.insert(steps.index('test_legacy_retrieval_matcher.py'), 'test_legacy_quest_definition_archive.py')
steps.insert(steps.index('test_legacy_retrieval_matcher.py'), 'test_legacy_retrieval_definition_binding.py')
steps.insert(steps.index('test_legacy_retrieval_matcher.py'), 'test_legacy_quest_entry_policy.py')
steps.insert(steps.index('test_legacy_retrieval_matcher.py'), 'test_legacy_quest_completion_planner.py')
steps.insert(steps.index('stage_test_runtime.py'), 'test_native_registry_datagen.py')
steps.insert(steps.index('stage_test_runtime.py'), 'test_native_startup_registration.py')
for step in steps:
    print('RUN',step,flush=True)
    subprocess.run([sys.executable,'-u',str(HERE/step)],cwd=ROOT,check=True)
print('CURRENT_PHASE_REBUILD_COMPLETE_FULL_PARITY_STILL_FALSE',flush=True)
