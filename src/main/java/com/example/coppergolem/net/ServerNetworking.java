package com.example.coppergolem.net;

import com.example.coppergolem.entity.GolemController;
import com.example.coppergolem.net.Packets.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Wires C2S packet receivers and provides static S2C senders.
 *
 * <p>Every C2S handler verifies that the sending player is the golem owner
 * ({@code player.getUUID().equals(controller.owner())}); packets from
 * non-owners are silently dropped.</p>
 */
public final class ServerNetworking {

    private ServerNetworking() {}

    // =========================================================================
    // Registration
    // =========================================================================

    /**
     * Registers global C2S receivers.  Call once, server-side, after
     * {@link Packets#register()}.
     *
     * @param controllerFor maps a golem UUID (the one the player owns) to its
     *                      {@link GolemController}.  Return {@code null} when
     *                      the player does not own a live golem.
     */
    public static void register(Function<UUID, GolemController> controllerFor) {

        // PromptC2S — start a job from a freeform prompt
        ServerPlayNetworking.registerGlobalReceiver(PromptC2S.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    ctx.server().execute(() -> {
                        GolemController ctrl = controllerFor.apply(player.getUUID());
                        if (ctrl == null || !ctrl.owner().equals(player.getUUID())) return;
                        ctrl.startFromPrompt(payload.text(), payload.preApprove());
                    });
                });

        // StopC2S — abandon the current job
        ServerPlayNetworking.registerGlobalReceiver(StopC2S.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    ctx.server().execute(() -> {
                        GolemController ctrl = controllerFor.apply(player.getUUID());
                        if (ctrl == null || !ctrl.owner().equals(player.getUUID())) return;
                        ctrl.stop();
                    });
                });

        // ApprovalReplyC2S — player answers an approval gate
        ServerPlayNetworking.registerGlobalReceiver(ApprovalReplyC2S.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    ctx.server().execute(() -> {
                        GolemController ctrl = controllerFor.apply(player.getUUID());
                        if (ctrl == null || !ctrl.owner().equals(player.getUUID())) return;
                        ctrl.receiveApproval(payload.approve());
                    });
                });

        // ErrorChoiceC2S — player picks an error-recovery option
        ServerPlayNetworking.registerGlobalReceiver(ErrorChoiceC2S.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    ctx.server().execute(() -> {
                        GolemController ctrl = controllerFor.apply(player.getUUID());
                        if (ctrl == null || !ctrl.owner().equals(player.getUUID())) return;
                        ctrl.receiveErrorChoice(payload.choice(), payload.instruction());
                    });
                });

        // ZoneEditC2S — add / remove / update a protection zone
        ServerPlayNetworking.registerGlobalReceiver(ZoneEditC2S.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    ctx.server().execute(() -> {
                        GolemController ctrl = controllerFor.apply(player.getUUID());
                        if (ctrl == null || !ctrl.owner().equals(player.getUUID())) return;
                        ctrl.handleZoneEdit(
                                payload.op(), payload.name(),
                                payload.minX(), payload.minZ(),
                                payload.maxX(), payload.maxZ());
                    });
                });

        // SetHomeC2S — player relocates the golem home point
        ServerPlayNetworking.registerGlobalReceiver(SetHomeC2S.TYPE,
                (payload, ctx) -> {
                    ServerPlayer player = ctx.player();
                    ctx.server().execute(() -> {
                        GolemController ctrl = controllerFor.apply(player.getUUID());
                        if (ctrl == null || !ctrl.owner().equals(player.getUUID())) return;
                        ctrl.setHomePoint(new BlockPos(payload.x(), payload.y(), payload.z()));
                    });
                });
    }

    // =========================================================================
    // S2C senders
    // =========================================================================

    /** Send the current plan step list to a player. */
    public static void sendPlanView(ServerPlayer player, List<StepLine> steps) {
        ServerPlayNetworking.send(player, new PlanViewS2C(steps));
    }

    /** Ask the player to approve an item acquisition. */
    public static void sendAskGate(ServerPlayer player, String itemDescription) {
        ServerPlayNetworking.send(player, new AskGateS2C(itemDescription));
    }

    /** Push a status update to the player. */
    public static void sendStatus(ServerPlayer player,
                                   String status, int activeKeys, int coolingKeys) {
        ServerPlayNetworking.send(player, new StatusS2C(status, activeKeys, coolingKeys));
    }

    /** Send the full zone list to the player. */
    public static void sendZoneList(ServerPlayer player, List<ZoneLine> zones) {
        ServerPlayNetworking.send(player, new ZoneListS2C(zones));
    }
}
