package dev.rustcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Жажда и температура тела. Голод — ванильный (показывается на худе рядом). */
public class SurvivalManager {

    private static class Stats {
        float thirst = 100f;
        float temp = 20f;
        boolean freezing;
        boolean latch;     // чтобы засчитать выпитое один раз
        int state;         // 0 норма, 1 холод, 2 жара, 3 хочется пить, 4 смертельная жажда
    }

    private final Map<UUID, Stats> stats = new HashMap<>();
    private final Map<UUID, Long> lastWater = new HashMap<>();

    // ------------------------------------------------------------------ хранение

    private Path file(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).resolve("rustcraft_players.json");
    }

    public void load(MinecraftServer srv) {
        stats.clear();
        lastWater.clear();
        Path f = file(srv);
        if (!Files.exists(f)) return;
        try {
            Map<String, float[]> d = new Gson().fromJson(Files.readString(f),
                    new TypeToken<Map<String, float[]>>() {}.getType());
            if (d == null) return;
            for (Map.Entry<String, float[]> e : d.entrySet()) {
                Stats s = new Stats();
                s.thirst = e.getValue()[0];
                s.temp = e.getValue()[1];
                stats.put(UUID.fromString(e.getKey()), s);
            }
        } catch (Exception e) {
            RustCraft.LOGGER.warn("Не удалось прочитать rustcraft_players.json: {}", e.getMessage());
        }
    }

    public void save(MinecraftServer srv) {
        Map<String, float[]> d = new HashMap<>();
        for (Map.Entry<UUID, Stats> e : stats.entrySet())
            d.put(e.getKey().toString(), new float[]{e.getValue().thirst, e.getValue().temp});
        try {
            Files.writeString(file(srv), new GsonBuilder().setPrettyPrinting().create().toJson(d));
        } catch (IOException e) {
            RustCraft.LOGGER.warn("Не удалось сохранить rustcraft_players.json: {}", e.getMessage());
        }
    }

    private Stats of(ServerPlayer p) {
        return stats.computeIfAbsent(p.getUUID(), k -> new Stats());
    }

    public void reset(ServerPlayer p) {
        Stats s = of(p);
        s.thirst = 60f;
        s.temp = 22f;
        s.freezing = false;
        s.state = 0;
        send(p, s);
    }

    public void send(ServerPlayer p) {
        send(p, of(p));
    }

    private void send(ServerPlayer p, Stats s) {
        ServerPlayNetworking.send(p, new StatsPayload(Math.round(s.thirst), Math.round(s.temp)));
    }

    // ------------------------------------------------------------------ тик

    public void tick(MinecraftServer server, int tick) {
        if (!RustCraft.config.survivalEnabled) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.isCreative() || p.isSpectator() || !p.isAlive()) continue;
            Stats s = of(p);

            // выпитые напитки
            if (p.isUsingItem() && p.getUseItemRemainingTicks() <= 1) {
                if (!s.latch) {
                    s.latch = true;
                    float add = drinkValue(p.getUseItem());
                    if (add > 0f) s.thirst = Math.min(100f, s.thirst + add);
                }
            } else if (!p.isUsingItem()) {
                s.latch = false;
            }

            // холод: замерзание (ванильный урон от холода; кожаная броня защищает)
            if (s.freezing) p.setTicksFrozen(Math.min(p.getTicksFrozen() + 3, 160));

            if ((tick + p.getId()) % 20 == 0) second(p, s);
        }
    }

    private static float drinkValue(ItemStack u) {
        if (u.is(Items.POTION)) return 25f;
        if (u.is(Items.MILK_BUCKET)) return 30f;
        if (u.is(Items.HONEY_BOTTLE)) return 10f;
        if (u.is(Items.MELON_SLICE)) return 8f;
        return 0f;
    }

    private void second(ServerPlayer p, Stats s) {
        ServerLevel level = (ServerLevel) p.level();
        BlockPos pos = p.blockPosition();

        s.temp += (targetTemp(p, level, pos) - s.temp) * 0.12f;

        float mult = 1f;
        if (p.isSprinting()) mult *= 2f;
        if (s.temp > 40f) mult *= 3f;
        else if (s.temp > 32f) mult *= 2f;
        s.thirst = Math.max(0f, s.thirst - mult / Math.max(1, RustCraft.config.thirstDrainSeconds));

        if (s.thirst <= 0f) p.addEffect(new MobEffectInstance(MobEffects.WITHER, 60, 0));
        else if (s.thirst < 15f) p.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 60, 0));

        s.freezing = s.temp < 5f;

        int state = s.thirst <= 0f ? 4 : s.thirst < 15f ? 3 : s.temp < 5f ? 1 : s.temp > 38f ? 2 : 0;
        if (state != s.state) {
            s.state = state;
            switch (state) {
                case 1 -> warn(p, "Вам холодно! Найдите костёр или оденьтесь теплее (кожаная броня)", ChatFormatting.AQUA);
                case 2 -> warn(p, "Жарко! Вода расходуется быстрее", ChatFormatting.GOLD);
                case 3 -> warn(p, "Хочется пить (Shift+ПКМ по воде пустой рукой)", ChatFormatting.BLUE);
                case 4 -> warn(p, "Вы умираете от жажды!", ChatFormatting.RED);
                default -> {}
            }
        }
        send(p, s);
    }

    private static void warn(ServerPlayer p, String text, ChatFormatting color) {
        p.sendSystemMessage(Component.literal(text).withStyle(color));
    }

    // ------------------------------------------------------------------ температура

    private float targetTemp(ServerPlayer p, ServerLevel level, BlockPos pos) {
        float env;
        if (level.dimension() == Level.NETHER) {
            env = 42f;
        } else if (level.dimension() == Level.END) {
            env = 8f;
        } else {
            float base = level.getBiome(pos).value().getBaseTemperature();
            env = Mth.clamp(base * 30f - 5f, -10f, 45f);
            long t = level.getDayTime() % 24000L;
            if (t >= 13000L && t <= 23000L) env -= base > 1.5f ? 15f : 8f;   // ночью холоднее, в пустыне — сильно
            if (level.isRainingAt(pos)) env -= 4f;
            if (pos.getY() > 90) env -= (pos.getY() - 90) / 8f;
            if (!level.canSeeSky(pos)) env = pos.getY() < 45 ? 12f : env * 0.6f + 6f;
        }
        if (p.isInWaterOrRain()) env -= 6f;

        float target = env + (nearHeat(level, pos) ? 18f : 0f);
        int ins = insulation(p);
        if (target < 18f) target += ins;
        else target -= ins * 0.4f;
        if (p.isSprinting()) target += 3f;
        return target;
    }

    private static boolean nearHeat(ServerLevel level, BlockPos c) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -3; dx <= 3; dx++)
            for (int dy = -1; dy <= 2; dy++)
                for (int dz = -3; dz <= 3; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    BlockState st = level.getBlockState(m);
                    if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE) || st.is(Blocks.LAVA)) return true;
                    if (st.getBlock() instanceof CampfireBlock && st.getValue(CampfireBlock.LIT)) return true;
                    if ((st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE) || st.is(Blocks.SMOKER))
                            && st.getValue(BlockStateProperties.LIT)) return true;
                }
        return false;
    }

    private static int insulation(ServerPlayer p) {
        int n = 0;
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot s : slots) {
            ItemStack it = p.getItemBySlot(s);
            if (it.isEmpty()) continue;
            boolean leather = it.is(Items.LEATHER_HELMET) || it.is(Items.LEATHER_CHESTPLATE)
                    || it.is(Items.LEATHER_LEGGINGS) || it.is(Items.LEATHER_BOOTS);
            n += leather ? 4 : 2;
        }
        return n;
    }

    // ------------------------------------------------------------------ питьё из воды

    /** Shift+ПКМ пустой рукой по воде — напиться. */
    public InteractionResult onUseItem(ServerPlayer p, InteractionHand hand) {
        if (!RustCraft.config.survivalEnabled || hand != InteractionHand.MAIN_HAND
                || !p.getMainHandItem().isEmpty() || !p.isShiftKeyDown()) return InteractionResult.PASS;
        ServerLevel level = (ServerLevel) p.level();
        long now = level.getGameTime();
        Long last = lastWater.get(p.getUUID());
        if (last != null && now - last < 10) return InteractionResult.PASS;

        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(4.5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, p));
        if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;
        if (!level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return InteractionResult.PASS;

        Stats s = of(p);
        s.thirst = Math.min(100f, s.thirst + 8f);
        lastWater.put(p.getUUID(), now);
        level.playSound(null, p.blockPosition(), SoundEvents.GENERIC_DRINK, SoundSource.PLAYERS, 0.8f, 1.0f);
        send(p, s);
        return InteractionResult.SUCCESS;
    }
}
