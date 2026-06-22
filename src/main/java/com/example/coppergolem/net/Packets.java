package com.example.coppergolem.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.List;

/**
 * All custom packet payload types for coppergolem.
 *
 * <p>C2S payloads use {@link PayloadTypeRegistry#serverboundPlay()} which takes
 * {@link RegistryFriendlyByteBuf}. S2C payloads use
 * {@link PayloadTypeRegistry#clientboundPlay()} likewise.</p>
 *
 * <p>Call {@link #register()} exactly once during mod initialisation (server side).</p>
 */
public final class Packets {

    private Packets() {}

    // =========================================================================
    // Shared inner record types (used in list payloads)
    // =========================================================================

    /** One row in a plan view — a step label and its execution state. */
    public record StepLine(String label, String state) {
        static final StreamCodec<FriendlyByteBuf, StepLine> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, StepLine::label,
                        ByteBufCodecs.STRING_UTF8, StepLine::state,
                        StepLine::new);
    }

    /** One row in a zone list. */
    public record ZoneLine(String name, int minX, int minZ, int maxX, int maxZ) {
        static final StreamCodec<FriendlyByteBuf, ZoneLine> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ZoneLine::name,
                        ByteBufCodecs.VAR_INT,     ZoneLine::minX,
                        ByteBufCodecs.VAR_INT,     ZoneLine::minZ,
                        ByteBufCodecs.VAR_INT,     ZoneLine::maxX,
                        ByteBufCodecs.VAR_INT,     ZoneLine::maxZ,
                        ZoneLine::new);
    }

    // =========================================================================
    // C2S payloads
    // =========================================================================

    /** Client tells server to start a new job from a freeform prompt. */
    public record PromptC2S(String text, boolean preApprove)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<PromptC2S> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "prompt_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PromptC2S> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, PromptC2S::text,
                        ByteBufCodecs.BOOL,        PromptC2S::preApprove,
                        PromptC2S::new);

        @Override
        public CustomPacketPayload.Type<PromptC2S> type() { return TYPE; }
    }

    /** Client tells server to stop the current job. */
    public record StopC2S() implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<StopC2S> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "stop_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StopC2S> CODEC =
                StreamCodec.unit(new StopC2S());

        @Override
        public CustomPacketPayload.Type<StopC2S> type() { return TYPE; }
    }

    /** Client answers an approval gate (yes/no). */
    public record ApprovalReplyC2S(boolean approve) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ApprovalReplyC2S> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "approval_reply_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ApprovalReplyC2S> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, ApprovalReplyC2S::approve,
                        ApprovalReplyC2S::new);

        @Override
        public CustomPacketPayload.Type<ApprovalReplyC2S> type() { return TYPE; }
    }

    /** Client sends an error-recovery choice + optional custom instruction. */
    public record ErrorChoiceC2S(String choice, String instruction)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ErrorChoiceC2S> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "error_choice_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ErrorChoiceC2S> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ErrorChoiceC2S::choice,
                        ByteBufCodecs.STRING_UTF8, ErrorChoiceC2S::instruction,
                        ErrorChoiceC2S::new);

        @Override
        public CustomPacketPayload.Type<ErrorChoiceC2S> type() { return TYPE; }
    }

    /**
     * Client edits a protection zone.
     * {@code op} is one of: "add", "remove", "update".
     */
    public record ZoneEditC2S(String op, String name,
                               int minX, int minZ, int maxX, int maxZ)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ZoneEditC2S> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "zone_edit_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ZoneEditC2S> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ZoneEditC2S::op,
                        ByteBufCodecs.STRING_UTF8, ZoneEditC2S::name,
                        ByteBufCodecs.VAR_INT,     ZoneEditC2S::minX,
                        ByteBufCodecs.VAR_INT,     ZoneEditC2S::minZ,
                        ByteBufCodecs.VAR_INT,     ZoneEditC2S::maxX,
                        ByteBufCodecs.VAR_INT,     ZoneEditC2S::maxZ,
                        ZoneEditC2S::new);

        @Override
        public CustomPacketPayload.Type<ZoneEditC2S> type() { return TYPE; }
    }

    /** Client sets the golem's home point. */
    public record SetHomeC2S(int x, int y, int z) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<SetHomeC2S> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "set_home_c2s"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SetHomeC2S> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetHomeC2S::x,
                        ByteBufCodecs.VAR_INT, SetHomeC2S::y,
                        ByteBufCodecs.VAR_INT, SetHomeC2S::z,
                        SetHomeC2S::new);

        @Override
        public CustomPacketPayload.Type<SetHomeC2S> type() { return TYPE; }
    }

    // =========================================================================
    // S2C payloads
    // =========================================================================

    /** Server sends the current plan view (list of steps with their states). */
    public record PlanViewS2C(List<StepLine> steps) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<PlanViewS2C> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "plan_view_s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, PlanViewS2C> CODEC =
                StreamCodec.composite(
                        StepLine.CODEC.apply(ByteBufCodecs.list()), PlanViewS2C::steps,
                        PlanViewS2C::new);

        @Override
        public CustomPacketPayload.Type<PlanViewS2C> type() { return TYPE; }
    }

    /** Server asks the player to approve an item acquisition. */
    public record AskGateS2C(String itemDescription) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<AskGateS2C> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "ask_gate_s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, AskGateS2C> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, AskGateS2C::itemDescription,
                        AskGateS2C::new);

        @Override
        public CustomPacketPayload.Type<AskGateS2C> type() { return TYPE; }
    }

    /** Server pushes a status line + key-pool counters. */
    public record StatusS2C(String status, int activeKeys, int coolingKeys)
            implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<StatusS2C> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "status_s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, StatusS2C> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, StatusS2C::status,
                        ByteBufCodecs.VAR_INT,     StatusS2C::activeKeys,
                        ByteBufCodecs.VAR_INT,     StatusS2C::coolingKeys,
                        StatusS2C::new);

        @Override
        public CustomPacketPayload.Type<StatusS2C> type() { return TYPE; }
    }

    /** Server sends the full zone list. */
    public record ZoneListS2C(List<ZoneLine> zones) implements CustomPacketPayload {

        public static final CustomPacketPayload.Type<ZoneListS2C> TYPE =
                new CustomPacketPayload.Type<>(
                        Identifier.fromNamespaceAndPath("coppergolem", "zone_list_s2c"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ZoneListS2C> CODEC =
                StreamCodec.composite(
                        ZoneLine.CODEC.apply(ByteBufCodecs.list()), ZoneListS2C::zones,
                        ZoneListS2C::new);

        @Override
        public CustomPacketPayload.Type<ZoneListS2C> type() { return TYPE; }
    }

    // =========================================================================
    // Registration
    // =========================================================================

    /**
     * Registers all payload types with the Fabric payload registries.
     * Must be called once, server-side, during mod initialisation.
     */
    public static void register() {
        // C2S
        PayloadTypeRegistry.serverboundPlay().register(PromptC2S.TYPE,        PromptC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(StopC2S.TYPE,          StopC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ApprovalReplyC2S.TYPE, ApprovalReplyC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ErrorChoiceC2S.TYPE,   ErrorChoiceC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ZoneEditC2S.TYPE,      ZoneEditC2S.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SetHomeC2S.TYPE,       SetHomeC2S.CODEC);

        // S2C
        PayloadTypeRegistry.clientboundPlay().register(PlanViewS2C.TYPE,  PlanViewS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AskGateS2C.TYPE,   AskGateS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(StatusS2C.TYPE,    StatusS2C.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ZoneListS2C.TYPE,  ZoneListS2C.CODEC);
    }
}
