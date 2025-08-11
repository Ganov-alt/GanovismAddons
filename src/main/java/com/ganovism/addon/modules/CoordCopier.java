package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;

/**
 * CoordCopier module
 * - auto-copy current coords to system clipboard (optional, interval in ticks)
 * - actions to copy current coords now, paste from clipboard into the notepad
 * - the "notepad" is stored as a String Setting and thus persists across relogs via Meteor's config system
 *
 * NOTE: If your environment blocks AWT clipboard access the copy/paste will warn and fail gracefully.
 */
public class CoordCopier extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoCopy = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-copy")
        .description("Automatically copy your current coordinates to clipboard on an interval.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> autoInterval = sgGeneral.add(new IntSetting.Builder()
        .name("auto-interval-ticks")
        .description("How many render frames between automatic copies when auto-copy is enabled.")
        .defaultValue(100)
        .min(1)
        .max(20_000)
        .build()
    );

    private final Setting<Boolean> copyOnEnable = sgGeneral.add(new BoolSetting.Builder()
        .name("copy-on-enable")
        .description("Copy your coordinates immediately when the module is enabled, then disable the module.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showToast = sgGeneral.add(new BoolSetting.Builder()
        .name("show-toast")
        .description("Show a small toast notification when a copy or paste happens.")
        .defaultValue(true)
        .build()
    );

    // Notepad (persisted setting)
    private final Setting<String> notes = sgGeneral.add(new StringSetting.Builder()
        .name("notepad")
        .description("Simple notepad area. You can paste coords from clipboard here.")
        .defaultValue("")
        .build()
    );

    // Actions (buttons in the module GUI) - implemented as momentary boolean toggles (pressed -> action runs -> reset)
    private final Setting<Boolean> copyNowButton = sgGeneral.add(new BoolSetting.Builder()
        .name("copy-now")
        .description("Press (toggle) to copy current coordinates to clipboard now. Resets automatically.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pasteClipboardButton = sgGeneral.add(new BoolSetting.Builder()
        .name("paste-clipboard")
        .description("Press to paste clipboard text into the notepad (appends). Resets automatically.")
        .defaultValue(false)
        .build()
    );

    // Removed appendCoordsButton and clearNotesButton from settings

    // internals
    private long tickCounter = 0;

    public CoordCopier() {
        super(GanovismAddon.CATEGORY, "coord-copier", "Automatically copy coords and keep a small persistent notepad.");
    }

    @Override
    public void onActivate() {
        tickCounter = 0;

        // If copy-on-enable is set, copy once and immediately disable the module.
        if (copyOnEnable.get() && mc.player != null) {
            copyCurrentCoordsToClipboard();
            toggle(); // disable module instantly
            return;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        tickCounter++;

        // handle momentary button toggles
        if (copyNowButton.get()) {
            copyCurrentCoordsToClipboard();
            copyNowButton.set(false);
        }

        // Removed appendCoordsButton handling

        if (pasteClipboardButton.get()) {
            pasteClipboardToNotes();
            pasteClipboardButton.set(false);
        }

        // Removed clearNotesButton handling

        if (autoCopy.get() && tickCounter % Math.max(1, autoInterval.get()) == 0) {
            copyCurrentCoordsToClipboard();
        }
    }

    private String formatCoords() {
        if (mc.player == null) return "";
        double x = mc.player.getX();
        double y = mc.player.getY();
        double z = mc.player.getZ();
        return String.format("%.2f %.2f %.2f", x, y, z);
    }

    private void copyCurrentCoordsToClipboard() {
        String coords = formatCoords();
        if (coords.isEmpty()) return;

        if (copyToClipboard(coords)) {
            if (showToast.get()) mc.getToastManager().add(new SystemToast(SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("CoordCopier"), Text.literal("Copied: " + coords)));
        } else {
            // Fallback: append coords horizontally spaced to the notes instead
            String current = notes.get();
            if (current == null || current.isEmpty()) {
                notes.set(coords);
            } else {
                notes.set(current + "  " + coords); // two spaces horizontally
            }
            ChatUtils.warning("CoordCopier: Could not copy to clipboard; appended coords to notepad instead.");
            if (showToast.get()) mc.getToastManager().add(new SystemToast(SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("CoordCopier"), Text.literal("Clipboard unavailable; appended coords to notes")));
        }
    }

    private void pasteClipboardToNotes() {
        String clip = readClipboard();
        if (clip == null || clip.isEmpty()) {
            ChatUtils.warning("CoordCopier: Clipboard empty or unavailable.");
            return;
        }
        String current = notes.get();
        if (current == null || current.isEmpty()) notes.set(clip);
        else notes.set(current + "\n" + clip);
        if (showToast.get()) mc.getToastManager().add(new SystemToast(SystemToast.Type.PERIODIC_NOTIFICATION, Text.literal("CoordCopier"), Text.literal("Pasted clipboard to notes")));
    }

    private boolean copyToClipboard(String text) {
        try {
            StringSelection selection = new StringSelection(text);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String readClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable data = clipboard.getContents(null);
            if (data != null && data.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                return (String) data.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Override
    public void onDeactivate() {
        // nothing special; notes Setting persists via Meteor's settings system
    }
}
