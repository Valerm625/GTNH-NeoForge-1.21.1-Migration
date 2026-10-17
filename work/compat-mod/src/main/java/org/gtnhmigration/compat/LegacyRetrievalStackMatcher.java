package org.gtnhmigration.compat;

import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** Preserves BQ 3.8.89 ItemComparison.StackMatch's damageable-item rule.
 * Only the opt-in, exact-identity legacy retrieval adapter calls this helper.
 * Damage was a separate 1.7.10 stack field, not part of its compared NBT.
 * Absent and empty legacy custom NBT are equivalent in CompareNBTTag.
 * All other components still pass through the task's original predicate.
 */
public final class LegacyRetrievalStackMatcher {
    private LegacyRetrievalStackMatcher() {}

    public static boolean matches(ItemStack required, ItemStack sample, Predicate<ItemStack> predicate) {
        if (!ItemStack.isSameItem(required, sample)) {
            return predicate.test(sample);
        }
        boolean normalizeDamage = required.isDamageableItem();
        CustomData requiredData = required.get(DataComponents.CUSTOM_DATA);
        CustomData sampleData = sample.get(DataComponents.CUSTOM_DATA);
        boolean normalizeEmptyNbt = (requiredData == null || requiredData.isEmpty())
                && (sampleData == null || sampleData.isEmpty())
                && (requiredData == null) != (sampleData == null);
        if (!normalizeDamage && !normalizeEmptyNbt) return predicate.test(sample);
        ItemStack normalized = sample.copy();
        if (normalizeDamage) normalized.setDamageValue(required.getDamageValue());
        if (normalizeEmptyNbt) {
            // Keep the predicate's requirement intact; give its sample the same
            // representation of semantically empty custom NBT.
            if (requiredData == null) normalized.remove(DataComponents.CUSTOM_DATA);
            else normalized.set(DataComponents.CUSTOM_DATA, requiredData);
        }
        return predicate.test(normalized);
    }
}
