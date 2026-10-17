package org.gtnhmigration.compat;

import dev.architectury.hooks.level.entity.PlayerHooks;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Source PlayerContainerListener main slots, including empty changes, are
 * deduplicated until the next START/Pre tick. Scoped to existing marked tasks;
 * this does not implement the future multi-retrieval or party-history bridge.
 */
@EventBusSubscriber(modid="gtnh_migration_compat")
public final class LegacyInventoryObserver {
    private static final LegacyInventoryRescanQueue<MinecraftServer,ServerPlayer> QUEUE=new LegacyInventoryRescanQueue<>();
    private LegacyInventoryObserver() {}
    public static boolean isMarked(Task task) { return task.hasTag("gtnh_legacy_single_retrieval"); }
    public static void schedule(ServerPlayer player) {
        MinecraftServer server=player.getServer();
        if(server!=null && !PlayerHooks.isFake(player)) QUEUE.schedule(server,player);
    }
    @SubscribeEvent
    public static void beforeTick(ServerTickEvent.Pre event) {
        for(ServerPlayer player:QUEUE.drain(event.getServer())) {
            ServerQuestFile file=ServerQuestFile.INSTANCE;
            if(file==null) continue;
            file.getTeamData(player).ifPresent(data->{
                if(data.isLocked()) return;
                file.withPlayerContext(player,()->{
                    for(Task task:file.getSubmitTasks()) {
                        if(isMarked(task) && data.canStartTasks(task.getQuest())) {
                            task.submitTask(data,player,ItemStack.EMPTY);
                        }
                    }
                });
            });
        }
    }
    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { QUEUE.clear(event.getServer()); }
}
