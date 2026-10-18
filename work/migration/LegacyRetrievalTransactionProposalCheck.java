package org.gtnhmigration.compat;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import org.gtnhmigration.compat.LegacyRetrievalEngine.*;
import org.gtnhmigration.compat.LegacyQuestRetrievalPlanner.*;
import org.gtnhmigration.compat.LegacyRetrievalInventoryBridge.*;
import org.gtnhmigration.compat.LegacyQuestProgressEnvelope.*;
import org.gtnhmigration.compat.LegacyQuestProgressStoreCodec.*;

/** Actual vanilla inventory and NBT codecs, real ledger, synthetic player files.
 * Native Inventory.save requires Player.registryAccess: it is not invoked here.
 */
public final class LegacyRetrievalTransactionProposalCheck {
    static int assertions;
    static final UUID ACTOR=new UUID(0,1),OFFLINE=new UUID(0,3),QUEST=new UUID(0,2),OTHER_QUEST=new UUID(0,99);
    static final TaskKey DETECT=new TaskKey(QUEST,0,"bq_standard:retrieval"),CONSUME=new TaskKey(QUEST,1,"bq_standard:retrieval"),OTHER=new TaskKey(OTHER_QUEST,5,"bq_standard:retrieval");
    static final Binding BINDING=new Binding("a".repeat(64),"b".repeat(64));
    static final Catalog CATALOG=new Catalog(List.of(QUEST,OTHER_QUEST),List.of(new Definition(DETECT,2),new Definition(CONSUME,1),new Definition(OTHER,1)));
    static HolderLookup.Provider registries;
    static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
    interface Action {void run()throws Exception;}
    static void rejects(Action action,String message)throws Exception{try{action.run();}catch(IOException|IllegalArgumentException|IllegalStateException expected){assertions++;return;}throw new AssertionError("Accepted "+message);}
    static Requirement<FrozenStack> req(Item item,int count){return new Requirement<>(count,k->k.stack().is(item));}
    static Quest<FrozenStack> quest(){return new Quest<>(QUEST,List.of(Task.retrieval(0,new Config<>(List.of(req(Items.GRAVEL,9),req(Items.OAK_LOG,5)),false,false,false,false)),Task.retrieval(1,new Config<>(List.of(req(Items.OAK_LOG,2)),true,false,false,false))),"AND",-1,false,false,false,false);}
    static Inventory inventory(){Inventory i=new Inventory(null);i.items.set(0,new ItemStack(Items.GRAVEL,9));i.items.set(1,new ItemStack(Items.OAK_LOG,5));i.offhand.set(0,new ItemStack(Items.DIAMOND,7));return i;}
    static LegacyQuestProgressEnvelope.State initial(){
        CompoundTag completion=new CompoundTag();completion.putString("uuid",OFFLINE.toString());completion.putLong("opaque",123456789L);
        return new LegacyQuestProgressEnvelope.State(Map.of(OTHER_QUEST,Map.of(OFFLINE,completion)),Map.of(OTHER,new LegacyRetrievalEngine.State(Map.of(OFFLINE,List.of(77)),Set.of(OFFLINE)),DETECT,new LegacyRetrievalEngine.State(Map.of(OFFLINE,List.of(3,4)),Set.of())));
    }
    static Map<Integer,LegacyRetrievalEngine.State> previous(LegacyQuestProgressEnvelope.State state){Map<Integer,LegacyRetrievalEngine.State> result=new HashMap<>();state.tasks().forEach((key,value)->{if(key.questId().equals(QUEST))result.put(key.index(),value);});return result;}
    static Prepared prepare(Inventory i,LegacyQuestProgressEnvelope.State before){return LegacyRetrievalInventoryBridge.prepare(quest(),previous(before),ACTOR,List.of(ACTOR,OFFLINE),Map.of(ACTOR,i),registries,new Gate(true,true,true,false));}
    // Fixture counterpart of the inspected vanilla Inventory.save slot order.
    static ListTag entries(Inventory inventory){
        ListTag result=new ListTag();
        for(int slot=0;slot<41;slot++){
            ItemStack stack=slot<36?inventory.items.get(slot):slot<40?inventory.armor.get(slot-36):inventory.offhand.get(0);
            if(!stack.isEmpty()){CompoundTag tag=new CompoundTag();tag.putByte("Slot",(byte)(slot<36?slot:slot<40?slot+64:150));result.add(stack.save(registries,tag));}
        }
        return result;
    }
    static CompoundTag player(Inventory inventory){CompoundTag p=new CompoundTag();p.putUUID("UUID",ACTOR);p.put("Inventory",entries(inventory));p.putLongArray("opaque",new long[]{Long.MAX_VALUE,Long.MIN_VALUE});CompoundTag persistent=new CompoundTag();persistent.putString("other:mod","retain");p.put("NeoForgeData",persistent);return p;}
    static LegacyRetrievalTransactionJournal journal(LegacyQuestProgressFileStore store){return new LegacyRetrievalTransactionJournal(store,BINDING,CATALOG);}
    static void normal(Path root)throws Exception{
        Inventory i=inventory();ItemStack logs=i.items.get(1);logs.set(DataComponents.CUSTOM_NAME,Component.literal("preserve components"));CompoundTag data=new CompoundTag();data.putByteArray("nested",new byte[]{1,2,3});logs.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        CompoundTag before=player(i);LegacyRetrievalTransactionProposal proposal;LegacyRetrievalTransactionJournal.Pending pending;
        try(var store=LegacyQuestProgressFileStore.open(root,BINDING,CATALOG)){
            store.commit(Optional.empty(),initial(),List.of(new SourceDocument("original",new byte[]{0,-1,7})));
            var prepared=prepare(i,initial());proposal=LegacyRetrievalTransactionProposal.bind(prepared,store.current(),CATALOG,before);
            check(i.items.get(1).getCount()==5,"Proposal mutated live inventory");
            check(proposal.afterState().tasks().get(OTHER).equals(initial().tasks().get(OTHER)),"Unrelated quest progress lost");
            check(proposal.afterState().questCompletions().equals(initial().questCompletions()),"Opaque quest completion changed");
            check(proposal.afterState().tasks().get(DETECT).progress().get(OFFLINE).equals(List.of(9,5)),"Offline participant did not receive the source planner credit");
            CompoundTag alias=proposal.afterPlayer();alias.getList("Inventory",10).clear();check(!proposal.afterPlayer().getList("Inventory",10).isEmpty(),"Proposal aliases caller");
            pending=proposal.prepare(journal(store));
            check(store.current().orElseThrow().frame().snapshot().state().equals(initial()),"Preparation committed progress early");
            prepared.applyCountsWithoutPublishing(()->true);
            check(i.items.get(1).getCount()==3 && i.offhand.get(0).getCount()==7,"Wrong real inventory delta");
            check(proposal.afterPlayer().get("Inventory").equals(entries(i)),"Proposed player encoding differs from applied actual inventory");
            check(proposal.afterPlayer().getCompound("NeoForgeData").equals(before.getCompound("NeoForgeData")),"Unrelated persistent data changed");
            Path file=root.resolveSibling("player-fixture.dat");NbtIo.writeCompressed(pending.playerDataToPersist(),file);
        }
        try(var store=LegacyQuestProgressFileStore.open(root,BINDING,CATALOG)){
            CompoundTag actual=NbtIo.readCompressed(root.resolveSibling("player-fixture.dat"),NbtAccounter.create(32L*1024*1024));
            check(journal(store).resolve(actual).decision()==LegacyRetrievalTransactionJournal.Decision.COMMIT,"Actual after-inventory failed to resolve");
            check(store.current().orElseThrow().frame().snapshot().state().equals(proposal.afterState()),"Wrong committed task state");
            check(store.current().orElseThrow().frame().snapshot().sources().getFirst().bytes()[1]==-1,"Original bytes lost");
            rejects(()->proposal.prepare(journal(store)),"stale proposal replay");
            var replay=prepare(i,proposal.afterState());check(replay.plan().consumed().isEmpty(),"Reloaded progress allows double consumption");
        }
    }
    static void boundaries(Path root)throws Exception{
        try(var store=LegacyQuestProgressFileStore.open(root,BINDING,CATALOG)){
            var head=store.commit(Optional.empty(),initial(),List.of());Inventory i=inventory();var prepared=prepare(i,initial());CompoundTag before=player(i);
            var proposal=LegacyRetrievalTransactionProposal.bind(prepared,Optional.of(head),CATALOG,before);
            var newer=store.commit(Optional.of(head.sha256()),initial(),List.of());
            rejects(()->proposal.prepare(journal(store)),"stale ledger revision with unchanged progress");
            check(store.current().orElseThrow().sha256().equals(newer.sha256()) && i.items.get(1).getCount()==5,"Stale rejection mutated state");
            var pending=LegacyRetrievalTransactionProposal.bind(prepared,store.current(),CATALOG,before).prepare(journal(store));
            check(journal(store).resolve(before).decision()==LegacyRetrievalTransactionJournal.Decision.ABORT,"Unapplied inventory did not abort");
            check(store.current().orElseThrow().frame().snapshot().state().equals(initial()),"Abort changed unrelated or old progress");
            var old=prepare(i,LegacyQuestProgressEnvelope.State.empty());rejects(()->LegacyRetrievalTransactionProposal.bind(old,store.current(),CATALOG,before),"stale per-task state");
            Catalog missing=new Catalog(List.of(QUEST),List.of(new Definition(CONSUME,1)));rejects(()->LegacyRetrievalTransactionProposal.bind(prepared,store.current(),missing,before),"partial task catalog");
            Catalog shape=new Catalog(List.of(QUEST),List.of(new Definition(DETECT,1),new Definition(CONSUME,1)));rejects(()->LegacyRetrievalTransactionProposal.bind(prepared,store.current(),shape,before),"changed requirement shape");
        }
        Inventory i=inventory();var p=prepare(i,initial());CompoundTag before=player(i);
        for(int scenario=0;scenario<7;scenario++){
            CompoundTag bad=before.copy();ListTag list=bad.getList("Inventory",10);
            switch(scenario){
                case 0 -> bad.putUUID("UUID",OFFLINE);
                case 1 -> list.getCompound(1).putInt("count",4);
                case 2 -> list.remove(1);
                case 3 -> list.add(list.getCompound(0).copy());
                case 4 -> list.getCompound(1).putByte("Slot",(byte)50);
                case 5 -> list.getCompound(1).putString("unknown_item_field","must not drop");
                case 6 -> list.getCompound(0).putInt("Slot",0);
            }
            rejects(()->p.proposedPlayerData(bad),"mismatched or lossy player snapshot "+scenario);
        }
        for(int slot=0;slot<40;slot++){
            Inventory one=new Inventory(null);if(slot<36)one.items.set(slot,new ItemStack(Items.OAK_LOG,2));else one.armor.set(slot-36,new ItemStack(Items.OAK_LOG,2));
            Quest<FrozenStack> q=new Quest<>(QUEST,List.of(Task.retrieval(0,new Config<>(List.of(req(Items.OAK_LOG,2)),true,false,false,false))),"AND",-1,false,false,false,false);
            var prepared=LegacyRetrievalInventoryBridge.prepare(q,Map.of(),ACTOR,List.of(ACTOR),Map.of(ACTOR,one),registries,new Gate(true,true,true,false));
            CompoundTag proposed=prepared.proposedPlayerData(player(one));prepared.applyCountsWithoutPublishing(()->true);
            check(proposed.get("Inventory").equals(entries(one)),"Native slot/removal mapping failed at "+slot);
        }
    }
    public static void main(String[] args)throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();registries=RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        Path root=Path.of(args[0]);normal(root.resolve("normal"));boundaries(root.resolve("boundaries"));
        System.out.println("{\"test\":\"actual_inventory_to_journal_proposal\",\"passed\":true,\"assertions\":"+assertions+",\"runtime_integrated\":false,\"export_enabled\":false,\"native_player_save_verified\":false,\"game_runtime_verified\":false,\"actual_player_save_fixtures\":0}");
    }
}
