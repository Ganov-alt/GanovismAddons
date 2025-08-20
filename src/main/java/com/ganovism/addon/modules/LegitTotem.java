package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Items;
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
        .description("Ticks to wait in strict mode before swapping totem (inventory -> offhand).")
        .defaultValue(10)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> randomMin = sg.add(new IntSetting.Builder()
        .name("random-min-delay")
        .description("Minimum ticks to wait in random mode before swapping totem (inventory -> offhand).")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 40)
        .build()
    );

    private final Setting<Integer> randomMax = sg.add(new IntSetting.Builder()
        .name("random-max-delay")
        .description("Maximum ticks to wait in random mode before swapping totem (inventory -> offhand).")
        .defaultValue(15)
        .min(1)
        .sliderRange(1, 60)
        .build()
    );

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

    private final Setting<Boolean> doubleTotem = sg.add(new BoolSetting.Builder()
        .name("double-totem")
        .description("Prefer equipping a hotbar totem first, then refill offhand with a different totem.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> switchBack = sg.add(new BoolSetting.Builder()
        .name("switch-back")
        .description("When enabled (and double-totem is on), switch back to your original hotbar slot after a delay.")
        .visible(doubleTotem::get)
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> equipDelayMin = sg.add(new IntSetting.Builder()
        .name("equip-delay-min")
        .description("Min ticks to wait AFTER equipping main-hand hotbar totem, BEFORE opening inventory to refill offhand.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .visible(doubleTotem::get)
        .build()
    );

    private final Setting<Integer> equipDelayMax = sg.add(new IntSetting.Builder()
        .name("equip-delay-max")
        .description("Max ticks to wait AFTER equipping main-hand hotbar totem, BEFORE opening inventory to refill offhand.")
        .defaultValue(3)
        .min(0)
        .sliderRange(0, 20)
        .visible(doubleTotem::get)
        .build()
    );

    private final Setting<Integer> dequipDelayMin = sg.add(new IntSetting.Builder()
        .name("dequip-delay-min")
        .description("Min ticks to wait AFTER inventory is closed before switching back to your original hotbar slot.")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 40)
        .visible(doubleTotem::get)
        .build()
    );

    private final Setting<Integer> dequipDelayMax = sg.add(new IntSetting.Builder()
        .name("dequip-delay-max")
        .description("Max ticks to wait AFTER inventory is closed before switching back to your original hotbar slot.")
        .defaultValue(10)
        .min(0)
        .sliderRange(0, 80)
        .visible(doubleTotem::get)
        .build()
    );

    private final Setting<Boolean> superSneaky = sg.add(new BoolSetting.Builder()
        .name("super-sneaky")
        .description("Adds extra randomness, jitter and tiny 'fumble' chances so behavior looks more human (recommended).")
        .defaultValue(false)
        .build()
    );

    private enum Mode { Strict, Random }

    private int delayTicks = 0;
    private int preOpenTicks = 0;
    private boolean shouldRefill = false;
    private boolean inventoryOpened = false;

    private boolean swappedToOffhand = false;
    private boolean equipScheduled = false;
    private int pendingEquipTicks = 0;

    private boolean dequipScheduled = false;
    private int pendingDequipTicks = 0;
    private int originalHotbarSlot = -1;
    private int hotbarSlotUsedForMain = -1;

    private boolean closeScheduled = false;
    private int pendingCloseTicks = 0;

    private final Random random = new Random();

    public LegitTotem() {
        super(GanovismAddon.CATEGORY, "legit-totem", "Refills totem into offhand with legit-like delay and slot variability.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // Handle dequip scheduling (switch back after inv closed)
        if (dequipScheduled && !(mc.currentScreen instanceof InventoryScreen)) {
            if (pendingDequipTicks > 0) {
                pendingDequipTicks--;
            } else {
                if (originalHotbarSlot >= 0 && originalHotbarSlot <= 8) {
                    mc.player.getInventory().setSelectedSlot(originalHotbarSlot);
                }
                dequipScheduled = false;
                pendingDequipTicks = 0;
                originalHotbarSlot = -1;
            }
        }

        // Handle scheduled close
        if (closeScheduled) {
            if (pendingCloseTicks > 0) {
                pendingCloseTicks--;
            } else {
                mc.player.closeHandledScreen();
                closeScheduled = false;
                pendingCloseTicks = 0;
                resetRefillState();
            }
        }

        // 🔑 Trigger when offhand has no totem & inv has one
        if (!shouldRefill
            && mc.player.getOffHandStack().getItem() != Items.TOTEM_OF_UNDYING
            && hasTotemInInventory()) {

            int basePre = preOpenMax.get() >= preOpenMin.get()
                ? random.nextInt(preOpenMax.get() - preOpenMin.get() + 1) + preOpenMin.get()
                : preOpenMin.get();

            if (superSneaky.get()) {
                basePre += random.nextInt(2);
                if (random.nextInt(100) < 3) basePre += 10 + random.nextInt(15);
            }

            preOpenTicks = basePre;
            shouldRefill = true;
            inventoryOpened = false;

            delayTicks = mode.get() == Mode.Strict
                ? strictDelay.get()
                : getRandomInRange(randomMin.get(), randomMax.get());

            if (superSneaky.get()) delayTicks += random.nextInt(3);

            swappedToOffhand = false;
            equipScheduled = false;
            pendingEquipTicks = 0;
            closeScheduled = false;
            pendingCloseTicks = 0;
            hotbarSlotUsedForMain = -1;
        }

        if (!shouldRefill) return;

        // If offhand already has totem, abort
        if (mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING) {
            resetRefillState();
            return;
        }

        // --- Step 1: Equip main-hand totem BEFORE opening inventory
        if (doubleTotem.get() && hotbarSlotUsedForMain == -1) {
            originalHotbarSlot = mc.player.getInventory().getSelectedSlot();
            int existingHotbar = getHotbarTotemSlot();
            if (existingHotbar != -1) {
                mc.player.getInventory().setSelectedSlot(existingHotbar);
                hotbarSlotUsedForMain = existingHotbar;
                int eq = getRandomInRange(equipDelayMin.get(), equipDelayMax.get());
                if (superSneaky.get()) eq += random.nextInt(2);
                if (eq > 0) {
                    equipScheduled = true;
                    pendingEquipTicks = eq;
                }
            }
        }

        // --- Step 2: Pre-open delay (includes waiting after main equip)
        if (!inventoryOpened) {
            if (equipScheduled && pendingEquipTicks > 0) {
                pendingEquipTicks--;
                return;
            }
            equipScheduled = false;

            if (preOpenTicks > 0) {
                preOpenTicks--;
                return;
            }
            if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
            inventoryOpened = true;
            return;
        }

        // --- Step 3: Inside inventory, wait before offhand swap
        if (mc.currentScreen instanceof InventoryScreen) {
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            if (!swappedToOffhand) {
                int offhandSource = getRandomTotemSlotAvoidingHotbarSlot(true, hotbarSlotUsedForMain);
                if (offhandSource == -1) offhandSource = getRandomTotemSlotAvoidingHotbarSlot(false, hotbarSlotUsedForMain);

                if (offhandSource == -1) {
                    if (superSneaky.get()) {
                        closeScheduled = true;
                        pendingCloseTicks = 1 + random.nextInt(3);
                    } else {
                        mc.player.closeHandledScreen();
                    }
                    resetRefillState();
                    return;
                }

                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, offhandSource, 40, SlotActionType.SWAP, mc.player);
                swappedToOffhand = true;

                // --- Step 4: Close inventory
                if (doubleTotem.get() && switchBack.get()) {
                    pendingDequipTicks = getRandomInRange(dequipDelayMin.get(), dequipDelayMax.get());
                    dequipScheduled = true;
                }

                if (superSneaky.get()) {
                    closeScheduled = true;
                    pendingCloseTicks = 1 + random.nextInt(4);
                } else {
                    mc.player.closeHandledScreen();
                    resetRefillState();
                }
                return;
            }
        } else {
            resetRefillState();
        }
    }

    private boolean hasTotemInInventory() {
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                return true;
            }
        }
        return false;
    }

    private int getRandomInRange(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        if (max == min) return min;
        return random.nextInt(max - min + 1) + min;
    }

    private void resetRefillState() {
        shouldRefill = false;
        inventoryOpened = false;
        delayTicks = 0;
        preOpenTicks = 0;
        swappedToOffhand = false;
        equipScheduled = false;
        pendingEquipTicks = 0;
        hotbarSlotUsedForMain = -1;
    }

    private int getRandomTotemSlot(boolean excludeHotbar) {
        List<Integer> slots = new ArrayList<>();
        int invSize = mc.player.getInventory().size();
        for (int i = 0; i < invSize; i++) {
            if (excludeHotbar && i < 9) continue;
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                slots.add(i < 9 ? i + 36 : i);
            }
        }
        if (slots.isEmpty()) return -1;
        Collections.shuffle(slots, random);
        return slots.get(0);
    }

    private int getRandomTotemSlotAvoidingHotbarSlot(boolean excludeHotbar, int avoidHotbarIndex) {
        List<Integer> slots = new ArrayList<>();
        int invSize = mc.player.getInventory().size();
        for (int i = 0; i < invSize; i++) {
            if (excludeHotbar && i < 9) continue;
            if (mc.player.getInventory().getStack(i).getItem() == Items.TOTEM_OF_UNDYING) {
                int containerSlot = i < 9 ? i + 36 : i;
                if (avoidHotbarIndex >= 0 && i < 9 && i == avoidHotbarIndex) continue;
                slots.add(containerSlot);
            }
        }
        if (slots.isEmpty()) return -1;
        Collections.shuffle(slots, random);
        return slots.get(0);
    }

    private int getHotbarTotemSlot() {
        for (int hotbarIndex = 0; hotbarIndex < 9; hotbarIndex++) {
            if (mc.player.getInventory().getStack(hotbarIndex).getItem() == Items.TOTEM_OF_UNDYING) {
                return hotbarIndex;
            }
        }
        return -1;
    }
}
