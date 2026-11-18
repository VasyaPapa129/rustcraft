package dev.rustcraft;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class RustCraft implements ModInitializer {

    public static final String MOD_ID = "rustcraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final Random RND = new Random();
    public static RustConfig config = new RustConfig();

    private static final BuildManager BUILD = new BuildManager();
    private static final SurvivalManager SURVIVAL = new SurvivalManager();
    private static final List<Charge> CHARGES = new ArrayList<>();
    private static int tick;

    private record LootEntry(Item item, int min, int max) {}

    private static final List<LootEntry> LOOT = List.of(
            new LootEntry(Items.IRON_INGOT, 8, 24),
            new LootEntry(Items.GUNPOWDER, 6, 16),
            new LootEntry(Items.REDSTONE, 8, 20),
            new LootEntry(Items.DIAMOND, 1, 3),
            new LootEntry(Items.COOKED_BEEF, 8, 16),
            new LootEntry(Items.IRON_SWORD, 1, 1),
            new LootEntry(Items.BOW, 1, 1),
            new LootEntry(Items.ARROW, 16, 32));

    private static class Charge {
        final ServerLevel level;
        final Vec3 pos;
        int left;

        Charge(ServerLevel level, Vec3 pos, int left) {
            this.level = level;
            this.pos = pos;
            this.left = left;
        }
    }

    public static BuildManager build() {
        return BUILD;
    }

    @Override
    public void onInitialize() {
        config = RustConfig.load();
        ModBlocks.init();
        ModItems.init();

        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(ModItems.PLAN);
            entries.accept(ModItems.HAMMER);
            entries.accept(ModItems.C4);
        });

        PayloadTypeRegistry.playS2C().register(StatsPayload.TYPE, StatsPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTED.register(BUILD::load);
        ServerLifecycleEvents.SERVER_STARTED.register(SURVIVAL::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(BUILD::save);
        ServerLifecycleEvents.SERVER_STOPPING.register(SURVIVAL::save);
        ServerTickEvents.END_SERVER_TICK.register(RustCraft::onTick);

        // Блоки построек нельзя сломать руками (даже в креативе) — только молотком или C4
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) ->
                !ModBlocks.PIECE_BLOCKS.contains(state.getBlock()));

        // Шкаф: авторизация, запрет строительства в чужой зоне
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (level.isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return BUILD.onUseBlock(sp, hand, hit);
        });

        // Напиться из воды: Shift+ПКМ пустой рукой
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide || !(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            return SURVIVAL.onUseItem(sp, hand);
        });

        // Случайный спавн, без кроватей
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) SURVIVAL.reset(newPlayer);
            if (!alive && newPlayer.level().dimension() == Level.OVERWORLD) teleportRandom(newPlayer);
        });

        // Новичок: случайная точка и стартовый набор
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayer p = handler.player;
            SURVIVAL.send(p);
            if (BUILD.firstJoin(p.getUUID())) {
                if (p.level().dimension() == Level.OVERWORLD) teleportRandom(p);
                if (config.giveStarterKit) {
                    give(p, new ItemStack(ModItems.PLAN));
                    give(p, new ItemStack(ModItems.HAMMER));
                }
            }
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, env) -> registerCommands(dispatcher));
    }

    private static void registerCommands(CommandDispatcher<net.minecraft.commands.CommandSourceStack> d) {
        d.register(Commands.literal("rustcraft")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("kit").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    give(p, new ItemStack(ModItems.PLAN));
                    give(p, new ItemStack(ModItems.HAMMER));
                    return 1;
                }))
                .then(Commands.literal("c4").executes(ctx -> {
                    give(ctx.getSource().getPlayerOrException(), new ItemStack(ModItems.C4, 4));
                    return 1;
                }))
                .then(Commands.literal("airdrop").executes(ctx -> {
                    airdrop(ctx.getSource().getServer());
                    return 1;
                })));
    }

    private static void give(ServerPlayer p, ItemStack st) {
        if (!p.getInventory().add(st)) p.drop(st, false);
    }

    // ------------------------------------------------------------------ тик

    private static void onTick(MinecraftServer server) {
        tick++;
        if (tick % 5 == 0) BUILD.tickPreview(server);
        if (tick % 40 == 0) radiationTick(server);
        if (tick % 6000 == 0) {
            BUILD.save(server);
            SURVIVAL.save(server);
        }
        SURVIVAL.tick(server, tick);
        if (tick % (Math.max(1, config.upkeepIntervalMinutes) * 1200) == 0) BUILD.processUpkeep(server);
        int period = Math.max(1, config.airdropIntervalMinutes) * 1200;
        if (tick % period == 0) airdrop(server);

        Iterator<Charge> it = CHARGES.iterator();
        while (it.hasNext()) {
            Charge c = it.next();
            c.left--;
            if (c.left % 5 == 0)
                c.level.sendParticles(ParticleTypes.SMOKE, c.pos.x, c.pos.y + 0.3, c.pos.z, 3, 0.1, 0.1, 0.1, 0.01);
            if (c.left <= 0) {
                it.remove();
                c.level.explode(null, c.pos.x, c.pos.y, c.pos.z, config.c4Power, Level.ExplosionInteraction.TNT);
                BUILD.damageAround(c.level, c.pos, config.c4DamageRadius);
            }
        }
    }

    public static void addCharge(ServerLevel level, Vec3 pos) {
        CHARGES.add(new Charge(level, pos, Math.max(1, config.c4FuseTicks)));
    }

    // ------------------------------------------------------------------ спавн

    public static BlockPos randomSpot(ServerLevel level) {
        int r = config.spawnRadius;
        for (int i = 0; i < 30; i++) {
            int x = RND.nextInt(-r, r + 1), z = RND.nextInt(-r, r + 1);
            level.getChunk(x >> 4, z >> 4); // прогружаем чанк
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
            while (y > -60 && level.getBlockState(new BlockPos(x, y - 1, z)).isAir()) y--;
            BlockPos feet = new BlockPos(x, y, z);
            BlockPos below = feet.below();
            if (level.getBlockState(below).isAir()) continue;
            if (!level.getFluidState(below).isEmpty() || !level.getFluidState(feet).isEmpty()) continue;
            return feet;
        }
        return level.getSharedSpawnPos();
    }

    private static void teleportRandom(ServerPlayer p) {
        BlockPos s = randomSpot((ServerLevel) p.level());
        p.connection.teleport(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, p.getYRot(), p.getXRot());
    }

    // ------------------------------------------------------------------ аирдроп

    public static void airdrop(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos pos = randomSpot(level);
        level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            List<Integer> slots = new ArrayList<>();
            for (int i = 0; i < chest.getContainerSize(); i++) slots.add(i);
            Collections.shuffle(slots, RND);
            int idx = 0;
            for (LootEntry e : LOOT) {
                if (idx >= slots.size()) break;
                int n = e.min() + RND.nextInt(e.max() - e.min() + 1);
                chest.setItem(slots.get(idx++), new ItemStack(e.item(), n));
            }
        }
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("✈ Аирдроп! Координаты: X " + pos.getX() + " Z " + pos.getZ())
                        .withStyle(ChatFormatting.GOLD), false);
    }

    // ------------------------------------------------------------------ радиация

    private static void radiationTick(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension() != Level.OVERWORLD || p.isCreative() || p.isSpectator()) continue;
            if (p.getItemBySlot(EquipmentSlot.CHEST).is(Items.LEATHER_CHESTPLATE)) continue; // «хазмат»
            for (RustConfig.Zone z : config.radiation) {
                double dx = p.getX() - z.x, dz = p.getZ() - z.z;
                if (dx * dx + dz * dz <= (double) z.radius * z.radius) {
                    p.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
                    p.displayClientMessage(Component.literal("☢ Радиация! Нужна кожаная броня")
                            .withStyle(ChatFormatting.GREEN), true);
                    break;
                }
            }
        }
    }
}
