package org.gtnhmigration.compat;

import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

/** Durable preparation/decision journal carried in the ledger's immutable byte
 * archive. No world/player save hooks are registered. Player writes are an
 * external adapter contract: marker and complete inventory must be persisted
 * together before resolve reads the actual file. Never infer that from save().
 */
public final class LegacyRetrievalTransactionJournal {
    public static final String MARKER_KEY = "gtnh_migration:retrieval_transaction";
    private static final String PREFIX = "gtnh-generated-transaction:v1/";
    private final LegacyQuestProgressFileStore store;
    private final Binding binding;
    private final Catalog catalog;

    public record Marker(UUID transaction, String preparationSha256) {
        public Marker { Objects.requireNonNull(transaction); digest(preparationSha256); }
    }
    public enum Decision { ABORT, COMMIT }
    public static final class Pending {
        private final UUID transaction, actor;
        private final long baseRevision;
        private final String baseHead;
        private final int baseSourceCount;
        private final CompoundTag beforePlayer, afterPlayer;
        private final State beforeState, afterState;
        private final SourceDocument source;
        private Pending(UUID transaction, UUID actor, long revision, String head, int sourceCount,
                CompoundTag beforePlayer, CompoundTag afterPlayer, State beforeState, State afterState, SourceDocument source) {
            this.transaction=transaction;this.actor=actor;this.baseRevision=revision;this.baseHead=head;this.baseSourceCount=sourceCount;
            this.beforePlayer=beforePlayer.copy();this.afterPlayer=afterPlayer.copy();this.beforeState=beforeState;this.afterState=afterState;this.source=source;
        }
        public UUID transaction() { return transaction; }
        public UUID actor() { return actor; }
        public Marker marker() { return new Marker(transaction, source.sha256()); }
        /** Proposed player data, not evidence that a live player file contains it. */
        public CompoundTag playerDataToPersist() { return stamp(afterPlayer, marker()); }
        public CompoundTag originalPlayerData() { return beforePlayer.copy(); }
    }
    public record Resolution(UUID transaction, Decision decision, String ledgerSha256) {}
    private record Scan(Optional<Pending> pending, Map<UUID, Optional<Marker>> expectedMarkers) {}

    public LegacyRetrievalTransactionJournal(LegacyQuestProgressFileStore store, Binding binding, Catalog catalog) {
        this.store=Objects.requireNonNull(store);this.binding=Objects.requireNonNull(binding);this.catalog=Objects.requireNonNull(catalog);
    }
    public Optional<Pending> pending() throws IOException { synchronized(store) { return scan().pending(); } }

    /** beforePlayer and afterPlayer must differ only in Inventory. Arbitrary
     * other player fields are retained opaquely; changing them is not this operation.
     * expectedHead must come from the snapshot used to calculate afterState;
     * obtaining it here would allow a stale plan to overwrite newer progress.
     * An unfinished transaction globally blocks preparing another ledger update.
     */
    public Pending prepare(Optional<String> expectedHead, UUID actor, CompoundTag beforePlayer, CompoundTag afterPlayer, State afterState) throws IOException {
        synchronized(store) {
        Objects.requireNonNull(expectedHead);
        require(store.current().map(LegacyQuestProgressFileStore.Head::sha256).equals(expectedHead), "Plan was computed from a stale ledger head");
        Scan scan=scan();
        require(scan.pending().isEmpty(), "Unfinished transaction must be resolved first");
        validatePlayerPair(actor,beforePlayer,afterPlayer);
        if(scan.expectedMarkers().containsKey(actor)) require(readMarker(beforePlayer).equals(scan.expectedMarkers().get(actor)),"Player marker rolled back or changed");
        else require(readMarker(beforePlayer).isEmpty(),"Player marker has no journal history");
        var current=store.current();
        State before=current.map(h->h.frame().snapshot().state()).orElse(State.empty());
        List<SourceDocument> sources=current.map(h->h.frame().snapshot().sources()).orElse(List.of());
        UUID transaction=UUID.randomUUID();
        CompoundTag record=new CompoundTag();record.putInt("schema_version",1);record.putString("kind","prepare");
        record.putString("transaction",transaction.toString());record.putString("actor",actor.toString());
        record.putLong("base_revision",current.map(h->h.frame().revision()).orElse(0L));
        record.putString("base_head",current.map(LegacyQuestProgressFileStore.Head::sha256).orElse(GENESIS));
        record.putInt("base_source_count",sources.size());
        record.put("before_player",beforePlayer.copy());record.put("after_player",afterPlayer.copy());
        record.put("before_state",stateTag(before));record.put("after_state",stateTag(afterState));
        SourceDocument source=new SourceDocument(PREFIX+transaction+"/prepare",encode(record));
        Pending parsed=parsePrepared(source);
        List<SourceDocument> appended=new ArrayList<>(sources);appended.add(source);
        store.commit(current.map(LegacyQuestProgressFileStore.Head::sha256),before,appended);
        return parsed;
        }
    }

    /** actualPlayerData must be freshly read from the authoritative player file,
     * with fallback disabled. This method never modifies any player inventory.
     * Ambiguous, missing, foreign or stale file contents stop recovery.
     */
    public Resolution resolve(CompoundTag actualPlayerData) throws IOException {
        synchronized(store) {
        Scan scan=scan();Pending pending=scan.pending().orElseThrow(()->new IOException("No pending transaction"));
        require(playerId(actualPlayerData).equals(pending.actor),"Recovery player UUID mismatch");
        Optional<Marker> actual=readMarker(actualPlayerData);
        Decision decision;
        if(actual.equals(Optional.of(pending.marker())) && inventory(actualPlayerData).equals(inventory(pending.afterPlayer))) decision=Decision.COMMIT;
        else if(actual.equals(readMarker(pending.beforePlayer)) && inventory(actualPlayerData).equals(inventory(pending.beforePlayer))) decision=Decision.ABORT;
        else throw new IOException("Player inventory and transaction marker do not prove a recoverable state");
        var head=store.current().orElseThrow();
        // Recheck immediately before CAS; other callers must not mutate this store outside the coordinator.
        requirePendingHead(head,pending);
        CompoundTag record=new CompoundTag();record.putInt("schema_version",1);record.putString("kind","resolve");
        record.putString("transaction",pending.transaction.toString());record.putString("preparation_sha256",pending.source.sha256());
        record.putString("prepared_head",head.sha256());record.putString("decision",decision.name());
        List<SourceDocument> sources=new ArrayList<>(head.frame().snapshot().sources());
        sources.add(new SourceDocument(PREFIX+pending.transaction+"/resolve",encode(record)));
        var committed=store.commit(Optional.of(head.sha256()),decision==Decision.COMMIT?pending.afterState:pending.beforeState,sources);
        return new Resolution(pending.transaction,decision,committed.sha256());
        }
    }

    /** Check completed transaction markers before admitting a player. Inventory
     * may legitimately change after a completed transaction; its old contents
     * are not imposed on normal play. Pending actors must use resolve instead.
     */
    public void verifyResolvedPlayer(CompoundTag playerData) throws IOException {
        synchronized(store) {
        Scan scan=scan();UUID actor=playerId(playerData);
        require(scan.pending().isEmpty(),"Pending world transaction must be resolved before player admission");
        Optional<Marker> actual=readMarker(playerData);
        if(scan.expectedMarkers().containsKey(actor)) require(actual.equals(scan.expectedMarkers().get(actor)),"Player transaction marker does not match committed history");
        else require(actual.isEmpty(),"Player marker has no matching ledger history");
        }
    }

    private Scan scan() throws IOException {
        var head=store.current();
        if(head.isEmpty()) return new Scan(Optional.empty(),Map.of());
        Pending pending=null,lastPrepared=null;
        CompoundTag lastResolution=null;
        Set<UUID> ids=new HashSet<>();Map<UUID,Optional<Marker>> expected=new HashMap<>();
        List<SourceDocument> sources=head.orElseThrow().frame().snapshot().sources();
        for(int i=0;i<sources.size();i++) {
            SourceDocument source=sources.get(i);
            if(!source.identity().startsWith(PREFIX)) {
                require(pending==null,"Source archive changed during a pending transaction");
                continue;
            }
            CompoundTag record=decode(source.bytes());
            require(record.get("schema_version") instanceof IntTag && record.getInt("schema_version")==1,"Unknown journal schema");
            String kind=string(record,"kind");
            if(kind.equals("prepare")) {
                require(pending==null,"Overlapping journal preparations");
                pending=parsePrepared(source);lastPrepared=pending;lastResolution=null;
                require(ids.add(pending.transaction),"Duplicate transaction UUID");
                require(pending.baseSourceCount==i,"Preparation archive position mismatch");
                if(expected.containsKey(pending.actor)) require(readMarker(pending.beforePlayer).equals(expected.get(pending.actor)),"Broken player marker chain");
                else require(readMarker(pending.beforePlayer).isEmpty(),"First preparation has an unbound player marker");
            } else if(kind.equals("resolve")) {
                require(pending!=null,"Resolution without preparation");
                fields(record,"schema_version","kind","transaction","preparation_sha256","prepared_head","decision");
                require(string(record,"transaction").equals(pending.transaction.toString()),"Resolution transaction mismatch");
                require(source.identity().equals(PREFIX+pending.transaction+"/resolve"),"Resolution archive identity mismatch");
                require(string(record,"preparation_sha256").equals(pending.source.sha256()),"Resolution preparation digest mismatch");
                digest(string(record,"prepared_head"));
                Decision decision=decision(string(record,"decision"));
                expected.put(pending.actor,decision==Decision.COMMIT?Optional.of(pending.marker()):readMarker(pending.beforePlayer));
                lastResolution=record;pending=null;
            } else throw new IOException("Unknown journal record kind");
        }
        if(pending!=null) requirePendingHead(head.orElseThrow(),pending);
        else if(lastPrepared!=null) {
            var current=head.orElseThrow();Decision decision=decision(string(lastResolution,"decision"));
            require(current.frame().revision()==Math.addExact(lastPrepared.baseRevision,2),"Ledger advanced outside journal resolution");
            require(current.frame().parentSha256().equals(string(lastResolution,"prepared_head")),"Resolution parent mismatch");
            require(current.frame().snapshot().state().equals(decision==Decision.COMMIT?lastPrepared.afterState:lastPrepared.beforeState),"Resolved progress state mismatch");
            require(sources.size()==lastPrepared.baseSourceCount+2,"Archive advanced outside journal resolution");
        }
        return new Scan(Optional.ofNullable(pending),Collections.unmodifiableMap(expected));
    }
    private void requirePendingHead(LegacyQuestProgressFileStore.Head head,Pending pending) throws IOException {
        require(head.frame().revision()==Math.addExact(pending.baseRevision,1),"Prepared ledger revision mismatch");
        require(head.frame().parentSha256().equals(pending.baseHead),"Prepared ledger parent mismatch");
        require(head.frame().snapshot().state().equals(pending.beforeState),"Progress changed before player persistence");
        require(head.frame().snapshot().sources().size()==pending.baseSourceCount+1,"Archive changed after preparation");
    }
    private Pending parsePrepared(SourceDocument source) throws IOException {
        CompoundTag record=decode(source.bytes());
        fields(record,"schema_version","kind","transaction","actor","base_revision","base_head","base_source_count","before_player","after_player","before_state","after_state");
        require(record.get("schema_version") instanceof IntTag && record.getInt("schema_version")==1 && string(record,"kind").equals("prepare"),"Invalid preparation schema/kind");
        UUID transaction=uuid(string(record,"transaction")),actor=uuid(string(record,"actor"));
        require(source.identity().equals(PREFIX+transaction+"/prepare"),"Preparation archive identity mismatch");
        require(record.get("base_revision") instanceof LongTag && record.getLong("base_revision")>=0,"Invalid base revision");
        require(record.get("base_source_count") instanceof IntTag && record.getInt("base_source_count")>=0,"Invalid source count");
        String parent=string(record,"base_head");digest(parent);
        CompoundTag before=compound(record,"before_player"),after=compound(record,"after_player");
        validatePlayerPair(actor,before,after);
        return new Pending(transaction,actor,record.getLong("base_revision"),parent,record.getInt("base_source_count"),before,after,
            readState(compound(record,"before_state")),readState(compound(record,"after_state")),source);
    }
    private CompoundTag stateTag(State state) { return LegacyQuestProgressStoreCodec.write(new Frame(1,GENESIS,new Snapshot(binding,state,List.of())),catalog); }
    private State readState(CompoundTag tag) throws IOException {
        var read=LegacyQuestProgressStoreCodec.read(tag,binding,catalog);require(read.accepted(),"Unsupported journal progress state");
        Frame frame=read.frame().orElseThrow();require(frame.revision()==1 && frame.parentSha256().equals(GENESIS) && frame.snapshot().sources().isEmpty(),"Invalid embedded state envelope");
        return frame.snapshot().state();
    }
    private static void validatePlayerPair(UUID actor,CompoundTag before,CompoundTag after) throws IOException {
        require(playerId(before).equals(actor) && playerId(after).equals(actor),"Player UUID mismatch");
        inventory(before);inventory(after);readMarker(before);readMarker(after);
        CompoundTag a=before.copy(),b=after.copy();a.remove("Inventory");b.remove("Inventory");
        require(a.equals(b),"Proposed transaction modifies noninventory player data");
    }
    public static Optional<Marker> readMarker(CompoundTag player) throws IOException {
        if(!player.contains("NeoForgeData")) return Optional.empty();
        CompoundTag data=compound(player,"NeoForgeData");
        if(!data.contains(MARKER_KEY)) return Optional.empty();
        CompoundTag tag=compound(data,MARKER_KEY);fields(tag,"schema_version","transaction","preparation_sha256");
        require(tag.get("schema_version") instanceof IntTag && tag.getInt("schema_version")==1,"Unknown player marker schema");
        return Optional.of(new Marker(uuid(string(tag,"transaction")),string(tag,"preparation_sha256")));
    }
    private static CompoundTag stamp(CompoundTag player,Marker marker) {
        CompoundTag copy=player.copy();CompoundTag data=copy.contains("NeoForgeData")?copy.getCompound("NeoForgeData"):new CompoundTag();
        CompoundTag tag=new CompoundTag();tag.putInt("schema_version",1);tag.putString("transaction",marker.transaction.toString());tag.putString("preparation_sha256",marker.preparationSha256);
        data.put(MARKER_KEY,tag);copy.put("NeoForgeData",data);return copy;
    }
    private static UUID playerId(CompoundTag player) throws IOException {
        require(player.get("UUID") instanceof IntArrayTag && player.getIntArray("UUID").length==4,"Missing canonical player UUID");return player.getUUID("UUID");
    }
    private static ListTag inventory(CompoundTag player) throws IOException {
        require(player.get("Inventory") instanceof ListTag,"Missing player inventory list");
        ListTag list=(ListTag)player.get("Inventory");require(list.isEmpty() || list.getElementType()==Tag.TAG_COMPOUND,"Invalid player inventory list type");return list;
    }
    private static byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(DataOutputStream out=new DataOutputStream(bytes)){NbtIo.write(tag,out);}return bytes.toByteArray();
    }
    private static CompoundTag decode(byte[] bytes) throws IOException {
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag tag=NbtIo.read(input,NbtAccounter.create(256L*1024*1024));require(tag!=null && input.read()==-1,"Malformed journal NBT");return tag;
        } catch(RuntimeException e) { throw new IOException("Malformed journal record",e); }
    }
    private static CompoundTag compound(CompoundTag tag,String key) throws IOException { require(tag.get(key) instanceof CompoundTag,"Expected compound: "+key);return tag.getCompound(key); }
    private static String string(CompoundTag tag,String key) throws IOException { require(tag.get(key) instanceof StringTag,"Expected string: "+key);return tag.getString(key); }
    private static void fields(CompoundTag tag,String... fields) throws IOException { require(tag.getAllKeys().equals(Set.of(fields)),"Missing or unknown journal fields"); }
    private static UUID uuid(String value) throws IOException { try { UUID id=UUID.fromString(value);require(id.toString().equals(value),"Noncanonical journal UUID");return id; } catch(IllegalArgumentException e) { throw new IOException("Invalid journal UUID",e); } }
    private static void digest(String value) { if(value==null || !value.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid digest"); }
    private static Decision decision(String value) throws IOException { try{return Decision.valueOf(value);}catch(IllegalArgumentException e){throw new IOException("Unknown journal decision",e);} }
    private static void require(boolean value,String problem) throws IOException { if(!value)throw new IOException(problem); }
    private static final String GENESIS=LegacyQuestProgressStoreCodec.GENESIS;
}
