package com.example.coppergolem;

import com.example.coppergolem.agent.AgentPlanner;
import com.example.coppergolem.config.GolemConfig;
import com.example.coppergolem.entity.GolemController;
import com.example.coppergolem.entity.GolemLife;
import com.example.coppergolem.gemini.GroqClient;
import com.example.coppergolem.gemini.KeyPool;
import com.example.coppergolem.inventory.GolemInventory;
import com.example.coppergolem.net.Packets;
import com.example.coppergolem.net.ServerNetworking;
import com.example.coppergolem.zone.ZoneManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.golem.CopperGolem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.UUID;

public class CopperGolemMod implements ModInitializer {
    public static final String MOD_ID = "coppergolem";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** Built once at init from config; shared by every spawned controller. */
    private AgentPlanner planner;

    @Override
    public void onInitialize() {
        LOG.info("[coppergolem] server init");

        // Plan A foundation: reference the field so the persistent AttachmentType registers.
        if (GolemAttachments.GOLEM_DATA == null) {
            throw new IllegalStateException("golem attachment failed to register");
        }

        // ---- Config + AI stack -------------------------------------------------
        // FabricLoader.getInstance().getConfigDir() verified against fabric-loader.
        Path configFile = FabricLoader.getInstance().getConfigDir().resolve("golem.json");
        GolemConfig config = GolemConfig.loadOrCreate(configFile);

        if (config.geminiKeys() == null || config.geminiKeys().isEmpty()) {
            // KeyPool throws on empty keys; without keys the planner can't run.
            // Don't crash the server — log loudly and leave planner null (spawn still works).
            LOG.warn("[coppergolem] no API keys in {} — AgentPlanner disabled until keys are added.",
                    configFile);
            this.planner = null;
        } else {
            KeyPool pool = new KeyPool(config.geminiKeys(), System::currentTimeMillis);
            GroqClient groq = new GroqClient(pool, config.model(), HttpClient.newHttpClient());
            this.planner = new AgentPlanner(groq);
        }

        // ---- Networking + life hooks ------------------------------------------
        Packets.register();
        // ServerNetworking maps OWNER uuid -> controller (B10 receivers are owner-checked).
        ServerNetworking.register(owner -> GolemRegistry.INSTANCE.get(owner));
        // GolemLife keys by ENTITY uuid; hand it the registry's entity-index view.
        GolemLife.register(GolemRegistry.INSTANCE.byEntityView());

        // ---- Server tick: advance every controller ----------------------------
        // ServerTickEvents.END_SERVER_TICK.register(server -> ...) verified
        // (EndTick.onEndTick(MinecraftServer)).
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Reconcile owner index in case GolemLife respawned a golem last tick.
            GolemRegistry.INSTANCE.syncOwnerIndex();
            for (GolemController controller : GolemRegistry.INSTANCE.all()) {
                ServerLevel level = (ServerLevel) controller.golem().level();
                controller.tick(level);
            }
        });

        // ---- Commands ----------------------------------------------------------
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        LOG.info("[coppergolem] init complete (planner={})",
                planner != null ? "enabled" : "disabled");
    }

    // -------------------------------------------------------------------------
    // /golem spawn | /golem stop
    // -------------------------------------------------------------------------

    private void registerCommands(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher) {

        LiteralArgumentBuilder<CommandSourceStack> golem = Commands.literal("golem")
                .then(Commands.literal("spawn").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    return spawnGolem(player);
                }))
                .then(Commands.literal("stop").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    GolemController ctrl = GolemRegistry.INSTANCE.get(player.getUUID());
                    if (ctrl == null) {
                        player.sendSystemMessage(
                                net.minecraft.network.chat.Component.literal(
                                        "[golem] you have no active golem."));
                        return 0;
                    }
                    ctrl.stop();
                    player.sendSystemMessage(
                            net.minecraft.network.chat.Component.literal("[golem] stopped."));
                    return 1;
                }));

        dispatcher.register(golem);
    }

    /**
     * Spawns a vanilla copper golem at the player's position, builds a
     * {@link GolemController} bound to the player's UUID, sets 20 HP via
     * {@link GolemLife}, and registers it in {@link GolemRegistry}.
     */
    private int spawnGolem(ServerPlayer player) {
        ServerLevel level = (ServerLevel) player.level();
        UUID owner = player.getUUID();

        GolemController existing = GolemRegistry.INSTANCE.get(owner);
        if (existing != null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[golem] you already have a golem. Use /golem stop or wait."));
            return 0;
        }

        BlockPos pos = player.blockPosition();
        // spawn(ServerLevel, PostSpawnProcessor<T>, BlockPos, EntitySpawnReason, boolean, boolean)
        CopperGolem golem = EntityTypes.COPPER_GOLEM.spawn(
                level, entity -> {}, pos, EntitySpawnReason.COMMAND, false, false);
        if (golem == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[golem] failed to spawn copper golem."));
            return 0;
        }

        // Fresh inventory + zones; ApprovalGate placeholder lives inside the
        // controller (auto-approve, TODO B12). AgentPlanner is the shared stack.
        GolemInventory inventory = new GolemInventory();
        ZoneManager zones = new ZoneManager();

        GolemController controller =
                new GolemController(owner, golem, level, inventory, zones);
        // Home point = spawn position so a respawn lands near the owner.
        controller.setHomePoint(pos);
        // GolemController's constructor already called GolemLife.initHealth (20 HP).

        GolemRegistry.INSTANCE.register(controller);

        // TODO(B11/B12): the AgentPlanner (this.planner) is not yet handed to the
        // controller — GolemController.startFromPrompt is still a stub. Wire the
        // planner into the controller when PlanExecutor assignment lands.
        if (planner == null) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[golem] spawned (planner disabled — add API keys to config/golem.json)."));
        } else {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                    "[golem] spawned and bound to you."));
        }
        return 1;
    }
}
