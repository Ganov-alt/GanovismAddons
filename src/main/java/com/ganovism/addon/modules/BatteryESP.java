package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
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

/**
 * BatteryESP
 *
 * - Outlines living entities and fills the inside like a "battery" representing health.
 * - Options to show health as percent or numbers and to show name / type.
 * - Simple boolean filter (players / mobs / animals) and range.
 *
 * NOTE: This file follows common Meteor Client addon patterns (Render3DEvent, Settings API, event.renderer.box(...)).
 * Depending on Meteor Client version you may need to adjust imports or method names for text rendering.
 */
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

    private final Setting<SettingColor> batteryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("battery-color")
        .description("Base color for the battery fill (the module will interpolate green->red by health).")
        .defaultValue(new SettingColor(0, 255, 0, 200))
        .build());

    private final Setting<Double> fillOpacity = sgGeneral.add(new DoubleSetting.Builder()
        .name("fill-opacity")
        .description("Opacity of the fill (0-1).")
        .defaultValue(0.6)
        .min(0).max(1)
        .sliderMax(1)
        .build());

    private final Setting<Boolean> showPercent = sgGeneral.add(new BoolSetting.Builder()
        .name("show-percent")
        .description("Show health as percent above the entity.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> showNumbers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-numbers")
        .description("Show health as numbers (current / max).")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> showName = sgGeneral.add(new BoolSetting.Builder()
        .name("show-name")
        .description("Show entity type/name above the battery.")
        .defaultValue(true)
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
            if (e == mc.player) continue; // skip self by default

            if (!shouldApplyTo(living)) continue;

            double distSq = mc.player.squaredDistanceTo(living);
            if (distSq > range.get() * range.get()) continue;

            // Interpolate position for smooth rendering
            double x = MathHelper.lerp(event.tickDelta, e.lastRenderX, e.getX()) - e.getX();
            double y = MathHelper.lerp(event.tickDelta, e.lastRenderY, e.getY()) - e.getY();
            double z = MathHelper.lerp(event.tickDelta, e.lastRenderZ, e.getZ()) - e.getZ();

            Box bb = e.getBoundingBox();

            // Health ratio
            double health = 0;
            double maxHealth = 1;
            if (living instanceof LivingEntity le) {
                health = le.getHealth();
                maxHealth = Math.max(1.0, le.getMaxHealth());
            }
            double ratio = MathHelper.clamp((float) (health / maxHealth), 0f, 1f);

            // Prepare colors
            Color outline = new Color();
            outline.set(outlineColor.get());

            Color base = new Color();
            base.set(batteryColor.get());

            // Create gradient: green (full) -> yellow -> red (low)
            Color battery = lerpColor(new Color(0, 255, 0, 255), new Color(255, 255, 0, 255), new Color(255, 0, 0, 255), ratio);
            battery.a((int) (base.a * fillOpacity.get()));

            // Background (dim fill) so you can see unfilled part
            Color bg = new Color();
            bg.set(0, 0, 0, (int) (base.a * fillOpacity.get() * 0.45));

            // Draw full bounding box background (dark semi-transparent)
            event.renderer.box(
                x + bb.minX, y + bb.minY, z + bb.minZ,
                x + bb.maxX, y + bb.maxY, z + bb.maxZ,
                bg, outline, shapeMode.get(), 0
            );

            // Draw the "battery fill" from bottom up to health level
            double minY = y + bb.minY;
            double maxY = y + bb.maxY;
            double fillY = minY + (maxY - minY) * ratio;

            event.renderer.box(
                x + bb.minX, minY, z + bb.minZ,
                x + bb.maxX, fillY, z + bb.maxZ,
                battery, outline, shapeMode.get(), 0
            );

            // Draw a thin outline on the top of the filled area so it reads like a battery
            Color topLine = new Color();
            topLine.set(outline).a(255);
            double epsilon = 0.001; // avoid z-fighting by nudging
            event.renderer.box(
                x + bb.minX + epsilon, fillY - 0.0005, z + bb.minZ + epsilon,
                x + bb.maxX - epsilon, fillY + 0.0005, z + bb.maxZ - epsilon,
                battery, topLine, ShapeMode.Both, 0
            );

            // Text: name / health
            if (showName.get() || showPercent.get() || showNumbers.get()) {
                StringBuilder sb = new StringBuilder();
                if (showName.get()) sb.append(e.getType().getName().getString());
                if (showPercent.get()) {
                    if (sb.length() > 0) sb.append(" ");
                    int p = (int) Math.round(ratio * 100);
                    sb.append(p).append("%");
                }
                if (showNumbers.get()) {
                    if (sb.length() > 0) sb.append(" ");
                    sb.append((int) Math.round(health)).append("/").append((int) Math.round(maxHealth));
                }

                if (sb.length() > 0) {
                    // Position text slightly above the top of the bounding box
                    double textX = x + (bb.minX + bb.maxX) / 2.0;
                    double textY = y + bb.maxY + 0.15; // a little above
                    double textZ = z + (bb.minZ + bb.maxZ) / 2.0;

                    // event.renderer.text(...) is used by many Meteor modules. If your version
                    // uses a different signature you may need to call TextRenderer.get().draw(...) instead.
                    try {
                        // common helper: draw world text at 3d position
                        // Use Meteor's TextRenderer so the code compiles across Meteor versions.
                        try {
                            meteordevelopment.meteorclient.renderer.text.TextRenderer tr = meteordevelopment.meteorclient.renderer.text.TextRenderer.get();
                            tr.begin(0.02);
                            tr.render(sb.toString(), textX, textY, outline, true);
                            tr.end();
                        } catch (Throwable ignored) {
                            // TextRenderer not available — ignore text rendering.
                        }
                    } catch (Throwable ignored) {
                        // If that method doesn't exist on your version, ignore; the battery visualization still works.
                    }
                }
            }
        }
    }

    private boolean shouldApplyTo(LivingEntity e) {
        if (e instanceof PlayerEntity) return players.get();
        if (e instanceof AnimalEntity) return animals.get();
        if (e instanceof MobEntity) return mobs.get();
        // default: include living things
        return true;
    }

    /**
     * Interpolates between three colors: [lowColor, midColor, highColor] using t in [0,1].
     * 0 -> lowColor (red), 0.5 -> midColor (yellow), 1 -> highColor (green)
     */
    private Color lerpColor(Color low, Color mid, Color high, double t) {
        // map t from 0..1 to 0..1 where <0.5 uses low->mid and >=0.5 uses mid->high
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
