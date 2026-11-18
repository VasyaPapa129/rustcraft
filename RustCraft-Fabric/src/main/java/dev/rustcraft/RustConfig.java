package dev.rustcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Настройки: файл config/rustcraft.json (создаётся при первом запуске). */
public class RustConfig {

    public static class Zone {
        public int x, z, radius;

        public Zone() {}

        public Zone(int x, int z, int radius) {
            this.x = x;
            this.z = z;
            this.radius = radius;
        }
    }

    public int spawnRadius = 3000;
    public boolean giveStarterKit = true;       // выдавать план и молоток новым игрокам

    public int c4FuseTicks = 100;               // 20 тиков = 1 секунда
    public float c4Power = 5.0f;                // у обычного TNT = 4
    public double c4DamageRadius = 3.5;         // в этом радиусе элементы построек получают урон

    public int airdropIntervalMinutes = 30;

    public int demolishSeconds = 600;

    // Содержание и распад построек
    public int upkeepIntervalMinutes = 60;      // как часто списывается содержание / идёт распад
    public int upkeepPerPiece = 1;              // сколько материала шкаф тратит на один элемент за интервал
    public int[] decayPerInterval = {20, 10, 5};// потеря целостности за интервал (дерево, камень, металл)
    public int repairPerInterval = 25;          // восстановление целостности, если содержание оплачено

    // Выживание
    public boolean survivalEnabled = true;
    public int thirstDrainSeconds = 12;         // секунд на 1% воды в покое (12 с = 20 минут до нуля)
    public int cupboardRadius = 32;             // зона привата шкафа (в блоках, по каждой оси)
    public int cupboardHp = 2;                  // сколько C4 нужно, чтобы уничтожить шкаф
    public Map<String, Integer> costs = new LinkedHashMap<>();
    public double[] tierCostMult = {1.0, 1.0, 0.5};   // дерево, камень, металл
    public int[] tierHp = {1, 3, 6};                  // сколько C4 нужно на элемент

    public List<Zone> radiation = new ArrayList<>(List.of(new Zone(500, 500, 60), new Zone(-800, 300, 45)));

    {
        costs.put("FOUNDATION", 40);
        costs.put("FLOOR", 30);
        costs.put("WALL", 20);
        costs.put("DOORWAY", 14);
        costs.put("WINDOW", 14);
        costs.put("STAIRS", 24);
        costs.put("CUPBOARD", 25);
    }

    public static RustConfig load() {
        Path p = FabricLoader.getInstance().getConfigDir().resolve("rustcraft.json");
        Gson g = new GsonBuilder().setPrettyPrinting().create();
        try {
            if (Files.exists(p)) {
                RustConfig c = g.fromJson(Files.readString(p), RustConfig.class);
                if (c != null) return c;
            }
            RustConfig c = new RustConfig();
            Files.writeString(p, g.toJson(c));
            return c;
        } catch (Exception e) {
            RustCraft.LOGGER.warn("Не удалось прочитать rustcraft.json, используются значения по умолчанию: {}", e.getMessage());
            return new RustConfig();
        }
    }
}
