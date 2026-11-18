package dev.rustcraft;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Клиентская часть: худ (еда, вода, температура) в правом нижнем углу. */
public class RustCraftClient implements ClientModInitializer {

    private static volatile int thirst = 100;
    private static volatile int temp = 20;

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(StatsPayload.TYPE, (payload, context) -> {
            thirst = payload.thirst();
            temp = payload.temperature();
        });
        HudRenderCallback.EVENT.register(RustCraftClient::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.player.isCreative() || mc.player.isSpectator()) return;

        int w = mc.getWindow().getGuiScaledWidth();
        int h = mc.getWindow().getGuiScaledHeight();
        int x = w - 146;
        int y = h - 50;

        int food = mc.player.getFoodData().getFoodLevel() * 5;
        bar(g, mc, x, y, "Еда", food, 0xFFE0A030, food + "%");
        bar(g, mc, x, y + 13, "Вода", thirst, thirst < 15 ? 0xFFFF5555 : 0xFF3FA7FF, thirst + "%");

        int t = temp;
        int tColor = t < 5 ? 0xFF4FA3FF : t > 38 ? 0xFFFF5530 : t > 32 ? 0xFFFFA030 : 0xFF55D060;
        int tPct = Math.max(0, Math.min(100, Math.round((t + 10) / 55f * 100f)));
        bar(g, mc, x, y + 26, "Темп.", tPct, tColor, t + "°C");
    }

    private static void bar(GuiGraphics g, Minecraft mc, int x, int y, String label, int pct, int color, String text) {
        pct = Math.max(0, Math.min(100, pct));
        g.drawString(mc.font, label, x, y, 0xFFFFFFFF);
        int bx = x + 34;
        g.fill(bx, y, bx + 80, y + 8, 0xAA000000);
        int fw = Math.round(78f * pct / 100f);
        if (fw > 0) g.fill(bx + 1, y + 1, bx + 1 + fw, y + 7, color);
        g.drawString(mc.font, text, bx + 84, y, 0xFFFFFFFF);
    }
}
