"""Test the unintegrated engine against executable, hash-pinned BQ methods.

Only fixture inventory/item classes replace Minecraft. Original detect,
checkAndComplete, onInventoryChange and Detector code is extracted unchanged.
No dependency files, source archive or old workspace is modified.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess

R = Path(__file__).resolve().parents[2]
inputs = Path(os.environ.get('GTNH_TEST_INPUT_ROOT', str(R)))
java = Path(json.loads((inputs / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
source = inputs / 'work/exact-sources/BetterQuesting/3.8.89-GTNH/src/main/java/bq_standard/tasks/TaskRetrieval.java'
source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
assert source_hash == '8ac57331877e643a95c5c5d39966cce8a3d854d27319df80bee8514d19a22f71', 'BQ source changed; re-audit before testing'
original = source.read_text(encoding='utf-8')
detect = original[original.index('    public void detect('):original.index('    // region IItemTask')]
observe_start = original.index('    public void onInventoryChange(')
observe = original[observe_start:original.index('    @SuppressWarnings', observe_start)]
detector = original[original.index('    static class Detector {'):original.rfind('\n}')]
out = R / 'work/migration/retrieval-engine-check'
out.mkdir(parents=True, exist_ok=True)

# This generated oracle is a local test fixture, not production mod code.
# Counter algorithms below are taken directly from the pinned upstream file;
# adapters supply snapshots and an injected predicate instead of real MC NBT.
header = r'''import java.util.*;
import java.util.function.*;
import java.util.stream.IntStream;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;

public final class BqRetrievalReference {
    @interface Nonnull {}
    interface IQuest {}
    record Tuple2<A,B>(A first,B second) {
        A getFirst() { return first; }
        B getSecond() { return second; }
    }
    static final class ItemStack {
        String item; int stackSize; Predicate<? super String> predicate;
        ItemStack(String item,int count) { this.item=item; stackSize=count; }
        ItemStack(Predicate<? super String> predicate) { this.predicate=predicate; stackSize=1; }
    }
    static final class BigItemStack {
        final Requirement<String> requirement; final int stackSize;
        BigItemStack(Requirement<String> requirement) { this.requirement=requirement; stackSize=requirement.count(); }
        ItemStack getBaseStack() { return new ItemStack(requirement.matches()); }
        Object getOreIngredient() { return null; }
        Object GetTagCompound() { return null; }
    }
    static final class ItemComparison {
        static boolean StackMatch(ItemStack requirement,ItemStack sample,boolean checkNbt,boolean partial) {
            return requirement.predicate.test(sample.item);
        }
        static boolean OreDictionaryMatch(Object ore,Object nbt,ItemStack sample,boolean checkNbt,boolean partial) {
            return false; // The injected predicate already supplies the full match decision.
        }
    }
    static final class InventoryPlayer {
        final UUID owner;
        final List<Slot<String>> originals;
        final List<ItemStack> slots=new ArrayList<>();
        final List<Consumption<String>> consumed;
        InventoryPlayer(UUID owner,List<Slot<String>> input,List<Consumption<String>> consumed) {
            this.owner=owner; originals=input; this.consumed=consumed;
            for (Slot<String> slot:input) slots.add(slot.count()==0?null:new ItemStack(slot.item(),slot.count()));
        }
        int getSizeInventory() { return slots.size(); }
        ItemStack getStackInSlot(int i) { return slots.get(i); }
        ItemStack decrStackSize(int i,int count) {
            ItemStack slot=slots.get(i);
            if (slot==null) return null;
            int removed=Math.min(count,slot.stackSize);
            consumed.add(new Consumption<>(owner,originals.get(i).index(),slot.item,removed));
            ItemStack result=new ItemStack(slot.item,removed);
            if (slot.stackSize<=count) slots.set(i,null);
            else slot.stackSize-=count;
            return result;
        }
        List<Slot<String>> snapshot() {
            List<Slot<String>> result=new ArrayList<>();
            for (int i=0;i<slots.size();i++) {
                ItemStack slot=slots.get(i); Slot<String> old=originals.get(i);
                result.add(new Slot<>(old.index(),old.item(),slot==null?0:slot.stackSize));
            }
            return result;
        }
    }
    record Player(InventoryPlayer inventory) {}
    static final class ParticipantInfo {
        final UUID UUID; final Player PLAYER; final List<UUID> ALL_UUIDS; final List<Player> ACTIVE_PLAYERS;
        boolean dirty;
        ParticipantInfo(Context<String> context,Map<UUID,Player> players) {
            UUID=context.actor(); PLAYER=players.get(UUID); ALL_UUIDS=context.allMembers(); ACTIVE_PLAYERS=new ArrayList<>(players.values());
        }
        void markDirtyParty(UUID quest) { dirty=true; }
    }
    static final class TaskRetrieval {
        boolean consume,autoConsume,groupDetect,requireOnlyOneItem,ignoreNBT,partialMatch;
        final List<BigItemStack> requiredItems=new ArrayList<>();
        final Map<UUID,int[]> progress=new LinkedHashMap<>();
        final Set<UUID> completeUsers=new LinkedHashSet<>();
        boolean progressWritten;
        int[] getUsersProgress(UUID id) {
            int[] row=progress.get(id);
            return row==null || row.length!=requiredItems.size()?new int[requiredItems.size()]:row;
        }
        List<Tuple2<UUID,int[]>> getBulkProgress(List<UUID> ids) {
            return ids.stream().map(id->new Tuple2<>(id,getUsersProgress(id))).toList();
        }
        void setBulkProgress(List<Tuple2<UUID,int[]>> rows) {
            progressWritten=true;
            rows.forEach(row->progress.put(row.getFirst(),row.getSecond()));
        }
        boolean isComplete(UUID id) { return completeUsers.contains(id); }
        void setComplete(UUID id) { completeUsers.add(id); }
'''
tail = r'''
    }
    public static Result<String> run(Config<String> config,State state,Context<String> context,boolean manual) {
        TaskRetrieval task=new TaskRetrieval();
        task.consume=config.consume(); task.autoConsume=config.autoConsume(); task.groupDetect=config.groupDetect();
        task.requireOnlyOneItem=config.requireOnlyOneItem(); task.partialMatch=true;
        config.requirements().forEach(requirement->task.requiredItems.add(new BigItemStack(requirement)));
        state.progress().forEach((id,row)->task.progress.put(id,row.stream().mapToInt(Integer::intValue).toArray()));
        task.completeUsers.addAll(state.completed());
        List<Consumption<String>> consumed=new ArrayList<>();
        Map<UUID,Player> players=new LinkedHashMap<>();
        context.inventories().forEach((id,slots)->players.put(id,new Player(new InventoryPlayer(id,slots,consumed))));
        ParticipantInfo info=new ParticipantInfo(context,players);
        Map.Entry<UUID,IQuest> quest=Map.entry(new UUID(0,2),new IQuest(){});
        if (manual) task.detect(info,quest);
        else task.onInventoryChange(quest,info);
        Map<UUID,List<Integer>> progress=new LinkedHashMap<>();
        task.progress.forEach((id,row)->progress.put(id,Arrays.stream(row).boxed().toList()));
        Map<UUID,List<Slot<String>>> inventories=new LinkedHashMap<>();
        players.forEach((id,player)->inventories.put(id,player.inventory.snapshot()));
        return new Result<>(new State(progress,task.completeUsers),inventories,consumed,task.progressWritten,info.dirty);
    }
}
'''
oracle = out / 'BqRetrievalReference.java'
oracle.write_text(header + detect + observe + detector + tail, encoding='utf-8')
engine = R / 'work/compat-mod/src/main/java/org/gtnhmigration/compat/LegacyRetrievalEngine.java'
test = R / 'work/migration/LegacyRetrievalEngineCheck.java'

def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()

def run(executable, args, name):
    def quote(value):
        assert not any(c in value for c in '\x00\r\n')
        return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'
    argfile = out / f'{name}.args'
    argfile.write_text('\n'.join(quote(value) for value in args) + '\n', encoding='utf-8')
    completed = subprocess.run([str(executable), '@' + str(argfile)], cwd=out, capture_output=True, timeout=60)
    log = (completed.stdout + completed.stderr).decode('utf-8', errors='replace')
    (out / f'{name}.log').write_text(log, encoding='utf-8')
    assert completed.returncode == 0, f'{name} failed:\n{log}'
    return log

run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-d', '.', relative(engine), relative(test), oracle.name], 'compile')
log = run(java, ['-cp', '.', 'LegacyRetrievalEngineCheck'], 'engine')
evidence = json.loads(next(line for line in log.splitlines() if line.startswith('{')))
evidence.update({
    'source_commit': '815d738923d46e9083f988421e5329193e2e5940',
    'source_file_sha256': source_hash,
    'engine_source_sha256': hashlib.sha256(engine.read_bytes()).hexdigest(),
    'test_source_sha256': hashlib.sha256(test.read_bytes()).hexdigest(),
    'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'oracle_fixture_sha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
    'source_methods_extracted_unchanged': ['detect', 'checkAndComplete', 'onInventoryChange', 'Detector'],
    'oracle_fixture_boundaries': ['ItemStack and InventoryPlayer are deterministic count/slot fixtures.',
                                 'ItemComparison delegates to an injected match predicate; ore branch returns false.',
                                 'ParticipantInfo, progress storage and completion marking are in-memory fixtures.'],
    'supported_modes': ['AND, groupDetect=false, nonconsuming multiple requirements', 'single-requirement manual consumption'],
    'inventory_slot_selection_is_caller_contract': True,
    'limitations': ['Item/NBT/ore matching is injected; fixture predicates are not Minecraft registry evidence.',
                    'No actual game inventory writes, persistence, party lifecycle, UI or packet integration.',
                    'groupDetect, OR, auto-consume and multi-requirement consumption are rejected.',
                    'Positive requirements and nonnegative saved counters are required; overflow rejects the plan instead of reproducing legacy Java wraparound.'],
})
(out / 'LegacyRetrievalEngineCheck.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print(json.dumps(evidence))
