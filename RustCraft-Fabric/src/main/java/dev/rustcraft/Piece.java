package dev.rustcraft;

import net.minecraft.core.Direction;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Один элемент постройки (фундамент, стена и т.д.). */
public class Piece {

    public enum Type { FOUNDATION, FLOOR, WALL, DOORWAY, WINDOW, STAIRS, CUPBOARD }

    public enum Tier {
        WOOD, STONE, METAL;

        public Tier next() {
            return switch (this) {
                case WOOD -> STONE;
                case STONE -> METAL;
                case METAL -> null;
            };
        }
    }

    public final UUID id, owner;
    public final String world;
    public final Type type;
    public final int cx, cz;
    /** Y слоя плиты того этажа, к которому относится элемент. */
    public final int y;
    /** Для стен: 'X' (вдоль X) или 'Z' (вдоль Z). */
    public final char axis;
    /** Для лестниц: направление подъёма. */
    public final Direction dir;

    /** Для шкафа: кто авторизован (имеет право строить в зоне). */
    public final Set<UUID> auth = new HashSet<>();
    /** Для шкафа: запас материалов на содержание построек (дерево, камень, металл). */
    public final int[] upkeep = new int[3];
    /** Для шкафа: сколько элементов каждого уровня он содержит (считается автоматически). */
    public final int[] load = new int[3];
    /** Целостность 0..100: падает у построек без шкафа или без оплаты содержания. */
    public int integrity = 100;

    public Tier tier;
    public int hp;
    public long changedAt;

    public Piece(UUID id, String world, Type type, int cx, int cz, int y, char axis, Direction dir,
                 UUID owner, Tier tier, int hp, long changedAt) {
        this.id = id;
        this.world = world;
        this.type = type;
        this.cx = cx;
        this.cz = cz;
        this.y = y;
        this.axis = axis;
        this.dir = dir;
        this.owner = owner;
        this.tier = tier;
        this.hp = hp;
        this.changedAt = changedAt;
    }
}
