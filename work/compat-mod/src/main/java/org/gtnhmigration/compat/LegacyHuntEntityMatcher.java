package org.gtnhmigration.compat;

import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;

/** The two distinct entity predicates in BetterQuesting 3.8.89 TaskHunt.
 * A registered subject and a known target class are required in both modes.
 * Subtype mode compares class ancestry; exact mode compares registry identity.
 */
public final class LegacyHuntEntityMatcher {
    private LegacyHuntEntityMatcher() {}

    /** Null preserves ordinary FTB matching, including its tags/name/NBT filters.
     * Class literals do not construct entities or require a game world.
     */
    public static Boolean override(boolean subtypes, boolean exact, String targetId,
                                   boolean hasEntityTypeTag, String customName, String nbtFilter,
                                   String subjectId, Class<?> subjectClass) {
        if (!subtypes && !exact) return null;
        if (subtypes == exact || targetId == null || hasEntityTypeTag
                || customName == null || !customName.isEmpty()
                || nbtFilter == null || !nbtFilter.isEmpty()) {
            throw new IllegalStateException("Unsupported legacy hunt marker configuration");
        }
        Class<?> targetClass = switch (targetId) {
            case "minecraft:pig" -> Pig.class;
            case "minecraft:cow" -> Cow.class;
            case "minecraft:wither" -> WitherBoss.class;
            case "minecraft:ender_dragon" -> EnderDragon.class;
            default -> throw new IllegalStateException("Unreviewed legacy hunt entity: " + targetId);
        };
        return matches(targetId, targetClass, subjectId, subjectClass, subtypes);
    }

    public static boolean matches(String targetId, Class<?> targetClass,
                                  String subjectId, Class<?> subjectClass, boolean subtypes) {
        if (targetId == null || targetClass == null || subjectId == null || subjectClass == null) return false;
        return subtypes ? targetClass.isAssignableFrom(subjectClass) : targetId.equals(subjectId);
    }
}
