package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

public class BatteryESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> players = sgGeneral.add(new BoolSetting.Builder()
        .name("players")
        .description("Apply BatteryESP to players.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> mobs = sgGeneral.add(new BoolSetting.Builder()
        .name("mobs")
        .description("Apply BatteryESP to hostile / mob entities.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> animals = sgGeneral.add(new BoolSetting.Builder()
        .name("animals")
        .description("Apply BatteryESP to passive animals.")
        .defaultValue(true)
        .build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum render distance for entities (blocks).")
        .defaultValue(100d)
        .min(0).sliderMax(200)
        .build());

    private final Setting<SettingColor> outlineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("outline-color")
        .description("Outline color for the entity box.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build());

    private final Setting<Double> fillOpacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("fill-opacity")
        .description("Opacity of the fill (0-1).")
        .defaultValue(0.6)
        .min(0).max(1)
        .sliderMax(1)
        .build());

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How shapes are rendered (Lines / Sides / Both).")
        .defaultValue(ShapeMode.Both)
        .build());

    public BatteryESP() {
        super(GanovismAddon.CATEGORY, "BatteryESP", "Outlines and fills entities like a health battery.");
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null) return;

        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity living)) continue;
            if (e == mc.player) continue;
            if (!shouldApplyTo(living)) continue;

            double distSq = mc.player.squaredDistanceTo(living);
            if (distSq > range.get() * range.get()) continue;

            double x = MathHelper.lerp(event.tickDelta, e.lastRenderX, e.getX()) - e.getX();
            double y = MathHelper.lerp(event.tickDelta, e.lastRenderY, e.getY()) - e.getY();
            double z = MathHelper.lerp(event.tickDelta, e.lastRenderZ, e.getZ()) - e.getZ();

            Box bb = e.getBoundingBox();

            double health = living.getHealth();
            double maxHealth = Math.max(1.0, living.getMaxHealth());
            double ratio = MathHelper.clamp((float) (health / maxHealth), 0f, 1f);

            Color outline = new Color().set(outlineColor.get());

            // Full HP → green, Low HP → red
            Color battery = lerpColor(new Color(255, 0, 0, 255), new Color(255, 255, 0, 255), new Color(0, 255, 0, 255), ratio);
            battery.a((int) (255 * fillOpacity.get()));

            Color bg = new Color(0, 0, 0, (int) (255 * fillOpacity.get() * 0.45));

            event.renderer.box(
                x + bb.minX, y + bb.minY, z + bb.minZ,
                x + bb.maxX, y + bb.maxY, z + bb.maxZ,
                bg, outline, shapeMode.get(), 0
            );

            double minY = y + bb.minY;
            double maxY = y + bb.maxY;
            double fillY = minY + (maxY - minY) * ratio;

            event.renderer.box(
                x + bb.minX, minY, z + bb.minZ,
                x + bb.maxX, fillY, z + bb.maxZ,
                battery, outline, shapeMode.get(), 0
            );
        }
    }

    private boolean shouldApplyTo(LivingEntity e) {
        if (e instanceof PlayerEntity) return players.get();
        if (e instanceof AnimalEntity) return animals.get();
        if (e instanceof MobEntity) return mobs.get();
        return true;
    }

    private Color lerpColor(Color low, Color mid, Color high, double t) {
        if (t <= 0.5) {
            double s = t / 0.5;
            return lerp(low, mid, s);
        } else {
            double s = (t - 0.5) / 0.5;
            return lerp(mid, high, s);
        }
    }

    private Color lerp(Color a, Color b, double t) {
        int r = (int) MathHelper.lerp((float) t, a.r, b.r);
        int g = (int) MathHelper.lerp((float) t, a.g, b.g);
        int bl = (int) MathHelper.lerp((float) t, a.b, b.b);
        int al = (int) MathHelper.lerp((float) t, a.a, b.a);
        return new Color(r, g, bl, al);
    }
}
