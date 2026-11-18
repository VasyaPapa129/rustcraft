package dev.rustcraft;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

public final class ModItems {

    public static final Item PLAN = register("building_plan", p -> new PlanItem(p.stacksTo(1)));
    public static final Item HAMMER = register("hammer", p -> new HammerItem(p.stacksTo(1)));
    public static final Item C4 = register("c4", p -> new C4Item(p.stacksTo(16)));

    private ModItems() {}

    public static void init() {}

    private static Item register(String name, Function<Item.Properties, Item> factory) {
        ResourceKey<Item> key = ResourceKey.create(Registries.ITEM,
                ResourceLocation.fromNamespaceAndPath(RustCraft.MOD_ID, name));
        return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
    }

    /** ПКМ — построить, Shift+ПКМ — выбрать следующий элемент. */
    public static class PlanItem extends Item {
        public PlanItem(Properties p) {
            super(p);
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            return handle(ctx.getLevel(), ctx.getPlayer());
        }

        @Override
        public InteractionResult use(Level level, Player player, InteractionHand hand) {
            return handle(level, player);
        }

        private InteractionResult handle(Level level, Player player) {
            if (!level.isClientSide && player instanceof ServerPlayer sp) {
                RustCraft.build().usePlan(sp);
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** ПКМ по элементу — улучшить, Shift+ПКМ — снести. */
    public static class HammerItem extends Item {
        public HammerItem(Properties p) {
            super(p);
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            if (!level.isClientSide && ctx.getPlayer() instanceof ServerPlayer sp) {
                RustCraft.build().useHammer(sp, ctx.getClickedPos());
            }
            return InteractionResult.SUCCESS;
        }
    }

    /** Заряд C4: ставится у стены, через несколько секунд взрывается. */
    public static class C4Item extends Item {
        public C4Item(Properties p) {
            super(p);
        }

        @Override
        public InteractionResult useOn(UseOnContext ctx) {
            Level level = ctx.getLevel();
            if (level.isClientSide) return InteractionResult.SUCCESS;
            if (level instanceof ServerLevel sl) {
                BlockPos pos = ctx.getClickedPos().relative(ctx.getClickedFace());
                RustCraft.addCharge(sl, Vec3.atCenterOf(pos));
                sl.playSound(null, pos, SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, 1.0F);
                Player pl = ctx.getPlayer();
                if (pl == null || !pl.isCreative()) ctx.getItemInHand().shrink(1);
            }
            return InteractionResult.SUCCESS;
        }
    }
}
