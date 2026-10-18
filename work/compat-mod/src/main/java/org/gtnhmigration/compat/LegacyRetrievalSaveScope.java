package org.gtnhmigration.compat;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/** Single-use native save permits for one server. No filesystem lock, inventory
 * mutation hook, durable admission recovery, or arbitrary-mod sandbox is implied.
 * A poisoned/abandoned scope remains quarantined in this process.
 */
public final class LegacyRetrievalSaveScope {
    public enum Operation { EVERYTHING, PLAYERS, PLAYER, PLAYER_FILE, CHUNKS, LEVEL_TAG, LEVEL_FILE }
    public enum Entry { ENTER, OWNER_CACHE, PLAYER_BASE }
    private static final Object LOCK=new Object();
    private static final IdentityHashMap<Object,Lease> NODES=new IdentityHashMap<>();
    private static final Map<Path,Lease> DIRECTORIES=new HashMap<>();
    private static final ThreadLocal<Frame> FRAME=new ThreadLocal<>();
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
        private boolean released,poisoned,closed;
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
            for(Object node:lease.nodes){Lease old=NODES.get(node);if(old!=null)fail(old,"Another submission owns this server/storage");}
            for(Path path:lease.directories){Lease old=DIRECTORIES.get(path);if(old!=null)fail(old,"Another submission owns this world directory");}
            if(FRAME.get()!=null)fail(FRAME.get().lease,"Cannot begin another submission from a native save callback");
            lease.nodes.forEach(n->NODES.put(n,lease));lease.directories.forEach(p->DIRECTORIES.put(p,lease));return lease;
        }
    }
    private static Lease find(Object node,Path directory){
        Lease direct=NODES.get(node),path=directory==null?null:DIRECTORIES.get(normalize(directory));
        if(direct!=null && path!=null && direct!=path){direct.poisoned=true;fail(path,"Native object and directory belong to different scopes");}
        return direct!=null?direct:path;
    }
    private static void fail(Lease lease,String message){lease.poisoned=true;throw new Violation(message);}
    private static void healthy(Lease lease){
        if(Thread.currentThread()!=lease.owner || lease.released || lease.closed || lease.poisoned)fail(lease,"Native save scope is not healthy and owned by this thread");
    }
    public static void assertHeld(Object node){synchronized(LOCK){
        Lease lease=NODES.get(node);if(lease==null)throw new Violation("Missing native transaction scope");healthy(lease);
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
        Lease lease;synchronized(LOCK){lease=NODES.get(node);if(lease==null)throw new Violation("Native save requested without a lease");healthy(lease);
            if(FRAME.get()!=null)fail(lease,"Reentrant root save");}
        return invoke(lease,operation,target,call);
    }
    /** Used only by redirects at pinned native parent-to-child call sites. */
    public static <T>T child(Object node,Operation parent,Operation operation,Object target,Supplier<T> call){
        Lease lease;synchronized(LOCK){lease=NODES.get(node);
            if(lease!=null){healthy(lease);Frame frame=FRAME.get();
                if(frame==null || frame.lease!=lease || frame.operation!=parent || frame.consumed!=frame.entries.size())
                    fail(lease,"Native save child outside its authorized parent");
                if(!allowed(parent,operation))fail(lease,"Unexpected native save edge");
            }
        }
        return lease==null?call.get():invoke(lease,operation,target,call);
    }
    private static boolean allowed(Operation parent,Operation child){return switch(parent){
        case EVERYTHING -> child==Operation.PLAYERS || child==Operation.CHUNKS;
        case PLAYERS -> child==Operation.PLAYER;
        case PLAYER -> child==Operation.PLAYER_FILE;
        case CHUNKS -> child==Operation.LEVEL_TAG;
        case LEVEL_TAG -> child==Operation.LEVEL_FILE;
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
