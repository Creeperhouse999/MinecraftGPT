package com.example.coppergolem.task;

import com.example.coppergolem.entity.GolemPrimitives;
import com.example.coppergolem.entity.ToolManager;
import net.minecraft.world.entity.LivingEntity;

/**
 * Attacks hostile mobs within 16 blocks until none remain in range.
 *
 * <p>If a {@code targetType} is given (e.g. "creeper", "zombie"), only mobs whose
 * type id contains that string are attacked; others are ignored. With no target,
 * all hostiles are cleared.</p>
 *
 * <p>Each tick: find target → move toward it → when within range, attack.
 * Completes when no matching target remains.</p>
 */
public final class AttackTask implements TaskHandler {

    private static final int SEARCH_RADIUS  = 16;
    private static final double ATTACK_RANGE = 2.5;

    /** Optional mob-type filter (lowercase substring of the type id); null = any hostile. */
    private final String targetType;

    /** Optional tool manager to equip a weapon (sword/axe) before fighting; may be null. */
    private final ToolManager tools;

    private LivingEntity currentTarget = null;
    private String statusMsg = "searching for hostiles";
    private boolean weaponEquipped = false;

    public AttackTask() {
        this(null, null);
    }

    public AttackTask(String targetType) {
        this(targetType, null);
    }

    public AttackTask(String targetType, ToolManager tools) {
        this.targetType = (targetType == null || targetType.isBlank())
                ? null
                : targetType.toLowerCase();
        this.tools = tools;
    }

    @Override
    public boolean tick(GolemPrimitives g) {
        // Equip best weapon once: prefer sword, fall back to axe. Hand/pickaxe work
        // too but deal less damage. Don't block the fight if neither is available.
        if (!weaponEquipped && tools != null) {
            if (g.inventory().findSwordSlot() >= 0) {
                tools.ensureTool(ToolManager.ToolKind.SWORD);
            } else if (g.inventory().findAxeSlot() >= 0) {
                tools.ensureTool(ToolManager.ToolKind.AXE);
            }
            weaponEquipped = true; // only attempt once; don't loop crafting
        }

        // Refresh or find a target.
        if (currentTarget == null || currentTarget.isRemoved() || currentTarget.isDeadOrDying()
                || !matches(currentTarget)) {
            currentTarget = findMatching(g);
        }

        if (currentTarget == null || currentTarget.isRemoved() || currentTarget.isDeadOrDying()) {
            statusMsg = targetType != null
                    ? "no " + targetType + " nearby"
                    : "no hostiles nearby";
            return true; // done
        }

        statusMsg = "attacking " + currentTarget.getType().getDescriptionId();

        if (g.position().closerThan(currentTarget.blockPosition(), ATTACK_RANGE)) {
            g.attackEntity(currentTarget);
        } else {
            g.moveTo(currentTarget.blockPosition());
        }
        return false; // still fighting
    }

    /** Find nearest hostile matching the optional type filter. */
    private LivingEntity findMatching(GolemPrimitives g) {
        LivingEntity nearest = g.findNearestHostile(SEARCH_RADIUS);
        if (targetType == null) return nearest;
        // findNearestHostile returns the closest; if it doesn't match the filter,
        // fall back to scanning all hostiles for one that does.
        if (nearest != null && matches(nearest)) return nearest;
        for (LivingEntity e : g.findHostiles(SEARCH_RADIUS)) {
            if (matches(e)) return e;
        }
        return null;
    }

    private boolean matches(LivingEntity e) {
        if (targetType == null) return true;
        if (e == null) return false;
        String id = e.getType().getDescriptionId().toLowerCase();
        return id.contains(targetType);
    }

    @Override
    public String status() {
        return statusMsg;
    }

    @Override
    public void pause() {}

    @Override
    public void resume() {}
}
