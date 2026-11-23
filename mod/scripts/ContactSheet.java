import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;

/**
 * Contact sheets, so a review looks at a few labelled images instead of every screenshot.
 *
 *   java -Djava.awt.headless=true mod/scripts/ContactSheet.java grid <outDir> <shotsDir>
 *       3x3 tiles at half size, each labelled with its shot name: sheet-01.png, sheet-02.png...
 *
 *   java -Djava.awt.headless=true mod/scripts/ContactSheet.java compare <outDir> luxium=<dir> iris=<dir> [vanilla=<dir>]
 *       one row per shot name found in the first dir, one column per label (shots are matched by
 *       name without the "0012_" counter prefix), 3 rows per sheet: compare-01.png... A missing
 *       shot shows as a grey tile. Optional filter: add "only=<substring>" to keep matching names.
 *
 * Heat maps (*-flicker.png) are left out of both. Full-size shots stay in the dirs for zooming in.
 */
public class ContactSheet {
    static final int TW = 427, TH = 240, LABEL = 18;

    public static void main(String[] a) throws Exception {
        if (a.length < 3) {
            System.err.println("usage: grid <out> <dir> | compare <out> label=dir label=dir... [only=text]");
            System.exit(1);
        }
        File out = new File(a[1]);
        out.mkdirs();
        if (a[0].equals("grid")) {
            grid(out, new File(a[2]));
        } else {
            LinkedHashMap<String, File> runs = new LinkedHashMap<>();
            String only = null;
            for (int i = 2; i < a.length; i++) {
                String[] kv = a[i].split("=", 2);
                if (kv[0].equals("only")) only = kv[1]; else runs.put(kv[0], new File(kv[1]));
            }
            compare(out, runs, only);
        }
    }

    static List<File> shots(File dir) {
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".png") && !n.endsWith("-flicker.png"));
        List<File> list = new ArrayList<>(fs == null ? List.of() : Arrays.asList(fs));
        list.sort(Comparator.comparing(File::getName));
        return list;
    }

    static String key(String name) {
        return name.replaceFirst("^\\d{4}_", "").replaceFirst("\\.png$", "");
    }

    static void grid(File out, File dir) throws Exception {
        List<File> fs = shots(dir);
        int per = 9;
        for (int s = 0; s * per < fs.size(); s++) {
            BufferedImage sheet = new BufferedImage(3 * TW, 3 * (TH + LABEL), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            for (int i = 0; i < per && s * per + i < fs.size(); i++) {
                File f = fs.get(s * per + i);
                tile(g, ImageIO.read(f), key(f.getName()), (i % 3) * TW, (i / 3) * (TH + LABEL));
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(out, String.format("sheet-%02d.png", s + 1)));
        }
    }

    static void compare(File out, LinkedHashMap<String, File> runs, String only) throws Exception {
        List<String> labels = new ArrayList<>(runs.keySet());
        List<Map<String, File>> byKey = new ArrayList<>();
        for (File d : runs.values()) {
            Map<String, File> m = new HashMap<>();
            for (File f : shots(d)) m.put(key(f.getName()), f);
            byKey.add(m);
        }
        List<String> keys = new ArrayList<>();
        for (File f : shots(runs.values().iterator().next())) {
            String k = key(f.getName());
            if (only == null || k.contains(only)) keys.add(k);
        }
        int rows = 3, cols = labels.size();
        for (int s = 0; s * rows < keys.size(); s++) {
            BufferedImage sheet = new BufferedImage(cols * TW, rows * (TH + LABEL), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            for (int r = 0; r < rows && s * rows + r < keys.size(); r++) {
                String k = keys.get(s * rows + r);
                for (int c = 0; c < cols; c++) {
                    File f = byKey.get(c).get(k);
                    tile(g, f == null ? null : ImageIO.read(f), labels.get(c) + " | " + k, c * TW, r * (TH + LABEL));
                }
            }
            g.dispose();
            ImageIO.write(sheet, "png", new File(out, String.format("compare-%02d.png", s + 1)));
        }
        System.out.println(keys.size() + " shots compared across " + labels);
    }

    static void tile(Graphics2D g, BufferedImage img, String label, int x, int y) {
        g.setColor(new Color(20, 20, 20));
        g.fillRect(x, y, TW, LABEL);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        g.drawString(label, x + 4, y + 13);
        if (img == null) {
            g.setColor(Color.GRAY);
            g.fillRect(x, y + LABEL, TW, TH);
            return;
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        // Keep the aspect ratio (resized windows give other shapes).
        double sc = Math.min((double) TW / img.getWidth(), (double) TH / img.getHeight());
        int w = (int) (img.getWidth() * sc), h = (int) (img.getHeight() * sc);
        g.drawImage(img, x + (TW - w) / 2, y + LABEL + (TH - h) / 2, w, h, null);
    }
}
