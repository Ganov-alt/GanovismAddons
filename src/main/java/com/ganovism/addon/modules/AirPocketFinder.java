package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

public class AirPocketFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Send a chat message when a pocket is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toastNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("toast-notify")
        .description("Show a toast notification when a pocket is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> batchSize = sgGeneral.add(new IntSetting.Builder()
        .name("scan-batch-size")
        .description("How many blocks to scan per render-frame tick in optimized mode. Lower -> less lag but slower scan.")
        .defaultValue(1500)
        .min(50)
        .max(10000)
        .build()
    );

    private final Setting<Boolean> bruteForce = sgGeneral.add(new BoolSetting.Builder()
        .name("brute-force")
        .description("If true, perform a full brute-force scan of the entire area (VERY LAGGY). Use with caution.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> bruteCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("brute-cooldown-ticks")
        .description("Minimum render frames between brute-force scans (prevents continuous catastrophic lag).")
        .defaultValue(200)
        .min(1)
        .max(2000)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to detected pockets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> tracerOffset = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-offset")
        .description("Forward distance from the camera along the look vector to start tracers.")
        .defaultValue(0.25)
        .min(0.0)
        .max(5.0)
        .build()
    );

    private final Setting<Double> tracerSmoothing = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-smoothing")
        .description("0 = no smoothing (instant). A small value can soften abrupt camera jumps (optional).")
        .defaultValue(0.0)
        .min(0.0)
        .max(1.0)
        .visible(tracers::get)
        .build()
    );

    private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
        .name("esp")
        .description("Draw ESP box around detected pockets.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("Color of the ESP box and tracer.")
        .defaultValue(new SettingColor(255, 200, 0, 120))
        .visible(esp::get)
        .build()
    );

    private final Set<BlockPos> foundPockets = new HashSet<>();
    private final Set<BlockPos> scanned = new HashSet<>();

    private int minX, maxX, minZ, maxZ, minY, maxY;
    private int curX, curY, curZ;
    private boolean scanPrepared = false;

    private long frameCounter = 0;
    private long lastBruteFrame = -Long.MAX_VALUE;

    private Vec3d lastTracerStart = null;

    public AirPocketFinder() {
        super(GanovismAddon.CATEGORY, "air-pocket-finder", "Detects 1x1 air pockets underground within render distance.");
    }

    private void prepareScanBounds() {
        if (mc.world == null || mc.player == null) return;

        int viewChunks = mc.options.getViewDistance().getValue();
        int dist = viewChunks * 16;
        BlockPos p = mc.player.getBlockPos();

        minX = p.getX() - dist;
        maxX = p.getX() + dist;
        minZ = p.getZ() - dist;
        maxZ = p.getZ() + dist;

        try {
            minY = mc.world.getBottomY();
        } catch (Throwable t) {
            minY = -64;
        }
        maxY = p.getY();

        curX = minX;
        curY = minY;
        curZ = minZ;

        scanned.clear();
        scanPrepared = true;
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        frameCounter++;
        if (!scanPrepared) prepareScanBounds();

        BlockPos p = mc.player.getBlockPos();
        int viewChunks = mc.options.getViewDistance().getValue();
        int dist = viewChunks * 16;

        if (p.getX() < minX + 8 || p.getX() > maxX - 8 || p.getZ() < minZ + 8 || p.getZ() > maxZ - 8) {
            prepareScanBounds();
            lastTracerStart = null;
        }

        if (bruteForce.get()) {
            if (frameCounter - lastBruteFrame >= Math.max(1, bruteCooldownTicks.get())) {
                lastBruteFrame = frameCounter;
                runBruteForceScan();
            }
        } else {
            int processed = 0;
            final int toProcess = Math.max(1, batchSize.get());

            while (processed < toProcess) {
                if (curY > maxY) break;

                BlockPos pos = new BlockPos(curX, curY, curZ);
                if (!scanned.contains(pos)) {
                    scanned.add(pos);
                    if (isAirPocket(pos)) {
                        if (foundPockets.add(pos)) {
                            if (chatNotify.get()) ChatUtils.info("AirPocketFinder", "Found 1x1 air pocket at " + pos.toShortString());
                            if (toastNotify.get()) {
                                mc.getToastManager().add(new SystemToast(
                                    SystemToast.Type.PERIODIC_NOTIFICATION,
                                    Text.literal("Air Pocket Found"),
                                    Text.literal(pos.toShortString())
                                ));
                            }
                        }
                    }
                }

                curX++;
                if (curX > maxX) {
                    curX = minX;
                    curZ++;
                    if (curZ > maxZ) {
                        curZ = minZ;
                        curY++;
                    }
                }
                processed++;
            }
        }

        for (BlockPos pos : foundPockets) {
            if (pos.getX() < p.getX() - dist || pos.getX() > p.getX() + dist || pos.getZ() < p.getZ() - dist || pos.getZ() > p.getZ() + dist) continue;
            if (esp.get()) event.renderer.box(pos, espColor.get(), espColor.get(), ShapeMode.Both, 0);

            if (tracers.get()) {
                Vec3d desiredStart = getCrosshairRayPoint(event.tickDelta);

                double smooth = tracerSmoothing.get();
                Vec3d start;
                if (lastTracerStart == null || smooth <= 0.0) {
                    start = desiredStart;
                } else {
                    if (lastTracerStart.distanceTo(desiredStart) > 5.0) start = desiredStart;
                    else start = lastTracerStart.multiply(1.0 - smooth).add(desiredStart.multiply(smooth));
                }
                lastTracerStart = start;

                Vec3d end = pos.toCenterPos();
                event.renderer.line(end.x, end.y, end.z, start.x, start.y, start.z, espColor.get());
            }
        }
    }

    private Vec3d getCrosshairRayPoint(float tickDelta) {
        Vec3d camPos = getCameraPos(tickDelta);
        Vec3d look = getCameraLookVec(tickDelta);
        double offset = Math.max(0.01, tracerOffset.get());
        return camPos.add(look.multiply(offset));
    }

    private Vec3d getCameraPos(float tickDelta) {
        try {
            if (mc.cameraEntity != null) return mc.cameraEntity.getCameraPosVec(tickDelta);
        } catch (Throwable ignored) {}
        return mc.player.getCameraPosVec(tickDelta);
    }

    private Vec3d getCameraLookVec(float tickDelta) {
        try {
            if (mc.cameraEntity != null) return mc.cameraEntity.getRotationVec(tickDelta).normalize();
        } catch (Throwable ignored) {}
        return mc.player.getRotationVec(tickDelta).normalize();
    }

    private void runBruteForceScan() {
        if (mc.world == null) return;
        if (!scanPrepared) prepareScanBounds();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (scanned.contains(pos)) continue;
                    scanned.add(pos);

                    if (isAirPocket(pos)) {
                        if (foundPockets.add(pos)) {
                            if (chatNotify.get()) ChatUtils.info("AirPocketFinder", "Found 1x1 air pocket at " + pos.toShortString());
                            if (toastNotify.get()) {
                                mc.getToastManager().add(new SystemToast(
                                    SystemToast.Type.PERIODIC_NOTIFICATION,
                                    Text.literal("Air Pocket Found"),
                                    Text.literal(pos.toShortString())
                                ));
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isAirPocket(BlockPos pos) {
        try {
            if (!mc.world.getBlockState(pos).isOf(Blocks.AIR)) return false;
            for (Direction dir : Direction.values()) {
                if (!mc.world.getBlockState(pos.offset(dir)).isOpaque()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDeactivate() {
        foundPockets.clear();
        scanned.clear();
        scanPrepared = false;
        frameCounter = 0;
        lastBruteFrame = -Long.MAX_VALUE;
        lastTracerStart = null;
    }
}
