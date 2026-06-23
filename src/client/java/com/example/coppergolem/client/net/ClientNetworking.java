package com.example.coppergolem.client.net;

import com.example.coppergolem.net.Packets;
import com.example.coppergolem.net.Packets.AskGateS2C;
import com.example.coppergolem.net.Packets.InventoryS2C;
import com.example.coppergolem.net.Packets.PlanViewS2C;
import com.example.coppergolem.net.Packets.SlotLine;
import com.example.coppergolem.net.Packets.StatusS2C;
import com.example.coppergolem.net.Packets.StepLine;
import com.example.coppergolem.net.Packets.ZoneLine;
import com.example.coppergolem.net.Packets.ZoneListS2C;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Collections;
import java.util.List;

/**
 * Client-side networking: registers receivers for the four S2C payloads, stores
 * the latest state statically for the screens to read, and provides static
 * senders for the six C2S payloads via {@link ClientPlayNetworking#send}.
 *
 * <p>State fields are written on the client network thread inside the receiver
 * callbacks and read on the render thread; they are {@code volatile} and the
 * lists are replaced wholesale (immutable copies) so no further synchronisation
 * is needed for the read-mostly UI.</p>
 *
 * <p>All payload types verified in {@link Packets}; the Fabric API
 * {@code ClientPlayNetworking.registerGlobalReceiver(Type, PlayPayloadHandler)}
 * and {@code send(CustomPacketPayload)} are verified against
 * fabric-networking-api-v1 (client package).</p>
 */
public final class ClientNetworking {

    private ClientNetworking() {}

    // ── Latest received state (read by screens) ─────────────────────────────

    private static volatile List<StepLine> latestPlan = Collections.emptyList();
    private static volatile List<ZoneLine> latestZones = Collections.emptyList();
    private static volatile String latestStatus = "idle";
    private static volatile int activeKeys = 0;
    private static volatile int coolingKeys = 0;

    /** Non-null when the server is waiting on an approval gate; the item text. */
    private static volatile String pendingAskGate = null;

    private static volatile List<SlotLine> latestInventory = Collections.emptyList();

    public static List<StepLine> plan()        { return latestPlan; }
    public static List<ZoneLine> zones()       { return latestZones; }
    public static List<SlotLine> inventory()   { return latestInventory; }
    public static String status()              { return latestStatus; }
    public static int activeKeys()             { return activeKeys; }
    public static int coolingKeys()            { return coolingKeys; }
    public static String pendingAskGate()      { return pendingAskGate; }

    /** Clears the pending ask-gate (after the modal is answered/dismissed). */
    public static void clearAskGate()          { pendingAskGate = null; }

    // ── Registration ────────────────────────────────────────────────────────

    /** Register all S2C receivers. Call once from client init. */
    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(PlanViewS2C.TYPE,
                (payload, ctx) -> latestPlan = List.copyOf(payload.steps()));

        ClientPlayNetworking.registerGlobalReceiver(StatusS2C.TYPE,
                (payload, ctx) -> {
                    latestStatus = payload.status();
                    activeKeys = payload.activeKeys();
                    coolingKeys = payload.coolingKeys();
                });

        ClientPlayNetworking.registerGlobalReceiver(ZoneListS2C.TYPE,
                (payload, ctx) -> latestZones = List.copyOf(payload.zones()));

        ClientPlayNetworking.registerGlobalReceiver(AskGateS2C.TYPE,
                (payload, ctx) -> pendingAskGate = payload.itemDescription());

        ClientPlayNetworking.registerGlobalReceiver(InventoryS2C.TYPE,
                (payload, ctx) -> latestInventory = List.copyOf(payload.slots()));
    }

    // ── C2S senders ─────────────────────────────────────────────────────────

    public static void sendPrompt(String text, boolean preApprove) {
        ClientPlayNetworking.send(new Packets.PromptC2S(text, preApprove));
    }

    public static void sendStop() {
        ClientPlayNetworking.send(new Packets.StopC2S());
    }

    public static void sendApproval(boolean approve) {
        ClientPlayNetworking.send(new Packets.ApprovalReplyC2S(approve));
    }

    public static void sendErrorChoice(String choice, String instruction) {
        ClientPlayNetworking.send(new Packets.ErrorChoiceC2S(choice, instruction));
    }

    public static void sendZoneEdit(String op, String name,
                                    int minX, int minZ, int maxX, int maxZ) {
        ClientPlayNetworking.send(new Packets.ZoneEditC2S(op, name, minX, minZ, maxX, maxZ));
    }

    public static void sendHome(int x, int y, int z) {
        ClientPlayNetworking.send(new Packets.SetHomeC2S(x, y, z));
    }

    public static void sendGiveItem() {
        ClientPlayNetworking.send(new Packets.GiveItemC2S());
    }
}
