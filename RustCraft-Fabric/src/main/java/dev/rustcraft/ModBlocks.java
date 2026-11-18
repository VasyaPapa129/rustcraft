package dev.rustcraft;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

import java.util.HashSet;
import java.util.Set;

/**
 * Блоки построек. Они неразрушимы руками и очень устойчивы к взрывам (как бедрок),
 * убрать их можно только молотком или через C4.
 */
public final class ModBlocks {

    public static final Set<Block> PIECE_BLOCKS = new HashSet<>();

    public static final Block WOOD_BLOCK = solid("wood_block", SoundType.WOOD);
    public static final Block STONE_BLOCK = solid("stone_block", SoundType.STONE);
    public static final Block METAL_BLOCK = solid("metal_block", SoundType.METAL);

    public static final Block CUPBOARD = solid("cupboard", SoundType.WOOD);

    public static final Block WOOD_STAIRS = stairs("wood_stairs", WOOD_BLOCK, SoundType.WOOD);
    public static final Block STONE_STAIRS = stairs("stone_stairs", STONE_BLOCK, SoundType.STONE);
    public static final Block METAL_STAIRS = stairs("metal_stairs", METAL_BLOCK, SoundType.METAL);

    private ModBlocks() {}

    public static void init() {}

    public static Block solidFor(Piece.Tier t) {
        return switch (t) {
            case WOOD -> WOOD_BLOCK;
            case STONE -> STONE_BLOCK;
            case METAL -> METAL_BLOCK;
        };
    }

    public static Block stairsFor(Piece.Tier t) {
        return switch (t) {
            case WOOD -> WOOD_STAIRS;
            case STONE -> STONE_STAIRS;
            case METAL -> METAL_STAIRS;
        };
    }

    private static ResourceKey<Block> key(String name) {
        return ResourceKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(RustCraft.MOD_ID, name));
    }

    private static BlockBehaviour.Properties props(ResourceKey<Block> k, SoundType sound) {
        return BlockBehaviour.Properties.of()
                .setId(k)
                .strength(-1.0F, 3600000.0F)
                .sound(sound)
                .pushReaction(PushReaction.BLOCK);
    }

    private static Block solid(String name, SoundType sound) {
        ResourceKey<Block> k = key(name);
        Block b = Registry.register(BuiltInRegistries.BLOCK, k, new Block(props(k, sound)));
        PIECE_BLOCKS.add(b);
        return b;
    }

    private static Block stairs(String name, Block base, SoundType sound) {
        ResourceKey<Block> k = key(name);
        Block b = Registry.register(BuiltInRegistries.BLOCK, k, new StairBlock(base.defaultBlockState(), props(k, sound)));
        PIECE_BLOCKS.add(b);
        return b;
    }
}
