import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Compares two screenshots pixel for pixel: how many pixels differ at all, how many by more than
 * 8 of 255 in some channel, and the largest difference. Writes a difference image (white = differs,
 * brighter = more) when an output path is given. Exit code 1 if any pixel differs.
 * <p>
 * {@code java -Djava.awt.headless=true mod/scripts/ImageDiff.java a.png b.png [diff.png]}
 */
public final class ImageDiff {
    public static void main(String[] args) throws Exception {
        BufferedImage a = ImageIO.read(new File(args[0]));
        BufferedImage b = ImageIO.read(new File(args[1]));
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            System.out.printf("sizes differ: %dx%d vs %dx%d%n", a.getWidth(), a.getHeight(), b.getWidth(), b.getHeight());
            System.exit(2);
        }
        int w = a.getWidth(), h = a.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        long differ = 0, over8 = 0;
        int max = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                int d = 0;
                for (int shift = 0; shift <= 16; shift += 8) {
                    d = Math.max(d, Math.abs(((p >> shift) & 255) - ((q >> shift) & 255)));
                }
                if (d > 0) {
                    differ++;
                }
                if (d > 8) {
                    over8++;
                }
                max = Math.max(max, d);
                int v = d == 0 ? 0 : Math.min(255, 64 + d * 4);
                out.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        double n = (double) w * h;
        System.out.printf("%s vs %s: %d of %d pixels differ (%.4f%%), %d by more than 8 (%.4f%%), largest difference %d%n",
                new File(args[0]).getName(), new File(args[1]).getName(), differ, w * h, 100 * differ / n, over8, 100 * over8 / n, max);
        if (args.length > 2) {
            ImageIO.write(out, "png", new File(args[2]));
        }
        System.exit(differ > 0 ? 1 : 0);
    }
}
