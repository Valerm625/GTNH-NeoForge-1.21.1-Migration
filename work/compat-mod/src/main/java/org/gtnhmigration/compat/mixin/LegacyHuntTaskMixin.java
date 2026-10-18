package org.gtnhmigration.compat.mixin;

import org.gtnhmigration.compat.*;

import dev.ftb.mods.ftbquests.quest.task.KillTask;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scoped to reviewed hunt tasks. Ordinary FTB kill tasks keep their own predicate.
 * Preserves the legacy subtype flag, including Cow -> MushroomCow. NBT filters,
 * damage-source restrictions and unreviewed entity class mappings remain blocked.
 * This does not establish equivalence of party progress or unported modded mobs.
 */
@Mixin(value=KillTask.class, remap=false)
public abstract class LegacyHuntTaskMixin {
    @Shadow private ResourceLocation entityTypeId;
    @Shadow private TagKey<EntityType<?>> entityTypeTag;
    @Shadow private String customName;
    @Shadow private String nbtFilter;

    @Inject(method="match", at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private void gtnhMigration$legacyHunt(LivingEntity entity, CallbackInfoReturnable<Boolean> ci) {
        Task task=(Task)(Object)this;
        boolean subtypes=task.hasTag("gtnh_legacy_hunt_subtypes");
        boolean exact=task.hasTag("gtnh_legacy_hunt_exact");
        if (!subtypes && !exact) return;
        ResourceLocation subject=BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String subjectId=subject != null && BuiltInRegistries.ENTITY_TYPE.containsKey(subject)
                && BuiltInRegistries.ENTITY_TYPE.get(subject) == entity.getType() ? subject.toString() : null;
        Boolean result=LegacyHuntEntityMatcher.override(subtypes, exact,
                entityTypeId == null ? null : entityTypeId.toString(), entityTypeTag != null,
                customName, nbtFilter, subjectId, entity.getClass());
        if (result != null) {
            ci.setReturnValue(result);
        }
    }
}
