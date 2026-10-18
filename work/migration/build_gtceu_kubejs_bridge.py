"""Recompile pinned GTCEu KubeJS integration, preserving every other JAR entry.

Writes an isolated derived artifact and its provenance; never installs it into
the Foundation pack. --old-output-control deliberately restores the known output
direction defect for the resource-loading regression check.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import urllib.request
import uuid
import zipfile

R = Path(__file__).resolve().parents[2]
O = R / "outputs/GTNH-NeoForge-1.21.1-Migration-Foundation"
OUT = R / "work/migration/gtceu-kubejs-bridge"
PATCH = R / "work/compat-mod/patches/gtceu-kubejs72.json"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8", newline="\n")


def classpath():
    launch = (O / "libraries/net/neoforged/neoforge/21.1.262/win_args.txt").read_text(encoding="utf-8")
    libraries = []
    for pattern in (r"^-DlegacyClassPath=(.*)$", r"^-p (.*)$"):
        libraries.extend(O / n for n in re.search(pattern, launch, re.M)[1].strip().split(";"))
    libraries.extend(O / "libraries" / n for n in (
        "net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar",
        "net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar",
        "net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar"))
    return libraries + sorted((O / "mods").glob("*.jar"))


def build(old_output_control=False):
    lock = read(R / "locks/gtceu-kubejs72-build.json")
    patch = read(PATCH)
    assert patch["format"] == 1 and patch["source_commit"] == lock["source_commit"]
    cache = R / "work/cache"
    for name, pin in lock["dependencies"].items():
        path = cache / name
        if not path.exists():
            with urllib.request.urlopen(pin["url"], timeout=60) as response:
                content = response.read()
            if digest(content) != pin["sha256"]:
                raise ValueError("Downloaded dependency hash mismatch: " + name)
            path.write_bytes(content)
        if digest(path.read_bytes()) != pin["sha256"]:
            raise ValueError("Dependency hash mismatch: " + name)
    original = O / "mods" / lock["original_jar"]
    assert digest(original.read_bytes()) == lock["original_sha256"]
    assert digest((O / "mods" / lock["kubejs_jar"]).read_bytes()) == lock["kubejs_sha256"]
    run = OUT / str(uuid.uuid4())
    classes = run / "classes"
    classes.mkdir(parents=True)
    java = Path(read(R / "work/migration/runtime.json")["java"])
    sources = []
    applied = {}
    with zipfile.ZipFile(cache / "gtceu-7.0.2-source.zip") as archive:
        for entry in patch["files"]:
            name = entry["path"]
            assert name.startswith("src/main/java/") and ".." not in Path(name).parts
            content = archive.read(lock["source_root"] + name)
            assert digest(content) == entry["original_sha256"], name
            control = old_output_control and name.endswith("/recipe/GTRecipeSchema.java")
            lines = content.decode("utf-8").splitlines(keepends=True)
            if not control:
                for edit in reversed(entry["edits"]):
                    start, end = edit["start"], edit["end"]
                    assert 0 <= start <= end <= len(lines)
                    assert "".join(lines[start:end]) == edit["before"], name
                    lines[start:end] = edit["after"].splitlines(keepends=True)
            content = "".join(lines).encode("utf-8")
            assert digest(content) == entry["original_sha256" if control else "patched_sha256"], name
            target = run / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
            sources.append(target)
            applied[name] = digest(content)
        (run / "lombok.config").write_bytes(archive.read(lock["source_root"] + "lombok.config"))
    libraries = classpath() + [cache / "lombok-1.18.38.jar", cache / "annotations-26.0.1.jar"]
    # Compile retained client methods too; these inputs are not added to the
    # dedicated-server runtime or used to strip any rendering implementation.
    client_pin = read(R / "locks/native-client-audit-inputs.json")["cache_files_sha256"]
    for name in ("neoforge-21.1.262-client.jar", "client-srg.jar", "client-extra.jar"):
        path = cache / "native-client-audit" / name
        assert digest(path.read_bytes()) == client_pin[name], name
        libraries.append(path)
    with zipfile.ZipFile(original) as archive:
        for name in archive.namelist():
            if name.startswith("META-INF/jarjar/") and name.endswith(".jar"):
                target = run / Path(name).name
                target.write_bytes(archive.read(name))
                libraries.append(target)
    cp = os.pathsep.join(dict.fromkeys(p.resolve().as_posix() for p in libraries))
    options = ["--release", "21", "-encoding", "UTF-8", "-cp", cp,
               "-processorpath", str(cache / "lombok-1.18.38.jar"), "-d", str(classes)]
    options += [str(p) for p in sources]
    argfile = run / "compile.args"
    argfile.write_text("\n".join(json.dumps(v, ensure_ascii=False) for v in options), encoding="utf-8")
    result = subprocess.run([str(java.with_name("javac.exe")), "-J-Duser.language=en",
                             "-J-Duser.country=US", "@" + str(argfile)], capture_output=True, timeout=120)
    (run / "compile.log").write_bytes(result.stdout + result.stderr)
    if result.returncode:
        raise RuntimeError("Bridge compilation failed; see " + str(run / "compile.log"))
    compiled = {p.relative_to(classes).as_posix(): p.read_bytes() for p in sorted(classes.rglob("*.class"))}
    assert compiled
    provenance = {"original_jar_sha256": lock["original_sha256"], "source_commit": lock["source_commit"],
                  "patch_sha256": digest(PATCH.read_bytes()), "old_output_control": old_output_control,
                  "source_sha256": applied}
    target = run / ("gtceu-1.21.1-7.0.2-gtnh-kubejs72" + ("-negative-control" if old_output_control else "") + ".jar")
    with zipfile.ZipFile(original) as archive, zipfile.ZipFile(target, "w") as output:
        assert set(compiled) <= set(archive.namelist()), "Unexpected new compiled class"
        assert not any(n.upper().endswith((".SF", ".RSA", ".DSA")) for n in archive.namelist())
        for info in archive.infolist():
            output.writestr(info, compiled.get(info.filename, archive.read(info.filename)))
        info = zipfile.ZipInfo("META-INF/gtnh-migration-kubejs72.json", (2026, 10, 18, 0, 0, 0))
        output.writestr(info, json.dumps(provenance, sort_keys=True, indent=2) + "\n")
    unchanged = 0
    with zipfile.ZipFile(original) as before, zipfile.ZipFile(target) as after:
        for name in before.namelist():
            if name not in compiled:
                assert before.read(name) == after.read(name), name
                unchanged += 1
        for name, value in compiled.items():
            assert after.read(name) == value
    report = {**provenance, "jar": target.relative_to(R).as_posix(), "jar_sha256": digest(target.read_bytes()),
              "compiled_class_sha256": {n: digest(v) for n, v in compiled.items()},
              "unchanged_original_entries": unchanged, "all_original_resources_unchanged": True,
              "compiled_source_files": len(sources), "compiler_exit": result.returncode,
              "installed_into_foundation": False, "full_parity": False}
    save(run / "build.json", report)
    save(OUT / ("old-output-control.json" if old_output_control else "build.json"), report)
    print(json.dumps({"jar": report["jar"], "sha256": report["jar_sha256"],
                      "compiled_classes": len(compiled), "unchanged_entries": unchanged}))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old-output-control", action="store_true")
    build(parser.parse_args().old_output_control)
