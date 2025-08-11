package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.Random;

public class LegitTotem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Strict or Random delay before moving the totem.")
        .defaultValue(Mode.Strict)
        .build()
    );

    private final Setting<Integer> strictDelay = sgGeneral.add(new IntSetting.Builder()
        .name("strict-delay")
        .description("Ticks to wait in strict mode.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> randomMin = sgGeneral.add(new IntSetting.Builder()
        .name("random-min-delay")
        .description("Minimum ticks to wait in random mode.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> randomMax = sgGeneral.add(new IntSetting.Builder()
        .name("random-max-delay")
        .description("Maximum ticks to wait in random mode.")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    private enum Mode { Strict, Random }

    private int delayTicks = 0;
    private boolean shouldRefill = false;
    private final Random random = new Random();

    public LegitTotem() {
        super(GanovismAddon.CATEGORY, "legit-totem", "Refills totem into offhand with legit-like delay.");
        System.out.println("LegitTotem module constructed!");
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof EntityStatusS2CPacket p) {
            if (p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
                triggerRefill();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!shouldRefill) {
            if (mc.player.getOffHandStack().getItem() != Items.TOTEM_OF_UNDYING && hasTotemInInventory()) {
                shouldRefill = true;
                delayTicks = mode.get() == Mode.Strict
                    ? strictDelay.get()
                    : random.nextInt(randomMax.get() - randomMin.get() + 1) + randomMin.get();
                mc.setScreen(new InventoryScreen(mc.player)); // Open inventory immediately
            }
            return;
        }

        // Wait in inventory screen
        if (mc.currentScreen instanceof InventoryScreen) {
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            int totemSlot = findTotemSlot();
            if (totemSlot != -1) {
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, totemSlot, 40, SlotActionType.SWAP, mc.player);
            }

            mc.player.closeHandledScreen();
            shouldRefill = false;
        } else {
            // If inventory is closed for some reason, abort refill
            shouldRefill = false;
        }
    }

    private void triggerRefill() {
        shouldRefill = true;
        delayTicks = mode.get() == Mode.Strict
            ? strictDelay.get()
            : random.nextInt(randomMax.get() - randomMin.get() + 1) + randomMin.get();
    }

    private boolean hasTotemInInventory() {
        return findTotemSlot() != -1;
    }

    private int findTotemSlot() {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return i < 9 ? i + 36 : i;
            }
        }
        return -1;
    }
}


