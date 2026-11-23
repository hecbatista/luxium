import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Looks for flicker in a frame sequence from the game test (screenshots/frames-<name>/00.png ...),
 * and writes the sequence as a looping GIF so a person can watch it.
 * <p>
 * Run without building anything: {@code java scripts/FlickerCheck.java <frames dir> [more dirs]}.
 * For a moving camera, where every pixel changes, add {@code --tiles} before the dirs: the frame is
 * cut into 16x16-pixel patches, and a patch flashes when its average brightness jumps by more than
 * {@link #TILE_JUMP} for one frame, the same way up or down against both neighbours, while the
 * neighbours agree within {@link #TILE_BACK}. Steady motion changes patches gradually and doesn't
 * do that. Exit code 1 if any frame has more than {@link #TILE_LIMIT} of its patches flashing.
 * <p>
 * Flicker here is a pixel that jumps for one frame and comes back: frame i differs from both
 * neighbours by more than {@link #JUMP}, while the neighbours agree with each other within
 * {@link #BACK}. Smooth motion (waves, waving leaves) moves steadily and doesn't do that; a
 * pass that sometimes reads the wrong buffer half, an uncleared target or a race does.
 * <p>
 * Output per sequence: the steady-motion level (mean change between frames), the flicker
 * pixels of each middle frame, and a heat map PNG (white = flickered in many frames). Exit code 1
 * if any frame has more than {@link #LIMIT} of its pixels flickering.
 */
public final class FlickerCheck {
    static final int JUMP = 40;      // of 255, largest channel difference
    static final int BACK = 12;
    static final double LIMIT = 0.001; // 0.1% of the pixels in one frame
    static final int TILE = 16;
    static final double TILE_JUMP = 20;   // average brightness, of 255
    static final double TILE_BACK = 8;
    static final double TILE_LIMIT = 0.005; // 0.5% of the patches in one frame

    public static void main(String[] args) throws Exception {
        boolean bad = false;
        boolean tiles = false;
        for (String arg : args) {
            if (arg.equals("--tiles")) {
                tiles = true;
                continue;
            }
            bad |= tiles ? checkTiles(new File(arg)) : check(new File(arg));
        }
        System.exit(bad ? 1 : 0);
    }

    static boolean check(File dir) throws Exception {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".png"));
        if (files == null || files.length < 3) {
            System.out.println(dir + ": fewer than 3 frames, skipped");
            return false;
        }
        Arrays.sort(files);
        List<BufferedImage> frames = new ArrayList<>();
        for (File f : files) {
            frames.add(ImageIO.read(f));
        }
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        int[][] px = new int[frames.size()][];
        for (int i = 0; i < frames.size(); i++) {
            px[i] = frames.get(i).getRGB(0, 0, w, h, null, 0, w);
        }

        double motion = 0;
        for (int i = 1; i < px.length; i++) {
            long sum = 0;
            for (int p = 0; p < px[i].length; p++) {
                sum += diff(px[i][p], px[i - 1][p]);
            }
            motion += (double) sum / px[i].length;
        }
        motion /= px.length - 1;

        int[] heat = new int[w * h];
        double worst = 0;
        int worstFrame = -1;
        StringBuilder perFrame = new StringBuilder();
        for (int i = 1; i + 1 < px.length; i++) {
            int count = 0;
            for (int p = 0; p < w * h; p++) {
                if (diff(px[i][p], px[i - 1][p]) > JUMP && diff(px[i][p], px[i + 1][p]) > JUMP
                        && diff(px[i - 1][p], px[i + 1][p]) <= BACK) {
                    count++;
                    heat[p]++;
                }
            }
            double fraction = (double) count / (w * h);
            perFrame.append(' ').append(count);
            if (fraction > worst) {
                worst = fraction;
                worstFrame = i;
            }
        }

        int max = Math.max(1, Arrays.stream(heat).max().orElse(1));
        BufferedImage map = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int p = 0; p < w * h; p++) {
            // the first frame, dimmed, with flicker painted on top in red -> white
            int base = px[0][p];
            int g = (((base >> 16) & 255) + ((base >> 8) & 255) + (base & 255)) / 12;
            int v = heat[p] == 0 ? 0 : 80 + 175 * heat[p] / max;
            map.setRGB(p % w, p / w, heat[p] == 0 ? (g << 16 | g << 8 | g) : (255 << 16 | v << 8 | v));
        }
        File parent = dir.getAbsoluteFile().getParentFile();
        ImageIO.write(map, "png", new File(parent, dir.getName() + "-flicker.png"));
        writeGif(frames, new File(parent, dir.getName() + ".gif"));

        boolean bad = worst > LIMIT;
        System.out.printf(Locale.ROOT, "%s: %d frames %dx%d, steady motion %.2f/255 per frame, flicker pixels per frame:%s; worst %.4f%% (frame %d) -> %s%n",
                dir.getName(), px.length, w, h, motion, perFrame, worst * 100, worstFrame, bad ? "FLICKER" : "ok");
        return bad;
    }

    /** The moving-camera check: patches whose average brightness flashes for one frame. */
    static boolean checkTiles(File dir) throws Exception {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".png"));
        if (files == null || files.length < 3) {
            System.out.println(dir + ": fewer than 3 frames, skipped");
            return false;
        }
        Arrays.sort(files);
        List<BufferedImage> frames = new ArrayList<>();
        for (File f : files) {
            frames.add(ImageIO.read(f));
        }
        int w = frames.get(0).getWidth();
        int h = frames.get(0).getHeight();
        int tw = w / TILE;
        int th = h / TILE;
        double[][] mean = new double[frames.size()][tw * th];
        double[] frameMean = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            int[] px = frames.get(i).getRGB(0, 0, w, h, null, 0, w);
            double total = 0;
            for (int ty = 0; ty < th; ty++) {
                for (int tx = 0; tx < tw; tx++) {
                    double sum = 0;
                    for (int y = ty * TILE; y < (ty + 1) * TILE; y++) {
                        for (int x = tx * TILE; x < (tx + 1) * TILE; x++) {
                            int c = px[y * w + x];
                            sum += (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255));
                        }
                    }
                    mean[i][ty * tw + tx] = sum / (TILE * TILE);
                    total += mean[i][ty * tw + tx];
                }
            }
            frameMean[i] = total / (tw * th);
        }
        int[] heat = new int[tw * th];
        double worst = 0;
        int worstFrame = -1;
        StringBuilder perFrame = new StringBuilder();
        for (int i = 1; i + 1 < frames.size(); i++) {
            int count = 0;
            for (int t = 0; t < tw * th; t++) {
                double a = mean[i - 1][t], b = mean[i][t], c = mean[i + 1][t];
                boolean up = b - a > TILE_JUMP && b - c > TILE_JUMP;
                boolean down = a - b > TILE_JUMP && c - b > TILE_JUMP;
                if ((up || down) && Math.abs(a - c) <= TILE_BACK) {
                    count++;
                    heat[t]++;
                }
            }
            double fraction = (double) count / (tw * th);
            if (count > 0) {
                perFrame.append(' ').append(i).append(':').append(count);
            }
            if (fraction > worst) {
                worst = fraction;
                worstFrame = i;
            }
        }
        // The worst frame, side by side with its neighbours, to look at.
        File parent = dir.getAbsoluteFile().getParentFile();
        if (worstFrame > 0) {
            BufferedImage strip = new BufferedImage(w * 3, h, BufferedImage.TYPE_INT_RGB);
            for (int k = 0; k < 3; k++) {
                strip.getGraphics().drawImage(frames.get(worstFrame - 1 + k), k * w, 0, null);
            }
            ImageIO.write(strip, "png", new File(parent, dir.getName() + "-worst.png"));
        }
        writeGif(frames, new File(parent, dir.getName() + ".gif"));
        StringBuilder trace = new StringBuilder();
        for (double m : frameMean) {
            trace.append(String.format(Locale.ROOT, " %.0f", m));
        }
        boolean bad = worst > TILE_LIMIT;
        System.out.printf(Locale.ROOT, "%s (patches): %d frames, %dx%d patches; flashing patches (frame:count):%s; worst %.2f%% (frame %d) -> %s%n  brightness per frame:%s%n",
                dir.getName(), frames.size(), tw, th, perFrame.length() == 0 ? " none" : perFrame, worst * 100, worstFrame,
                bad ? "FLASH" : "ok", trace);
        return bad;
    }

    static int diff(int a, int b) {
        return Math.max(Math.abs(((a >> 16) & 255) - ((b >> 16) & 255)),
                Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)), Math.abs((a & 255) - (b & 255))));
    }

    /** An endlessly looping GIF at 20 frames a second (one game tick per frame). */
    static void writeGif(List<BufferedImage> frames, File out) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (BufferedImage frame : frames) {
                BufferedImage rgb = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.getGraphics().drawImage(frame, 0, 0, null);
                IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(rgb), null);
                String format = meta.getNativeMetadataFormatName();
                IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
                IIOMetadataNode gce = child(root, "GraphicControlExtension");
                gce.setAttribute("disposalMethod", "none");
                gce.setAttribute("userInputFlag", "FALSE");
                gce.setAttribute("transparentColorFlag", "FALSE");
                gce.setAttribute("delayTime", "5");
                gce.setAttribute("transparentColorIndex", "0");
                IIOMetadataNode app = child(child(root, "ApplicationExtensions"), "ApplicationExtension");
                app.setAttribute("applicationID", "NETSCAPE");
                app.setAttribute("authenticationCode", "2.0");
                app.setUserObject(new byte[]{1, 0, 0});
                meta.setFromTree(format, root);
                writer.writeToSequence(new IIOImage(rgb, null, meta), null);
            }
            writer.endWriteSequence();
        }
    }

    static IIOMetadataNode child(IIOMetadataNode node, String name) {
        for (int i = 0; i < node.getLength(); i++) {
            if (node.item(i).getNodeName().equals(name)) {
                return (IIOMetadataNode) node.item(i);
            }
        }
        IIOMetadataNode created = new IIOMetadataNode(name);
        node.appendChild(created);
        return created;
    }
}
