package org.gtnhmigration.compat;

import java.nio.file.Path;
import java.lang.ref.*;
import java.util.*;
import java.util.function.Supplier;

/** Single-use native save permits for one server. No filesystem lock, inventory
 * mutation hook, durable admission recovery, or arbitrary-mod sandbox is implied.
 * A poisoned/abandoned scope remains quarantined in this process.
 */
public final class LegacyRetrievalSaveScope {
    public enum Operation { EVERYTHING, PLAYERS, PLAYER, PLAYER_FILE, CHUNKS, LEVEL_TAG, LEVEL_FILE, PLAYER_LOAD, PLAYER_FILE_LOAD, PLAYER_NBT_LOAD }
    public enum Entry { ENTER, OWNER_CACHE, PLAYER_BASE }
    private static final Object LOCK=new Object();
    private static final IdentityHashMap<Object,Lease> NODES=new IdentityHashMap<>();
    private static final Map<Path,Lease> DIRECTORIES=new HashMap<>();
    private static final ThreadLocal<Frame> FRAME=new ThreadLocal<>();
    private static final ReferenceQueue<Object> RETIRED_QUEUE=new ReferenceQueue<>();
    private static final Set<IdentityReference> RETIRED=new HashSet<>();
    private static final class IdentityReference extends WeakReference<Object> {
        final int hash;
        IdentityReference(Object value,ReferenceQueue<Object> queue){super(value,queue);hash=System.identityHashCode(value);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other || other instanceof IdentityReference ref && get()!=null && get()==ref.get();}
    }
    private static void notRetired(Object node){
        Reference<?> stale;while((stale=RETIRED_QUEUE.poll())!=null)RETIRED.remove(stale);
        if(node!=null && RETIRED.contains(new IdentityReference(node,null)))throw new Violation("Native object belongs to a closed failed startup");
    }
    enum ShutdownStep { PLAYERS, REMOVE, CHUNKS, STORAGE }
    private static final class Shutdown {
        final Object server,players,storage;int step;boolean closeEntered,failed;
        Shutdown(Object server,Object players,Object storage){this.server=server;this.players=players;this.storage=storage;}
    }

    private static final class Frame {
        final Lease lease;final Operation operation;final Object target;final List<Entry> entries;
        int consumed;
        Frame(Lease lease,Operation operation,Object target){
            this.lease=lease;this.operation=operation;this.target=target;
            entries=operation==Operation.PLAYER?(lease.integrated?List.of(Entry.OWNER_CACHE,Entry.PLAYER_BASE):List.of(Entry.PLAYER_BASE)):List.of(Entry.ENTER);
        }
    }
    public static final class Violation extends IllegalStateException {
        Violation(String message){super(message);}
    }
    public static final class Lease implements AutoCloseable {
        private final Thread owner=Thread.currentThread();
        private final Set<Object> nodes=Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Path> directories;private final boolean integrated;
        private boolean released,poisoned,closed;private Shutdown shutdown;
        private Lease(Collection<Object> nodes,Collection<Path> directories,boolean integrated){
            for(Object node:nodes)this.nodes.add(Objects.requireNonNull(node));
            this.directories=new HashSet<>();for(Path path:directories)this.directories.add(normalize(path));
            this.integrated=integrated;
        }
        public void assertHeld(){synchronized(LOCK){healthy(this);}}
        public boolean recoveryRequired(){synchronized(LOCK){return poisoned || closed&&!released;}}
        /** Only after the transaction and all auxiliary state are confirmed. */
        public void release(){synchronized(LOCK){
            healthy(this);if(FRAME.get()!=null)fail(this,"Cannot release inside a native save frame");
            nodes.forEach(n->{if(NODES.get(n)==this)NODES.remove(n);});
            directories.forEach(p->{if(DIRECTORIES.get(p)==this)DIRECTORIES.remove(p);});
            released=true;
        }}
        public void quarantine(){synchronized(LOCK){poisoned=true;}}
        @Override public void close(){synchronized(LOCK){
            if(Thread.currentThread()!=owner)fail(this,"Save lease closed on another thread");
            if(!released)poisoned=true;closed=true;
        }}
    }
    private LegacyRetrievalSaveScope(){}
    private static Path normalize(Path path){return Objects.requireNonNull(path).toAbsolutePath().normalize();}
    public static Lease begin(Collection<Object> nodes,Collection<Path> directories,boolean integrated){
        Lease lease=new Lease(nodes,directories,integrated);
        synchronized(LOCK){
            if(lease.nodes.isEmpty() || lease.directories.isEmpty())throw new IllegalArgumentException("Missing native scope identities");
            for(Object node:lease.nodes){notRetired(node);Lease old=NODES.get(node);if(old!=null)fail(old,"Another submission owns this server/storage");}
            for(Path path:lease.directories){Lease old=DIRECTORIES.get(path);if(old!=null)fail(old,"Another submission owns this world directory");}
            if(FRAME.get()!=null)fail(FRAME.get().lease,"Cannot begin another submission from a native save callback");
            lease.nodes.forEach(n->NODES.put(n,lease));lease.directories.forEach(p->DIRECTORIES.put(p,lease));return lease;
        }
    }
    private static Lease find(Object node,Path directory){
        notRetired(node);Lease direct=NODES.get(node),path=directory==null?null:DIRECTORIES.get(normalize(directory));
        if(direct!=null && path!=null && direct!=path){direct.poisoned=true;fail(path,"Native object and directory belong to different scopes");}
        return direct!=null?direct:path;
    }
    private static void fail(Lease lease,String message){lease.poisoned=true;if(lease.shutdown!=null)lease.shutdown.failed=true;throw new Violation(message);}
    private static void healthy(Lease lease){
        if(Thread.currentThread()!=lease.owner || lease.released || lease.closed || lease.poisoned)fail(lease,"Native save scope is not healthy and owned by this thread");
    }
    public static void assertHeld(Object node){synchronized(LOCK){
        notRetired(node);Lease lease=NODES.get(node);if(lease==null)throw new Violation("Missing native transaction scope");healthy(lease);
    }}
    /** Called at the first instruction of a guarded native method. */
    public static void enter(Object node,Path directory,Operation operation,Object target,Entry entry){synchronized(LOCK){
        Lease lease=find(node,directory);if(lease==null)return;healthy(lease);Frame frame=FRAME.get();
        if(frame==null || frame.lease!=lease || frame.operation!=operation || frame.target!=target
                || frame.consumed>=frame.entries.size() || frame.entries.get(frame.consumed)!=entry)
            fail(lease,"Unpermitted native entry: "+operation+"/"+entry);
        frame.consumed++;
    }}
    public static void denyLifecycle(Object node,Path directory,String operation){synchronized(LOCK){
        Lease lease=find(node,directory);if(lease!=null)fail(lease,"Lifecycle change during transaction/quarantine: "+operation);
    }}
    public static <T>T root(Object node,Operation operation,Object target,Supplier<T> call){
        Lease lease;synchronized(LOCK){notRetired(node);lease=NODES.get(node);if(lease==null)throw new Violation("Native save requested without a lease");healthy(lease);
            if(FRAME.get()!=null)fail(lease,"Reentrant root save");}
        return invoke(lease,operation,target,call);
    }
    /** Used only by redirects at pinned native parent-to-child call sites. */
    public static <T>T child(Object node,Operation parent,Operation operation,Object target,Supplier<T> call){
        Lease lease;synchronized(LOCK){notRetired(node);lease=NODES.get(node);
            if(lease!=null){healthy(lease);Frame frame=FRAME.get();
                if(frame==null || frame.lease!=lease || frame.operation!=parent || frame.consumed!=frame.entries.size())
                    fail(lease,"Native save child outside its authorized parent");
                if(!allowed(parent,operation))fail(lease,"Unexpected native save edge");
                if((parent==Operation.PLAYER || parent==Operation.PLAYER_LOAD || parent==Operation.PLAYER_FILE_LOAD) && frame.target!=target)
                    fail(lease,"Native child changed the permitted player identity");
            }
        }
        return lease==null?call.get():invoke(lease,operation,target,call);
    }
    /** A narrowly scoped, non-saving cleanup for a failed loadLevel HEAD hook.
     * The native adapter must prove original world loading never began, no
     * players/levels exist, and runServer has entered its terminal stop path.
     * No regular save permit is granted and no pending transaction is resolved.
     */
    static boolean beginEmptyShutdown(Object server,Object players,Object storage,Runnable verifyEmpty){synchronized(LOCK){
        notRetired(server);Lease lease=NODES.get(server);if(lease==null)return false;
        if(Thread.currentThread()!=lease.owner || !lease.closed || !lease.poisoned || lease.released || FRAME.get()!=null || lease.shutdown!=null)fail(lease,"Failed-startup cleanup requires an unwound quarantined lease");
        if(NODES.get(players)!=lease || NODES.get(storage)!=lease)fail(lease,"Cleanup storage/player-list identity mismatch");
        lease.shutdown=new Shutdown(server,players,storage);
        try{verifyEmpty.run();}catch(RuntimeException|Error failure){lease.shutdown.failed=true;throw failure;}return true;
    }}
    static boolean shutdownActive(Object server){synchronized(LOCK){Lease lease=NODES.get(server);return lease!=null && lease.shutdown!=null;}}
    private static Shutdown cleanup(Lease lease,Object server){
        if(Thread.currentThread()!=lease.owner || !lease.closed || !lease.poisoned || lease.released || FRAME.get()!=null || lease.shutdown==null || lease.shutdown.server!=server || lease.shutdown.failed)fail(lease,"Failed-startup cleanup is not usable");
        return lease.shutdown;
    }
    static boolean shutdownStep(Object server,Object target,ShutdownStep step,Runnable verifyEmpty){synchronized(LOCK){
        notRetired(server);Lease lease=NODES.get(server);if(lease==null)return false;Shutdown shutdown=cleanup(lease,server);
        Object expected=switch(step){case PLAYERS,REMOVE->shutdown.players;case CHUNKS->shutdown.server;case STORAGE->shutdown.storage;};
        if(shutdown.step!=step.ordinal() || target!=expected)fail(lease,"Unexpected failed-startup cleanup edge");
        try{verifyEmpty.run();}catch(RuntimeException|Error failure){shutdown.failed=true;throw failure;}
        shutdown.step++;return true;
    }}
    public static void enterStorageClose(Object storage,Path directory){synchronized(LOCK){
        Lease lease=find(storage,directory);if(lease==null)return;Shutdown shutdown=cleanup(lease,lease.shutdown==null?null:lease.shutdown.server);
        if(shutdown.storage!=storage || shutdown.step!=4 || shutdown.closeEntered)fail(lease,"Unpermitted world storage close during cleanup");shutdown.closeEntered=true;
    }}
    static void storageClosed(Object server){synchronized(LOCK){
        Lease lease=NODES.get(server);if(lease==null)throw new Violation("Cleanup lease disappeared");Shutdown shutdown=cleanup(lease,server);
        if(shutdown.step!=4 || !shutdown.closeEntered)fail(lease,"World close hook did not complete");shutdown.step=5;
    }}
    static void verifyShutdownComplete(Object server,Runnable verifyEmpty){synchronized(LOCK){
        Lease lease=NODES.get(server);if(lease==null)throw new Violation("Missing cleanup lease");Shutdown shutdown=cleanup(lease,server);
        if(shutdown.step!=5)fail(lease,"Failed-startup native close did not finish");
        try{verifyEmpty.run();}catch(RuntimeException|Error failure){shutdown.failed=true;throw failure;}
    }}
    static void finishShutdown(Object server,Runnable verifyEmpty){synchronized(LOCK){
        verifyShutdownComplete(server,verifyEmpty);Lease lease=NODES.get(server);
        for(Object node:lease.nodes){RETIRED.add(new IdentityReference(node,RETIRED_QUEUE));if(NODES.get(node)==lease)NODES.remove(node);}
        for(Path directory:lease.directories)if(DIRECTORIES.get(directory)==lease)DIRECTORIES.remove(directory);
        lease.released=true;
    }}
    /** runServer wrapper calls this in finally. Failure never releases paths. */
    static void abandonShutdown(Object server){synchronized(LOCK){Lease lease=NODES.get(server);if(lease!=null && lease.shutdown!=null){lease.shutdown.failed=true;lease.poisoned=true;}}}
    private static boolean allowed(Operation parent,Operation child){return switch(parent){
        case EVERYTHING -> child==Operation.PLAYERS || child==Operation.CHUNKS;
        case PLAYERS -> child==Operation.PLAYER;
        case PLAYER -> child==Operation.PLAYER_FILE;
        case CHUNKS -> child==Operation.LEVEL_TAG;
        case LEVEL_TAG -> child==Operation.LEVEL_FILE;
        case PLAYER_LOAD -> child==Operation.PLAYER_FILE_LOAD || child==Operation.PLAYER_NBT_LOAD;
        case PLAYER_FILE_LOAD -> child==Operation.PLAYER_NBT_LOAD;
        default -> false;
    };}
    private static <T>T invoke(Lease lease,Operation operation,Object target,Supplier<T> call){
        Objects.requireNonNull(target);Objects.requireNonNull(call);Frame old=FRAME.get(),frame=new Frame(lease,operation,target);
        FRAME.set(frame);
        try{
            T result=call.get();synchronized(LOCK){healthy(lease);if(frame.consumed!=frame.entries.size())fail(lease,"Required native entry hook did not execute");}
            return result;
        }finally{if(old==null)FRAME.remove();else FRAME.set(old);}
    }
}
