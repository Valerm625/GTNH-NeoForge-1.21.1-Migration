"""Real 1.21.1 NBT and fixed BetterQuesting outer progress routing methods.

Candidate-model test only: no JSON importer, file storage, game events or exports.
All generated fixtures, classes and reports stay in quest-progress-envelope-check.
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
    'betterquesting/questing/QuestDatabase.java': '5a127eb3c1cc6d329a9ee98fca27197383903c4f634baca80ef265b1481743e4',
    'betterquesting/questing/QuestInstance.java': '723ed08931294710f7e287eccc7f6c042c9ff4bf5dd500183522758b4726bffb',
    'betterquesting/questing/tasks/TaskStorage.java': '2d72c4226afa772240cd883d386b0eac5c6770e6679d725e5282f4368a38b8ac',
    'betterquesting/api/utils/NBTConverter.java': 'f6a0362455bc0d226974313cd507e8be304bcb52175e3a40f9a14da8516e0db0',
    'betterquesting/api/utils/UuidConverter.java': '5f926386eeeb753dc0c18012ba5e4a6129211668e905fc0deb6d821e3819bc9f',
}
sources = {}
for name, expected in source_hashes.items():
    path = source_root / name
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, f'Changed BQ source: {name}'
    sources[name] = path.read_text(encoding='utf-8')


def method(text, signature):
    start = text.index('    ' + signature)
    pos = text.index('{', start)
    depth, end = 1, pos + 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end] + '\n'


database_method = method(sources['betterquesting/questing/QuestDatabase.java'], 'public synchronized void readProgressFromNBT(')
quest_method = method(sources['betterquesting/questing/QuestInstance.java'], 'public void readProgressFromNBT(')
task_method = method(sources['betterquesting/questing/tasks/TaskStorage.java'], 'public void readProgressFromNBT(')
uuid_methods = ''.join(method(sources['betterquesting/api/utils/NBTConverter.java'], signature)
                       for signature in ['public Optional<UUID> tryReadId(', 'public UUID readId('])
legacy_method = method(sources['betterquesting/api/utils/UuidConverter.java'], 'public static UUID convertLegacyId(')

out = R / 'work/migration/quest-progress-envelope-check'
out.mkdir(parents=True, exist_ok=True)
classes = out / 'classes'
classes.mkdir(exist_ok=True)
oracle = out / 'BqEnvelopeReference.java'
header = r'''import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine;
import org.gtnhmigration.compat.LegacyRetrievalProgressCodec;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;

/** Only routing methods are an independent source oracle. Inner task persistence
 * delegates to the already separately checked codec; no 1.7.10 runtime is used. */
public final class BqEnvelopeReference {
    enum Level { ERROR }
    static final class Log { void log(Level level,String message,Exception error) {} }
    static final class BetterQuesting { static final Log logger=new Log(); }
    static final class Preconditions {
        static void checkArgument(boolean valid) { if (!valid) throw new IllegalArgumentException(); }
    }
    static final class NBTTagCompound {
        final CompoundTag value;
        NBTTagCompound(CompoundTag value) { this.value=value; }
        boolean hasKey(String key,int type) { return value.contains(key,type); }
        int getInteger(String key) { return value.getInt(key); }
        long getLong(String key) { return value.getLong(key); }
        String getString(String key) { return value.getString(key); }
        NBTTagList getTagList(String key,int type) { return new NBTTagList(value.getList(key,type)); }
        NBTTagCompound getCompoundTag(String key) { return new NBTTagCompound(value.getCompound(key)); }
        Object copy() { return new NBTTagCompound(value.copy()); }
    }
    static final class NBTTagList {
        final ListTag value;
        NBTTagList(ListTag value) { this.value=value; }
        int tagCount() { return value.size(); }
        NBTTagCompound getCompoundTagAt(int index) { return new NBTTagCompound(value.getCompound(index)); }
    }
    // Valid canonical factory strings only. No old ResourceLocation parser claim.
    record ResourceLocation(String value) {}
    static final class NBTConverter {
        static final class UuidValueType {
            static final UuidValueType QUEST=new UuidValueType();
            final String highIdFieldName="questIDHigh", lowIdFieldName="questIDLow";
'''
between1 = r'''
        }
    }
    static final class UuidConverter {
'''
between2 = r'''
    }
    interface ITask {
        ResourceLocation getFactoryID();
        void readProgressFromNBT(NBTTagCompound data,boolean merge);
    }
    static final class RetrievalTask implements ITask {
        final Definition definition;
        LegacyRetrievalEngine.State state;
        boolean touched;
        RetrievalTask(Definition definition,LegacyRetrievalEngine.State state) {
            this.definition=definition; this.state=state;
        }
        public ResourceLocation getFactoryID() { return new ResourceLocation(definition.key().factoryId()); }
        public void readProgressFromNBT(NBTTagCompound data,boolean merge) {
            CompoundTag payload=data.value.copy(); payload.remove("index"); payload.remove("taskID");
            var result=LegacyRetrievalProgressCodec.readLegacy(payload,definition.requirementCount(),state,merge);
            if (!result.active()) throw new AssertionError("Oracle input outside recognized inner domain: "+result.problems());
            state=result.state().orElseThrow(); touched=true;
        }
    }
    // Required to compile unchanged TaskStorage branches; unsupported by tests/model.
    static final class TaskPlaceholder implements ITask {
        public ResourceLocation getFactoryID() { return new ResourceLocation("betterquesting:placeholder"); }
        public void readProgressFromNBT(NBTTagCompound data,boolean merge) { throw new AssertionError("Placeholder oracle forbidden"); }
        void setTaskProgressData(NBTTagCompound data) { throw new AssertionError("Placeholder oracle forbidden"); }
    }
    static final class FactoryTaskPlaceholder {
        static final FactoryTaskPlaceholder INSTANCE=new FactoryTaskPlaceholder();
        ResourceLocation getRegistryName() { return new ResourceLocation("betterquesting:placeholder"); }
    }
    static final class TaskStorage {
        final Map<Integer,ITask> values=new HashMap<>();
        ITask getValue(int index) { return values.get(index); }
'''
between3 = r'''
    }
    static final class QuestInstance {
        final Map<UUID,NBTTagCompound> completeUsers=new HashMap<>();
        final TaskStorage tasks=new TaskStorage();
'''
between4 = r'''
    }
    interface IQuest { void readProgressFromNBT(NBTTagCompound data,boolean merge); }
    static final class QuestAdapter implements IQuest {
        final QuestInstance quest=new QuestInstance();
        boolean touched;
        public void readProgressFromNBT(NBTTagCompound data,boolean merge) {
            touched=true; quest.readProgressFromNBT(data,merge);
        }
    }
    static final class QuestDatabase {
        final Map<UUID,IQuest> quests=new HashMap<>();
        IQuest get(UUID id) { return quests.get(id); }
'''
tail = r'''
    }
    public static State read(CompoundTag input,Catalog catalog,State previous,boolean merge) {
        QuestDatabase database=new QuestDatabase();
        Map<UUID,Map<UUID,CompoundTag>> completions=new LinkedHashMap<>(previous.questCompletions());
        Map<TaskKey,LegacyRetrievalEngine.State> states=new LinkedHashMap<>(previous.tasks());
        for (UUID id:catalog.quests()) {
            QuestAdapter adapter=new QuestAdapter();
            previous.questCompletions().getOrDefault(id,Map.of()).forEach((player,data)->
                    adapter.quest.completeUsers.put(player,new NBTTagCompound(data.copy())));
            database.quests.put(id,adapter);
        }
        for (Definition definition:catalog.definitions()) {
            QuestAdapter adapter=(QuestAdapter)database.get(definition.key().questId());
            adapter.quest.tasks.values.put(definition.key().index(),new RetrievalTask(definition,
                    previous.tasks().getOrDefault(definition.key(),LegacyRetrievalEngine.State.empty())));
        }
        database.readProgressFromNBT(new NBTTagList(input.getList("questProgress",Tag.TAG_COMPOUND)),merge);
        database.quests.forEach((id,value)->{
            QuestAdapter adapter=(QuestAdapter)value;
            if (adapter.touched) {
                Map<UUID,CompoundTag> rows=new LinkedHashMap<>();
                adapter.quest.completeUsers.forEach((player,data)->rows.put(player,data.value.copy()));
                completions.put(id,rows);
            }
            adapter.quest.tasks.values.values().forEach(task->{
                RetrievalTask retrieval=(RetrievalTask)task;
                if (retrieval.touched) states.put(retrieval.definition.key(),retrieval.state);
            });
        });
        return new State(completions,states);
    }
}
'''
oracle.write_text(header + uuid_methods + between1 + legacy_method + between2 + task_method
                  + between3 + quest_method + between4 + database_method + tail,
                  encoding='utf-8', newline='\n')

launch = (O / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries = [O / part for part in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
minecraft = O / 'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
libraries = [minecraft] + libraries


def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()


classpath = ';'.join(relative(path) for path in libraries)
engine = R / 'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalEngine.java'
codec = engine.with_name('LegacyRetrievalProgressCodec.java')
envelope = engine.with_name('LegacyQuestProgressEnvelope.java')
check = R / 'work/migration/LegacyQuestProgressEnvelopeCheck.java'


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
    '-d', 'classes', relative(engine), relative(codec), relative(envelope), relative(check), oracle.name], 'compile')
log = run(java, ['-cp', 'classes;' + classpath, 'LegacyQuestProgressEnvelopeCheck'], 'envelope')
evidence = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
bound_sources = {'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java/' + name: value
                 for name, value in source_hashes.items()}
for path in [engine, codec, envelope, check, Path(__file__).resolve()]:
    bound_sources[path.relative_to(R).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
evidence.update({
    'source_commit': '815d738923d46e9083f988421e5329193e2e5940',
    'source_sha256': bound_sources,
    'minecraft_jar_sha256': hashlib.sha256(minecraft.read_bytes()).hexdigest(),
    'oracle_fixture_sha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
    'compiled_classes_directory': classes.relative_to(R).as_posix(),
    'source_methods_extracted_unchanged': ['QuestDatabase.readProgressFromNBT', 'QuestInstance.readProgressFromNBT',
        'TaskStorage.readProgressFromNBT', 'NBTConverter.UuidValueType.tryReadId', 'NBTConverter.UuidValueType.readId',
        'UuidConverter.convertLegacyId'],
    'oracle_boundaries': ['Six fixed routing methods execute unchanged; old NBT method names delegate to real 1.21.1 NBT.',
        'The inner retrieval codec is reused, not an independent inner-persistence oracle in this test.',
        'Canonical factory equality, definition registry, logger and touched-state bookkeeping are fixtures.',
        'Strict rejection tests intentionally differ from source coercion, silent skip and placeholder fallback.'],
    'limitations': ['Accepted means interpreted candidate state, not a runnable game save.',
        'Quest completion compounds are opaque except their UUID; no claimed/timestamp or reward behavior is executed.',
        'No JSON parsing, real player-save import, file order selection, storage writes or cross-file lifecycle.',
        'No FTB task registration, events, network, game runtime, server/world startup or export integration.',
        'Raw NBT tree preservation does not assert identical serialized/compressed bytes.'],
})
(out / 'LegacyQuestProgressEnvelopeCheck.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8', newline='\n')
print(json.dumps(evidence))
