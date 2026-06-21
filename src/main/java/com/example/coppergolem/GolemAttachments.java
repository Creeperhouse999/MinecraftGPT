package com.example.coppergolem;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * Foundation for Plan A (decided in task-0b-report.md): the golem is a real vanilla
 * Copper Golem entity, and its persistent inventory / state is stored as a Fabric
 * AttachmentType keyed on the entity. Persists across save/reload via {@code persistent}.
 */
public final class GolemAttachments {

    /** Per-golem persistent data (inventory + scheduling state will live here). */
    public static final AttachmentType<CompoundTag> GOLEM_DATA =
            AttachmentRegistry.create(
                    Identifier.fromNamespaceAndPath(CopperGolemMod.MOD_ID, "golem_data"),
                    builder -> builder.persistent(CompoundTag.CODEC).copyOnDeath());

    private GolemAttachments() {}
}
