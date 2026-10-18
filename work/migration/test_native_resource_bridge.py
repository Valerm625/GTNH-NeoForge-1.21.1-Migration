"""Real resource reload of the derived GTCEu bridge, including failing controls.

No server/world/player instances. All runs use private copied mods/config/scripts;
the installed Foundation pack remains unchanged during testing. This does not prove machine
execution, rendering, complete GTNH recipes, or complete pack migration.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import shutil
import subprocess
import uuid
import zipfile
from build_gtceu_kubejs_bridge import build, classpath, R, O, OUT, PATCH, read, save
from gtceu_artifact import runtime_jar, original_jar


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def arguments(path, values, compiler=False):
    path.write_text("\n".join(json.dumps(v, ensure_ascii=False) for v in values),
                    encoding="utf-8" if compiler or os.name != "nt" else "mbcs")


def snapshot():
    return {p.relative_to(O).as_posix(): sha(p) for folder in ("mods", "config", "kubejs")
            for p in sorted((O / folder).rglob("*")) if p.is_file()}


def check_dump(dump):
    assert dump["reload_listener_events"] == dump["tag_update_events"] == 1
    assert dump["server_instance_present"] is False
    assert len(dump["component_round_trips"]) == 5
    assert dump["boost_round_trips"] == 18
    rows = dump["recipes"]
    migrated = {k: v for k, v in rows.items() if k.startswith("gtnh_migration:") and v["type"].startswith("gtceu:")}
    assert len(migrated) == 167
    assert all(v.get("outputs") for v in migrated.values()), "Migration machine recipes lost outputs"
    assert len([k for k in rows if k.startswith("gtnh_migration:")]) == 170
    boots = rows["gtnh_migration:assembler/core/assembler/quantumpartboots_4ad7ecdd5c"]
    assert boots["duration"] == 1200 and boots["tickInputs"]["eu"] == [{"content": 1920}]
    assert [v["content"]["count"] for v in boots["inputs"]["item"]] == [1, 4]
    assert boots["outputs"]["item"] == [item("gtnh_migration:quantumpartboots", 1)]

    directions = rows["gtnh_audit:assembler/directions"]
    assert directions["duration"] == 123
    assert directions["inputs"]["item"] == [item("minecraft:iron_ingot", 2), item("minecraft:stick", 1, chance=0)]
    assert directions["outputs"]["item"] == [item("minecraft:gold_ingot", 3), item("minecraft:diamond", 2, chance=2500, tierChanceBoost=750)]
    assert directions["inputs"]["fluid"] == [fluid("minecraft:water", 250)]
    assert directions["outputs"]["fluid"] == [fluid("minecraft:lava", 500), fluid("minecraft:water", 100, chance=4000, tierChanceBoost=200)]
    assert directions["tickInputs"] == {"eu": [{"content": {"voltage": 32, "amperage": 2}}],
                                        "item": [item("minecraft:redstone", 1)], "fluid": [fluid("minecraft:water", 10)]}
    assert directions["tickOutputs"] == {"item": [item("minecraft:glowstone_dust", 2)], "fluid": [fluid("minecraft:lava", 20)]}
    assert directions["recipeConditions"] == [{"dimension": "minecraft:overworld", "type": "gtceu:dimension"}]
    generator = rows["gtnh_audit:assembler/generator"]
    assert "tickInputs" not in generator
    assert generator["tickOutputs"] == {"eu": [{"content": {"voltage": 128, "amperage": 2}}]}
    fraction = rows["gtnh_audit:assembler/fraction"]
    assert fraction["outputs"]["item"] == [item("minecraft:diamond", 1, chance=3333, maxChance=9999, tierChanceBoost=300), item("minecraft:gold_ingot", 1)]
    replacement = rows["gtnh_audit:assembler/replacement"]
    assert replacement["inputs"]["item"] == [item("minecraft:copper_ingot", 4)]
    assert replacement["outputs"]["item"] == [item("minecraft:emerald", 2, chance=2500, tierChanceBoost=750)]
    shaped = rows["gtnh_audit:tool_shaped"]
    assert shaped["type"] == "kubejs:shaped" and shaped["kubejs:mirror"] is False
    assert shaped["pattern"] == ["hI"] and shaped["key"]["h"] == {"tag": "gtceu:crafting_tools/hammer"}
    expected_fuels = {v["target_id"]: v["burn_ticks"] for v in read(O / "docs/fuel-block-migration/fuel-blocks.json")["blocks"]}
    assert len(expected_fuels) == 16 and dump["fuels"] == expected_fuels
    return {"migration_machine_recipes_with_outputs": len(migrated), "migration_namespace_recipes": 170,
            "fixture_recipes": 5, "fuel_values_verified": 16, "component_round_trips": 5,
            "boost_copy_codec_network_round_trips": 18,
            "ordinary_and_tick_directions": True, "chance_and_fraction_preserved": True,
            "replacement_counts_and_chance_preserved": True, "tool_symbol_and_no_mirror": True}


def item(name, count, **extra):
    return {"content": {"ingredient": {"item": name}, "count": count}, **extra}


def fluid(name, amount, **extra):
    return {"content": {"ingredient": {"fluid": name}, "amount": amount}, **extra}


def main():
    before = snapshot()
    eula = (O / "eula.txt").read_bytes()
    assert re.search(rb"(?m)^eula=false\s*$", eula)
    positive = build()
    installed = runtime_jar(O)
    assert sha(installed) == positive["jar_sha256"], "Installed artifact differs from fresh source rebuild"
    negative = build(old_output_control=True)
    java = Path(read(R / "work/migration/runtime.json")["java"])
    run = OUT / ("resource-check-" + str(uuid.uuid4()))
    classes = run / "classes"
    classes.mkdir(parents=True)
    sources = [R / "work/migration" / n for n in ("NativeResourceAuditLaunchHandler.java", "NativeResourceAuditMain.java")]
    cp = os.pathsep.join(p.resolve().as_posix() for p in classpath())
    argfile = run / "compile.args"
    arguments(argfile, ["-proc:none", "--release", "21", "-encoding", "UTF-8", "-cp", cp,
                        "-d", str(classes)] + [str(p) for p in sources], True)
    result = subprocess.run([str(java.with_name("javac.exe")), "@" + str(argfile)], capture_output=True, timeout=60)
    assert result.returncode == 0, result.stderr.decode(errors="replace")
    launcher = run / "resource-audit-launcher.jar"
    with zipfile.ZipFile(launcher, "w") as output:
        name = "org/gtnhmigration/audit/launcher/NativeResourceAuditLaunchHandler.class"
        output.write(classes / name, name)
        output.writestr("META-INF/services/cpw.mods.modlauncher.api.ILaunchHandlerService", "org.gtnhmigration.audit.launcher.NativeResourceAuditLaunchHandler\n")
    testmod = run / "resource-audit.jar"
    with zipfile.ZipFile(testmod, "w") as output:
        name = "org/gtnhmigration/startupaudit/NativeResourceAuditMain.class"
        output.write(classes / name, name)
        output.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nAutomatic-Module-Name: gtnh_startup_audit\n\n")
        output.writestr("META-INF/neoforge.mods.toml", 'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="MIT"\n[[mods]]\nmodId="gtnh_startup_audit"\nversion="0.1.0"\ndisplayName="Resource reload audit"\n')
    launch_text = (O / "libraries/net/neoforged/neoforge/21.1.262/win_args.txt").read_text(encoding="utf-8")
    cases = {}
    details = None
    for case, bridge in (("ported", positive), ("old_output", negative), ("original_api", None)):
        directory = run / case
        directory.mkdir()
        for folder in ("mods", "config", "kubejs"):
            shutil.copytree(O / folder, directory / folder)
        if case != "ported":
            # The positive case uses the installed pack unchanged. Only negative
            # fixtures replace its verified copy with the corresponding control.
            copied = directory / "mods" / installed.name
            assert copied.resolve().is_relative_to(run.resolve()) and sha(copied) == sha(installed)
            copied.unlink()
            control = R / bridge["jar"] if bridge else original_jar()
            shutil.copy2(control, directory / "mods" / control.name)
        shutil.copy2(testmod, directory / "mods" / testmod.name)
        shutil.copy2(R / "work/migration/KubeJSBridgeFixtures.js", directory / "kubejs/server_scripts/99_gtnh_bridge_audit.js")
        replacement = directory / "kubejs/data/gtnh_audit/recipe/assembler/replacement.json"
        replacement.parent.mkdir(parents=True, exist_ok=True)
        save(replacement, {"type": "gtceu:assembler", "duration": 60,
                           "inputs": {"item": [item("minecraft:iron_ingot", 4)]},
                           "outputs": {"item": [item("minecraft:diamond", 2, chance=2500, tierChanceBoost=750)]},
                           "tickInputs": {"eu": [{"content": 8}]}})
        (directory / "eula.txt").write_bytes(eula)
        options = ["-Xms512M", "-Xmx4G"]
        for line in launch_text.splitlines():
            if line.startswith("-p "):
                options += ["-p", ";".join((O / n).as_posix() for n in line[3:].split(";"))]
            elif line.startswith("-DlegacyClassPath="):
                legacy = ";".join((O / n).as_posix() for n in line.split("=", 1)[1].split(";")) + ";" + launcher.as_posix()
                options += ["-DlegacyClassPath=" + legacy, "-cp", legacy]
            elif line.startswith("-DlibraryDirectory="):
                options += ["-DlibraryDirectory=" + (O / "libraries").as_posix()]
            elif line.startswith("--launchTarget"):
                options += ["--launchTarget", "gtnhresourceaudit"]
            elif line.startswith("--"):
                options += line.split()
            else:
                options.append(line)
        options += ["--gameDir", "."]
        arguments(directory / "launch.args", options)
        with (directory / "launch.log").open("wb") as log:
            result = subprocess.run([str(java), "@launch.args"], cwd=directory, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        log = (directory / "launch.log").read_text(encoding="utf-8", errors="replace")
        assert (directory / "eula.txt").read_bytes() == eula
        assert not list(directory.rglob("level.dat"))
        entry = {"exit": result.returncode, "log_sha256": sha(directory / "launch.log")}
        if case == "original_api":
            assert result.returncode != 0 and "dev/latvian/mods/kubejs/bindings/event/ServerEvents" in log
            entry["rejected_missing_old_server_events_api"] = True
        else:
            assert result.returncode == 0 and "GTNH_RESOURCE_PROBE_COMPLETE_NO_WORLD" in log
            assert "/ERROR]" not in log and "Error in 'ServerEvents" not in log
            dump = read(directory / "resource-dump.json")
            entry["dump_sha256"] = sha(directory / "resource-dump.json")
            if case == "ported":
                details = check_dump(dump)
                entry["passed_semantic_checks"] = True
            else:
                try:
                    check_dump(dump)
                except AssertionError as error:
                    assert str(error) == "Migration machine recipes lost outputs", str(error)
                    entry["rejected_missing_machine_outputs"] = True
                else:
                    raise AssertionError("Old output direction unexpectedly passed")
        cases[case] = entry
        print(json.dumps({"case": case, **entry}), flush=True)
    assert snapshot() == before and (O / "eula.txt").read_bytes() == eula
    source_paths = sources + [Path(__file__), R / "work/migration/build_gtceu_kubejs_bridge.py",
                              R / "work/migration/KubeJSBridgeFixtures.js", PATCH, R / "locks/gtceu-kubejs72-build.json",
                              R / "locks/native-client-audit-inputs.json", R / "locks/gtceu-derived-artifact.json",
                              R / "work/migration/gtceu_artifact.py", R / "work/migration/install_gtceu_bridge.py"]
    report = {"passed": True, "scope": "installed source-built artifact copied into isolated resource checks; no game world",
              "checks": details, "cases": cases, "derived_jar_sha256": positive["jar_sha256"],
              "preserved_original_entries": positive["unchanged_original_entries"],
              "source_sha256": {p.relative_to(R).as_posix(): sha(p) for p in source_paths},
              "foundation_unchanged": True, "eula_accepted": False, "server_world_player_constructed": False,
              "installed_into_foundation": True, "installed_artifact_matches_fresh_build": True,
              "input_sha256": before, "machine_execution_verified": False,
              "client_visuals_verified": False, "full_parity": False}
    save(run / "report.json", report)
    save(R / "evidence/NativeResourceBridgeCheck.json", report)
    print(json.dumps({"passed": True, "checks": details, "foundation_unchanged": True}))


if __name__ == "__main__":
    main()
