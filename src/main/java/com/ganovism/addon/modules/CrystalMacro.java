package com.ganovism.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.Random;

import com.ganovism.addon.GanovismAddon;

public class CrystalMacro extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
            .name("mode")
            .description("Strict = fixed ticks. Random = random ticks between min and max.")
            .defaultValue(Mode.STRICT)
            .build()
    );

    private final Setting<Integer> strictTicks = sg.add(new IntSetting.Builder()
            .name("strict-ticks")
            .description("Number of ticks to wait in Strict mode.")
            .defaultValue(4)
            .min(1)
            .sliderMin(1)
            .sliderMax(40)
            .build()
    );

    private final Setting<Integer> randomMin = sg.add(new IntSetting.Builder()
            .name("random-min")
            .description("Minimum ticks to wait in Random mode.")
            .defaultValue(3)
            .min(1)
            .sliderMin(1)
            .sliderMax(40)
            .build()
    );

    private final Setting<Integer> randomMax = sg.add(new IntSetting.Builder()
            .name("random-max")
            .description("Maximum ticks to wait in Random mode.")
            .defaultValue(6)
            .min(1)
            .sliderMin(1)
            .sliderMax(80)
            .build()
    );

    // keybind to reset the internal timer (uses Meteor's Keybind type)
    // private final Setting<Keybind> resetBind = sg.add(new KeybindSetting.Builder()
    //         .name("reset-bind")
    //         .description("Press to reset the macro's timer.")
    //         .build()
    // );

    // internal state
    private int targetEntityId = -1;
    private int ticksPassed = 0;
    private int requiredTicks = 0;
    private int hitsOnCrystal = 0; // Track hits per crystal
    private final Random random = new Random();

    public CrystalMacro() {
        super(GanovismAddon.CATEGORY, "crystal-macro", "Automatically explode end crystals you are staring at, with configurable delay.");
        // Optionally register a small HUD element or other settings here using GanovismAddon.HUD_GROUP if needed:
        // Hud.get().register(...);
    }

    @Override
    public void onActivate() {
        resetState();
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        targetEntityId = -1;
        ticksPassed = 0;
        requiredTicks = 0;
        hitsOnCrystal = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        // Reset when resetBind pressed. Keybind API provides wasPressed()
        // try {
        //     Keybind rb = resetBind.get();
        //     if (rb != null && rb.wasPressed()) {
        //         resetState();
        //         info("CrystalMacro timer reset");
        //     }
        // } catch (Exception ignored) {
        //     // If Keybind API differs in a specific fork/version, ignore gracefully.
        // }

        // Check crosshair
        HitResult hr = mc.crosshairTarget;
        if (!(hr instanceof EntityHitResult ehr)) {
            // Not looking at entity -> abort timer
            resetState();
            return;
        }

        if (!(ehr.getEntity() instanceof EndCrystalEntity crystal)) {
            // Not an end crystal -> abort
            resetState();
            return;
        }

        int currentId = ehr.getEntity().getId();

        // If switching to a different crystal, start new timer
        if (targetEntityId != currentId) {
            targetEntityId = currentId;
            ticksPassed = 0;
            requiredTicks = 0;
            hitsOnCrystal = 0;
            if (mode.get() == Mode.STRICT) {
                requiredTicks = strictTicks.get();
            } else {
                int min = Math.min(randomMin.get(), randomMax.get());
                int max = Math.max(randomMin.get(), randomMax.get());
                requiredTicks = random.nextInt(max - min + 1) + min;
            }
            return; // wait next tick to start counting
        }

        // still targeting same crystal: increment
        ticksPassed++;

        // double-check we are still looking at same entity
        HitResult hr2 = mc.crosshairTarget;
        if (!(hr2 instanceof EntityHitResult ehr2) || ehr2.getEntity().getId() != targetEntityId) {
            // aborted
            resetState();
            return;
        }

        // If wait finished -> attack
        if (requiredTicks > 0 && ticksPassed >= requiredTicks) {
            if (hitsOnCrystal < 2) {
                try {
                    mc.interactionManager.attackEntity(mc.player, crystal);
                    mc.player.swingHand(Hand.MAIN_HAND);
                } catch (Exception e) {
                    // Some mappings can differ — ignore to avoid crash
                } finally {
                    hitsOnCrystal++;
                    ticksPassed = 0;
                    if (mode.get() == Mode.STRICT) {
                        requiredTicks = strictTicks.get();
                    } else {
                        int min = Math.min(randomMin.get(), randomMax.get());
                        int max = Math.max(randomMin.get(), randomMax.get());
                        requiredTicks = random.nextInt(max - min + 1) + min;
                    }
                    // Only reset state if we've hit twice
                    if (hitsOnCrystal >= 2) {
                        resetState();
                    }
                }
            } else {
                resetState();
            }
        }
    }

    private enum Mode {
        STRICT,
        RANDOM
    }
}
