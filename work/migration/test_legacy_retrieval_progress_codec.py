"""Actual 1.21.1 NBT and hash-pinned original BQ persistence methods.

No server, world, registry bootstrap, FTB storage integration or game EULA.
All generated wrappers/output stay under work/migration/retrieval-progress-check.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess

R = Path(__file__).resolve().parents[2]
inputs = Path(os.environ.get('GTNH_TEST_INPUT_ROOT', str(R)))
O = inputs / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
java = Path(json.loads((inputs / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
source_root = inputs / 'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java'
source_hashes = {
    'bq_standard/tasks/base/TaskBase.java': 'cc2383b39e7d24f9cffb2a57076e8aee6434b1e120236320b63afcbb9c704fce',
    'bq_standard/tasks/base/TaskProgressableBase.java': '6b4f2e0ff58e355ccec32c31174ef6f0fd07eafff1092ff1d797356d5b994a99',
    'bq_standard/tasks/TaskRetrieval.java': '8ac57331877e643a95c5c5d39966cce8a3d854d27319df80bee8514d19a22f71',
}
sources = {}
for name, expected in source_hashes.items():
    file = source_root / name
    assert hashlib.sha256(file.read_bytes()).hexdigest() == expected, f'Changed BQ source: {name}'
    sources[name] = file.read_text(encoding='utf-8')

def method(text, signature):
    start = text.index('    ' + signature)
    pos = text.index('{', start)
    depth = 1
    end = pos + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end] + '\n'

base_methods = ''.join(method(sources['bq_standard/tasks/base/TaskBase.java'], signature) for signature in [
    'public void readProgressFromNBT(', 'public NBTTagCompound writeProgressToNBT('])
progress_methods = ''.join(method(sources['bq_standard/tasks/base/TaskProgressableBase.java'], signature) for signature in [
    'public void readProgressFromNBT(', 'public NBTTagCompound writeProgressToNBT('])
retrieval_methods = ''.join(method(sources['bq_standard/tasks/TaskRetrieval.java'], signature) for signature in [
    'public int[] getUsersProgress(', 'public int[] readUserProgressFromNBT(', 'public void writeUserProgressToNBT('])

out = R / 'work/migration/retrieval-progress-check'
out.mkdir(parents=True, exist_ok=True)
oracle = out / 'BqProgressReference.java'
header = r'''import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine.State;

public final class BqProgressReference {
    @interface Nullable {}
    enum Level { ERROR }
    static final class Log { void log(Level level,String message,Exception e) {} }
    static final class BQ_Standard { static final Log logger=new Log(); }
    static final class Constants { static final class NBT { static final int TAG_LIST=9,TAG_INT=3,TAG_COMPOUND=10,TAG_STRING=8; } }
    static class NBTBase {
        final Tag tag; NBTBase(Tag tag) { this.tag=tag; }
    }
    static final class NBTPrimitive extends NBTBase {
        NBTPrimitive(NumericTag tag) { super(tag); }
        int func_150287_d() { return ((NumericTag)tag).getAsInt(); }
    }
    static final class NBTTagString extends NBTBase { NBTTagString(String value) { super(StringTag.valueOf(value)); } }
    static final class NBTTagCompound extends NBTBase {
        NBTTagCompound() { this(new CompoundTag()); }
        NBTTagCompound(CompoundTag tag) { super(tag); }
        CompoundTag value() { return (CompoundTag)tag; }
        String getString(String key) { return value().getString(key); }
        void setString(String key,String value) { value().putString(key,value); }
        boolean hasKey(String key,int type) { return value().contains(key,type); }
        int[] getIntArray(String key) { return value().getIntArray(key); }
        void setIntArray(String key,int[] values) { value().putIntArray(key,values); }
        NBTTagList getTagList(String key,int type) { return new NBTTagList(value().getList(key,type)); }
        void setTag(String key,NBTBase value) { value().put(key,value.tag); }
    }
    static final class NBTTagList extends NBTBase {
        NBTTagList() { this(new ListTag()); }
        NBTTagList(ListTag tag) { super(tag); }
        ListTag value() { return (ListTag)tag; }
        int tagCount() { return value().size(); }
        String getStringTagAt(int i) { return value().getString(i); }
        NBTTagCompound getCompoundTagAt(int i) { return new NBTTagCompound(value().getCompound(i)); }
        void appendTag(NBTBase value) { value().add(value.tag); }
    }
    static final class NBTConverter {
        static List<NBTBase> getTagList(NBTTagList list) {
            List<NBTBase> result=new ArrayList<>();
            for (Tag tag:list.value()) result.add(tag instanceof NumericTag n ? new NBTPrimitive(n) : new NBTBase(tag));
            return result;
        }
    }
    static class TaskBase {
        final Set<UUID> completeUsers=new TreeSet<>();
'''
between = r'''
    }
    static abstract class TaskProgressableBase<T> extends TaskBase {
        final TreeMap<UUID,T> userProgress=new TreeMap<>();
        abstract T readUserProgressFromNBT(NBTTagCompound nbt);
        abstract void writeUserProgressToNBT(NBTTagCompound nbt,T data);
'''
between2 = r'''
    }
    static final class TaskRetrieval extends TaskProgressableBase<int[]> {
        final List<Object> requiredItems;
        TaskRetrieval(int count) { requiredItems=Collections.nCopies(count,new Object()); }
'''
tail = r'''
    }
    static TaskRetrieval task(State state,int count) {
        TaskRetrieval task=new TaskRetrieval(count);
        task.completeUsers.addAll(state.completed());
        state.progress().forEach((id,row)->task.userProgress.put(id,row.stream().mapToInt(Integer::intValue).toArray()));
        return task;
    }
    public static State read(CompoundTag raw,int count,State previous,boolean merge) {
        TaskRetrieval task=task(previous,count);
        task.readProgressFromNBT(new NBTTagCompound(raw.copy()),merge);
        Map<UUID,List<Integer>> progress=new LinkedHashMap<>();
        task.userProgress.forEach((id,row)->progress.put(id,Arrays.stream(row).boxed().toList()));
        return new State(progress,task.completeUsers);
    }
    public static CompoundTag write(State state,List<UUID> selected) {
        return task(state,1).writeProgressToNBT(new NBTTagCompound(),selected).value();
    }
    public static int[] liveGetter(State state,UUID id,int count) { return task(state,count).getUsersProgress(id); }
}
'''
oracle.write_text(header + base_methods + between + progress_methods + between2 + retrieval_methods + tail, encoding='utf-8')

launch = (O / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries = [O / part for part in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
minecraft = O / 'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
libraries = [minecraft] + libraries
def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()
classpath = ';'.join(relative(path) for path in libraries)
engine = R / 'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalEngine.java'
codec = engine.with_name('LegacyRetrievalProgressCodec.java')
check = R / 'work/migration/LegacyRetrievalProgressCodecCheck.java'

def run(executable, arguments, name):
    def quote(value):
        assert not any(c in value for c in '\x00\r\n')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    argfile = out / f'{name}.args'
    argfile.write_text('\n'.join(quote(value) for value in arguments) + '\n', encoding='utf-8')
    result = subprocess.run([str(executable), '@' + str(argfile)], cwd=out, capture_output=True, timeout=60)
    log = (result.stdout + result.stderr).decode('utf-8', errors='replace')
    (out / f'{name}.log').write_text(log, encoding='utf-8')
    assert result.returncode == 0, f'{name} failed:\n{log}'
    return log

run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath, '-d', '.', relative(engine), relative(codec), relative(check), oracle.name], 'compile')
log = run(java, ['-cp', '.;' + classpath, 'LegacyRetrievalProgressCodecCheck'], 'codec')
evidence = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
evidence.update({
    'source_commit': '815d738923d46e9083f988421e5329193e2e5940',
    'source_file_sha256': source_hashes,
    'codec_source_sha256': hashlib.sha256(codec.read_bytes()).hexdigest(),
    'test_source_sha256': hashlib.sha256(check.read_bytes()).hexdigest(),
    'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'minecraft_jar_sha256': hashlib.sha256(minecraft.read_bytes()).hexdigest(),
    'oracle_fixture_sha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
    'source_methods_extracted_unchanged': ['TaskBase.readProgressFromNBT', 'TaskBase.writeProgressToNBT',
        'TaskProgressableBase.readProgressFromNBT', 'TaskProgressableBase.writeProgressToNBT',
        'TaskRetrieval.getUsersProgress', 'TaskRetrieval.readUserProgressFromNBT', 'TaskRetrieval.writeUserProgressToNBT'],
    'oracle_boundaries': ['Old NBT names delegate to actual 1.21.1 NBT classes; no 1.7.10 Minecraft runtime.',
                         'Logger and requiredItems count are fixtures; methods execute pinned source unchanged.',
                         'Source equivalence covers recognized nonnegative progress payloads only.'],
    'limitations': ['Unknown versions/fields/types, invalid UUIDs and negative counters retain raw NBT but cannot activate.',
                    'Raw NBT-tree preservation does not imply identical compressed bytes.',
                    'No FTB save bridge, task registry, events, party lifecycle, packet/UI integration or world/server startup.'],
})
(out / 'LegacyRetrievalProgressCodecCheck.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print(json.dumps(evidence))
