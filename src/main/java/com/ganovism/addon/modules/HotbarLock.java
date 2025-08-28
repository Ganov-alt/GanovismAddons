package com.ganovism.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.meteor.KeyEvent;
import meteordevelopment.meteorclient.events.meteor.MouseButtonEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

/**
 * HotbarLock for Meteor (1.21.8). Semi lock:
 *  - DOES NOT allow moving items out of hotbar to main inventory.
 *  - ALLOWS placing items into empty hotbar slots and using currently held slot.
 *
 * Legit mode: fully client-side, cancels only inputs that move items in the hotbar.
 */
public class HotbarLock extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Method {
        Legit, Packet
    }

    public enum Lock {
        Off, Semi, Full
    }

    private final Setting<Method> method = sgGeneral.add(new EnumSetting.Builder<Method>()
        .name("method")
        .description("How the hotbar lock blocks changes: client-side (Legit) or by cancelling packets (Packet).")
        .defaultValue(Method.Legit)
        .build()
    );

    private final Setting<Lock> lock = sgGeneral.add(new EnumSetting.Builder<Lock>()
        .name("lock")
        .description("Which hotbar lock level to use.")
        .defaultValue(Lock.Off)
        .build()
    );

    private final Setting<Boolean> noDrop = sgGeneral.add(new BoolSetting.Builder()
        .name("no-drop")
        .description("Prevents dropping items from the hotbar with Q / Ctrl+Q / middle click.")
        .defaultValue(false)
        .build()
    );

    public HotbarLock() {
        super(com.ganovism.addon.GanovismAddon.CATEGORY, "hotbar-lock", "Prevents items in your hotbar from being moved or replaced.");
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (lock.get() == Lock.Off) return;
        if (method.get() == Method.Legit) return; // Legit: fully client-side, do not cancel packets

        Object pkt = event.packet;

        try {
            if (pkt instanceof ClickSlotC2SPacket click) {
                int s = click.slot();
                if (!isHotbarSlot(s)) return;

                int selected = getSelectedSlotSafe();
                if (s == selected) return; // allow actions with currently held slot

                ItemStack cur = mc.player != null ? mc.player.getInventory().getStack(s) : ItemStack.EMPTY;

                if (lock.get() == Lock.Full) {
                    event.cancel();
                } else if (lock.get() == Lock.Semi) {
                    if (!cur.isEmpty()) event.cancel();
                }
            }
        } catch (Throwable ignored) {}

        // Reflection fallback for packets with slot fields
        try {
            Field[] fields = pkt.getClass().getDeclaredFields();
            for (Field f : fields) {
                if (!(f.getType() == int.class || f.getType() == short.class || f.getType() == byte.class)) continue;
                f.setAccessible(true);
                Object valObj = f.get(pkt);
                if (!(valObj instanceof Number)) continue;
                int v = ((Number) valObj).intValue();
                if (!isHotbarSlot(v)) continue;

                int selected = getSelectedSlotSafe();
                if (v == selected) return; // allow currently held

                ItemStack cur = mc.player != null ? mc.player.getInventory().getStack(v) : ItemStack.EMPTY;
                if (lock.get() == Lock.Full) event.cancel();
                else if (lock.get() == Lock.Semi && !cur.isEmpty()) event.cancel();
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler
    private void onKey(KeyEvent event) {
        if (!isPressAction(event)) return;

        // Handle no-drop
        if (noDrop.get() && event.key == GLFW.GLFW_KEY_Q) {
            int sel = getSelectedSlotSafe();
            if (isHotbarSlot(sel)) event.cancel();
        }

        // Number keys 1-9
        if (event.key >= GLFW.GLFW_KEY_1 && event.key <= GLFW.GLFW_KEY_9) {
            int targetIndex = event.key - GLFW.GLFW_KEY_1;
            int selected = getSelectedSlotSafe();
            boolean inventoryOpen = mc.currentScreen instanceof HandledScreen;

            // Full lock
            if (lock.get() == Lock.Full) {
                if (method.get() == Method.Legit && !inventoryOpen) return; // allow selection outside GUI
                event.cancel();
                return;
            }

            // Semi lock
            if (lock.get() == Lock.Semi) {
                if (inventoryOpen) {
                    ItemStack target = mc.player.getInventory().getStack(targetIndex);
                    if (!target.isEmpty()) event.cancel(); // block swap into occupied slot
                }
                // Outside GUI: always allow selection
                else return;
            }
        }
    }

    @EventHandler
    private void onMouse(MouseButtonEvent event) {
        if (!isPressAction(event)) return;

        // No-drop middle click
        if (noDrop.get() && event.button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            int sel = getSelectedSlotSafe();
            if (isHotbarSlot(sel)) event.cancel();
        }

        if (method.get() == Method.Legit && lock.get() == Lock.Full) {
            boolean inventoryOpen = mc.currentScreen instanceof HandledScreen;
            if (inventoryOpen) event.cancel();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Reserved for future client-side slot checks
    }

    // Helpers
    private boolean isPressAction(Object event) {
        try {
            Field actionField = event.getClass().getDeclaredField("action");
            actionField.setAccessible(true);
            Object actionVal = actionField.get(event);

            if (actionVal instanceof Number) return ((Number) actionVal).intValue() == GLFW.GLFW_PRESS;
            if (actionVal instanceof Enum<?>) {
                String name = ((Enum<?>) actionVal).name().toUpperCase();
                return name.equals("PRESS") || name.equals("PRESSED") || name.equals("DOWN");
            }
            if (actionVal instanceof Boolean) return (Boolean) actionVal;

            String s = actionVal.toString().toUpperCase();
            return s.contains("PRESS") || s.contains("PRESSED") || s.contains("DOWN");
        } catch (Throwable ignored) {}
        return false;
    }

    private int getSelectedSlotSafe() {
        try {
            return mc.player.getInventory().getSelectedSlot();
        } catch (Throwable t) {
            return -1;
        }
    }

    private boolean isHotbarSlot(int slot) {
        return slot >= 0 && slot <= 8;
    }
}
