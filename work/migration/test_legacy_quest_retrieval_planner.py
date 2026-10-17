"""Execute the pinned BQ QuestInstance.detect loop against the pure planner.

The quest loop and database ordering methods are extracted unchanged. Retrieval
tasks execute the original BQ methods using the existing deterministic adapter
definitions, loaded as AST literals without executing that runner. All generated
third-party source/class files remain under ignored work/migration/quest-planner-check.
"""
from pathlib import Path
import ast
import hashlib
import json
import os
import subprocess

R = Path(__file__).resolve().parents[2]
inputs = Path(os.environ.get('GTNH_TEST_INPUT_ROOT', str(R)))
java = Path(json.loads((inputs / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
source_root = inputs / 'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java'
pins = {
    'bq_standard/tasks/TaskRetrieval.java': '8ac57331877e643a95c5c5d39966cce8a3d854d27319df80bee8514d19a22f71',
    'betterquesting/questing/QuestInstance.java': '723ed08931294710f7e287eccc7f6c042c9ff4bf5dd500183522758b4726bffb',
    'betterquesting/questing/tasks/TaskStorage.java': '2d72c4226afa772240cd883d386b0eac5c6770e6679d725e5282f4368a38b8ac',
    'betterquesting/api2/storage/SimpleDatabase.java': '7a9addf9389a651325d5e27c0004f182d3548389e36ec96b6131110569ceda89',
    'betterquesting/api2/storage/LookupLogic.java': '220c6021ac4edb2c6259b3650873acd3dbc3f5e458fcc97cc1df6ae3882cf60c',
}
sources = {}
for relative, expected in pins.items():
    path = source_root / relative
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, f'Source changed: {relative}'
    sources[relative] = path.read_text(encoding='utf-8')

out = R / 'work/migration/quest-planner-check'
out.mkdir(parents=True, exist_ok=True)
adapter = R / 'work/migration/test_legacy_retrieval_engine.py'
literals = {}
for node in ast.parse(adapter.read_text(encoding='utf-8')).body:
    if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
        if node.targets[0].id in ('header', 'tail'):
            literals[node.targets[0].id] = ast.literal_eval(node.value)
assert set(literals) == {'header', 'tail'}
retrieval = sources['bq_standard/tasks/TaskRetrieval.java']
detect = retrieval[retrieval.index('    public void detect('):retrieval.index('    // region IItemTask')]
observe_start = retrieval.index('    public void onInventoryChange(')
observe = retrieval[observe_start:retrieval.index('    @SuppressWarnings', observe_start)]
detector = retrieval[retrieval.index('    static class Detector {'):retrieval.rfind('\n}')]
retrieval_oracle = out / 'BqRetrievalReference.java'
retrieval_oracle.write_text(literals['header'] + detect + observe + detector + literals['tail'], encoding='utf-8', newline='\n')

quest_source = sources['betterquesting/questing/QuestInstance.java']
quest_detect = quest_source[quest_source.index('    public void detect('):quest_source.index('    @Override\n    public boolean hasClaimed(')]
database = sources['betterquesting/api2/storage/SimpleDatabase.java']
get_entries_start = database.index('    public synchronized List<DBEntry<T>> getEntries()')
get_entries = database[get_entries_start:database.index('\n    /**', get_entries_start)]
lookup = sources['betterquesting/api2/storage/LookupLogic.java']
get_cache = lookup[lookup.index('    public List<DBEntry<T>> getRefCache()'):lookup.index('    public abstract List<DBEntry<T>> bulkLookup')]
compute_cache = lookup[lookup.index('    protected void computeRefCache()'):lookup.rfind('\n}')]

header = r'''import java.util.*;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;

public final class BqQuestRetrievalReference {
    interface IQuest {}
    interface ITask {
        boolean isComplete(UUID id);
        void detect(ParticipantInfo participants, Map.Entry<UUID,IQuest> quest);
        boolean ignored(UUID id);
    }
    record DBEntry<T>(int id,T value) { T getValue() { return value; } }
    static final class SimpleDatabase<T> {
        final TreeMap<Integer,T> mapDB=new TreeMap<>();
        final LookupLogic<T> logic=new LookupLogic<>(this);
        LookupLogic<T> getLookupLogic() { return logic; }
        int size() { return mapDB.size(); }
'''
middle = r'''
    }
    static final class LookupLogic<T> {
        final SimpleDatabase<T> simpleDatabase;
        List<DBEntry<T>> refCache;
        LookupLogic(SimpleDatabase<T> database) { simpleDatabase=database; }
'''
fixture = r'''
    }
    static final class QuestCache {
        static final String LOC_QUEST_CACHE="fixture";
        boolean dirty;
        void markQuestDirty(UUID id) { dirty=true; }
    }
    static final class EntityPlayer {
        Context<String> context;
        final Gate gate;
        final QuestCache cache=new QuestCache();
        boolean evaluated;
        EntityPlayer(Context<String> context,Gate gate) { this.context=context; this.gate=gate; }
        Object getExtendedProperties(String key) { return gate.questCacheAvailable()?cache:null; }
    }
    static final class QuestingAPI {
        static UUID getQuestingUUID(EntityPlayer player) { return player.context.actor(); }
    }
    static final class ParticipantInfo {
        final EntityPlayer player;
        ParticipantInfo(EntityPlayer player) { this.player=player; player.evaluated=true; }
    }
    static final class QuestDatabase {
        static final QuestDatabase INSTANCE=new QuestDatabase();
        UUID lookupKey(SourceQuest quest) { return quest.id; }
    }
    static final class Maps {
        static <A,B> Map.Entry<A,B> immutableEntry(A a,B b) { return Map.entry(a,b); }
    }
    record Property<T>(String key) {}
    static final class Logic {
        boolean result;
        boolean getResult(int done,int total) { result=done>=total; return result; }
    }
    static final class NativeProps {
        static final Property<Long> REPEAT_TIME=new Property<>("repeat");
        static final Property<Boolean> SIMULTANEOUS=new Property<>("simultaneous");
        static final Property<Boolean> EDIT_MODE=new Property<>("edit");
        static final Property<Logic> LOGIC_TASK=new Property<>("logic");
    }
    static final class Properties {
        final Map<Property<?>,Object> values=new HashMap<>();
        @SuppressWarnings("unchecked") <T>T getProperty(Property<T> key) { return (T)values.get(key); }
    }
    static final class QuestSettings {
        static final QuestSettings INSTANCE=new QuestSettings();
        boolean getProperty(Property<Boolean> key) { return false; }
    }
    static final class RetrievalTask implements ITask {
        final Task<String> declaration;
        final List<Detection<String>> detections;
        final List<Consumption<String>> consumed;
        State state;
        RetrievalTask(Task<String> declaration,State state,List<Detection<String>> detections,List<Consumption<String>> consumed) {
            this.declaration=declaration; this.state=state; this.detections=detections; this.consumed=consumed;
        }
        public boolean isComplete(UUID id) { return state.completed().contains(id); }
        public boolean ignored(UUID id) { return false; }
        public void detect(ParticipantInfo participants,Map.Entry<UUID,IQuest> quest) {
            EntityPlayer player=participants.player;
            Result<String> result=BqRetrievalReference.run(declaration.retrieval(),state,player.context,true);
            state=result.state();
            player.context=new Context<>(player.context.actor(),player.context.allMembers(),result.inventories());
            detections.add(new Detection<>(declaration.index(),result)); consumed.addAll(result.consumed());
        }
    }
    static final class SourceQuest implements IQuest {
        final UUID id;
        final EntityPlayer player;
        final SimpleDatabase<ITask> tasks=new SimpleDatabase<>();
        final List<Object> rewards=List.of(new Object());
        final Properties qInfo=new Properties();
        final Logic logic=new Logic();
        SourceQuest(Quest<String> quest,EntityPlayer player) {
            id=quest.id(); this.player=player;
            qInfo.values.put(NativeProps.REPEAT_TIME,quest.repeatTime());
            qInfo.values.put(NativeProps.SIMULTANEOUS,false);
            qInfo.values.put(NativeProps.LOGIC_TASK,logic);
        }
        boolean isComplete(UUID id) { return player.gate.questAlreadyComplete(); }
        boolean canSubmit(EntityPlayer player) { return player.gate.canSubmit(); }
        boolean isUnlocked(UUID id) { return player.gate.unlocked(); }
        void setComplete(UUID id,long time) { throw new AssertionError("Unsupported edit mode reached"); }
        void resetUser(UUID id,boolean rewards) { throw new AssertionError("Unsupported simultaneous mode reached"); }
'''
tail = r'''
    }
    public static Plan<String> run(Quest<String> declaration,Map<Integer,State> previous,Context<String> context,Gate gate) {
        EntityPlayer player=new EntityPlayer(context,gate);
        SourceQuest quest=new SourceQuest(declaration,player);
        Map<Integer,State> before=new LinkedHashMap<>(previous);
        List<Detection<String>> detections=new ArrayList<>();
        List<Consumption<String>> consumed=new ArrayList<>();
        for(Task<String> task:declaration.tasks()) {
            before.putIfAbsent(task.index(),State.empty());
            quest.tasks.mapDB.put(task.index(),new RetrievalTask(task,before.get(task.index()),detections,consumed));
        }
        quest.detect(player);
        Map<Integer,State> after=new LinkedHashMap<>(before);
        quest.tasks.mapDB.forEach((index,task)->after.put(index,((RetrievalTask)task).state));
        return new Plan<>(declaration.id(),before,after,context.inventories(),player.context.inventories(),
                detections,consumed,player.evaluated,quest.logic.result,player.cache.dirty);
    }
}
'''
quest_oracle = out / 'BqQuestRetrievalReference.java'
quest_oracle.write_text(header + get_entries + middle + get_cache + compute_cache + fixture + quest_detect + tail,
                        encoding='utf-8', newline='\n')
engine = R / 'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalEngine.java'
planner = engine.with_name('LegacyQuestRetrievalPlanner.java')
test = R / 'work/migration/LegacyQuestRetrievalPlannerCheck.java'

def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()

def run(executable, args, name):
    def quote(value):
        assert not any(char in value for char in '\0\r\n')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    argfile = out / f'{name}.args'
    argfile.write_text('\n'.join(quote(value) for value in args) + '\n', encoding='utf-8', newline='\n')
    completed = subprocess.run([str(executable), '@' + argfile.name], cwd=out, capture_output=True, timeout=60)
    log = (completed.stdout + completed.stderr).decode('utf-8', errors='replace')
    (out / f'{name}.log').write_text(log, encoding='utf-8', newline='\n')
    assert completed.returncode == 0, f'{name} failed:\n{log}'
    return log

run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-d', '.',
                                *[relative(path) for path in [engine, planner, test, retrieval_oracle, quest_oracle]]], 'compile')
log = run(java, ['-cp', '.', 'LegacyQuestRetrievalPlannerCheck'], 'planner')
report = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
report.update({
    'source_commit': '815d738923d46e9083f988421e5329193e2e5940',
    'upstream_source_sha256': pins,
    'source_sha256': {path.relative_to(R).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                      for path in [engine, planner, test, Path(__file__), adapter]},
    'oracle_sha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in [retrieval_oracle, quest_oracle]},
    'source_methods_extracted_unchanged': ['QuestInstance.detect', 'SimpleDatabase.getEntries',
        'LookupLogic.getRefCache', 'LookupLogic.computeRefCache',
        'TaskRetrieval.detect', 'TaskRetrieval.checkAndComplete', 'TaskRetrieval.onInventoryChange', 'TaskRetrieval.Detector'],
    'limitations': [
        'Only AND, nonrepeat, nonsimultaneous, nonglobal, nonignored retrieval tasks; unsupported declarations block the whole plan.',
        'Gate values, player/quest/cache classes and AND property logic are deterministic fixtures, not runtime authorization evidence.',
        'The original task loop and database ordering run; Minecraft inventories, NBT/item predicates and task storage are fixture adapters.',
        'K must be immutable or a deep snapshot owned by the caller; predicates must be pure.',
        'No persistence, durable transaction, FTB callback, actual inventory write, quest completion or reward is implemented.',
    ],
})
(out / 'LegacyQuestRetrievalPlannerCheck.json').write_text(json.dumps(report, indent=2), encoding='utf-8', newline='\n')
print(json.dumps(report))
