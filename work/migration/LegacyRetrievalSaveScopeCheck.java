package org.gtnhmigration.compat;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.*;
import static org.gtnhmigration.compat.LegacyRetrievalSaveScope.Operation.*;

/** Execute the production permit state machine; native call bodies are explicit fixtures. */
public final class LegacyRetrievalSaveScopeCheck {
    static int assertions,serial;
    static Path root;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    static void rejects(Runnable action,String message){try{action.run();}catch(Violation expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static final class World implements AutoCloseable {
        final Object server=new Object(),players=new Object(),file=new Object(),world=new Object();
        final List<Object> actors=new ArrayList<>();final Path worldPath,playerPath;final boolean integrated;
        final Lease lease;int playerWrites,worldWrites;Runnable callback=()->{};
        World(boolean integrated,int count){
            this.integrated=integrated;worldPath=root.resolve("world-"+(serial++));playerPath=worldPath.resolve("playerdata");
            for(int i=0;i<count;i++)actors.add(new Object());
            lease=begin(List.of(server,players,file,world),List.of(worldPath,playerPath),integrated);
        }
        void saveOne(Object actor){child(players,PLAYERS,PLAYER,actor,()->{
            if(integrated)enter(players,null,PLAYER,actor,Entry.OWNER_CACHE);
            enter(players,null,PLAYER,actor,Entry.PLAYER_BASE);
            child(players,PLAYER,PLAYER_FILE,actor,()->{enter(file,playerPath,PLAYER_FILE,actor,Entry.ENTER);playerWrites++;callback.run();return null;});return null;
        });}
        void savePlayers(){enter(players,null,PLAYERS,players,Entry.ENTER);for(Object actor:actors)saveOne(actor);}
        void saveAll(){LegacyRetrievalSaveScope.root(server,EVERYTHING,server,()->{
            enter(server,null,EVERYTHING,server,Entry.ENTER);
            child(server,EVERYTHING,PLAYERS,players,()->{savePlayers();return null;});
            child(server,EVERYTHING,CHUNKS,server,()->{
                enter(server,null,CHUNKS,server,Entry.ENTER);
                child(server,CHUNKS,LEVEL_TAG,world,()->{
                    enter(world,worldPath,LEVEL_TAG,world,Entry.ENTER);
                    child(world,LEVEL_TAG,LEVEL_FILE,world,()->{enter(world,worldPath,LEVEL_FILE,world,Entry.ENTER);worldWrites++;return null;});return null;
                });return null;
            });return null;
        });}
        @Override public void close(){lease.close();}
    }
    static void normal(){
        for(boolean integrated:new boolean[]{false,true})for(int count:new int[]{0,1,2,8})try(World w=new World(integrated,count)){
            w.saveAll();check(w.playerWrites==count && w.worldWrites==1,"Native-order write count");
            LegacyRetrievalSaveScope.root(w.server,PLAYERS,w.players,()->{w.savePlayers();return null;});
            check(w.playerWrites==2*count && w.worldWrites==1,"Abort repair wrote world");
            if(count>0)LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,w.actors.getFirst(),()->{enter(w.file,w.playerPath,PLAYER_FILE,w.actors.getFirst(),Entry.ENTER);w.playerWrites++;return null;});
            w.lease.assertHeld();check(!w.lease.recoveryRequired(),"Valid sequence poisoned");w.lease.release();
            denyLifecycle(w.server,null,"normal tick");enter(w.file,w.playerPath,PLAYER_FILE,new Object(),Entry.ENTER);
            check(!w.lease.recoveryRequired(),"Released server still quarantined");
            try(var again=begin(List.of(w.server,w.players,w.file,w.world),List.of(w.worldPath,w.playerPath),integrated)){again.release();}
        }
        Object idle=new Object();int[] calls={0};child(idle,PLAYERS,PLAYER,idle,()->{calls[0]++;return null;});check(calls[0]==1,"Unleased vanilla call was changed");
        rejects(()->LegacyRetrievalSaveScope.root(idle,PLAYER_FILE,idle,()->null),"unleased coordinator save");
    }
    static void callbackViolations(){
        for(boolean swallowed:new boolean[]{false,true})for(String kind:List.of("same_file","another_file","root","child","lifecycle","alternate_storage","begin","release","thread"))try(World w=new World(true,2)){
            Object actor=w.actors.getFirst();
            Runnable illegal=switch(kind){
                case "same_file" -> ()->enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);
                case "another_file" -> ()->enter(w.file,w.playerPath,PLAYER_FILE,w.actors.get(1),Entry.ENTER);
                case "root" -> ()->LegacyRetrievalSaveScope.root(w.server,EVERYTHING,w.server,()->null);
                case "child" -> ()->child(w.players,PLAYER,PLAYER_FILE,actor,()->null);
                case "lifecycle" -> ()->denyLifecycle(w.players,null,"load before fallback");
                case "alternate_storage" -> ()->enter(new Object(),w.playerPath.resolve("."),PLAYER_FILE,actor,Entry.ENTER);
                case "begin" -> ()->begin(List.of(w.server,new Object()),List.of(root.resolve("other")),true);
                case "release" -> ()->w.lease.release();
                default -> ()->{
                    AtomicReference<Throwable> result=new AtomicReference<>();
                    Thread t=new Thread(()->{try{enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);}catch(Throwable e){result.set(e);}});
                    t.start();try{t.join(5000);}catch(InterruptedException e){throw new AssertionError(e);}
                    check(!t.isAlive() && result.get() instanceof Violation,"Cross-thread save escaped");
                    throw (Violation)result.get();
                };
            };
            w.callback=()->{if(swallowed){try{illegal.run();}catch(Violation ignored){}}else illegal.run();};
            rejects(w::saveAll,"callback "+kind+" swallowed="+swallowed);
            check(w.lease.recoveryRequired(),"Violation not retained: "+kind);
            check(w.playerWrites==1 && w.worldWrites==0,"Later native writes ran after violation");
            rejects(()->w.lease.assertHeld(),"reuse after violation");rejects(()->w.lease.release(),"release poisoned scope");
            rejects(()->denyLifecycle(w.server,null,"subsequent tick"),"quarantine bypass");
        }
    }
    static void badFrames(){
        for(String kind:List.of("no_hook","duplicate","wrong_target","wrong_operation","wrong_entry","owner_base_first","missing_base","unexpected_edge"))try(World w=new World(true,1)){
            Object actor=w.actors.getFirst();
            Runnable call=switch(kind){
                case "no_hook" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->null);
                case "duplicate" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);return null;});
                case "wrong_target" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,PLAYER_FILE,new Object(),Entry.ENTER);return null;});
                case "wrong_operation" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,LEVEL_FILE,actor,Entry.ENTER);return null;});
                case "wrong_entry" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.PLAYER_BASE);return null;});
                case "owner_base_first" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER,actor,()->{enter(w.players,null,PLAYER,actor,Entry.PLAYER_BASE);return null;});
                case "missing_base" -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER,actor,()->{enter(w.players,null,PLAYER,actor,Entry.OWNER_CACHE);return null;});
                default -> ()->LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);child(w.file,PLAYER_FILE,LEVEL_TAG,w.world,()->null);return null;});
            };
            rejects(call,kind);check(w.lease.recoveryRequired(),"Bad frame was reusable");
        }
        try(World a=new World(false,0);World b=new World(true,0)){
            rejects(()->enter(a.file,b.playerPath,PLAYER_FILE,new Object(),Entry.ENTER),"mixed storage identities");
            check(a.lease.recoveryRequired() && b.lease.recoveryRequired(),"Both mismatched scopes must isolate");
        }
        try(World w=new World(false,0)){
            rejects(()->begin(List.of(new Object()),List.of(w.worldPath),false),"second ledger on same directory");check(w.lease.recoveryRequired(),"Directory collision did not poison owner");
        }
    }
    static void exceptionsAndClosure(){
        for(boolean error:new boolean[]{false,true})try(World w=new World(false,1)){
            Object actor=w.actors.getFirst();
            try{LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);if(error)throw new AssertionError("native body");throw new IllegalArgumentException("native body");});}
            catch(AssertionError|IllegalArgumentException expected){check(expected.getMessage().equals("native body"),"Native failure changed");}
            // Ordinary native failures are classified by the coordinator's readback;
            // finally must remove the call frame even for Error.
            w.lease.assertHeld();LegacyRetrievalSaveScope.root(w.server,PLAYER_FILE,actor,()->{enter(w.file,w.playerPath,PLAYER_FILE,actor,Entry.ENTER);return null;});
            check(!w.lease.recoveryRequired(),"Ordinary save exception became a permit violation");w.lease.release();
        }
        World abandoned=new World(false,1);abandoned.close();check(abandoned.lease.recoveryRequired(),"Unresolved close released scope");
        rejects(()->enter(abandoned.file,abandoned.playerPath,PLAYER_FILE,abandoned.actors.getFirst(),Entry.ENTER),"save after abandoned close");
        rejects(()->denyLifecycle(abandoned.players,null,"logout"),"logout after abandoned close");
        try(World w=new World(false,1)){
            AtomicReference<Throwable> failure=new AtomicReference<>();Thread t=new Thread(()->{try{w.lease.close();}catch(Throwable e){failure.set(e);}});t.start();try{t.join(5000);}catch(InterruptedException e){throw new AssertionError(e);}
            check(!t.isAlive() && failure.get() instanceof Violation && w.lease.recoveryRequired(),"Cross-thread close released scope");
        }
    }
    public static void main(String[] args){root=Path.of(args[0]);normal();callbackViolations();badFrames();exceptionsAndClosure();
        System.out.println("{\"test\":\"native_save_scope_state_machine\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"game_runtime_verified\":false,\"durable_admission_recovery_verified\":false}");
    }
}
