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

    // Double totem toggle
    private final Setting<Boolean> doubleTotem = sg.add(new BoolSetting.Builder()
        .name("double-totem")
        .description("Prefer equipping a hotbar totem first, then refill offhand with a different totem.")
        .defaultValue(false)
        .build()
    );

    // Switch-back toggle (visible only when double-totem is on)
    private final Setting<Boolean> switchBack = sg.add(new BoolSetting.Builder()
        .name("switch-back")
        .description("When enabled (and double-totem is on), switch back to your original hotbar slot after a delay.")
        .visible(doubleTotem::get)
        .defaultValue(true)
        .build()
    );

    // Equip/dequip randomizers for double-totem
    private final Setting<Integer> equipDelayMin = sg.add(new IntSetting.Builder()
        .name("equip-delay-min")
        .description("Min ticks to wait (while in inventory) AFTER equipping main-hand hotbar totem, BEFORE swapping a different totem to offhand.")
        .defaultValue(0)
        .min(0)
        .sliderRange(0, 10)
        .visible(doubleTotem::get)
        .build()
    );

    private final Setting<Integer> equipDelayMax = sg.add(new IntSetting.Builder()
        .name("equip-delay-max")
        .description("Max ticks to wait (while in inventory) AFTER equipping main-hand hotbar totem, BEFORE swapping a different totem to offhand.")
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

    // NEW: Super sneaky mode toggle
    private final Setting<Boolean> superSneaky = sg.add(new BoolSetting.Builder()
        .name("super-sneaky")
        .description("Adds extra randomness, jitter and tiny 'fumble' chances so behavior looks more human (recommended).")
        .defaultValue(false)
        .build()
    );

    private enum Mode { Strict, Random }

    // delayTicks = inventory -> offhand delay (strict/random)
    private int delayTicks = 0;
    // preOpenTicks = before opening inventory
    private int preOpenTicks = 0;
    private boolean shouldRefill = false;
    private boolean inventoryOpened = false;

    // Double-totem scheduling states
    private boolean swappedToOffhand = false;
    private boolean equipScheduled = false;
    private int pendingEquipTicks = 0;

    private boolean dequipScheduled = false;    // scheduled to switch back outside inventory
    private int pendingDequipTicks = 0;
    private int originalHotbarSlot = -1;        // store original slot to switch back to
    private int hotbarSlotUsedForMain = -1;     // the hotbar index (0..8) we used for main-hand totem, -1 none

    // close scheduling (small random delay before close when superSneaky enabled)
    private boolean closeScheduled = false;
    private int pendingCloseTicks = 0;

    // small adaptive backoff: if many pops happen quickly, back off briefly
    private int recentPops = 0;
    private int backoffTicks = 0;

    private final Random random = new Random();

    public LegitTotem() {
        super(GanovismAddon.CATEGORY, "legit-totem", "Refills totem into offhand with legit-like delay and slot variability.");
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof EntityStatusS2CPacket p) {
            if (p.getStatus() == 35 && p.getEntity(mc.world) == mc.player) {
                // adaptive backoff: if many pops recently, add extra delay and increase jitter
                if (recentPops > 3) {
                    backoffTicks = 40 + random.nextInt(20); // back off 40-60 ticks
                }
                recentPops++;

                // Randomize pre-open delay between min and max (preOpenMin/Max)
                int basePre = preOpenMax.get() >= preOpenMin.get()
                    ? random.nextInt(preOpenMax.get() - preOpenMin.get() + 1) + preOpenMin.get()
                    : preOpenMin.get();

                // apply superSneaky jitter / long-tail occasionally
                if (superSneaky.get()) {
                    basePre += random.nextInt(2); // add 0..1 tick jitter
                    if (random.nextInt(100) < 3) basePre += 10 + random.nextInt(15); // rare long-tail
                }

                preOpenTicks = basePre + backoffTicks;
                shouldRefill = true;
                inventoryOpened = false;

                // delayTicks controls inventory -> offhand delay (strict/random)
                delayTicks = mode.get() == Mode.Strict
                    ? strictDelay.get()
                    : (randomMax.get() >= randomMin.get() ? random.nextInt(randomMax.get() - randomMin.get() + 1) + randomMin.get() : randomMin.get());

                if (superSneaky.get()) delayTicks += random.nextInt(3); // small jitter

                // reset per-refill transient states
                swappedToOffhand = false;
                equipScheduled = false;
                pendingEquipTicks = 0;
                closeScheduled = false;
                pendingCloseTicks = 0;
                hotbarSlotUsedForMain = -1;
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null) return;

        // decay counters
        if (backoffTicks > 0) backoffTicks--;
        if (recentPops > 0) recentPops = Math.max(0, recentPops - 1);

        // Handle dequip (switch back) scheduling when NOT in inventory
        if (dequipScheduled && !(mc.currentScreen instanceof InventoryScreen)) {
            if (pendingDequipTicks > 0) {
                pendingDequipTicks--;
            } else {
                // time to switch back
                if (originalHotbarSlot >= 0 && originalHotbarSlot <= 8) {
                    mc.player.getInventory().setSelectedSlot(originalHotbarSlot);
                }
                dequipScheduled = false;
                pendingDequipTicks = 0;
                originalHotbarSlot = -1;
            }
        }

        // Handle pending close if scheduled (close after a small delay)
        if (closeScheduled) {
            if (pendingCloseTicks > 0) {
                pendingCloseTicks--;
            } else {
                mc.player.closeHandledScreen();
                closeScheduled = false;
                pendingCloseTicks = 0;
                resetRefillState(); // clear refill state (keeps dequip scheduling)
            }
        }

        if (!shouldRefill) return;

        // If offhand already has a totem, abort
        if (mc.player.getOffHandStack().getItem() == Items.TOTEM_OF_UNDYING) {
            resetRefillState();
            return;
        }

        // Wait before opening inventory (pre-open)
        if (!inventoryOpened) {
            if (backoffTicks > 0) {
                if (preOpenTicks > 0) preOpenTicks--;
                return;
            }
            if (preOpenTicks > 0) {
                preOpenTicks--;
                return;
            }
            // Open inventory reliably
            if (!(mc.currentScreen instanceof InventoryScreen)) mc.setScreen(new InventoryScreen(mc.player));
            inventoryOpened = true;
            return;
        }

        // Inventory is open: FIRST wait inventory -> offhand delay (delayTicks)
        if (mc.currentScreen instanceof InventoryScreen) {
            if (delayTicks > 0) {
                delayTicks--;
                return;
            }

            // -- Step A: Ensure main-hand is a hotbar totem if doubleTotem is enabled --
            if (doubleTotem.get() && hotbarSlotUsedForMain == -1) {
                // record original slot for switch-back (store before selecting new slot)
                originalHotbarSlot = mc.player.getInventory().getSelectedSlot();

                // Fast path: if there's already a hotbar totem, select it
                int existingHotbar = getHotbarTotemSlot();
                if (existingHotbar != -1) {
                    mc.player.getInventory().setSelectedSlot(existingHotbar);
                    hotbarSlotUsedForMain = existingHotbar;
                    // apply equip delay (equipDelayMin/Max) BEFORE proceeding to offhand swap
                    int eq = getRandomInRange(equipDelayMin.get(), equipDelayMax.get());
                    if (superSneaky.get()) eq += random.nextInt(2);
                    if (eq > 0) {
                        equipScheduled = true;
                        pendingEquipTicks = eq;
                        return;
                    }
                    // else proceed immediately
                } else {
                    // No hotbar totem: try moving one from inventory into current selected hotbar slot
                    int source = getRandomTotemSlot(true); // prefer inventory
                    if (source != -1) {
                        int targetHotbarIndex = mc.player.getInventory().getSelectedSlot();
                        int targetContainerSlot = targetHotbarIndex + 36;
                        try {
                            int windowId = mc.player.currentScreenHandler.syncId;
                            mc.interactionManager.clickSlot(windowId, source, 0, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(windowId, targetContainerSlot, 0, SlotActionType.PICKUP, mc.player);
                            mc.interactionManager.clickSlot(windowId, source, 0, SlotActionType.PICKUP, mc.player);
                            // now select the hotbar slot
                            mc.player.getInventory().setSelectedSlot(targetHotbarIndex);
                            hotbarSlotUsedForMain = targetHotbarIndex;
                            int eq = getRandomInRange(equipDelayMin.get(), equipDelayMax.get());
                            if (superSneaky.get()) eq += random.nextInt(2);
                            if (eq > 0) {
                                equipScheduled = true;
                                pendingEquipTicks = eq;
                                return;
                            }
                        } catch (Exception ignored) {
                            // fallback: try any hotbar totem if moving failed
                            int any = getHotbarTotemSlot();
                            if (any != -1) {
                                mc.player.getInventory().setSelectedSlot(any);
                                hotbarSlotUsedForMain = any;
                                int eq = getRandomInRange(equipDelayMin.get(), equipDelayMax.get());
                                if (superSneaky.get()) eq += random.nextInt(2);
                                if (eq > 0) {
                                    equipScheduled = true;
                                    pendingEquipTicks = eq;
                                    return;
                                }
                            } else {
                                // human-like fumble or abort
                                if (superSneaky.get()) {
                                    closeScheduled = true;
                                    pendingCloseTicks = 1 + random.nextInt(3);
                                } else {
                                    mc.player.closeHandledScreen();
                                }
                                resetRefillState();
                                return;
                            }
                        }
                    } else {
                        // no inventory totem to move: attempt switching to any hotbar totem (last resort)
                        int anyHotbar = getHotbarTotemSlot();
                        if (anyHotbar != -1) {
                            mc.player.getInventory().setSelectedSlot(anyHotbar);
                            hotbarSlotUsedForMain = anyHotbar;
                            int eq = getRandomInRange(equipDelayMin.get(), equipDelayMax.get());
                            if (superSneaky.get()) eq += random.nextInt(2);
                            if (eq > 0) {
                                equipScheduled = true;
                                pendingEquipTicks = eq;
                                return;
                            }
                        } else {
                            // No totems at all -> abort gracefully
                            if (superSneaky.get()) {
                                closeScheduled = true;
                                pendingCloseTicks = 1 + random.nextInt(3);
                            } else {
                                mc.player.closeHandledScreen();
                            }
                            resetRefillState();
                            return;
                        }
                    }
                }
            }

            // -- If an equip to hotbar was scheduled, wait here --
            if (equipScheduled) {
                if (pendingEquipTicks > 0) {
                    pendingEquipTicks--;
                    return;
                }
                equipScheduled = false;
                pendingEquipTicks = 0;
                // proceed to offhand refill
            }

            // -- Step B: Refill offhand using a different totem --
            if (!swappedToOffhand) {
                int offhandSource = getRandomTotemSlotAvoidingHotbarSlot(true, hotbarSlotUsedForMain);
                if (offhandSource == -1) {
                    offhandSource = getRandomTotemSlotAvoidingHotbarSlot(false, hotbarSlotUsedForMain);
                }

                if (offhandSource == -1) {
                    // nothing to refill from
                    if (superSneaky.get()) {
                        closeScheduled = true;
                        pendingCloseTicks = 1 + random.nextInt(3);
                    } else {
                        mc.player.closeHandledScreen();
                    }
                    resetRefillState();
                    return;
                }

                // perform the swap into offhand
                mc.interactionManager.clickSlot(mc.player.currentScreenHandler.syncId, offhandSource, 40, SlotActionType.SWAP, mc.player);
                swappedToOffhand = true;

                // after swapping, schedule switch-back if requested
                if (doubleTotem.get() && switchBack.get()) {
                    pendingDequipTicks = getRandomInRange(dequipDelayMin.get(), dequipDelayMax.get());
                    dequipScheduled = true;
                }

                // close inventory (with small delay if superSneaky)
                if (superSneaky.get()) {
                    closeScheduled = true;
                    pendingCloseTicks = 1 + random.nextInt(4);
                } else {
                    mc.player.closeHandledScreen();
                    resetRefillState();
                }
                return;
            }

            // fallback safety
            resetRefillState();
        } else {
            // Inventory closed prematurely
            resetRefillState();
        }
    }

    private int getRandomInRange(int a, int b) {
        int min = Math.min(a, b);
        int max = Math.max(a, b);
        if (max == min) return min;
        return random.nextInt(max - min + 1) + min;
    }

    /**
     * Reset transient refill state but keep dequip scheduling intact so we can switch back after leaving inventory.
     */
    private void resetRefillState() {
        shouldRefill = false;
        inventoryOpened = false;
        delayTicks = 0;
        preOpenTicks = 0;
        swappedToOffhand = false;
        equipScheduled = false;
        pendingEquipTicks = 0;
        hotbarSlotUsedForMain = -1;
        // note: do not clear dequipScheduled/pendingDequipTicks/originalHotbarSlot here
    }

    // Returns a randomized slot to swap totem from.
    // If excludeHotbar == true, prefers totems not in hotbar (i >= 9).
    // Returned slot is a container window slot id (hotbar mapped to 36..44).
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

    // Like getRandomTotemSlot but avoids a specific hotbar index (0..8) if provided.
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

    // Find a hotbar slot index (0..8) that contains a totem. Returns -1 if none.
    private int getHotbarTotemSlot() {
        for (int hotbarIndex = 0; hotbarIndex < 9; hotbarIndex++) {
            if (mc.player.getInventory().getStack(hotbarIndex).getItem() == Items.TOTEM_OF_UNDYING) {
                return hotbarIndex;
            }
        }
        return -1;
    }
}
