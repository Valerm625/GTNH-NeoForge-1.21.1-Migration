"""Exercise packaged queue, fixed BQ source methods and actual vanilla menus.

Bridge classes are inspected, not manually transformed. No game event dispatch,
world/server startup, EULA change, pipeline edit or runtime configuration edit.
"""
from pathlib import Path
import hashlib
import json
import os
import re
import subprocess
import zipfile

R = Path(__file__).resolve().parents[2]
inputs = Path(os.environ.get('GTNH_TEST_INPUT_ROOT', str(R)))
O = inputs / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation'
java = Path(json.loads((inputs / 'work/migration/runtime.json').read_text(encoding='utf-8'))['java'])
out = R / 'work/migration/inventory-observer-check'
out.mkdir(parents=True, exist_ok=True)
source_hashes = {
    'BetterQuesting/3.8.89-GTNH/src/main/java/bq_standard/handlers/PlayerContainerListener.java': '416c037c5ab874cc953ca485bf47afca3df30ea5bfc64fdee1ad4b2bdb5b36ea',
    'BetterQuesting/3.8.89-GTNH/src/main/java/bq_standard/handlers/EventHandler.java': 'cf81446168721ec29a0634042f0c3052aca7dd13d0a4ad86f7f27a4ec0b73ce1',
    'FTB-Quests/v2101.1.36/common/src/main/java/dev/ftb/mods/ftbquests/util/FTBQuestsInventoryListener.java': '80486cff499c2393ee95a36707a52e90def300d221b151fdb6afce95b53962fa',
    'FTB-Quests/v2101.1.36/common/src/main/java/dev/ftb/mods/ftbquests/quest/ServerQuestFile.java': 'becb3d571f490203a9893034543a62d7847ba890b387045235fb437fcde7ebbf',
    'FTB-Quests/v2101.1.36/common/src/main/java/dev/ftb/mods/ftbquests/quest/task/Task.java': 'f11d0979aa9418579a083cf60b0c070141dbcc1724daac214091d178ca2f649a',
}
sources = {}
for name, expected in source_hashes.items():
    file = inputs / 'work/exact-sources' / name
    assert hashlib.sha256(file.read_bytes()).hexdigest() == expected, f'Re-audit changed source: {name}'
    sources[name.split('/')[-1]] = file.read_text(encoding='utf-8')

def method(text, signature):
    start = text.index('    ' + signature)
    end = text.index('{', start) + 1
    depth = 1
    while depth:
        if text[end] == '{':
            depth += 1
        elif text[end] == '}':
            depth -= 1
        end += 1
    return text[start:end] + '\n'

listener_methods = ''.join(method(sources['PlayerContainerListener.java'], s) for s in [
    'public void sendContainerAndContentsToPlayer(', 'public void sendSlotContents(', 'private void updateTasks('])
event_methods = ''.join(method(sources['EventHandler.java'], s) for s in [
    'public static void schedulePlayerInventoryCheck(', 'public static void cleanup(', 'public void onServerTick('])
header = r'''import java.util.*;
public final class BqInventoryReference {
    static final Set<String> OBSERVED=new HashSet<>();
    enum Phase { START, END }
    record ServerTickEvent(Phase phase) {}
    static class EntityPlayer { final String name; final Object inventory=new Object(); EntityPlayer(String name){this.name=name;} }
    public static final class Player extends EntityPlayer { Player(String name){super(name);} }
    static final class Container {}
    record ItemStack(boolean empty) {}
    interface ITask {}
    interface ITaskInventory extends ITask { void onInventoryChange(Map.Entry<UUID,IQuest> entry,ParticipantInfo info); }
    record DBEntry<T>(T value) { T getValue(){return value;} }
    static final class TaskStorage {
        List<DBEntry<ITask>> getEntries(){return List.of(new DBEntry<>((ITaskInventory)(entry,info)->OBSERVED.add(info.player.name)));}
    }
    interface IQuest { TaskStorage getTasks(); }
    static final class ParticipantInfo {
        final EntityPlayer player;ParticipantInfo(EntityPlayer player){this.player=player;}
        Set<UUID> getSharedQuests(){return Set.of(new UUID(0,1));}
    }
    static final class ApiReference { static final Object QUEST_DB=new Object(); }
    static final class Database {
        Map<UUID,IQuest> filterKeys(Set<UUID> keys){return Map.of(new UUID(0,1),()->new TaskStorage());}
    }
    static final class QuestingAPI { static Database getAPI(Object key){return new Database();} }
    static final class PlayerContainerListener {
        private final EntityPlayer player;
        PlayerContainerListener(EntityPlayer player){this.player=player;}
'''
between = r'''
    }
    static final class EventHandler {
        private static final HashSet<EntityPlayer> playerInventoryUpdates=new HashSet<>();
'''
tail = r'''
    }
    public static Player player(String name){return new Player(name);}
    public static void slot(Player player,int index,boolean empty){new PlayerContainerListener(player).sendSlotContents(new Container(),index,empty?null:new ItemStack(false));}
    public static void initial(Player player){new PlayerContainerListener(player).sendContainerAndContentsToPlayer(new Container(),List.of());}
    public static void tick(boolean start){new EventHandler().onServerTick(new ServerTickEvent(start?Phase.START:Phase.END));}
    public static Set<String> observed(){return Set.copyOf(OBSERVED);}
    public static void cleanup(){EventHandler.cleanup();}
    public static void reset(){cleanup();OBSERVED.clear();}
}
'''
oracle = out / 'BqInventoryReference.java'
oracle.write_text(header + listener_methods + between + event_methods + tail, encoding='utf-8')

launch = (O / 'libraries/net/neoforged/neoforge/21.1.262/win_args.txt').read_text(encoding='utf-8')
libraries = [O / path for path in re.search(r'^-DlegacyClassPath=(.*)$', launch, re.M).group(1).strip().split(';')]
minecraft = O / 'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar'
extra = minecraft.with_name('server-1.21.1-20240808.144430-extra.jar')
compat = R / 'outputs/GTNH-NeoForge-1.21.1-Migration-Foundation/mods/gtnh-migration-compat-0.1.0.jar'
ftb = O / 'mods/ftb-quests-neoforge-2101.1.36.jar'
libraries = [compat, minecraft, extra, O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar',
             O / 'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar'] + libraries + sorted((O / 'mods').glob('*.jar'))
def relative(path):
    return Path(os.path.relpath(path, out)).as_posix()
classpath = ';'.join(relative(path) for path in libraries)
check = R / 'work/migration/LegacyInventoryObserverCheck.java'
source_dir = R / 'work/compat-mod/src/main/java/org/gtnhmigration/compat'
names = ['LegacyInventoryRescanQueue', 'LegacyInventoryObserver', 'LegacyInventoryListenerMixin',
         'LegacyTaskLoginTimingMixin', 'LegacyInitialInventorySyncMixin']
compat_hash = hashlib.sha256(compat.read_bytes()).hexdigest()
adapter_source_hashes = {name: hashlib.sha256((source_dir / (name + '.java')).read_bytes()).hexdigest() for name in names}
recompiled = out / 'source-classes'
recompiled.mkdir(exist_ok=True)

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

run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath,
    '-d', 'source-classes'] + [relative(source_dir / (name + '.java')) for name in names], 'source-compile')
compiled_hashes = {}
with zipfile.ZipFile(compat) as jar:
    config = json.loads(jar.read('gtnh_migration_compat.mixins.json'))
    for mixin in ['LegacyInventoryListenerMixin', 'LegacyTaskLoginTimingMixin', 'LegacyInitialInventorySyncMixin']:
        assert mixin in config['mixins'], f'Bridge missing from packaged config: {mixin}'
    for name in names:
        path = 'org/gtnhmigration/compat/' + name + '.class'
        assert jar.read(path) == (recompiled / path).read_bytes(), f'Stale packaged bridge class: {name}'
        compiled_hashes[name] = hashlib.sha256(jar.read(path)).hexdigest()
run(java.with_name('javac.exe'), ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', classpath,
    '-d', '.', relative(check), oracle.name], 'check-compile')
log = run(java, ['-cp', '.;' + classpath, 'LegacyInventoryObserverCheck', relative(compat), relative(ftb), relative(minecraft)], 'observer')
evidence = json.loads(next(line[line.index('{'): ] for line in log.splitlines() if '{"test":' in line))
assert hashlib.sha256(compat.read_bytes()).hexdigest() == compat_hash, 'Compat JAR changed during testing; rerun'
assert adapter_source_hashes == {name: hashlib.sha256((source_dir / (name + '.java')).read_bytes()).hexdigest() for name in names}, 'Bridge sources changed during testing; rerun'
evidence.update({
    'bq_source_commit': '815d738923d46e9083f988421e5329193e2e5940',
    'ftb_source_commit': '8c53f35a97d8897c861b097f1e39f4fc3beb3a15',
    'source_file_sha256': source_hashes,
    'adapter_source_sha256': adapter_source_hashes,
    'source_sha256': {
        **{'work/compat-mod/src/main/java/org/gtnhmigration/compat/' + name + '.java': digest for name, digest in adapter_source_hashes.items()},
        'work/migration/LegacyInventoryObserverCheck.java': hashlib.sha256(check.read_bytes()).hexdigest(),
        'work/migration/test_legacy_inventory_observer.py': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    },
    'adapter_compiled_sha256': compiled_hashes,
    'test_source_sha256': hashlib.sha256(check.read_bytes()).hexdigest(),
    'runner_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
    'oracle_fixture_sha256': hashlib.sha256(oracle.read_bytes()).hexdigest(),
    'compat_jar_sha256': compat_hash,
    'ftb_artifact_sha256': hashlib.sha256(ftb.read_bytes()).hexdigest(),
    'minecraft_artifact_sha256': hashlib.sha256(minecraft.read_bytes()).hexdigest(),
    'packaged_bridge_matches_sources': True,
    'source_methods_extracted_unchanged': ['PlayerContainerListener.sendContainerAndContentsToPlayer',
        'PlayerContainerListener.sendSlotContents', 'PlayerContainerListener.updateTasks',
        'EventHandler.schedulePlayerInventoryCheck', 'EventHandler.cleanup', 'EventHandler.onServerTick'],
    'oracle_boundaries': ['BQ source executes against fixture player/quest/task objects, without 1.7.10 game runtime.',
                         'Source comparison covers slot policy, initial notification, START phase and deduplication for eligible inventory tasks.',
                         'Server isolation and post-drain scheduling are queue-contract checks, not claims about every BQ reentrant callback.'],
    'limitations': ['Actual vanilla menus prove absence of guaranteed initial slot callbacks; patched callback dispatch is shape-checked only here.',
                    'NeoForge event-bus registration, fake-player integration, team lookup, disconnect/respawn lifecycle and game timing are not run.',
                    'Real Sponge transformation is covered by the separate isolated transformation report, not this test.'],
})
(out / 'LegacyInventoryObserverCheck.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
print(json.dumps(evidence))
