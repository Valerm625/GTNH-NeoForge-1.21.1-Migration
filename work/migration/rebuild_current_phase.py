"""Rebuild current authored migration outputs, preserve EULA and avoid stale 2.8.4 generators."""
from pathlib import Path
import subprocess,sys,json
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).parent
steps=['port_exact_core_items.py','port_exact_core_recipes.py','port_core_integrations.py','port_wooden_form.py','port_extra_localization.py','port_fuel_blocks.py','port_railcraft_coke.py','material_catalog.py','port_material_bridges.py','port_soldering_alloy.py','generate_material_runtime_audit.py','verify_material_mapping.py','verify_soldering_alloy.py','verify_vanilla_identity_mapping.py','add_quest_parity_adapter.py','build_capacity_compat.py','export_supported_quests.py','verify_crafting_adapter_contract.py','verify_choice_reward_contract.py','verify_hunt_adapter_contract.py','test_quest_export_java.py','verify_quest_checkpoint.py','verify_capacity_compat.py','verify_ender_config.py','verify_long_fuels.py','verify_fuel_blocks.py','verify_exact_source.py','verify_migration.py','test_generated_regressions.py','document_ender_differences.py','stage_test_runtime.py','write_material_phase_status.py','write_quest_checkpoint_status.py']
for step in steps:
    print('RUN',step,flush=True)
    subprocess.run([sys.executable,'-u',str(HERE/step)],cwd=ROOT,check=True)
print('CURRENT_PHASE_REBUILD_COMPLETE_FULL_PARITY_STILL_FALSE',flush=True)
