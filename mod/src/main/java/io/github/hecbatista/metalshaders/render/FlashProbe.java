package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Debug, on with {@code LUXIUM_FLASH_PROBE=1}: watches every finished frame for flashes. Each
 * frame's image is copied back from the GPU right after the pack's final pass and cut into
 * 16x16-pixel patches. A patch flashes when its average brightness jumps by more than 20/255 for
 * one frame (the same way against both neighbours) while the neighbours agree within 8/255, the
 * same rule as scripts/FlickerCheck.java --tiles. When more than 0.5% of a frame's patches flash,
 * the log says so and the three frames (before, flash, after) are saved side by side, half size,
 * in {@code luxium-flashes/} in the game folder.
 * <p>
 * Costly (a full-frame copy and read every frame): for finding flashes, not for playing.
 */
public final class FlashProbe {
    public static final boolean ON = System.getenv("LUXIUM_FLASH_PROBE") != null;
    private static final int TILE = 16;
    private static final double JUMP = 20;
    private static final double BACK = 8;
    private static final double LIMIT = 0.005;
    private static final int MAX_SAVED = 20;

    private static long frame;
    private static long checked;
    private static int flashes;
    private static int saved;
    private static long lastSummary;
    /** The last two frames read back: tile means and pixels (RGBA bytes). */
    private static double[] tilesA;
    private static double[] tilesB;
    private static byte[] pixelsA;
    private static byte[] pixelsB;
    private static long frameA = -1;
    private static long frameB = -1;
    private static int width;
    private static int height;

    private FlashProbe() {
    }

    /** After the final pass: queue a copy of the frame for checking. */
    static void capture(GpuDevice device, CommandEncoder encoder, GpuTexture color) {
        if (!ON) {
            return;
        }
        long index = frame++;
        int w = color.getWidth(0);
        int h = color.getHeight(0);
        int bytesPerPixel = color.getFormat().blockSize();
        if (bytesPerPixel != 4) {
            return;   // only 8-bit RGBA images are read
        }
        GpuBuffer buf = device.createBuffer(() -> "Luxium flash probe", GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ, (long) w * h * 4);
        encoder.copyTextureToBuffer(color, buf, 0, () -> {
            try (GpuBufferSlice.MappedView v = buf.map(true, false)) {
                ByteBuffer data = v.data();
                byte[] pixels = new byte[w * h * 4];
                data.get(0, pixels);
                analyse(index, w, h, pixels);
            } catch (RuntimeException e) {
                MetalShadersClient.LOGGER.warn("Flash probe: reading frame {} failed: {}", index, e.toString());
            } finally {
                buf.close();
            }
        }, 0);
    }

    private static void analyse(long index, int w, int h, byte[] pixels) {
        double[] tiles = tiles(w, h, pixels);
        boolean consecutive = tilesA != null && tilesB != null && frameA == index - 2 && frameB == index - 1 && w == width && h == height;
        if (consecutive) {
            checked++;
            int tw = w / TILE;
            int count = 0;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
            for (int t = 0; t < tiles.length; t++) {
                double a = tilesA[t], b = tilesB[t], c = tiles[t];
                boolean up = b - a > JUMP && b - c > JUMP;
                boolean down = a - b > JUMP && c - b > JUMP;
                if ((up || down) && Math.abs(a - c) <= BACK) {
                    count++;
                    minX = Math.min(minX, t % tw);
                    maxX = Math.max(maxX, t % tw);
                    minY = Math.min(minY, t / tw);
                    maxY = Math.max(maxY, t / tw);
                }
            }
            double fraction = (double) count / tiles.length;
            if (fraction > LIMIT) {
                flashes++;
                String file = saved < MAX_SAVED ? save(index - 1, w, h, pixelsA, pixelsB, pixels) : "(not saved: limit reached)";
                MetalShadersClient.LOGGER.warn(String.format(Locale.ROOT,
                        "Flash probe: frame %d flashed in %d patches (%.1f%% of the frame), area x %d-%d y %d-%d (of %dx%d px); saved %s",
                        index - 1, count, fraction * 100, minX * TILE, (maxX + 1) * TILE, minY * TILE, (maxY + 1) * TILE, w, h, file));
            }
        }
        long now = System.nanoTime();
        if (now - lastSummary > 10_000_000_000L) {
            lastSummary = now;
            MetalShadersClient.LOGGER.info("Flash probe: {} frames checked, {} flashes so far", checked, flashes);
        }
        tilesA = tilesB;
        pixelsA = pixelsB;
        frameA = frameB;
        tilesB = tiles;
        pixelsB = pixels;
        frameB = index;
        width = w;
        height = h;
    }

    private static double[] tiles(int w, int h, byte[] px) {
        int tw = w / TILE;
        int th = h / TILE;
        double[] out = new double[tw * th];
        for (int ty = 0; ty < th; ty++) {
            for (int tx = 0; tx < tw; tx++) {
                double sum = 0;
                for (int y = ty * TILE; y < (ty + 1) * TILE; y++) {
                    int row = y * w;
                    for (int x = tx * TILE; x < (tx + 1) * TILE; x++) {
                        int i = (row + x) * 4;
                        sum += 0.2126 * (px[i] & 255) + 0.7152 * (px[i + 1] & 255) + 0.0722 * (px[i + 2] & 255);
                    }
                }
                out[ty * tw + tx] = sum / (TILE * TILE);
            }
        }
        return out;
    }

    /** Before, flash, after: side by side at half size. The GPU image is upside down (OpenGL rows). */
    private static String save(long flashFrame, int w, int h, byte[]... frames) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("luxium-flashes");
            Files.createDirectories(dir);
            int hw = w / 2;
            int hh = h / 2;
            try (NativeImage image = new NativeImage(hw * 3, hh, false)) {
                for (int k = 0; k < 3; k++) {
                    byte[] px = frames[k];
                    for (int y = 0; y < hh; y++) {
                        for (int x = 0; x < hw; x++) {
                            int i = ((y * 2) * w + x * 2) * 4;
                            int argb = 0xFF000000 | (px[i] & 255) << 16 | (px[i + 1] & 255) << 8 | (px[i + 2] & 255);
                            image.setPixel(k * hw + x, hh - 1 - y, argb);
                        }
                    }
                }
                Path file = dir.resolve("flash-" + flashFrame + ".png");
                image.writeToFile(file);
                saved++;
                return file.getFileName().toString();
            }
        } catch (Exception e) {
            return "(save failed: " + e + ")";
        }
    }
}
