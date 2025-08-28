package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class FastCrystal extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Strict = fixed CPS. Random = random CPS between min and max.")
        .defaultValue(Mode.STRICT)
        .build()
    );

    private final Setting<Integer> strictCps = sg.add(new IntSetting.Builder()
        .name("strict-cps")
        .description("Clicks per second in Strict mode.")
        .defaultValue(8)
        .min(1).sliderMin(1)
        .max(20).sliderMax(20)
        .build()
    );

    private final Setting<Integer> randomMinCps = sg.add(new IntSetting.Builder()
        .name("random-min-cps")
        .description("Minimum CPS in Random mode.")
        .defaultValue(6)
        .min(1).sliderMin(1)
        .max(20).sliderMax(20)
        .build()
    );

    private final Setting<Integer> randomMaxCps = sg.add(new IntSetting.Builder()
        .name("random-max-cps")
        .description("Maximum CPS in Random mode.")
        .defaultValue(12)
        .min(1).sliderMin(1)
        .max(20).sliderMax(20)
        .build()
    );

    private enum Mode { STRICT, RANDOM }

    private long lastClickTime = 0;
    private int clickIntervalMillis = 0;
    private final Random random = new Random();

    public FastCrystal() {
        super(GanovismAddon.CATEGORY, "fast-crystal", "Auto-clicks crystals while holding right-click.");
    }

    @Override
    public void onActivate() {
        lastClickTime = 0;
        updateClickInterval();
    }

    @Override
    public void onDeactivate() {
        lastClickTime = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (mc.player.getMainHandStack().getItem() != Items.END_CRYSTAL) return;

        long window = mc.getWindow().getHandle();
        boolean rightClickHeld = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (!rightClickHeld) {
            lastClickTime = 0;
            return;
        }

        long now = System.currentTimeMillis();
        if (lastClickTime == 0 || now - lastClickTime >= clickIntervalMillis) {
            // Perform real server-side right click
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);

            lastClickTime = now;
            updateClickInterval();
        }
    }

    private void updateClickInterval() {
        int cps;
        if (mode.get() == Mode.STRICT) {
            cps = strictCps.get();
        } else {
            int min = Math.min(randomMinCps.get(), randomMaxCps.get());
            int max = Math.max(randomMinCps.get(), randomMaxCps.get());
            cps = random.nextInt(max - min + 1) + min;
        }
        // Add tiny random jitter to avoid anticheat detection
        clickIntervalMillis = Math.max(1, (int)(1000.0 / cps + random.nextInt(3) - 1));
    }
}

//no bazingus
