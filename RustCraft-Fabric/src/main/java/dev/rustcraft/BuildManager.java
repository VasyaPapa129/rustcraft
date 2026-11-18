package dev.rustcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Система строительства как в Rust.
 *
 * Сетка: шаг 4 блока. Клетка (cx,cz) занимает x=4cx..4cx+4, z=4cz..4cz+4 (границы общие с соседями),
 * внутри остаётся проход 3x3. Этаж: плита (1 блок) + 3 блока высоты стен, т.е. шаг по Y = 4.
 */
public class BuildManager {

    public record BlockKey(String w, long p) {}

    public static class Placement {
        public boolean valid;
        public String reason;
        public Piece.Type type;
        public int cx, cz, y;
        public char axis = 'X';
        public Direction dir = Direction.NORTH;
        public double[] box;

        static Placement bad(String reason) {
            Placement p = new Placement();
            p.reason = reason;
            return p;
        }
    }

    private static class Dto {
        String id, world, type, axis, dir, owner, tier;
        List<String> auth = new ArrayList<>();
        int integrity;
        int[] upkeep;
        int cx, cz, y, hp;
        long t;
    }

    private static class Data {
        List<Dto> pieces = new ArrayList<>();
        List<String> seen = new ArrayList<>();
    }

    private MinecraftServer server;
    private final Map<UUID, Piece> pieces = new HashMap<>();
    private final Map<String, Piece> slots = new HashMap<>();
    private final Map<String, Piece> foundations = new HashMap<>();
    private final Map<String, List<Piece>> cellSlabs = new HashMap<>();
    private final Map<BlockKey, List<Piece>> blockIndex = new HashMap<>();
    private final Map<UUID, Piece.Type> selected = new HashMap<>();
    private final Set<UUID> seen = new HashSet<>();
    private final List<Piece> cupboards = new ArrayList<>();

    // ------------------------------------------------------------------ вспомогательное

    private static String dim(Level l) {
        return l.dimension().location().toString();
    }

    private ServerLevel levelOf(String id) {
        if (server == null) return null;
        for (ServerLevel l : server.getAllLevels()) if (dim(l).equals(id)) return l;
        return null;
    }

    private static boolean isAdmin(ServerPlayer p) {
        return p.createCommandSourceStack().hasPermission(2);
    }

    private static void msg(ServerPlayer p, String text, ChatFormatting color) {
        p.displayClientMessage(Component.literal(text).withStyle(color), true);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1);
    }

    public boolean firstJoin(UUID id) {
        return seen.add(id);
    }

    // ------------------------------------------------------------------ выбор элемента

    public Piece.Type selected(ServerPlayer p) {
        return selected.getOrDefault(p.getUUID(), Piece.Type.FOUNDATION);
    }

    public void cycle(ServerPlayer p) {
        Piece.Type[] v = Piece.Type.values();
        Piece.Type n = v[(selected(p).ordinal() + 1) % v.length];
        selected.put(p.getUUID(), n);
        msg(p, "Выбрано: " + label(n), ChatFormatting.AQUA);
    }

    public static String label(Piece.Type t) {
        return switch (t) {
            case FOUNDATION -> "Фундамент";
            case FLOOR -> "Перекрытие";
            case WALL -> "Стена";
            case DOORWAY -> "Дверной проём";
            case WINDOW -> "Оконный проём";
            case STAIRS -> "Лестница";
            case CUPBOARD -> "Шкаф (приват)";
        };
    }

    public static String tierName(Piece.Tier t) {
        return switch (t) {
            case WOOD -> "дерево";
            case STONE -> "камень";
            case METAL -> "металл";
        };
    }

    public static String resLabel(Piece.Tier t) {
        return switch (t) {
            case WOOD -> "досок";
            case STONE -> "булыжника";
            case METAL -> "железных слитков";
        };
    }

    // ------------------------------------------------------------------ ключи и геометрия

    private static String fk(String w, int cx, int cz) {
        return w + "|" + cx + "|" + cz;
    }

    private static String slabKey(String w, int cx, int cz, int y) {
        return "S|" + w + "|" + cx + "|" + cz + "|" + y;
    }

    private static String wallKey(String w, char a, int cx, int cz, int y) {
        return "W|" + w + "|" + a + "|" + cx + "|" + cz + "|" + y;
    }

    private static String stairKey(String w, int cx, int cz, int y) {
        return "T|" + w + "|" + cx + "|" + cz + "|" + y;
    }

    private static String slotKey(Piece p) {
        return switch (p.type) {
            case FOUNDATION, FLOOR -> slabKey(p.world, p.cx, p.cz, p.y);
            case STAIRS -> stairKey(p.world, p.cx, p.cz, p.y);
            case CUPBOARD -> "C|" + p.world + "|" + p.cx + "|" + p.y + "|" + p.cz;
            default -> wallKey(p.world, p.axis, p.cx, p.cz, p.y);
        };
    }

    private static boolean isSlab(Piece p) {
        return p.type == Piece.Type.FOUNDATION || p.type == Piece.Type.FLOOR;
    }

    /** Все твёрдые блоки элемента (проёмы не входят). */
    public static List<int[]> blocks(Piece p) {
        List<int[]> l = new ArrayList<>();
        int x0 = 4 * p.cx, z0 = 4 * p.cz;
        switch (p.type) {
            case FOUNDATION, FLOOR -> {
                for (int i = 0; i < 5; i++)
                    for (int j = 0; j < 5; j++)
                        l.add(new int[]{x0 + i, p.y, z0 + j});
            }
            case CUPBOARD -> l.add(new int[]{p.cx, p.y, p.cz});
            case STAIRS -> {
                for (int t = 0; t < 3; t++) {
                    for (int s = 1; s <= 3; s++) {
                        int x, z;
                        switch (p.dir) {
                            case EAST -> { x = x0 + 1 + t; z = z0 + s; }
                            case WEST -> { x = x0 + 3 - t; z = z0 + s; }
                            case SOUTH -> { x = x0 + s; z = z0 + 1 + t; }
                            default -> { x = x0 + s; z = z0 + 3 - t; }
                        }
                        l.add(new int[]{x, p.y + 1 + t, z});
                    }
                }
            }
            default -> {
                for (int a = 0; a < 5; a++) {
                    for (int h = 1; h <= 3; h++) {
                        if (p.type == Piece.Type.DOORWAY && a == 2 && h <= 2) continue;
                        if (p.type == Piece.Type.WINDOW && a == 2 && h == 2) continue;
                        l.add(p.axis == 'X'
                                ? new int[]{x0 + a, p.y + h, z0}
                                : new int[]{x0, p.y + h, z0 + a});
                    }
                }
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ хранилище

    private void clearAll() {
        pieces.clear();
        cupboards.clear();
        slots.clear();
        foundations.clear();
        cellSlabs.clear();
        blockIndex.clear();
        selected.clear();
        seen.clear();
    }

    private void addPiece(Piece p) {
        pieces.put(p.id, p);
        if (p.type == Piece.Type.CUPBOARD) cupboards.add(p);
        slots.put(slotKey(p), p);
        if (p.type == Piece.Type.FOUNDATION) foundations.put(fk(p.world, p.cx, p.cz), p);
        if (isSlab(p)) cellSlabs.computeIfAbsent(fk(p.world, p.cx, p.cz), k -> new ArrayList<>()).add(p);
        for (int[] c : blocks(p))
            blockIndex.computeIfAbsent(new BlockKey(p.world, new BlockPos(c[0], c[1], c[2]).asLong()),
                    k -> new ArrayList<>(2)).add(p);
    }

    public boolean isClaimed(Level level, BlockPos pos) {
        return blockIndex.containsKey(new BlockKey(dim(level), pos.asLong()));
    }

    private List<Piece> piecesAt(Level level, BlockPos pos) {
        List<Piece> l = blockIndex.get(new BlockKey(dim(level), pos.asLong()));
        return l == null ? List.of() : l;
    }

    /** Выбор элемента по блоку (общие блоки: предпочитаем стены/лестницы, затем плиту своей клетки). */
    private Piece pick(Level level, BlockPos pos) {
        List<Piece> l = piecesAt(level, pos);
        if (l.isEmpty()) return null;
        for (Piece p : l) if (!isSlab(p)) return p;
        int cx = Math.floorDiv(pos.getX(), 4), cz = Math.floorDiv(pos.getZ(), 4);
        for (Piece p : l) if (p.cx == cx && p.cz == cz) return p;
        return l.get(0);
    }

    // ------------------------------------------------------------------ содержание и распад

    private static Piece.Tier depositTier(ItemStack held) {
        for (Piece.Tier t : Piece.Tier.values()) if (matches(held, t)) return t;
        return null;
    }

    /** Разбивает элементы по шкафам (ближайший из накрывающих). Элементы без шкафа попадают в unprotected. */
    private void assign(Map<Piece, List<Piece>> assigned, List<Piece> unprotected) {
        for (Piece p : pieces.values()) {
            if (p.type == Piece.Type.CUPBOARD) continue;
            BlockPos r = rep(p);
            List<Piece> cov = covering(p.world, r.getX(), r.getY(), r.getZ());
            if (cov.isEmpty()) {
                unprotected.add(p);
                continue;
            }
            Piece best = null;
            long bestD = Long.MAX_VALUE;
            for (Piece tc : cov) {
                long dx = tc.cx - r.getX(), dz = tc.cz - r.getZ();
                long d = dx * dx + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = tc;
                }
            }
            assigned.computeIfAbsent(best, k -> new ArrayList<>()).add(p);
        }
    }

    private void recomputeLoads() {
        for (Piece tc : cupboards) Arrays.fill(tc.load, 0);
        Map<Piece, List<Piece>> a = new HashMap<>();
        assign(a, new ArrayList<>());
        for (Map.Entry<Piece, List<Piece>> e : a.entrySet())
            for (Piece p : e.getValue()) e.getKey().load[p.tier.ordinal()]++;
    }

    private String upkeepHours(Piece tc) {
        double best = Double.MAX_VALUE;
        boolean any = false;
        for (int t = 0; t < 3; t++) {
            int need = tc.load[t] * RustCraft.config.upkeepPerPiece;
            if (need <= 0) continue;
            any = true;
            best = Math.min(best, (double) tc.upkeep[t] / need * RustCraft.config.upkeepIntervalMinutes / 60.0);
        }
        if (!any) return "расходов нет";
        return "хватит на ~" + (int) Math.floor(best) + " ч";
    }

    /** Раз в интервал: шкаф списывает материалы (и чинит постройки), остальные постройки разрушаются. */
    public void processUpkeep(MinecraftServer srv) {
        RustConfig c = RustCraft.config;
        Map<Piece, List<Piece>> assigned = new HashMap<>();
        List<Piece> unprotected = new ArrayList<>();
        assign(assigned, unprotected);

        Set<UUID> warn = new HashSet<>();
        List<Piece> toDestroy = new ArrayList<>();

        for (Map.Entry<Piece, List<Piece>> e : assigned.entrySet()) {
            Piece tc = e.getKey();
            int[] cnt = new int[3];
            for (Piece p : e.getValue()) cnt[p.tier.ordinal()]++;
            boolean[] paid = new boolean[3];
            for (int t = 0; t < 3; t++) {
                int need = cnt[t] * c.upkeepPerPiece;
                if (need == 0) {
                    paid[t] = true;
                } else if (tc.upkeep[t] >= need) {
                    tc.upkeep[t] -= need;
                    paid[t] = true;
                }
            }
            for (Piece p : e.getValue()) {
                int t = p.tier.ordinal();
                if (paid[t]) {
                    p.integrity = Math.min(100, p.integrity + c.repairPerInterval);
                } else {
                    p.integrity -= c.decayPerInterval[t];
                    warn.add(p.owner);
                    if (p.integrity <= 0) toDestroy.add(p);
                }
            }
        }
        for (Piece p : unprotected) {
            p.integrity -= c.decayPerInterval[p.tier.ordinal()];
            warn.add(p.owner);
            if (p.integrity <= 0) toDestroy.add(p);
        }
        for (Piece p : toDestroy) destroy(p);
        recomputeLoads();

        for (UUID id : warn) {
            ServerPlayer sp = srv.getPlayerList().getPlayer(id);
            if (sp != null)
                sp.sendSystemMessage(Component.literal(
                        "⚠ Часть ваших построек разрушается: нет шкафа или в нём не хватает материалов (осмотрите молотком)")
                        .withStyle(ChatFormatting.RED));
        }
    }

    /** С молотком в руке показываем состояние элемента или шкафа. */
    private void hammerInfo(ServerPlayer p) {
        ServerLevel level = (ServerLevel) p.level();
        Vec3 eye = p.getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(6.0)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        if (hit.getType() != HitResult.Type.BLOCK) return;
        Piece pc = pick(level, hit.getBlockPos());
        if (pc == null) return;
        if (pc.type == Piece.Type.CUPBOARD) {
            msg(p, "Шкаф: авториз. " + pc.auth.size() + " | запас: дерево " + pc.upkeep[0] + ", камень "
                    + pc.upkeep[1] + ", металл " + pc.upkeep[2] + " | " + upkeepHours(pc), ChatFormatting.AQUA);
        } else {
            BlockPos r = rep(pc);
            boolean covered = !covering(pc.world, r.getX(), r.getY(), r.getZ()).isEmpty();
            msg(p, label(pc.type) + " (" + tierName(pc.tier) + ") — целостность " + pc.integrity + "%"
                    + (covered ? " — под защитой шкафа" : " — БЕЗ ШКАФА, разрушается"),
                    covered ? ChatFormatting.GREEN : ChatFormatting.RED);
        }
    }

    // ------------------------------------------------------------------ шкаф (приват)

    private List<Piece> covering(String world, int x, int y, int z) {
        int r = RustCraft.config.cupboardRadius;
        List<Piece> res = new ArrayList<>();
        for (Piece tc : cupboards) {
            if (!tc.world.equals(world)) continue;
            if (Math.abs(tc.cx - x) <= r && Math.abs(tc.cz - z) <= r && Math.abs(tc.y - y) <= r) res.add(tc);
        }
        return res;
    }

    private static BlockPos rep(Piece p) {
        return switch (p.type) {
            case CUPBOARD -> new BlockPos(p.cx, p.y, p.cz);
            case WALL, DOORWAY, WINDOW -> p.axis == 'X'
                    ? new BlockPos(4 * p.cx + 2, p.y + 1, 4 * p.cz)
                    : new BlockPos(4 * p.cx, p.y + 1, 4 * p.cz + 2);
            default -> new BlockPos(4 * p.cx + 2, p.y + 1, 4 * p.cz + 2);
        };
    }

    /** Можно ли игроку строить/улучшать/сносить этот элемент. Если есть шкаф — нужна авторизация во всех шкафах зоны, иначе — быть владельцем. */
    public boolean canTouch(ServerPlayer p, Piece x) {
        if (isAdmin(p)) return true;
        BlockPos r = rep(x);
        List<Piece> cov = covering(x.world, r.getX(), r.getY(), r.getZ());
        if (cov.isEmpty()) return x.owner.equals(p.getUUID());
        for (Piece tc : cov) if (!tc.auth.contains(p.getUUID())) return false;
        return true;
    }

    /** Можно ли ставить что-либо в этой точке (нет чужих шкафов, в зону которых мы попадаем). */
    public boolean canBuildAt(ServerPlayer p, ServerLevel level, BlockPos pos) {
        if (isAdmin(p)) return true;
        for (Piece tc : covering(dim(level), pos.getX(), pos.getY(), pos.getZ()))
            if (!tc.auth.contains(p.getUUID())) return false;
        return true;
    }

    private Piece tcAt(Level level, BlockPos pos) {
        for (Piece p : piecesAt(level, pos)) if (p.type == Piece.Type.CUPBOARD) return p;
        return null;
    }

    private Placement cupboard(ServerPlayer pl, ServerLevel level, BlockPos hb, Direction face) {
        if (face != Direction.UP) return Placement.bad("шкаф ставится на пол: целься в верх плиты");
        Piece slab = null;
        for (Piece s : piecesAt(level, hb)) if (isSlab(s)) { slab = s; break; }
        if (slab == null) return Placement.bad("шкаф ставится на фундамент или перекрытие");
        if (!canTouch(pl, slab)) return Placement.bad("чужая постройка (нужна авторизация в шкафу)");
        BlockPos pos = hb.above();
        if (isClaimed(level, pos)) return Placement.bad("место занято");
        BlockState st = level.getBlockState(pos);
        if (!(st.isAir() || st.canBeReplaced())) return Placement.bad("мешают блоки");
        Placement res = new Placement();
        res.type = Piece.Type.CUPBOARD;
        res.cx = pos.getX();
        res.cz = pos.getZ();
        res.y = pos.getY();
        res.box = new double[]{pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1};
        res.valid = true;
        return res;
    }

    /** ПКМ по блоку: авторизация в шкафу и запрет установки блоков в чужой зоне. */
    public InteractionResult onUseBlock(ServerPlayer p, InteractionHand hand, BlockHitResult hit) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        ServerLevel level = (ServerLevel) p.level();
        BlockPos pos = hit.getBlockPos();
        ItemStack held = p.getMainHandItem();
        if (held.is(ModItems.PLAN) || held.is(ModItems.HAMMER) || held.is(ModItems.C4)) return InteractionResult.PASS;

        if (level.getBlockState(pos).is(ModBlocks.CUPBOARD)) {
            Piece tc = tcAt(level, pos);
            if (tc != null) {
                UUID me = p.getUUID();
                if (p.isShiftKeyDown() && held.isEmpty()) {
                    if (!tc.auth.contains(me) && !isAdmin(p)) {
                        msg(p, "Вы не авторизованы в этом шкафу", ChatFormatting.RED);
                    } else {
                        tc.auth.clear();
                        msg(p, "Список авторизации очищен", ChatFormatting.YELLOW);
                    }
                    return InteractionResult.SUCCESS;
                }
                Piece.Tier dep = depositTier(held);
                if (dep != null && !p.isShiftKeyDown()) {
                    if (!tc.auth.contains(me) && !isAdmin(p)) {
                        msg(p, "Вы не авторизованы в этом шкафу", ChatFormatting.RED);
                        return InteractionResult.SUCCESS;
                    }
                    int n = held.getCount();
                    tc.upkeep[dep.ordinal()] += n;
                    held.shrink(n);
                    msg(p, "В шкаф внесено: " + n + " " + resLabel(dep) + " — " + upkeepHours(tc), ChatFormatting.GREEN);
                    return InteractionResult.SUCCESS;
                }
                if (!p.isShiftKeyDown()) {
                    if (tc.auth.remove(me)) {
                        msg(p, "Вы больше не авторизованы в шкафу", ChatFormatting.YELLOW);
                    } else {
                        tc.auth.add(me);
                        msg(p, "Вы авторизованы в шкафу (" + tc.auth.size() + " чел.)", ChatFormatting.GREEN);
                    }
                    return InteractionResult.SUCCESS;
                }
            }
        }

        if (held.getItem() instanceof BlockItem && !isAdmin(p)
                && !canBuildAt(p, level, pos.relative(hit.getDirection()))) {
            msg(p, "Здесь действует чужой шкаф: строить нельзя", ChatFormatting.RED);
            return InteractionResult.FAIL;
        }
        return InteractionResult.PASS;
    }

    // ------------------------------------------------------------------ расчёт размещения

    public Placement compute(ServerPlayer pl, Piece.Type type, boolean strict) {
        ServerLevel level = (ServerLevel) pl.level();
        Vec3 eye = pl.getEyePosition();
        Vec3 end = eye.add(pl.getLookAngle().scale(8.0));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, pl));
        if (hit.getType() != HitResult.Type.BLOCK) return Placement.bad("наведите на блок");
        BlockPos hb = hit.getBlockPos();
        Vec3 hp = hit.getLocation();
        Direction face = hit.getDirection();
        boolean admin = isAdmin(pl);
        return switch (type) {
            case FOUNDATION -> foundation(pl, level, hb, strict, admin);
            case CUPBOARD -> cupboard(pl, level, hb, face);
            case FLOOR, STAIRS -> upper(pl, level, hb, hp, type, admin);
            default -> wall(pl, level, hp, type, admin);
        };
    }

    private Placement foundation(ServerPlayer pl, ServerLevel level, BlockPos hb, boolean strict, boolean admin) {
        BlockState hs = level.getBlockState(hb);
        if (isClaimed(level, hb) || hs.is(BlockTags.LEAVES) || hs.is(BlockTags.LOGS))
            return Placement.bad("целься в землю");
        String wid = dim(level);
        UUID me = pl.getUUID();
        int cx = Math.floorDiv(hb.getX(), 4), cz = Math.floorDiv(hb.getZ(), 4);
        if (foundations.containsKey(fk(wid, cx, cz))) return Placement.bad("здесь уже есть фундамент");

        Integer snap = null;
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : n4) {
            Piece n = foundations.get(fk(wid, cx + d[0], cz + d[1]));
            if (n != null) {
                if (snap != null && snap != n.y) return Placement.bad("соседние фундаменты на разной высоте");
                snap = n.y;
            }
        }
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                Piece n = foundations.get(fk(wid, cx + dx, cz + dz));
                if (n != null && !canTouch(pl, n)) return Placement.bad("рядом чужая постройка");
            }

        int y = snap != null ? snap : hb.getY();
        if (Math.abs(hb.getY() - y) > 6) return Placement.bad("слишком неровная местность");
        if (!canBuildAt(pl, level, new BlockPos(4 * cx + 2, y + 1, 4 * cz + 2)))
            return Placement.bad("здесь действует чужой шкаф");
        if (!level.getFluidState(new BlockPos(4 * cx + 2, y + 1, 4 * cz + 2)).isEmpty())
            return Placement.bad("нельзя строить в воде");

        if (strict) {
            for (int i = 0; i < 5; i++)
                for (int j = 0; j < 5; j++)
                    for (int yy = y; yy <= y + 4; yy++) {
                        BlockPos bp = new BlockPos(4 * cx + i, yy, 4 * cz + j);
                        if (!level.getBlockState(bp).isAir() && !isClaimed(level, bp) && level.getBlockEntity(bp) != null)
                            return Placement.bad("мешает контейнер");
                    }
        }

        Placement res = new Placement();
        res.type = Piece.Type.FOUNDATION;
        res.cx = cx;
        res.cz = cz;
        res.y = y;
        res.box = new double[]{4 * cx, y, 4 * cz, 4 * cx + 5, y + 1, 4 * cz + 5};
        res.valid = true;
        return res;
    }

    private Placement wall(ServerPlayer pl, ServerLevel level, Vec3 hp, Piece.Type type, boolean admin) {
        String wid = dim(level);
        UUID me = pl.getUUID();
        double px = hp.x, py = hp.y, pz = hp.z;
        double ux = (px - 0.5) / 4.0, uz = (pz - 0.5) / 4.0;

        char axis;
        int cx, cz;
        if (Math.abs(ux - Math.rint(ux)) <= Math.abs(uz - Math.rint(uz))) {
            axis = 'Z';
            cx = (int) Math.rint(ux);
            cz = Math.floorDiv((int) Math.floor(pz), 4);
        } else {
            axis = 'X';
            cx = Math.floorDiv((int) Math.floor(px), 4);
            cz = (int) Math.rint(uz);
        }
        int[][] adj = axis == 'X' ? new int[][]{{cx, cz - 1}, {cx, cz}} : new int[][]{{cx - 1, cz}, {cx, cz}};

        Integer lvl = null;
        for (int[] a : adj)
            for (Piece s : cellSlabs.getOrDefault(fk(wid, a[0], a[1]), List.of()))
                if (s.y <= py - 0.01 && (lvl == null || s.y > lvl)) lvl = s.y;
        if (lvl == null) return Placement.bad("нужен фундамент или перекрытие рядом");
        int y = lvl;

        for (int[] a : adj) {
            Piece s = slots.get(slabKey(wid, a[0], a[1], y));
            if (s != null && !canTouch(pl, s)) return Placement.bad("чужая постройка (нужна авторизация в шкафу)");
        }
        if (slots.containsKey(wallKey(wid, axis, cx, cz, y))) return Placement.bad("здесь уже есть стена");

        Placement res = new Placement();
        res.type = type;
        res.axis = axis;
        res.cx = cx;
        res.cz = cz;
        res.y = y;
        res.box = axis == 'X'
                ? new double[]{4 * cx, y + 1, 4 * cz, 4 * cx + 5, y + 4, 4 * cz + 1}
                : new double[]{4 * cx, y + 1, 4 * cz, 4 * cx + 1, y + 4, 4 * cz + 5};

        String err = obstruction(level, tmp(res, wid, me), pl);
        if (err != null) return Placement.bad(err);
        res.valid = true;
        return res;
    }

    private Placement upper(ServerPlayer pl, ServerLevel level, BlockPos hb, Vec3 hp, Piece.Type type, boolean admin) {
        String wid = dim(level);
        UUID me = pl.getUUID();
        int cx = Math.floorDiv(hb.getX(), 4), cz = Math.floorDiv(hb.getZ(), 4);

        Integer lvl = null;
        for (Piece s : cellSlabs.getOrDefault(fk(wid, cx, cz), List.of()))
            if (s.y <= hp.y - 0.01 && (lvl == null || s.y > lvl)) lvl = s.y;
        if (lvl == null) return Placement.bad("нужен фундамент или перекрытие внизу");
        int base = lvl;

        Piece below = slots.get(slabKey(wid, cx, cz, base));
        if (below != null && !canTouch(pl, below)) return Placement.bad("чужая постройка (нужна авторизация в шкафу)");
        if (slots.containsKey(stairKey(wid, cx, cz, base)))
            return Placement.bad(type == Piece.Type.FLOOR ? "здесь лестница" : "здесь уже лестница");

        Placement res = new Placement();
        res.type = type;
        res.cx = cx;
        res.cz = cz;
        if (type == Piece.Type.FLOOR) {
            int ny = base + 4;
            if (slots.containsKey(slabKey(wid, cx, cz, ny))) return Placement.bad("перекрытие уже есть");
            res.y = ny;
            res.box = new double[]{4 * cx, ny, 4 * cz, 4 * cx + 5, ny + 1, 4 * cz + 5};
        } else {
            if (slots.containsKey(slabKey(wid, cx, cz, base + 4))) return Placement.bad("сверху уже перекрытие");
            res.y = base;
            res.dir = pl.getDirection();
            res.box = new double[]{4 * cx + 1, base + 1, 4 * cz + 1, 4 * cx + 4, base + 5, 4 * cz + 4};
        }

        String err = obstruction(level, tmp(res, wid, me), pl);
        if (err != null) return Placement.bad(err);
        res.valid = true;
        return res;
    }

    private Piece tmp(Placement r, String wid, UUID me) {
        return new Piece(UUID.randomUUID(), wid, r.type, r.cx, r.cz, r.y, r.axis, r.dir, me,
                Piece.Tier.WOOD, 1, 0L);
    }

    private String obstruction(ServerLevel level, Piece tmp, ServerPlayer pl) {
        for (int[] c : blocks(tmp)) {
            BlockPos pos = new BlockPos(c[0], c[1], c[2]);
            List<Piece> owners = blockIndex.get(new BlockKey(tmp.world, pos.asLong()));
            if (owners != null) {
                for (Piece o : owners)
                    if (!canTouch(pl, o)) return "занято чужой постройкой";
                continue;
            }
            BlockState st = level.getBlockState(pos);
            if (!(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty())) return "мешают блоки";
        }
        return null;
    }

    // ------------------------------------------------------------------ стоимость

    private int hpOf(Piece.Tier t) {
        return RustCraft.config.tierHp[t.ordinal()];
    }

    public int cost(Piece.Type type, Piece.Tier tier) {
        int base = RustCraft.config.costs.getOrDefault(type.name(), 20);
        double mult = RustCraft.config.tierCostMult[tier.ordinal()];
        return Math.max(1, (int) Math.ceil(base * mult));
    }

    private static boolean matches(ItemStack s, Piece.Tier t) {
        if (s.isEmpty()) return false;
        return switch (t) {
            case WOOD -> s.is(ItemTags.PLANKS);
            case STONE -> s.is(Items.COBBLESTONE);
            case METAL -> s.is(Items.IRON_INGOT);
        };
    }

    private static Item resItem(Piece.Tier t) {
        return switch (t) {
            case WOOD -> Items.OAK_PLANKS;
            case STONE -> Items.COBBLESTONE;
            case METAL -> Items.IRON_INGOT;
        };
    }

    public int count(ServerPlayer p, Piece.Tier t) {
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (matches(s, t)) n += s.getCount();
        }
        return n;
    }

    private void take(ServerPlayer p, Piece.Tier t, int amount) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize() && amount > 0; i++) {
            ItemStack s = inv.getItem(i);
            if (!matches(s, t)) continue;
            int k = Math.min(amount, s.getCount());
            s.shrink(k);
            amount -= k;
        }
        inv.setChanged();
    }

    private void give(ServerPlayer p, Piece.Tier t, int n) {
        while (n > 0) {
            int k = Math.min(64, n);
            ItemStack st = new ItemStack(resItem(t), k);
            if (!p.getInventory().add(st)) p.drop(st, false);
            n -= k;
        }
    }

    // ------------------------------------------------------------------ действия игрока

    public void usePlan(ServerPlayer p) {
        if (p.isShiftKeyDown()) {
            cycle(p);
            return;
        }
        Placement pm = compute(p, selected(p), true);
        if (!pm.valid) {
            msg(p, cap(pm.reason), ChatFormatting.RED);
            return;
        }
        String err = place(p, pm);
        if (err != null) msg(p, cap(err), ChatFormatting.RED);
    }

    public void useHammer(ServerPlayer p, BlockPos pos) {
        Piece pc = pick(p.level(), pos);
        if (pc == null) {
            msg(p, "Это не элемент постройки", ChatFormatting.RED);
            return;
        }
        if (p.isShiftKeyDown()) {
            String err = demolish(p, pc);
            if (err != null) msg(p, cap(err), ChatFormatting.RED);
            else msg(p, "Снесено", ChatFormatting.YELLOW);
        } else {
            String err = upgrade(p, pc);
            if (err != null) msg(p, cap(err), ChatFormatting.RED);
            else msg(p, label(pc.type) + " улучшено: " + tierName(pc.tier), ChatFormatting.GREEN);
        }
    }

    private String place(ServerPlayer pl, Placement pm) {
        ServerLevel level = (ServerLevel) pl.level();
        Piece.Tier tier = Piece.Tier.WOOD;
        int cost = cost(pm.type, tier);
        boolean free = pl.isCreative();
        if (!free && count(pl, tier) < cost) return "нужно " + cost + " " + resLabel(tier);

        int hp = pm.type == Piece.Type.CUPBOARD ? RustCraft.config.cupboardHp : hpOf(tier);
        Piece p = new Piece(UUID.randomUUID(), dim(level), pm.type, pm.cx, pm.cz, pm.y, pm.axis, pm.dir,
                pl.getUUID(), tier, hp, System.currentTimeMillis());
        if (p.type == Piece.Type.CUPBOARD) p.auth.add(pl.getUUID());
        addPiece(p);
        if (p.type == Piece.Type.FOUNDATION) prepareTerrain(level, p);
        render(level, p);
        recomputeLoads();
        if (!free) take(pl, tier, cost);
        level.playSound(null, pl.blockPosition(), SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 1f);
        return null;
    }

    private String upgrade(ServerPlayer pl, Piece p) {
        if (!canTouch(pl, p)) return "это не ваша постройка (нужна авторизация в шкафу)";
        if (p.type == Piece.Type.CUPBOARD) return "шкаф нельзя улучшить";
        Piece.Tier n = p.tier.next();
        if (n == null) return "уже максимальный уровень";
        int cost = cost(p.type, n);
        boolean free = pl.isCreative();
        if (!free && count(pl, n) < cost) return "нужно " + cost + " " + resLabel(n);
        ServerLevel level = levelOf(p.world);
        if (level == null) return "мир не найден";
        if (!free) take(pl, n, cost);
        p.tier = n;
        p.hp = hpOf(n);
        p.integrity = 100;
        p.changedAt = System.currentTimeMillis();
        render(level, p);
        level.playSound(null, pl.blockPosition(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.7f, 1.2f);
        return null;
    }

    private String demolish(ServerPlayer pl, Piece p) {
        boolean admin = isAdmin(pl);
        if (!canTouch(pl, p)) return "это не ваша постройка (нужна авторизация в шкафу)";
        long limit = RustCraft.config.demolishSeconds * 1000L;
        boolean inTime = System.currentTimeMillis() - p.changedAt <= limit;
        if (!admin && !inTime)
            return "снос возможен только в течение " + (limit / 60000) + " мин после постройки или улучшения";
        if (hasDependents(p)) return "сначала снесите всё, что на этом держится";
        int cost = cost(p.type, p.tier);
        Piece.Tier tier = p.tier;
        destroy(p);
        recomputeLoads();
        if (inTime && !pl.isCreative()) give(pl, tier, cost);
        return null;
    }

    private boolean hasDependents(Piece s) {
        if (!isSlab(s)) return false;
        for (Piece tc : cupboards)
            if (tc.world.equals(s.world) && tc.y == s.y + 1
                    && tc.cx >= 4 * s.cx && tc.cx <= 4 * s.cx + 4 && tc.cz >= 4 * s.cz && tc.cz <= 4 * s.cz + 4)
                return true;
        String w = s.world;
        int cx = s.cx, cz = s.cz, y = s.y;
        if (slots.containsKey(slabKey(w, cx, cz, y + 4))) return true;
        if (slots.containsKey(stairKey(w, cx, cz, y))) return true;
        return slots.containsKey(wallKey(w, 'X', cx, cz, y))
                || slots.containsKey(wallKey(w, 'X', cx, cz + 1, y))
                || slots.containsKey(wallKey(w, 'Z', cx, cz, y))
                || slots.containsKey(wallKey(w, 'Z', cx + 1, cz, y));
    }

    // ------------------------------------------------------------------ урон от C4

    /** Каждый взрыв отнимает 1 прочность у всех элементов, до которых достаёт радиус. */
    public void damageAround(ServerLevel level, Vec3 center, double radius) {
        String wid = dim(level);
        Set<Piece> hit = new LinkedHashSet<>();
        int ir = (int) Math.ceil(radius);
        BlockPos o = BlockPos.containing(center);
        for (int dx = -ir; dx <= ir; dx++)
            for (int dy = -ir; dy <= ir; dy++)
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radius * radius) continue;
                    List<Piece> l = blockIndex.get(new BlockKey(wid, o.offset(dx, dy, dz).asLong()));
                    if (l != null) hit.addAll(l);
                }
        for (Piece p : hit) {
            p.hp--;
            if (p.hp <= 0) destroy(p);
        }
    }

    /** Полностью убирает элемент (снос или разрушение взрывом). */
    public void destroy(Piece p) {
        pieces.remove(p.id);
        cupboards.remove(p);
        slots.remove(slotKey(p));
        if (p.type == Piece.Type.FOUNDATION) foundations.remove(fk(p.world, p.cx, p.cz));
        if (isSlab(p)) {
            String k = fk(p.world, p.cx, p.cz);
            List<Piece> l = cellSlabs.get(k);
            if (l != null) {
                l.remove(p);
                if (l.isEmpty()) cellSlabs.remove(k);
            }
        }
        ServerLevel level = levelOf(p.world);
        Set<Piece> rerender = new LinkedHashSet<>();
        for (int[] c : blocks(p)) {
            BlockPos pos = new BlockPos(c[0], c[1], c[2]);
            BlockKey k = new BlockKey(p.world, pos.asLong());
            List<Piece> l = blockIndex.get(k);
            if (l == null) continue;
            l.remove(p);
            if (l.isEmpty()) {
                blockIndex.remove(k);
                if (level != null) {
                    level.levelEvent(2001, pos, Block.getId(level.getBlockState(pos)));
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            } else {
                rerender.addAll(l);
            }
        }
        if (level != null) for (Piece q : rerender) render(level, q);
    }

    // ------------------------------------------------------------------ мир

    private void render(ServerLevel level, Piece p) {
        for (int[] c : blocks(p)) {
            BlockPos pos = new BlockPos(c[0], c[1], c[2]);
            BlockState st = p.type == Piece.Type.CUPBOARD
                    ? ModBlocks.CUPBOARD.defaultBlockState()
                    : p.type == Piece.Type.STAIRS
                    ? ModBlocks.stairsFor(p.tier).defaultBlockState().setValue(StairBlock.FACING, p.dir)
                    : ModBlocks.solidFor(p.tier).defaultBlockState();
            level.setBlock(pos, st, Block.UPDATE_CLIENTS);
        }
    }

    /** Под фундаментом — заполняем до земли, над ним — расчищаем место. */
    private void prepareTerrain(ServerLevel level, Piece p) {
        BlockState fill = ModBlocks.solidFor(p.tier).defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                int x = 4 * p.cx + i, z = 4 * p.cz + j;
                for (int yy = p.y + 1; yy <= p.y + 4; yy++) {
                    BlockPos pos = new BlockPos(x, yy, z);
                    if (!isClaimed(level, pos)) level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                }
                for (int yy = p.y - 1; yy >= p.y - 12 && yy > -63; yy--) {
                    BlockPos pos = new BlockPos(x, yy, z);
                    if (isClaimed(level, pos)) break;
                    BlockState st = level.getBlockState(pos);
                    if (!(st.isAir() || st.canBeReplaced() || !st.getFluidState().isEmpty())) break;
                    level.setBlock(pos, fill, Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // ------------------------------------------------------------------ предпросмотр

    public void tickPreview(MinecraftServer srv) {
        for (ServerPlayer p : srv.getPlayerList().getPlayers()) {
            if (p.getMainHandItem().is(ModItems.HAMMER)) {
                hammerInfo(p);
                continue;
            }
            if (!p.getMainHandItem().is(ModItems.PLAN)) continue;
            Piece.Type t = selected(p);
            Placement pm = compute(p, t, false);
            if (pm.valid) {
                drawBox((ServerLevel) p.level(), p, pm.box);
                Piece.Tier tier = Piece.Tier.WOOD;
                msg(p, label(t) + " — " + cost(t, tier) + " " + resLabel(tier)
                        + " (есть " + count(p, tier) + ")", ChatFormatting.GREEN);
            } else {
                msg(p, label(t) + ": " + pm.reason, ChatFormatting.RED);
            }
        }
    }

    private void drawBox(ServerLevel level, ServerPlayer pl, double[] b) {
        double[] xs = {b[0], b[3]}, ys = {b[1], b[4]}, zs = {b[2], b[5]};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                line(level, pl, xs[0], ys[i], zs[j], xs[1], ys[i], zs[j]);
                line(level, pl, xs[i], ys[0], zs[j], xs[i], ys[1], zs[j]);
                line(level, pl, xs[i], ys[j], zs[0], xs[i], ys[j], zs[1]);
            }
        }
    }

    private void line(ServerLevel level, ServerPlayer pl, double x0, double y0, double z0,
                      double x1, double y1, double z1) {
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int n = Math.max(1, (int) (len / 0.75));
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            level.sendParticles(pl, ParticleTypes.HAPPY_VILLAGER, true,
                    x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, 1, 0, 0, 0, 0);
        }
    }

    // ------------------------------------------------------------------ сохранение (в папке мира)

    private Path file(MinecraftServer s) {
        return s.getWorldPath(LevelResource.ROOT).resolve("rustcraft.json");
    }

    public void save(MinecraftServer srv) {
        Data d = new Data();
        for (Piece p : pieces.values()) {
            Dto o = new Dto();
            o.id = p.id.toString();
            o.world = p.world;
            o.type = p.type.name();
            o.cx = p.cx;
            o.cz = p.cz;
            o.y = p.y;
            o.axis = String.valueOf(p.axis);
            o.dir = p.dir.name();
            o.owner = p.owner.toString();
            o.tier = p.tier.name();
            o.hp = p.hp;
            o.t = p.changedAt;
            for (UUID a : p.auth) o.auth.add(a.toString());
            o.integrity = p.integrity;
            o.upkeep = p.upkeep.clone();
            d.pieces.add(o);
        }
        for (UUID u : seen) d.seen.add(u.toString());
        try {
            Gson g = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(file(srv), g.toJson(d));
        } catch (IOException e) {
            RustCraft.LOGGER.warn("Не удалось сохранить rustcraft.json: {}", e.getMessage());
        }
    }

    public void load(MinecraftServer srv) {
        this.server = srv;
        clearAll();
        Path f = file(srv);
        if (!Files.exists(f)) return;
        try {
            Data d = new Gson().fromJson(Files.readString(f), Data.class);
            if (d == null) return;
            for (String s : d.seen) seen.add(UUID.fromString(s));
            int n = 0;
            for (Dto o : d.pieces) {
                try {
                    Piece np = new Piece(UUID.fromString(o.id), o.world, Piece.Type.valueOf(o.type), o.cx, o.cz, o.y,
                            o.axis.charAt(0), Direction.valueOf(o.dir), UUID.fromString(o.owner),
                            Piece.Tier.valueOf(o.tier), o.hp, o.t);
                    if (o.auth != null) for (String a : o.auth) np.auth.add(UUID.fromString(a));
                    np.integrity = o.integrity <= 0 ? 100 : o.integrity;
                    if (o.upkeep != null && o.upkeep.length == 3) System.arraycopy(o.upkeep, 0, np.upkeep, 0, 3);
                    addPiece(np);
                    n++;
                } catch (Exception ex) {
                    RustCraft.LOGGER.warn("Пропущен повреждённый элемент: {}", ex.getMessage());
                }
            }
            recomputeLoads();
            RustCraft.LOGGER.info("Загружено элементов построек: {}", n);
        } catch (Exception e) {
            RustCraft.LOGGER.warn("Не удалось прочитать rustcraft.json: {}", e.getMessage());
        }
    }
}
