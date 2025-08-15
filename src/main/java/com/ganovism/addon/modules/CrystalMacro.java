package com.ganovism.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
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

    private final Setting<Boolean> blockInInventory = sg.add(new BoolSetting.Builder()
        .name("block-in-inventory")
        .description("Prevents crystal breaking while your inventory or any container is open.")
        .defaultValue(true)
        .build()
    );

    // Internal state
    private int targetEntityId = -1;
    private int ticksPassed = 0;
    private int requiredTicks = 0;
    private int hitsOnCrystal = 0;
    private final Random random = new Random();

    public CrystalMacro() {
        super(GanovismAddon.CATEGORY, "crystal-macro", "Automatically explode end crystals you are staring at, with configurable delay.");
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

        // Inventory/container blocking
        if (blockInInventory.get() && mc.currentScreen instanceof HandledScreen) {
            resetState();
            return;
        }

        HitResult hr = mc.crosshairTarget;
        if (!(hr instanceof EntityHitResult ehr)) {
            resetState();
            return;
        }

        if (!(ehr.getEntity() instanceof EndCrystalEntity crystal)) {
            resetState();
            return;
        }

        int currentId = ehr.getEntity().getId();

        // New crystal targeted
        if (targetEntityId != currentId) {
            targetEntityId = currentId;
            ticksPassed = 0;
            hitsOnCrystal = 0;
            requiredTicks = (mode.get() == Mode.STRICT)
                ? strictTicks.get()
                : random.nextInt(Math.max(randomMin.get(), randomMax.get()) - Math.min(randomMin.get(), randomMax.get()) + 1)
                    + Math.min(randomMin.get(), randomMax.get());
            return;
        }

        ticksPassed++;

        // Confirm still looking at same crystal
        HitResult hr2 = mc.crosshairTarget;
        if (!(hr2 instanceof EntityHitResult ehr2) || ehr2.getEntity().getId() != targetEntityId) {
            resetState();
            return;
        }

        if (requiredTicks > 0 && ticksPassed >= requiredTicks) {
            if (hitsOnCrystal < 2) {
                try {
                    mc.interactionManager.attackEntity(mc.player, crystal);
                    mc.player.swingHand(Hand.MAIN_HAND);
                } catch (Exception ignored) {
                } finally {
                    hitsOnCrystal++;
                    ticksPassed = 0;
                    requiredTicks = (mode.get() == Mode.STRICT)
                        ? strictTicks.get()
                        : random.nextInt(Math.max(randomMin.get(), randomMax.get()) - Math.min(randomMin.get(), randomMax.get()) + 1)
                            + Math.min(randomMin.get(), randomMax.get());
                    if (hitsOnCrystal >= 2) resetState();
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
