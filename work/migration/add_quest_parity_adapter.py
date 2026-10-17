"""Add opt-in legacy retrieval semantics for reviewed exported FTB item tasks only."""
from pathlib import Path
import json
R=Path(__file__).resolve().parents[2];W=R/'work';P=W/'compat-mod/src/main/java/org/gtnhmigration/compat';P.mkdir(parents=True,exist_ok=True)
(P/'LegacyRetrievalCounter.java').write_text('''package org.gtnhmigration.compat;
import java.util.Map;
import java.util.UUID;
/** Independently authored model of reviewed BQ3.8.89 single-item non-consuming retrieval semantics.
 * Partial progress is a current-inventory observation, not a high-water mark.
 * Completed requirements remain latched; multiple calls must not accumulate.
 */
public final class LegacyRetrievalCounter {
    private LegacyRetrievalCounter() {}
    public static long next(long previous, long required, Map<UUID, ? extends Iterable<Integer>> matches) {
        if (required < 1 || previous < 0) throw new IllegalArgumentException("Invalid retrieval progress");
        if (previous >= required) return required;
        long total = 0;
        for (Iterable<Integer> stacks : matches.values()) {
            for (int count : stacks) {
                if (count < 0) throw new IllegalArgumentException("Negative item count");
                total += Math.min((long) count, required - total);
                if (total >= required) return required;
            }
        }
        return total;
    }
}
''',encoding='utf-8')
(P/'LegacyRetrievalItemTaskMixin.java').write_text('''package org.gtnhmigration.compat;
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
            for (ItemStack stack:member.getInventory().items) if (!stack.isEmpty() && test(stack)) counts.add(stack.getCount());
            for (ItemStack stack:member.getInventory().armor) if (!stack.isEmpty() && test(stack)) counts.add(stack.getCount());
            inventory.put(member.getUUID(),counts);
        }
        long progress=LegacyRetrievalCounter.next(data.getProgress(task),required,inventory);
        if (progress != data.getProgress(task)) data.setProgress(task,progress);
    }
}
''',encoding='utf-8')
(P/'LegacyDimensionLocationTaskMixin.java').write_text('''package org.gtnhmigration.compat;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.LocationTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Opt-in adapter for reviewed BetterQuesting dimension-only location tasks.
 * It intentionally does not alter ordinary FTB LocationTask coordinate boxes.
 */
@Mixin(value=LocationTask.class, remap=false)
public abstract class LegacyDimensionLocationTaskMixin {
    @Shadow private ResourceKey<Level> dimension;
    @Inject(method="canSubmit", at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private void gtnhMigration$dimensionOnly(TeamData data, ServerPlayer player, CallbackInfoReturnable<Boolean> ci) {
        Task task=(Task)(Object)this;
        if (!task.hasTag("gtnh_legacy_dimension_only")) return;
        if (dimension == null) throw new IllegalStateException("Missing dimension on legacy location task: " + task.id);
        ci.setReturnValue(dimension == player.level().dimension());
    }
}
''',encoding='utf-8')
# Patch existing reproducible build without changing the published filename; hash/source locks identify this build.
p=W/'migration/build_capacity_compat.py';s=p.read_text(encoding='utf-8')
needle='[[mixins]]\nconfig="gtnh_migration_compat.mixins.json"';dep='''[[dependencies.gtnh_migration_compat]]
modId="ftbquests"
type="required"
versionRange="[2101.1.36]"
ordering="AFTER"
side="BOTH"
'''
if 'modId="ftbquests"' not in s:s=s.replace(needle,dep+needle)
s=s.replace("'mixins':['EnderTankCapacityMixin']","'mixins':['EnderTankCapacityMixin','LegacyRetrievalItemTaskMixin']")
needle=";cp=';'.join(p.relative_to(R).as_posix() for p in libs)"
replacement=";libs += [O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-server.jar', O/'libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar', O/'libraries/net/neoforged/neoforge/21.1.262/neoforge-21.1.262-universal.jar'] + list((O/'mods').glob('*.jar'));cp=';'.join(p.relative_to(R).as_posix() for p in libs)"
assert needle in s or 'libs += [O/' in s
if 'libs += [O/' not in s:s=s.replace(needle,replacement)
s=s.replace("'runtime_storage_verified':False}","'runtime_storage_verified':False,'quest_adapter':'scoped single-item legacy retrieval','quest_adapter_live_verified':False}")
p.write_text(s,encoding='utf-8')
print('LEGACY_RETRIEVAL_ADAPTER_SOURCE_WRITTEN')
