package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.ItemTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import dev.ftb.mods.ftbquests.item.MissingItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
/** A scoped adapter, NOT a global change to FTB semantics.
 * Only marked single-item, no-groupDetect, non-consuming tasks are supported.
 * Original 1.7.10 player inventory = main+armor; modern offhand intentionally excluded.
 * Full party membership/save semantics and live mixin application require further testing.
 */
@Mixin(value=ItemTask.class, remap=false)
public abstract class LegacyRetrievalItemTaskMixin {
    @Shadow public abstract ItemStack getItemStack();
    @Shadow public abstract boolean test(ItemStack stack);
    @Shadow public abstract boolean consumesResources();
    @Shadow public abstract boolean isOnlyFromCrafting();
    @Shadow public abstract boolean isTaskScreenOnly();
    @Inject(method="submitTask", at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private void gtnhMigration$legacyRetrieval(TeamData data, ServerPlayer player, ItemStack crafted, CallbackInfo ci) {
        Task task=(Task)(Object)this;
        if (!task.hasTag("gtnh_legacy_single_retrieval")) return;
        if (consumesResources() || isOnlyFromCrafting() || isTaskScreenOnly() || task.getQuest().getRequireSequentialTasks()) {
            throw new IllegalStateException("Unsupported legacy retrieval marker configuration: " + task.id);
        }
        ci.cancel();
        if (getItemStack().isEmpty() || getItemStack().getItem() instanceof MissingItem || !data.canStartTasks(task.getQuest()) || data.isCompleted(task)) return;
        long required=task.getMaxProgress();
        if (data.getProgress(task) >= required) return;
        Map<UUID, ServerPlayer> members=new LinkedHashMap<>();
        for (ServerPlayer member:data.getOnlineMembers()) members.put(member.getUUID(),member);
        members.putIfAbsent(player.getUUID(),player);
        Map<UUID,List<Integer>> inventory=new LinkedHashMap<>();
        for (ServerPlayer member:members.values()) {
            List<Integer> counts=new ArrayList<>();
            for (ItemStack stack:member.getInventory().items) if (!stack.isEmpty() && LegacyRetrievalStackMatcher.matches(getItemStack(),stack,this::test)) counts.add(stack.getCount());
            for (ItemStack stack:member.getInventory().armor) if (!stack.isEmpty() && LegacyRetrievalStackMatcher.matches(getItemStack(),stack,this::test)) counts.add(stack.getCount());
            inventory.put(member.getUUID(),counts);
        }
        long progress=LegacyRetrievalCounter.next(data.getProgress(task),required,inventory);
        if (progress != data.getProgress(task)) data.setProgress(task,progress);
    }
}
