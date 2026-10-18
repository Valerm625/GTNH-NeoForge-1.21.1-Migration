package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.util.FTBQuestsInventoryListener;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leave ordinary FTB listeners intact; legacy scans use their own Pre queue. */
@Mixin(value=FTBQuestsInventoryListener.class,remap=false)
public abstract class LegacyInventoryListenerMixin {
    @Shadow @Final public ServerPlayer player;
    @Inject(method="slotChanged",at=@At("HEAD"),require=1,remap=false)
    private void gtnhMigration$queueLegacySlot(AbstractContainerMenu menu,int index,ItemStack stack,CallbackInfo ci) {
        if(LegacyInventoryRescanQueue.observesSlot(menu==player.inventoryMenu,index)) {
            LegacyInventoryObserver.schedule(player);
        }
    }
    // The call resides in javac's synthetic lambda within detect. The pinned
    // class has exactly one such call; require/allow reject target drift.
    @Redirect(method="*",at=@At(value="INVOKE",target="Ldev/ftb/mods/ftbquests/quest/task/Task;submitTask(Ldev/ftb/mods/ftbquests/quest/TeamData;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V"),require=1,allow=1,remap=false)
    private static void gtnhMigration$deferLegacyAutomatic(Task task,TeamData data,ServerPlayer player,ItemStack crafted) {
        if(crafted.isEmpty() && LegacyInventoryObserver.isMarked(task)) return;
        task.submitTask(data,player,crafted);
    }
}
