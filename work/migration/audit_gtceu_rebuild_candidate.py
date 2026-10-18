"""Read a pinned Git tree and compare it with the installed GTCEu baseline.

No build, installation, world creation, EULA change, or candidate-repository write.
Resource hashes compare packaged 7.0.2 with candidate source resources, not GTNH
visual equivalence. Duplicate source paths remain separate candidates.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tomllib
import zipfile
from gtceu_artifact import original_jar

ROOT = Path(__file__).resolve().parents[2]
FOUNDATION = ROOT / "outputs/GTNH-NeoForge-1.21.1-Migration-Foundation"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("candidate", type=Path)
    args = parser.parse_args()
    lock = read_json(ROOT / "locks/gtceu-rebuild-candidate.json")

    def git(*command):
        return subprocess.check_output(["git", *command], cwd=args.candidate)

    commit = git("rev-parse", "HEAD").decode().strip()
    if commit != lock["commit"]:
        raise ValueError("Candidate HEAD does not match the reviewed commit")
    files = {}
    with tarfile.open(fileobj=io.BytesIO(git("archive", "--format=tar", commit))) as archive:
        for member in archive:
            if member.isfile():
                files[member.name] = archive.extractfile(member).read()

    def source(path):
        return files[path].decode("utf-8")

    old_jar = original_jar()
    kjs_jar = FOUNDATION / "mods" / lock["current_kubejs_filename"]
    for path, key in [(old_jar, "current_gtceu_sha256"), (kjs_jar, "current_kubejs_sha256")]:
        if sha(path.read_bytes()) != lock[key]:
            raise ValueError(f"Pinned artifact changed: {path.name}")

    # Compare every source candidate; never invent Gradle overwrite precedence.
    resources = {}
    for path, data in files.items():
        for prefix in ("src/main/resources/", "src/generated/resources/"):
            if path.startswith(prefix):
                name = path[len(prefix):]
                if name.startswith("assets/"):
                    resources.setdefault(name, []).append({"source": path, "sha256": sha(data)})
    with zipfile.ZipFile(old_jar) as archive:
        baseline = {n: sha(archive.read(n)) for n in archive.namelist()
                    if n.startswith("assets/") and not n.endswith("/")}
    common = baseline.keys() & resources.keys()
    identical = sorted(n for n in common if any(r["sha256"] == baseline[n] for r in resources[n]))
    changed = sorted(common - set(identical))
    old_only = sorted(baseline.keys() - resources.keys())
    new_only = sorted(resources.keys() - baseline.keys())

    targets = {}
    for name in ("gtceu-client-isolation.json", "library-client-callbacks.json"):
        for entry in read_json(ROOT / "locks" / name)["classes"]:
            if entry.startswith("com/gregtechceu/"):
                path = "src/main/java/" + entry.removesuffix(".class") + ".java"
                targets[entry] = {"same_source_path_present": path in files,
                                  "candidate_source_sha256": sha(files[path]) if path in files else None}

    plugin_path = "src/main/java/com/gregtechceu/gtceu/integration/kjs/GregTechKubeJSPlugin.java"
    schema_path = "src/main/java/com/gregtechceu/gtceu/integration/kjs/recipe/GTShapedRecipeSchema.java"
    output_path = "src/main/java/com/gregtechceu/gtceu/integration/kjs/recipe/GTRecipeSchema.java"
    component_path = "src/main/java/com/gregtechceu/gtceu/integration/kjs/recipe/components/ContentJS.java"
    selected = ["README.md", "docs/RECOVERY_STATUS.md", "docs/RUNTIME_VERIFICATION_2026-10-06.md",
                "gradle/forge.versions.toml", "gradle/libs.versions.toml", "dependencies.gradle",
                "FORK_PROVENANCE.properties", "SOURCE_LINEAGE.csv", plugin_path, schema_path,
                output_path, component_path]

    # Compile the exact referenced field against the current runtime classpath.
    # The companion positive control establishes that the class itself resolves.
    probe = ROOT / "work/gtceu-rebuild-candidate-audit"
    probe.mkdir(exist_ok=True)
    launch = (FOUNDATION / "libraries/net/neoforged/neoforge/21.1.262/win_args.txt").read_text(encoding="utf-8")
    libraries = [FOUNDATION / name for name in
                 re.search(r"^-DlegacyClassPath=(.*)$", launch, re.M)[1].strip().split(";")]
    libraries += [FOUNDATION / name for name in
                  re.search(r"^-p (.*)$", launch, re.M)[1].strip().split(";")]
    libraries += [FOUNDATION / "libraries" / name for name in (
        "net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar",
        "net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar",
        "net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar")]
    libraries += sorted((FOUNDATION / "mods").glob("*.jar"))
    classpath = os.pathsep.join(dict.fromkeys(p.resolve().as_posix() for p in libraries))
    javac = Path(read_json(ROOT / "work/migration/runtime.json")["java"]).with_name("javac.exe")
    controls = {}
    for field in ("MIRROR_KEY", "STAGE_KEY"):
        name = "CandidateField" + field.replace("_", "")
        java = probe / (name + ".java")
        java.write_text("public class " + name + " { public static final String VALUE = "
                        "dev.latvian.mods.kubejs.recipe.special.KubeJSCraftingRecipe."
                        + field + "; }\n", encoding="utf-8")
        options = ["-proc:none", "--release", "21", "-encoding", "UTF-8", "-cp", classpath,
                   "-d", str(probe), str(java)]
        argfile = probe / (name + ".args")
        argfile.write_text("\n".join(json.dumps(o, ensure_ascii=False) for o in options), encoding="utf-8")
        result = subprocess.run([str(javac), "-J-Duser.language=en", "-J-Duser.country=US",
                                 "@" + str(argfile)], capture_output=True, timeout=60)
        diagnostics = result.stderr.decode(errors="replace")
        controls[field] = {"compile_exit": result.returncode,
                           "cannot_find_field": "cannot find symbol" in diagnostics and field in diagnostics}
    if controls["MIRROR_KEY"]["compile_exit"] != 0 or not controls["STAGE_KEY"]["cannot_find_field"]:
        raise AssertionError("Field compatibility probe did not reproduce the expected API boundary")
    if "KubeJSCraftingRecipe.STAGE_KEY" not in source(schema_path):
        raise AssertionError("Candidate no longer contains the reviewed reference")

    report = {
        "scope": "pinned source and artifact compatibility assessment only",
        "repository": lock["repository"], "commit": commit,
        "git_tree": git("rev-parse", "HEAD^{tree}").decode().strip(),
        "candidate_files": len(files),
        "candidate_versions": tomllib.loads(source("gradle/forge.versions.toml"))["versions"],
        "candidate_platform": tomllib.loads(source("gradle/libs.versions.toml"))["versions"],
        "source_sha256": {p: sha(files[p]) for p in selected},
        "audit_script_sha256": sha(Path(__file__).read_bytes()),
        "current_artifacts": {k: v for k, v in lock.items() if k.startswith("current_")},
        "kubejs_field_compile_controls": controls,
        "candidate_generic_output_uses_output_keys": bool(re.search(
            r"GTKubeRecipe output\(RecipeCapability<T> capability, Object\.\.\. obj\)\s*\{\s*"
            r"var key = perTick \? ALL_TICK_OUTPUTS : ALL_OUTPUTS;", source(output_path))),
        "current_coremod_target_source_paths": targets,
        "asset_comparison": {
            "scope": "7.0.2 packaged assets versus 8.0.1-unofficial main/generated source assets; raw bytes only",
            "baseline_paths": len(baseline), "candidate_paths": len(resources),
            "common_paths": len(common), "same_bytes_in_at_least_one_candidate": len(identical),
            "different_bytes_at_common_path": len(changed),
            "baseline_only_count": len(old_only), "candidate_only_count": len(new_only),
            "different_common_paths": [{"path": n, "baseline_sha256": baseline[n],
                                        "candidates": resources[n]} for n in changed],
            "baseline_only_paths": old_only, "candidate_only_paths": new_only,
            "multiple_source_candidates": {n: v for n, v in resources.items() if len(v) > 1},
            "visual_equivalence_verified": False, "gtnh_equivalence_verified": False,
        },
        "candidate_build_executed": False, "candidate_gametests_executed": False,
        "candidate_installed": False, "full_parity": False,
        "decision": "Use as a pinned source reference; resolve API, resource and dependency differences before any replacement.",
    }
    destination = ROOT / "evidence/gtceu-rebuild-candidate-audit.json"
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"commit": commit, "candidate_installed": False,
                      "asset_counts": {k: v for k, v in report["asset_comparison"].items()
                                       if isinstance(v, int) and not isinstance(v, bool)},
                      "field_controls": controls, "assessment_completed": True}))


if __name__ == "__main__":
    main()
