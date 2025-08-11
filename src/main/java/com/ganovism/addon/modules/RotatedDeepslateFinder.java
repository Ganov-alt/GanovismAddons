package com.ganovism.addon.modules;

import com.ganovism.addon.GanovismAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.client.toast.SystemToast;

import java.util.HashSet;
import java.util.Set;

public class RotatedDeepslateFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgOptimizations = settings.createGroup("Optimizations");

    private final Setting<Boolean> chatNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Send a chat message when a rotated deepslate block is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> toastNotify = sgGeneral.add(new BoolSetting.Builder()
        .name("toast-notify")
        .description("Show a toast notification when a rotated deepslate block is found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> batchSize = sgGeneral.add(new IntSetting.Builder()
        .name("scan-batch-size")
        .description("How many blocks to scan per render-frame tick in optimized mode.")
        .defaultValue(1500)
        .min(50)
        .max(10000)
        .build()
    );

    private final Setting<Boolean> bruteForce = sgGeneral.add(new BoolSetting.Builder()
        .name("brute-force")
        .description("If true, perform a full brute-force scan immediately (VERY LAGGY).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> bruteCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("brute-cooldown-ticks")
        .description("Minimum ticks between brute force scans.")
        .defaultValue(200)
        .min(1)
        .max(2000)
        .build()
    );

    // Rescan delay default to 300 (5 minutes)
    private final Setting<Integer> rescanDelaySeconds = sgGeneral.add(new IntSetting.Builder()
        .name("rescan-delay")
        .description("How often (in seconds) to rescan and clear previous results.")
        .defaultValue(300) // 5 minutes
        .min(1)
        .max(3600)
        .build()
    );

    // Optimization: stop scanning column after 20 air blocks (default OFF)
    private final Setting<Boolean> stopAfter20AirBlocks = sgOptimizations.add(new BoolSetting.Builder()
        .name("stop-after-20-air-blocks")
        .description("Stop scanning a column upwards if 20 consecutive air blocks found without rotated deepslate.")
        .defaultValue(false)
        .build()
    );

    // Show old cache during rescan (default OFF)
    private final Setting<Boolean> showOldCacheWhileRescanning = sgGeneral.add(new BoolSetting.Builder()
        .name("show-old-cache-while-rescanning")
        .description("Continue rendering previous found blocks while rescanning.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> tracers = sgRender.add(new BoolSetting.Builder()
        .name("tracers")
        .description("Draw tracers to detected rotated deepslate blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> tracerOffset = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-offset")
        .description("Offset from camera for tracers.")
        .defaultValue(0.25)
        .min(0)
        .max(1)
        .build()
    );

    private final Setting<Double> tracerSmoothing = sgRender.add(new DoubleSetting.Builder()
        .name("tracer-smoothing")
        .description("Smoothing for tracers (0 = instant).")
        .defaultValue(0)
        .min(0)
        .max(1)
        .visible(tracers::get)
        .build()
    );

    private final Setting<Boolean> esp = sgRender.add(new BoolSetting.Builder()
        .name("esp")
        .description("Draw ESP box around detected blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> espColor = sgRender.add(new ColorSetting.Builder()
        .name("esp-color")
        .description("ESP box and tracer color.")
        .defaultValue(new SettingColor(0, 200, 255, 120))
        .visible(esp::get)
        .build()
    );

    private final Set<BlockPos> foundBlocks = new HashSet<>();
    private final Set<BlockPos> scanned = new HashSet<>();

    private int minX, maxX, minY, maxY, minZ, maxZ;
    private int curX, curY, curZ;
    private boolean scanPrepared = false;

    private long frameCounter = 0;
    private long lastBruteFrame = -Long.MAX_VALUE;

    private Vec3d lastTracerStart = null;

    private int ticksSinceLastRescan = 0;
    private boolean isRescanning = false;

    public RotatedDeepslateFinder() {
        super(GanovismAddon.CATEGORY, "rotated-deepslate-finder", "Detects rotated deepslate blocks underground.");
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
        ticksSinceLastRescan++;

        if (!scanPrepared) prepareScanBounds();

        int rescanTicks = rescanDelaySeconds.get() * 20;
        if (ticksSinceLastRescan >= rescanTicks) {
            ticksSinceLastRescan = 0;
            isRescanning = true;

            if (!showOldCacheWhileRescanning.get()) {
                foundBlocks.clear();
            }

            scanned.clear();
            prepareScanBounds();

            lastBruteFrame = frameCounter;

            ChatUtils.info("RotatedDeepslateFinder", "Starting rescan for rotated deepslate...");
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int viewChunks = mc.options.getViewDistance().getValue();
        int dist = viewChunks * 16;

        // Recenter scan if player moves near edges
        if (playerPos.getX() < minX + 8 || playerPos.getX() > maxX - 8 || playerPos.getZ() < minZ + 8 || playerPos.getZ() > maxZ - 8) {
            prepareScanBounds();
            lastTracerStart = null;
        }

        if (bruteForce.get()) {
            if (frameCounter - lastBruteFrame >= bruteCooldownTicks.get()) {
                lastBruteFrame = frameCounter;
                runBruteForceScan();
                isRescanning = false;
            }
        } else {
            int processed = 0;
            int toProcess = Math.max(1, batchSize.get());

            boolean skipColumn = false;
            int airBlocksCount = 0;

            while (processed < toProcess) {
                if (curY > maxY) break;

                BlockPos pos = new BlockPos(curX, curY, curZ);

                if (!scanned.contains(pos)) {
                    scanned.add(pos);

                    // Optimization: if enabled, stop scanning up column after 20 air blocks
                    if (stopAfter20AirBlocks.get()) {
                        BlockState state = mc.world.getBlockState(pos);
                        if (state.isAir()) {
                            airBlocksCount++;
                        } else {
                            airBlocksCount = 0;
                        }

                        if (airBlocksCount >= 20) {
                            // skip rest of this vertical column
                            airBlocksCount = 0;

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
                            continue;
                        }
                    }

                    if (isRotatedDeepslate(pos)) {
                        if (foundBlocks.add(pos)) {
                            if (chatNotify.get()) ChatUtils.info("RotatedDeepslateFinder", "Found rotated deepslate at " + pos.toShortString());
                            if (toastNotify.get()) {
                                mc.getToastManager().add(new SystemToast(
                                    SystemToast.Type.PERIODIC_NOTIFICATION,
                                    Text.literal("Rotated Deepslate Found"),
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

            // If finished scanning all positions, reset rescan flag
            if (curY > maxY) isRescanning = false;
        }

        // Render ESP and tracers - render found blocks regardless if rescanning unless disabled
        if (!isRescanning || showOldCacheWhileRescanning.get()) {
            for (BlockPos pos : foundBlocks) {
                if (pos.getX() < playerPos.getX() - dist || pos.getX() > playerPos.getX() + dist || pos.getZ() < playerPos.getZ() - dist || pos.getZ() > playerPos.getZ() + dist)
                    continue;

                if (esp.get()) event.renderer.box(pos, espColor.get(), espColor.get(), ShapeMode.Both, 0);

                if (tracers.get()) {
                    Vec3d camPos = getCameraPos(event.tickDelta);
                    Vec3d look = getCameraLookVec(event.tickDelta);

                    double offset = Math.max(0, Math.min(1, tracerOffset.get()));
                    Vec3d desiredStart = camPos.add(look.multiply(offset));

                    double smooth = Math.max(0, Math.min(1, tracerSmoothing.get()));
                    Vec3d start;
                    if (lastTracerStart == null || smooth <= 0) {
                        start = desiredStart;
                    } else {
                        if (lastTracerStart.distanceTo(desiredStart) > 5) start = desiredStart;
                        else start = lastTracerStart.multiply(1 - smooth).add(desiredStart.multiply(smooth));
                    }
                    lastTracerStart = start;

                    Vec3d end = pos.toCenterPos();
                    event.renderer.line(start.x, start.y, start.z, end.x, end.y, end.z, espColor.get());
                }
            }
        }
    }

    private Vec3d getCameraPos(float tickDelta) {
        try {
            if (mc.cameraEntity != null) return mc.cameraEntity.getCameraPosVec(tickDelta);
        } catch (Throwable ignored) {}
        try {
            return mc.player.getCameraPosVec(tickDelta);
        } catch (Throwable ignored) {}
        return new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
    }

    private Vec3d getCameraLookVec(float tickDelta) {
        try {
            if (mc.cameraEntity != null) return mc.cameraEntity.getRotationVec(tickDelta).normalize();
        } catch (Throwable ignored) {}
        try {
            return mc.player.getRotationVec(tickDelta).normalize();
        } catch (Throwable ignored) {}

        double yaw = Math.toRadians(mc.player.getYaw());
        double pitch = Math.toRadians(mc.player.getPitch());
        double x = -Math.sin(yaw) * Math.cos(pitch);
        double y = -Math.sin(pitch);
        double z = Math.cos(yaw) * Math.cos(pitch);
        return new Vec3d(x, y, z).normalize();
    }

    private void runBruteForceScan() {
        if (mc.world == null) return;

        if (!scanPrepared) prepareScanBounds();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int airBlocksCount = 0;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (scanned.contains(pos)) continue;
                    scanned.add(pos);

                    if (stopAfter20AirBlocks.get()) {
                        BlockState state = mc.world.getBlockState(pos);
                        if (state.isAir()) {
                            airBlocksCount++;
                        } else {
                            airBlocksCount = 0;
                        }
                        if (airBlocksCount >= 20) break; // stop scanning this column upward
                    }

                    if (isRotatedDeepslate(pos)) {
                        if (foundBlocks.add(pos)) {
                            if (chatNotify.get()) ChatUtils.info("RotatedDeepslateFinder", "Found rotated deepslate at " + pos.toShortString());
                            if (toastNotify.get()) {
                                mc.getToastManager().add(new SystemToast(
                                    SystemToast.Type.PERIODIC_NOTIFICATION,
                                    Text.literal("Rotated Deepslate Found"),
                                    Text.literal(pos.toShortString())
                                ));
                            }
                        }
                    }
                }
            }
        }

        isRescanning = false;
    }

    private boolean isRotatedDeepslate(BlockPos pos) {
        try {
            if (mc.world == null) return false;

            BlockState state = mc.world.getBlockState(pos);
            Block block = state.getBlock();

            if (block != Blocks.DEEPSLATE) return false;

            if (!state.contains(Properties.AXIS)) return false;

            Direction.Axis axis = state.get(Properties.AXIS);

            // Normal deepslate spawns vertical (Y axis), so rotated = X or Z
            return axis == Direction.Axis.X || axis == Direction.Axis.Z;

        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onDeactivate() {
        foundBlocks.clear();
        scanned.clear();
        scanPrepared = false;
        frameCounter = 0;
        lastBruteFrame = -Long.MAX_VALUE;
        lastTracerStart = null;
        ticksSinceLastRescan = 0;
        isRescanning = false;
    }
}
