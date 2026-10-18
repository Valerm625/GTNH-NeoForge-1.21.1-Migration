package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipFile;
import net.minecraft.util.DirectoryLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;
import static org.gtnhmigration.compat.LegacyPlayerDataFileReaderCheck.*;

/** Real permit state machine, world ledger and DirectoryLock; server objects and
 * shutdown calls are fixtures. Native call sites are separately bytecode audited.
 */
public final class LegacyStartupShutdownCheck {
    static int assertions,serial;static Path root;
    interface Action {void run()throws Exception;}
    static void ok(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void no(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalStateException|IllegalArgumentException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static final class EqualNode {public boolean equals(Object other){return other instanceof EqualNode;}public int hashCode(){return 7;}}
    static final class Fixture {
        final Object server=new EqualNode(),players=new EqualNode(),storage=new EqualNode(),file=new EqualNode();
        final Path path=root.resolve("world-"+(serial++));final Lease lease;
        boolean failedBefore=true,terminal=true,levels,activePlayers;int writes;
        Fixture(boolean integrated){lease=begin(List.of(server,players,storage,file),List.of(path,path.resolve("playerdata")),integrated);}
        void unwind(){lease.quarantine();lease.close();}
        void verify(){if(!failedBefore || !terminal || levels || activePlayers)throw new IllegalStateException("Unsafe fixture state");}
        void start(){ok(beginEmptyShutdown(server,players,storage,this::verify),"Missing cleanup");}
        void step(ShutdownStep step){Object target=switch(step){case PLAYERS,REMOVE->players;case CHUNKS->server;case STORAGE->storage;};if(!shutdownStep(server,target,step,this::verify))writes++;}
        void beforeClose(){step(ShutdownStep.PLAYERS);step(ShutdownStep.REMOVE);step(ShutdownStep.CHUNKS);step(ShutdownStep.STORAGE);}
        void closed(){enterStorageClose(storage,path);storageClosed(server);}
        void finish(){finishShutdown(server,this::verify);}
        void retained()throws Exception{
            ok(lease.recoveryRequired(),"Quarantine lost");no(lease::release,"ordinary release");
            no(()->begin(List.of(new Object()),List.of(path),false),"reuse of failed cleanup path");
            no(()->enter(file,path.resolve("playerdata"),PLAYER_FILE,file,Entry.ENTER),"save after failure");ok(writes==0,"Native saves ran");
        }
    }
    static Map<String,String> bytes(Path path)throws Exception{
        Map<String,String> result=new TreeMap<>();try(var files=Files.walk(path)){for(Path p:files.filter(Files::isRegularFile).toList())result.put(path.relativize(p).toString(),Set.of("session.lock","ledger.lock").contains(p.getFileName().toString())?"exclusive-lock":LegacyQuestProgressStoreCodec.digest(Files.readAllBytes(p)));}return result;
    }
    static void successful(boolean integrated)throws Exception{
        Fixture f=new Fixture(integrated);var store=LegacyQuestWorldStoreCheck.create(f.path);var nativeLock=DirectoryLock.create(f.path);
        var authority=integrated?LegacyPlayerDataFileReader.Authority.WORLD_PLAYER:LegacyPlayerDataFileReader.Authority.PLAYER_FILE;
        var journal=LegacyQuestWorldStoreCheck.journal(store);
        var pending=journal.prepare(store.ledger().current().map(LegacyQuestProgressFileStore.Head::sha256),ACTOR,player(5),player(3),LegacyQuestWorldStoreCheck.after(),authority);
        LegacyQuestWorldStoreCheck.writePlayer(f.path,pending.originalPlayerData());LegacyQuestWorldStoreCheck.writeWorld(f.path,pending.originalPlayerData());
        Files.copy(f.path.resolve("level.dat"),f.path.resolve("level.dat_old"));
        Files.write(f.path.resolve("foreign-mod.bin"),new byte[]{-1,0,6,4});
        var before=bytes(f.path);String head=store.ledger().current().orElseThrow().sha256();
        no(()->LegacyQuestWorldStore.open(f.path),"second ledger lock");
        no(()->DirectoryLock.create(f.path),"second native directory lock");
        f.unwind();f.start();f.beforeClose();enterStorageClose(f.storage,f.path);nativeLock.close();storageClosed(f.server);
        verifyShutdownComplete(f.server,f::verify);store.close();f.finish();abandonShutdown(f.server);
        ok(bytes(f.path).equals(before),"Cleanup changed world/player/backup/foreign/journal bytes");ok(f.writes==0,"Cleanup executed saves");
        try(var reopened=LegacyQuestWorldStore.open(f.path);var relocked=DirectoryLock.create(f.path)){
            ok(reopened.ledger().current().orElseThrow().sha256().equals(head),"Cleanup changed pending head");
            ok(LegacyQuestWorldStoreCheck.journal(reopened).pending().isPresent(),"Cleanup resolved transaction");
            ok(relocked.isValid(),"Native directory lock did not reopen");
        }
        var freshServer=new EqualNode();
        try(var next=begin(List.of(freshServer,new EqualNode()),List.of(f.path,f.path.resolve("playerdata")),integrated)){
            for(Object old:List.of(f.server,f.players,f.storage,f.file)){
                no(()->enter(old,f.path,PLAYER_FILE,old,Entry.ENTER),"retired save");
                no(()->denyLifecycle(old,f.path,"load"),"retired lifecycle");
                no(()->begin(List.of(old),List.of(root.resolve("elsewhere")),false),"retired rebind");
                no(()->LegacyRetrievalSaveScope.root(old,PLAYER_FILE,old,()->null),"retired root");
                no(()->child(old,PLAYER,PLAYER_FILE,old,()->null),"retired child");
                no(()->assertHeld(old),"retired held");
            }
            no(()->enterStorageClose(f.storage,f.path),"retired storage close");
            no(()->beginEmptyShutdown(f.server,f.players,f.storage,()->{}),"retired shutdown");
            no(()->shutdownStep(f.server,f.players,ShutdownStep.PLAYERS,()->{}),"retired cleanup edge");
            next.assertHeld();next.release();
        }
        ok(bytes(f.path).equals(before),"Reopen altered files");
    }
    static void failures(boolean integrated)throws Exception{
        for(String kind:List.of("healthy","not_unwound","wrong_identity","not_startup","not_terminal","levels","players","wrong_thread","wrong_step","wrong_target","repeat_step","missing_close_hook","repeat_close","storage_io","resource_io","ledger_io","late_players","swallowed_guard","retry","unfinished")){
            Fixture f=new Fixture(integrated);
            if(kind.equals("healthy")){no(f::start,kind);f.lease.close();f.retained();continue;}
            f.lease.quarantine();if(!kind.equals("not_unwound"))f.lease.close();
            if(kind.equals("not_unwound")){no(f::start,kind);f.lease.close();f.retained();continue;}
            if(kind.equals("wrong_identity")){no(()->beginEmptyShutdown(f.server,new Object(),f.storage,f::verify),kind);f.retained();continue;}
            if(Set.of("not_startup","not_terminal","levels","players").contains(kind)){
                f.failedBefore=!kind.equals("not_startup");f.terminal=!kind.equals("not_terminal");f.levels=kind.equals("levels");f.activePlayers=kind.equals("players");
                no(f::start,kind);f.failedBefore=true;f.terminal=true;f.levels=false;f.activePlayers=false;no(f::start,"retry failed validation");f.retained();continue;
            }
            f.start();
            switch(kind){
                case "wrong_thread" -> {AtomicReference<Throwable> failure=new AtomicReference<>();Thread thread=new Thread(()->{try{f.step(ShutdownStep.PLAYERS);}catch(Throwable e){failure.set(e);}});thread.start();thread.join(5000);ok(!thread.isAlive() && failure.get() instanceof Violation,"Cross-thread cleanup accepted");}
                case "wrong_step" -> no(()->f.step(ShutdownStep.CHUNKS),kind);
                case "wrong_target" -> no(()->shutdownStep(f.server,new Object(),ShutdownStep.PLAYERS,f::verify),kind);
                case "repeat_step" -> {f.step(ShutdownStep.PLAYERS);no(()->f.step(ShutdownStep.PLAYERS),kind);}
                case "missing_close_hook" -> {f.beforeClose();no(()->storageClosed(f.server),kind);}
                case "repeat_close" -> {f.beforeClose();enterStorageClose(f.storage,f.path);no(()->enterStorageClose(f.storage,f.path),kind);}
                case "storage_io" -> {f.beforeClose();enterStorageClose(f.storage,f.path);/* close throws IOException, vanilla logs and returns */}
                case "resource_io" -> {f.step(ShutdownStep.PLAYERS);f.step(ShutdownStep.REMOVE);f.step(ShutdownStep.CHUNKS);abandonShutdown(f.server);}
                case "ledger_io" -> {f.beforeClose();f.closed();verifyShutdownComplete(f.server,f::verify);abandonShutdown(f.server);}
                case "late_players" -> {f.step(ShutdownStep.PLAYERS);f.activePlayers=true;no(()->f.step(ShutdownStep.REMOVE),kind);f.activePlayers=false;}
                case "swallowed_guard" -> {no(()->denyLifecycle(f.file,f.path,"callback load"),kind);}
                case "retry" -> no(f::start,kind);
                case "unfinished" -> {}
                default -> throw new AssertionError(kind);
            }
            no(f::finish,"finish after "+kind);abandonShutdown(f.server);f.retained();
        }
        Fixture frame=new Fixture(integrated);
        no(()->LegacyRetrievalSaveScope.root(frame.server,CHUNKS,frame.server,()->{enter(frame.server,null,CHUNKS,frame.server,Entry.ENTER);frame.unwind();frame.start();return null;}),"cleanup inside native save frame");frame.retained();
    }
    static ClassNode read(ZipFile jar,String name)throws Exception{var node=new ClassNode();new ClassReader(jar.getInputStream(jar.getEntry(name+".class")).readAllBytes()).accept(node,0);return node;}
    static MethodNode method(ClassNode node,String name){return node.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow();}
    static List<AbstractInsnNode> code(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(n->n.getOpcode()>=0).toList();}
    static List<MethodInsnNode> calls(MethodNode method){return Arrays.stream(method.instructions.toArray()).filter(n->n instanceof MethodInsnNode).map(n->(MethodInsnNode)n).toList();}
    static void audit(Path nativeJar,Path compatJar)throws Exception{
        String server="net/minecraft/server/MinecraftServer",storage="net/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess";
        try(var jar=new ZipFile(nativeJar.toFile());var compat=new ZipFile(compatJar.toFile())){
            ClassNode node=read(jar,server);var run=code(method(node,"runServer"));int stops=0;
            for(int i=0;i<run.size();i++)if(run.get(i) instanceof MethodInsnNode call && call.owner.equals(server) && call.name.equals("stopServer")){
                ok(run.get(i-1) instanceof VarInsnNode v && v.getOpcode()==Opcodes.ALOAD && v.var==0,"Stop receiver");
                ok(run.get(i-2) instanceof FieldInsnNode field && field.getOpcode()==Opcodes.PUTFIELD && field.name.equals("stopped") && field.desc.equals("Z"),"Stop without terminal field");
                ok(run.get(i-3).getOpcode()==Opcodes.ICONST_1,"Terminal state not true");stops++;
            }
            ok(stops==3,"Native terminal stop paths changed");
            var saveCalls=calls(method(node,"stopServer"));var actual=new ArrayList<String>();
            for(var c:saveCalls)if(c.name.equals("saveAll") || c.name.equals("removeAll") || c.name.equals("saveAllChunks") || c.name.equals("close"))actual.add(c.owner+"."+c.name);
            ok(actual.equals(List.of("net/minecraft/server/players/PlayerList.saveAll","net/minecraft/server/players/PlayerList.removeAll",server+".saveAllChunks","net/minecraft/server/level/ServerLevel.close",server+"$ReloadableResources.close",storage+".close")),"Shutdown save/close order changed");
            var closes=calls(method(read(jar,storage),"close"));ok(closes.size()==1 && closes.getFirst().owner.equals("net/minecraft/util/DirectoryLock") && closes.getFirst().name.equals("close"),"Storage close gained side effects");
            ok(calls(method(read(jar,"net/minecraft/server/level/ServerChunkCache"),"close")).stream().anyMatch(c->c.name.equals("save")),"Loaded chunk close no longer writes; reassess limitation");
            var lifecycle=read(compat,"org/gtnhmigration/compat/LegacyQuestNativeLifecycle");
            var wrapper=method(lifecycle,"stopFromRunLoop");ok(!wrapper.tryCatchBlocks.isEmpty(),"Missing stop finally protection");
            ok(calls(wrapper).stream().filter(c->c.name.equals("abandonShutdown")).count()==2,"Cleanup failure not guarded by finally");
            for(String name:List.of("beforeWorldStop","stopFromRunLoop","stopSavePlayers","stopRemovePlayers","stopSaveChunks","stopCloseStorage"))ok((method(lifecycle,name).access&Opcodes.ACC_PUBLIC)!=0,"Inaccessible transplanted call "+name);
            ok((method(read(compat,"org/gtnhmigration/compat/LegacyRetrievalSaveScope"),"enterStorageClose").access&Opcodes.ACC_PUBLIC)!=0,"Inaccessible storage close entry");
            String raw=new String(compat.getInputStream(compat.getEntry("org/gtnhmigration/compat/LegacyQuestNativeLifecycle.class")).readAllBytes(),java.nio.charset.StandardCharsets.ISO_8859_1);
            ok(!raw.contains("net/minecraft/client/"),"Common lifecycle depends on client");
        }
    }
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);
        Object idle=new Object();ok(!beginEmptyShutdown(idle,idle,idle,()->{throw new AssertionError("Unleased validation ran");}),"Unleased cleanup changed");
        for(var step:ShutdownStep.values())ok(!shutdownStep(idle,idle,step,()->{throw new AssertionError("Unleased validation ran");}),"Unleased native fallback changed");
        enterStorageClose(idle,root.resolve("unbound"));
        for(boolean integrated:new boolean[]{false,true}){successful(integrated);failures(integrated);}
        audit(Path.of(args[1]),Path.of(args[2]));
        System.out.println("{\"test\":\"startup_failure_shutdown\",\"passed\":true,\"assertions\":"+assertions+",\"real_directory_and_ledger_lock_reopens\":2,\"native_stop_call_sites_audited\":3,\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false}");
    }
}
