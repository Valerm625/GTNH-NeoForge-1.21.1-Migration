"""Actual MC/FTB matching outside a game world. Inputs are read-only.

GTNH_TEST_INPUT_ROOT can point to a restored dependency workspace. Compilation,
logs and evidence stay in this checkout's work tree. The vanilla SRG jar comes
first; this test does not claim to exercise NeoForge's live mod loader.
"""
from pathlib import Path
import ast, hashlib, json, os, re, subprocess, zipfile

R = Path(__file__).resolve().parents[2]
inputs = Path(os.environ.get('GTNH_TEST_INPUT_ROOT', str(R)))
O = inputs / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
java = Path(json.loads((inputs / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
out = R / 'work/migration/retrieval-matcher-check'
out.mkdir(parents=True, exist_ok=True)
launch = (O / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
legacy_libs = [O / p for p in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
minecraft = O / 'libraries/net/minecraft/server/1.21.1-20240808.144430'
ftb = O / 'mods/ftb-quests-neoforge-2101.1.36.jar'
libs = [minecraft / 'server-1.21.1-20240808.144430-srg.jar',
        minecraft / 'server-1.21.1-20240808.144430-extra.jar',
        O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar',
        O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar'] + legacy_libs + list((O / 'mods').glob('*.jar'))
# Relative paths also keep this restored D: workspace's Chinese ancestor out
# of the launcher argfile. Windows Java's native launcher can decode argfiles
# differently from javac; UTF-8 file.encoding does not fix launcher decoding.
def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()

cp = ';'.join(relative(p) for p in libs)
helper = R / 'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalStackMatcher.java'
test = R / 'work/migration/LegacyRetrievalStackMatcherCheck.java'

# Ensure the build generator does not erase the repair.
generator = ast.parse((R / 'work/migration/add_quest_parity_adapter.py').read_text(encoding='utf-8'))
generated = {}
for node in ast.walk(generator):
    if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == 'write_text':
        target = node.func.value
        if isinstance(target, ast.BinOp) and isinstance(target.right, ast.Constant) and node.args and isinstance(node.args[0], ast.Constant):
            generated[target.right.value] = node.args[0].value
for name in ['LegacyRetrievalStackMatcher.java', 'LegacyRetrievalItemTaskMixin.java', 'LegacyCraftingStackMatcher.java', 'LegacyCraftingItemTaskMixin.java']:
    actual = helper.with_name(name).read_text(encoding='utf-8')
    assert generated[name] == actual, f'Generator/source divergence: {name}'

command_lengths = {}

def run(argv, name):
    # Java/Javac response files avoid Windows' 32,767-character CreateProcess
    # limit. Quote each token using the Java argfile grammar, not shell quoting;
    # doubling backslashes preserves Windows paths (including \t and \n).
    def argfile_token(value):
        value = str(value)
        assert not any(c in value for c in '\x00\r\n'), 'Invalid Java argument'
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    argfile = out / f'{name}.args'
    argfile.write_text('\n'.join(argfile_token(value) for value in argv[1:]) + '\n', encoding='utf-8')
    command = [argv[0], '@' + str(argfile)]
    command_lengths[name] = {'direct_windows_characters_with_nul': len(subprocess.list2cmdline(argv)) + 1,
                             'argfile_windows_characters_with_nul': len(subprocess.list2cmdline(command)) + 1}
    assert command_lengths[name]['argfile_windows_characters_with_nul'] < 32767
    (out / 'command-lengths.json').write_text(json.dumps(command_lengths, indent=2), encoding='utf-8')
    result = subprocess.run(command, cwd=out, capture_output=True, timeout=60)
    log = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    (out / f'{name}.log').write_text(log, encoding='utf-8')
    assert result.returncode == 0, f'{name} failed:\n{log}'
    return log

sources = [helper, test, helper.with_name('LegacyCraftingStackMatcher.java'),
           helper.with_name('LegacyCraftingItemTaskMixin.java'), helper.with_name('LegacyRetrievalItemTaskMixin.java'),
           helper.with_name('LegacyRetrievalCounter.java'), R / 'work/migration/QuestAdapterShapeCheck.java',
           R / 'work/migration/LegacyCraftingShapeCheck.java']
run([str(java.with_name('javac.exe')), '-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', cp, '-d', '.'] + [relative(p) for p in sources], 'compile')
compiled = out / 'matcher-adapters.jar'
with zipfile.ZipFile(compiled, 'w') as jar:
    for path in sorted((out / 'org/gtnhmigration/compat').glob('*.class')):
        jar.write(path, path.relative_to(out).as_posix())
for check in ['QuestAdapterShapeCheck', 'LegacyCraftingShapeCheck']:
    shape_log = run([str(java), '-cp', '.;' + cp, check, relative(ftb), relative(compiled)], check)
    shape = json.loads(next(line for line in shape_log.splitlines() if line.startswith('{')))
    (out / f'{check}.json').write_text(json.dumps(shape, indent=2), encoding='utf-8')
log = run([str(java), '-cp', '.;' + cp, 'LegacyRetrievalStackMatcherCheck'], 'matcher')
evidence = json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
evidence['ftb_artifact_sha256'] = hashlib.sha256(ftb.read_bytes()).hexdigest()
evidence['adapter_source_sha256'] = hashlib.sha256(helper.read_bytes()).hexdigest()
evidence['generator_source_matches'] = True
evidence['retrieval_and_crafting_mixin_shape_checked'] = True
evidence['test_environment'] = 'actual vanilla 1.21.1 classes + FTB 2101.1.36 matcher, no world/server startup'
(out / 'LegacyRetrievalStackMatcherCheck.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print(json.dumps(evidence))
