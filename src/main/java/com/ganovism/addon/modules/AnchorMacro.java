package com.ganovism.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;

import java.util.Optional;
import java.util.Random;

import com.ganovism.addon.GanovismAddon;

public class AnchorMacro extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> preGlowMode = sg.add(new EnumSetting.Builder<Mode>()
        .name("pre-glow-mode")
        .description("Strict = fixed ticks. Random = random ticks between min and max before placing glowstone.")
        .defaultValue(Mode.STRICT)
        .build()
    );

    private final Setting<Integer> preGlowStrictTicks = sg.add(new IntSetting.Builder()
        .name("pre-glow-strict-ticks")
        .description("Number of ticks to wait in Strict pre-glow mode.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> preGlowRandomMin = sg.add(new IntSetting.Builder()
        .name("pre-glow-random-min")
        .description("Minimum ticks to wait in Random pre-glow mode.")
        .defaultValue(3)
        .min(1)
        .sliderMin(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> preGlowRandomMax = sg.add(new IntSetting.Builder()
        .name("pre-glow-random-max")
        .description("Maximum ticks to wait in Random pre-glow mode.")
        .defaultValue(6)
        .min(1)
        .sliderMin(1)
        .sliderMax(80)
        .build()
    );

    private final Setting<Mode> postGlowMode = sg.add(new EnumSetting.Builder<Mode>()
        .name("post-glow-mode")
        .description("Strict = fixed ticks. Random = random ticks between min and max after placing glowstone.")
        .defaultValue(Mode.STRICT)
        .build()
    );

    private final Setting<Integer> postGlowStrictTicks = sg.add(new IntSetting.Builder()
        .name("post-glow-strict-ticks")
        .description("Number of ticks to wait in Strict post-glow mode.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> postGlowRandomMin = sg.add(new IntSetting.Builder()
        .name("post-glow-random-min")
        .description("Minimum ticks to wait in Random post-glow mode.")
        .defaultValue(3)
        .min(1)
        .sliderMin(1)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> postGlowRandomMax = sg.add(new IntSetting.Builder()
        .name("post-glow-random-max")
        .description("Maximum ticks to wait in Random post-glow mode.")
        .defaultValue(6)
        .min(1)
        .sliderMin(1)
        .sliderMax(80)
        .build()
    );

    private final Setting<Integer> explodeSlot = sg.add(new IntSetting.Builder()
        .name("explode-slot")
        .description("The hotbar slot to switch to before exploding (0–8).")
        .defaultValue(0)
        .min(0)
        .max(8)
        .sliderMin(0)
        .sliderMax(8)
        .build()
    );

    private final Setting<Boolean> blockInInventory = sg.add(new BoolSetting.Builder()
        .name("block-in-inventory")
        .description("Prevents macro from running while your inventory or any container is open.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> notifyNoGlow = sg.add(new BoolSetting.Builder()
        .name("notify-no-glow")
        .description("Notifies you when no glowstone is found in hotbar.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> forceUncrouch = sg.add(new BoolSetting.Builder()
        .name("force-uncrouch")
        .description("Prevents crouching when right-clicking anchor, guaranteeing an explosion.")
        .defaultValue(true)
        .build()
    );

    // State
    private enum Stage { NONE, PRE_GLOW_WAIT, POST_GLOW_WAIT }

    private Stage stage = Stage.NONE;
    private int ticksPassed = 0;
    private int requiredTicks = 0;
    private final Random random = new Random();

    public AnchorMacro() {
        super(GanovismAddon.CATEGORY, "anchor-macro", "Automatically charges and explodes respawn anchors.");
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
        stage = Stage.NONE;
        ticksPassed = 0;
        requiredTicks = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        if (blockInInventory.get() && mc.currentScreen instanceof HandledScreen) {
            resetState();
            return;
        }

        if (!(mc.crosshairTarget instanceof BlockHitResult bhr)) {
            resetState();
            return;
        }

        if (!mc.world.getBlockState(bhr.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) {
            resetState();
            return;
        }

        switch (stage) {
            case NONE -> {
                ticksPassed = 0;
                requiredTicks = getWait(preGlowMode.get(), preGlowStrictTicks.get(), preGlowRandomMin.get(), preGlowRandomMax.get());
                stage = Stage.PRE_GLOW_WAIT;
            }

            case PRE_GLOW_WAIT -> {
                ticksPassed++;

                // Uncrouch 1 tick before placing glowstone
                if (forceUncrouch.get() && ticksPassed == requiredTicks - 1) {
                    mc.options.sneakKey.setPressed(false);
                }

                if (ticksPassed >= requiredTicks) {
                    Optional<Integer> glowSlot = findGlowstoneInHotbar();
                    if (glowSlot.isEmpty()) {
                        if (notifyNoGlow.get()) info("No glowstone found in hotbar. Aborting.");
                        resetState();
                        return;
                    }

                    mc.player.getInventory().setSelectedSlot(glowSlot.get());
                    rightClick();

                    // Maintain uncrouch 1 tick after placing
                    if (forceUncrouch.get()) mc.options.sneakKey.setPressed(false);

                    ticksPassed = 0;
                    requiredTicks = getWait(postGlowMode.get(), postGlowStrictTicks.get(), postGlowRandomMin.get(), postGlowRandomMax.get());
                    stage = Stage.POST_GLOW_WAIT;
                }
            }

            case POST_GLOW_WAIT -> {
                ticksPassed++;

                // Uncrouch 1 tick before exploding
                if (forceUncrouch.get() && ticksPassed == requiredTicks - 1) {
                    mc.options.sneakKey.setPressed(false);
                }

                if (ticksPassed >= requiredTicks) {
                    mc.player.getInventory().setSelectedSlot(explodeSlot.get());
                    rightClick();

                    // Maintain uncrouch 1 tick after exploding
                    if (forceUncrouch.get()) mc.options.sneakKey.setPressed(false);

                    resetState();
                }
            }
        }
    }

    private int getWait(Mode mode, int strict, int min, int max) {
        return (mode == Mode.STRICT) ? strict : random.nextInt(Math.max(min, max) - Math.min(min, max) + 1) + Math.min(min, max);
    }

    private Optional<Integer> findGlowstoneInHotbar() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.GLOWSTONE) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private void rightClick() {
        try {
            InputUtil.Key useKey = mc.options.useKey.getDefaultKey();
            KeyBinding.setKeyPressed(useKey, true);
            KeyBinding.onKeyPressed(useKey);
            KeyBinding.setKeyPressed(useKey, false);
        } catch (Exception ignored) {}
    }

    private enum Mode {
        STRICT,
        RANDOM
    }
}
