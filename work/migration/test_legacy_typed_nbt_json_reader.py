"""Pinned BQ decoder methods against real 1.21.1 NBT, without a game or save writes.

Two supplied pack files are task definitions, not player save fixtures. Generated
old-name NBT adapters and compiled classes stay under ignored work/migration/.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
inputs = Path(os.environ.get('GTNH_TEST_INPUT_ROOT', str(ROOT)))
OUTPUT = inputs / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
out = ROOT / 'work/migration/typed-nbt-json-check'
out.mkdir(parents=True, exist_ok=True)
java = Path(json.loads((inputs / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
source_rel = 'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java/betterquesting/api/utils/NBTConverter.java'
source = inputs / source_rel
SOURCE_HASH = 'f6a0362455bc0d226974313cd507e8be304bcb52175e3a40f9a14da8516e0db0'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


assert sha(source) == SOURCE_HASH, 'Pinned NBTConverter source differs'
original = source.read_text(encoding='utf-8')


def method(signature):
    start = original.index('    ' + signature)
    pos = original.index('{', start)
    end, depth = pos + 1, 1
    while depth:
        if original[end] == '{':
            depth += 1
        elif original[end] == '}':
            depth -= 1
        end += 1
    return original[start:end] + '\n'


methods = ''.join(method(signature) for signature in [
    'public static NBTTagCompound JSONtoNBT_Object(',
    'private static NBTBase JSONtoNBT_Element(',
    'public static NBTBase instanceNumber(',
    'private static byte fallbackTagID(',
])
header = r'''import java.util.*;
import java.util.Map.Entry;
import com.google.gson.*;
import net.minecraft.nbt.*;

public final class BqTypedJsonReference {
    public static int errors;
    enum Level { WARN, ERROR }
    static class Logger { void log(Level level, String message, Object... details) { errors++; } }
    static class QuestingAPI { static Logger getLogger() { return new Logger(); } }
    static class NBTBase { final Tag value; NBTBase(Tag value) { this.value = value; } }
    static class NBTTagCompound extends NBTBase {
        NBTTagCompound() { super(new CompoundTag()); }
        void setTag(String name, NBTBase tag) { ((CompoundTag)value).put(name, tag.value); }
        boolean hasKey(String name) { return ((CompoundTag)value).contains(name); }
    }
    static class NBTTagList extends NBTBase {
        NBTTagList() { super(new ListTag()); }
        void appendTag(NBTBase tag) { ((ListTag)value).add(tag.value); }
    }
    static class NBTTagString extends NBTBase {
        NBTTagString() { this(""); }
        NBTTagString(String data) { super(StringTag.valueOf(data)); }
    }
    static class NBTTagByteArray extends NBTBase { NBTTagByteArray(byte[] data) { super(new ByteArrayTag(data)); } }
    static class NBTTagIntArray extends NBTBase { NBTTagIntArray(int[] data) { super(new IntArrayTag(data)); } }
'''
for name, primitive in [('Byte', 'byte'), ('Short', 'short'), ('Int', 'int'), ('Long', 'long'), ('Float', 'float'), ('Double', 'double')]:
    header += f'    static class NBTTag{name} extends NBTBase {{ NBTTag{name}({primitive} data) {{ super({name}Tag.valueOf(data)); }} }}\n'
tail = r'''
    public static CompoundTag read(String json) {
        errors = 0;
        return (CompoundTag)JSONtoNBT_Object(JsonParser.parseString(json).getAsJsonObject(), new NBTTagCompound(), true).value;
    }
}
'''
oracle = out / 'BqTypedJsonReference.java'
oracle.write_text(header + methods + tail, encoding='utf-8', newline='\n')

fixture_info = [
    ('work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/AndSoItBegins-AAAAAAAAAAAAAAAAAAAAAA==/SticksnStones-AAAAAAAAAAAAAAAAAAAAAg==.json',
     'b10a26b4012b0b407dd97bb5d486fbf4d819ee85c37248ea8ab61946e2d05f6b'),
    ('work/source/GT-New-Horizons-Modpack-master/config/betterquesting/DefaultQuests/Quests/TheGreenRevoluti-GbGDSZsbQUekpRKT9ZPdjw==/CropSticks-O65QUnR0QuGSoQrsUMTB1g==.json',
     'f3a9e572ac771aef9f76c6121ad7a4cb3f5238df6b9adc76238df056d389a52e'),
]
fixtures = []
for rel, expected in fixture_info:
    path = inputs / rel
    assert sha(path) == expected, f'Task definition fixture hash differs: {path.name}'
    fixtures.append(path)

launch = (OUTPUT / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries = [OUTPUT / part for part in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
minecraft = OUTPUT / 'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
libraries.insert(0, minecraft)


def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()


classpath = ';'.join(relative(path) for path in libraries)
production = ROOT / 'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyTypedNbtJsonReader.java'
check = ROOT / 'work/migration/LegacyTypedNbtJsonReaderCheck.java'
authored = [production, check, Path(__file__)]
source_hashes = {path.relative_to(ROOT).as_posix(): sha(path) for path in authored}


def run(executable, arguments, name):
    def quote(value):
        assert not any(c in value for c in '\x00\r\n')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    argfile = out / f'{name}.args'
    argfile.write_text('\n'.join(quote(value) for value in arguments) + '\n', encoding='utf-8', newline='\n')
    result = subprocess.run([str(executable), '@' + str(argfile)], cwd=out, capture_output=True, timeout=60)
    log = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    (out / f'{name}.log').write_text(log, encoding='utf-8', newline='\n')
    assert result.returncode == 0, f'{name} failed:\n{log}'
    return log


run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath,
                                 '-d', '.', relative(production), relative(check), oracle.name], 'compile')
log = run(java, ['-cp', '.;' + classpath, 'LegacyTypedNbtJsonReaderCheck', *[relative(path) for path in fixtures]], 'reader')
assert source_hashes == {path.relative_to(ROOT).as_posix(): sha(path) for path in authored}, 'Authored sources changed during tests'
evidence = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
evidence.update({
    'source_commit': '815d738923d46e9083f988421e5329193e2e5940',
    'source_file_sha256': {source_rel: SOURCE_HASH},
    'source_sha256': source_hashes,
    'reader_source_sha256': sha(production), 'test_source_sha256': sha(check), 'runner_sha256': sha(__file__),
    'compiled_reader_sha256': {path.relative_to(out).as_posix(): sha(path) for path in sorted((out / 'org/gtnhmigration/compat').glob('LegacyTypedNbtJsonReader*.class'))},
    'oracle_fixture_sha256': sha(oracle), 'minecraft_jar_sha256': sha(minecraft),
    'gson_jar_sha256': {path.name: sha(path) for path in libraries if path.name.startswith('gson-')},
    'task_definition_fixture_sha256': dict(fixture_info),
    'source_methods_extracted_unchanged': ['JSONtoNBT_Object', 'JSONtoNBT_Element', 'instanceNumber', 'fallbackTagID'],
    'supported_tag_ids': list(range(1, 12)),
    'unsupported_tag_zero': 'Original reader uses ID 0 for fallback inference; no lossless EndTag decoding is claimed.',
    'negative_zero_gap': 'Actual 1.21.1 FloatTag/DoubleTag public factories and loaders normalize negative zero. Such inputs retain original bytes and are rejected; no reflection or global access adaptation is used.',
    'runtime_integrated': False, 'export_enabled': False, 'compat_jar_verified': False,
    'oracle_boundaries': [
        'Original BQ decoder methods execute unchanged with old NBT names backed by real 1.21.1 NBT; the old game runtime is not launched.',
        'Logger and NBT-name adapters are fixtures. Source comparisons cover accepted, explicitly typed values, excluding lossy coercions and modern negative-zero limitations.',
        'The two supplied files are task definitions, never player saves; all rejection and numeric-boundary fixtures are synthetic.',
    ],
    'limitations': [
        'Accepted means only this typed JSON decoding succeeded, not that a save is safe to activate.',
        'Original UTF-8 bytes and JSON member-order ledger are retained separately; CompoundTag itself does not promise object-key iteration order.',
        'Missing/zero/noncanonical tag suffixes, inferred arrays, malformed or ambiguous input, integer coercions, float overflow/underflow and negative zero are retained but not accepted.',
        'Gson 2.10.1 non-lenient mode needs an additional lexical gate for raw string control characters, invalid escapes and uppercase literals; UTF-8 BOM inputs are conservatively retained but rejected.',
        'Input limits: 16 MiB, depth 128, 1,000,000 nodes, numeric tokens at most 128 characters; limits reject without discarding original bytes.',
        'No quest identity routing, envelope integration, FTB registry/save/event integration, world startup or on-disk player save modification.',
    ],
})
(out / 'LegacyTypedNbtJsonReaderCheck.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps(evidence))
