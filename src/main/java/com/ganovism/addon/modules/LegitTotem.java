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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class LegitTotem extends Module {
    private final SettingGroup sg = settings.getDefaultGroup();

    private final Setting<Mode> mode = sg.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Strict or Random delay before moving the totem.")
        .defaultValue(Mode.Strict)
        .build()
    );

    private final Setting<Integer> strictDelay = sg.add(new IntSetting.Builder()
        .name("strict-delay")
        .description("Ticks to wait in strict mode before swapping totem.")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> randomMin = sg.add(new IntSetting.Builder()
        .name("random-min-delay")
        .description("Minimum ticks to wait in random mode before swapping totem.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> randomMax = sg.add(new IntSetting.Builder()
        .name("random-max-delay")
        .description("Maximum ticks to wait in random mode before swapping totem.")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

    // Pre-open delay min/max
    private final Setting<Integer> preOpenMin = sg.add(new IntSetting.Builder()
        .name("pre-open-min")
        .description("Minimum ticks to wait before opening inventory to start auto-totem.")
        .defaultValue(1)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Integer> preOpenMax = sg.add(new IntSetting.Builder()
        .name("pre-open-max")
        .description("Maximum ticks to wait before opening inventory to start auto-totem.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 20)
        .build()
    );

    private enum Mode { Strict, Random }

    private int delayTicks = 0;
    private int preOpenTicks = 0;
    private boolean shouldRefill = false;
    private boolean inventoryOpened = false;
    private final Random random = new Random();

    public LegitTotem() {
        super(GanovismAddon.CATEGORY, "legit-totem", "Refills totem into offhand with legit-like delay and slot variability.");
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof EntityStatusS2CPacket p) {
            if (p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
                // Randomize pre-open delay between min and max
                preOpenTicks = random.nextInt(preOpenMax.get() - preOpenMin.get() + 1) + preOpenMin.get();
                shouldRefill = true;
                inventoryOpened = false;

                delayTicks = mode.get() == Mode.Strict
                    ? strictDelay.get()
                    : random.nextInt(randomMax.get() - randomMin.get() + 1) + randomMin.get();
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!shouldRefill) return;

        if (mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING) {
            resetState();
            return;
        }

        // Wait before opening inventory
        if (!inventoryOpened) {
            if (preOpenTicks > 0) {
                preOpenTicks--;
                return;
            }
            // Open inventory reliably
            if (!(mc.currentScreen instanceof InventoryScreen)) {
                mc.setScreen(new InventoryScreen(mc.player));
            }
            inventoryOpened = true;
            return;
        }

        // Wait in inventory
        if (mc.currentScreen instanceof InventoryScreen) {
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            int totemSlot = getRandomTotemSlot();
            if (totemSlot != -1) {
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, totemSlot, 40, SlotActionType.SWAP, mc.player);
            }

            mc.player.closeHandledScreen();
            resetState();
        } else {
            // Inventory closed early
            resetState();
        }
    }

    private void resetState() {
        shouldRefill = false;
        inventoryOpened = false;
        delayTicks = 0;
        preOpenTicks = 0;
    }

    // Returns a randomized slot to swap totem from
    private int getRandomTotemSlot() {
        List<Integer> slots = getTotemSlots();
        if (slots.isEmpty()) return -1;
        Collections.shuffle(slots, random);
        return slots.get(0);
    }

    private List<Integer> getTotemSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                slots.add(i < 9 ? i + 36 : i);
            }
        }
        return slots;
    }
}
