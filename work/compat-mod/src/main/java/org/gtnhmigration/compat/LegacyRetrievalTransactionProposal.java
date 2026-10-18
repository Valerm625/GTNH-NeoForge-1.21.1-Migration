package org.gtnhmigration.compat;

import java.io.IOException;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;

/** Binds one actual inventory plan to the ledger snapshot used to compute it.
 * No live player saving, event/reward publication or world registration occurs.
 * A future coordinator must verify the baseline player file, authorize the
 * actor/party and keep inventories quiescent until persistence is resolved.
 */
public final class LegacyRetrievalTransactionProposal {
    private final Optional<String> expectedHead;
    private final UUID actor;
    private final CompoundTag beforePlayer, afterPlayer;
    private final State afterState;

    private LegacyRetrievalTransactionProposal(Optional<String> expectedHead, UUID actor,
            CompoundTag beforePlayer, CompoundTag afterPlayer, State afterState) {
        this.expectedHead=expectedHead; this.actor=actor;
        this.beforePlayer=beforePlayer.copy(); this.afterPlayer=afterPlayer.copy(); this.afterState=afterState;
    }
    public static LegacyRetrievalTransactionProposal bind(LegacyRetrievalInventoryBridge.Prepared inventory,
            Optional<LegacyQuestProgressFileStore.Head> head, Catalog catalog, CompoundTag beforePlayer) {
        Objects.requireNonNull(inventory); Objects.requireNonNull(head); Objects.requireNonNull(catalog);
        var plan=inventory.plan();
        if(!plan.evaluated()) throw new IllegalArgumentException("Quest gate did not authorize an evaluated plan");
        State before=head.map(h->h.frame().snapshot().state()).orElse(State.empty());
        Map<Integer,Definition> definitions=new HashMap<>();
        for(Definition definition:catalog.definitions()) if(definition.key().questId().equals(plan.questId())) {
            if(!definition.key().factoryId().equals(LegacyQuestRetrievalPlanner.RETRIEVAL_TYPE))
                throw new IllegalArgumentException("Quest contains an unhandled task factory");
            definitions.put(definition.key().index(),definition);
        }
        if(definitions.isEmpty() || !definitions.keySet().equals(plan.beforeStates().keySet())
                || !definitions.keySet().equals(plan.afterStates().keySet()))
            throw new IllegalArgumentException("Plan does not cover the complete catalog quest");
        for(TaskKey key:before.tasks().keySet()) if(key.questId().equals(plan.questId())
                && (!definitions.containsKey(key.index()) || !definitions.get(key.index()).key().equals(key)))
            throw new IllegalArgumentException("Saved quest task identity differs from the catalog");
        Map<TaskKey,LegacyRetrievalEngine.State> after=new LinkedHashMap<>(before.tasks());
        for(Definition definition:definitions.values()) {
            TaskKey key=definition.key();
            var saved=before.tasks().getOrDefault(key,LegacyRetrievalEngine.State.empty());
            if(!saved.equals(plan.beforeStates().get(key.index()))) throw new IllegalArgumentException("Plan used stale quest progress");
            var next=plan.afterStates().get(key.index());
            for(var row:next.progress().values()) if(row.size()!=definition.requirementCount())
                throw new IllegalArgumentException("Plan requirement shape differs from the catalog");
            after.put(key,next);
        }
        CompoundTag proposed=inventory.proposedPlayerData(beforePlayer);
        return new LegacyRetrievalTransactionProposal(head.map(LegacyQuestProgressFileStore.Head::sha256),
                inventory.actor(),beforePlayer,proposed,new State(before.questCompletions(),after));
    }
    public Optional<String> expectedHead() { return expectedHead; }
    public CompoundTag beforePlayer() { return beforePlayer.copy(); }
    public CompoundTag afterPlayer() { return afterPlayer.copy(); }
    public State afterState() { return afterState; }
    public LegacyRetrievalTransactionJournal.Pending prepare(LegacyRetrievalTransactionJournal journal) throws IOException {
        return journal.prepare(expectedHead,actor,beforePlayer,afterPlayer,afterState);
    }
    public LegacyRetrievalTransactionJournal.Pending prepare(LegacyRetrievalTransactionJournal journal,
            LegacyPlayerDataFileReader.Authority authority) throws IOException {
        return journal.prepare(expectedHead,actor,beforePlayer,afterPlayer,afterState,authority);
    }
}
