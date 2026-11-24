package io.github.hecbatista.metalshaders.gui;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.pack.PackMenus;
import io.github.hecbatista.metalshaders.pack.PackOptions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A shader pack's own option screens, built from its shaders.properties menus (PackMenus) and lang
 * (PackLang), with Iris's widgets and interactions (see NOTICE):
 * <ul>
 *   <li>on/off options show On/Off, green or red only when not the default; value options cycle
 *   (left click forward, right click back) and show their value in blue; sliders (the pack's
 *   {@code sliders}) take the value under the mouse while dragged; links open a sub-screen; the
 *   profile row cycles the pack's profiles, "Custom" when none matches;</li>
 *   <li>a label turns yellow while its value differs from the applied one; Shift-click puts one
 *   option back to its default; Shift + Reset puts the whole pack back and applies;</li>
 *   <li>hovering shows the option's comment after half a second;</li>
 *   <li>Esc and Back go back through the sub-screens, then to the pack list (keeping what was
 *   picked); Cancel drops what was picked; Apply writes shaderpacks/&lt;pack&gt;.txt and reloads the
 *   pack live; Done applies and closes.</li>
 * </ul>
 */
public class ShaderOptionsScreen extends Screen {
    private static final int MODIFIED_COLOR = 0xFFFFC94A;   // Iris's colours
    private static final int VALUE_COLOR = 0xFF6688FF;
    private static final int ROW_HEIGHT = 24;
    private static final int CELL_HEIGHT = 20;
    private static final int TOOLTIP_TICKS = 10;
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");

    private final Screen packScreen;
    private final @Nullable Screen closeTo;
    private PackSettings settings;
    private final Deque<String> history = new ArrayDeque<>();
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 33);
    private @Nullable OptionList list;
    private @Nullable Button reset;
    private @Nullable Cell hoveredCell;
    private int hoveredTicks;

    /**
     * @param packScreen where Esc and Cancel go back to
     * @param closeTo    where Done goes
     */
    public ShaderOptionsScreen(Screen packScreen, @Nullable Screen closeTo, PackSettings settings) {
        super(Component.literal(settings.pack.getFileName().toString()));
        this.packScreen = packScreen;
        this.closeTo = closeTo;
        this.settings = settings;
    }

    private @Nullable String currentId() {
        return history.peekLast();
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 33);   // init runs again on every screen change
        PackMenus.Screen screen = settings.menus.screen(currentId());
        Component heading = screen.id() == null
                ? Component.literal(settings.pack.getFileName().toString()).withStyle(ChatFormatting.BOLD)
                : Component.literal(settings.screenLabel(screen.id()));
        LinearLayout header = layout.addToHeader(LinearLayout.horizontal().spacing(8));
        header.defaultCellSetting().alignVerticallyMiddle();
        Button back = header.addChild(Button.builder(Component.literal("< ").append(Component.translatable("luxium.options.back")),
                b -> back()).width(60).build());
        back.active = !history.isEmpty();
        header.addChild(new StringWidget(200, 20, heading, font));
        reset = header.addChild(Button.builder(Component.translatable("luxium.options.reset"), b -> resetAll()).width(60)
                .tooltip(Tooltip.create(Component.translatable("luxium.options.reset.tooltip"))).build());
        reset.active = minecraft.hasShiftDown();

        list = layout.addToContents(new OptionList(minecraft, screen));
        LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(4));
        footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> cancel()).width(100).build());
        footer.addChild(Button.builder(Component.translatable("luxium.shaderPacks.apply"), b -> apply()).width(100).build());
        footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> done()).width(100).build());
        layout.visitWidgets(this::addRenderableWidget);
        repositionElements();
    }

    @Override
    protected void repositionElements() {
        layout.arrangeElements();
        if (list != null) {
            list.updateSize(width, layout);
        }
    }

    @Override
    public void tick() {
        if (reset != null) {
            reset.active = minecraft.hasShiftDown();
        }
        hoveredTicks++;
    }

    // ---- navigation and applying ----------------------------------------------------------

    public void open(String screenId) {
        history.addLast(screenId);
        rebuildWidgets();
    }

    public void back() {
        if (history.isEmpty()) {
            minecraft.gui.setScreen(packScreen);
        } else {
            history.removeLast();
            rebuildWidgets();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE) {
            back();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        back();
    }

    private void cancel() {
        PackSettings.clearPending();
        minecraft.gui.setScreen(packScreen);
    }

    public void apply() {
        apply(false);
    }

    private void apply(boolean resetEverything) {
        if (!resetEverything && !PackSettings.hasPending()) {
            return;
        }
        try {
            settings.apply(resetEverything);
            settings = PackSettings.load(settings.pack, minecraft.options.languageCode);
        } catch (IOException | RuntimeException e) {
            MetalShadersClient.LOGGER.error("Couldn't apply the settings of {}", settings.pack.getFileName(), e);
        }
        rebuildWidgets();
    }

    private void resetAll() {
        if (minecraft.hasShiftDown()) {
            apply(true);
        }
    }

    private void done() {
        apply();
        minecraft.gui.setScreen(closeTo);
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        Cell hovered = list == null ? null : list.cellAt(mouseX, mouseY);
        if (hovered != hoveredCell) {
            hoveredCell = hovered;
            hoveredTicks = 0;
        }
        if (hovered == null) {
            return;
        }
        if (minecraft.hasShiftDown() && hovered instanceof OptionCell) {
            graphics.setTooltipForNextFrame(font, Component.translatable("luxium.options.setToDefault").withStyle(ChatFormatting.GREEN), mouseX, mouseY);
        } else if (hoveredTicks >= TOOLTIP_TICKS) {
            List<FormattedCharSequence> lines = hovered.tooltip(font);
            if (!lines.isEmpty()) {
                graphics.setTooltipForNextFrame(lines, mouseX, mouseY);
            }
        }
    }

    // ---- for the game test ----------------------------------------------------------------

    public PackSettings settings() {
        return settings;
    }

    public @Nullable String screenId() {
        return currentId();
    }

    /** Clicks the cell of option {@code name} (or link/profile by "[ID]" / "<profile>") on this screen. */
    public boolean clickCell(String name, boolean right) {
        if (list == null) {
            return false;
        }
        for (Row row : list.children()) {
            for (Cell c : row.cells) {
                if (c.key().equals(name)) {
                    c.press(right ? InputConstants.MOUSE_BUTTON_RIGHT : InputConstants.MOUSE_BUTTON_LEFT, minecraft.hasShiftDown());
                    return true;
                }
            }
        }
        return false;
    }

    /** Where a cell was last drawn, in GUI coordinates {x, y, width}, or null if not drawn. */
    public int @Nullable [] cellBounds(String key) {
        if (list == null) {
            return null;
        }
        for (Row row : list.children()) {
            for (Cell c : row.cells) {
                if (c.key().equals(key) && c.width > 0) {
                    return new int[]{c.x, c.y, c.width};
                }
            }
        }
        return null;
    }

    // ---- the list of rows -----------------------------------------------------------------

    private final class OptionList extends ObjectSelectionList<Row> {
        OptionList(Minecraft minecraft, PackMenus.Screen screen) {
            super(minecraft, ShaderOptionsScreen.this.width, ShaderOptionsScreen.this.layout.getContentHeight(),
                    ShaderOptionsScreen.this.layout.getHeaderHeight(), ROW_HEIGHT);
            int columns = Math.max(1, screen.columns());
            List<Cell> row = new ArrayList<>();
            for (PackMenus.Element e : screen.elements()) {
                row.add(cellFor(e));
                if (row.size() == columns) {
                    addEntry(new Row(row));
                    row = new ArrayList<>();
                }
            }
            if (!row.isEmpty()) {
                while (row.size() < columns) {
                    row.add(new EmptyCell());
                }
                addEntry(new Row(row));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.min(400, width - 12);
        }

        @Override
        protected boolean entriesCanBeSelected() {
            return false;
        }

        @Nullable Cell cellAt(int mouseX, int mouseY) {
            if (!isMouseOver(mouseX, mouseY)) {
                return null;
            }
            Row row = getEntryAtPosition(mouseX, mouseY);
            return row == null ? null : row.cellAt(mouseX);
        }
    }

    private Cell cellFor(PackMenus.Element e) {
        return switch (e) {
            case PackMenus.Empty x -> new EmptyCell();
            case PackMenus.ProfileSlot x -> new ProfileCell();
            case PackMenus.Link link -> new LinkCell(link.target());
            case PackMenus.OptionSlot o -> new OptionCell(o.name(), o.slider());
        };
    }

    private final class Row extends ObjectSelectionList.Entry<Row> {
        final List<Cell> cells;
        @Nullable Cell pressed;

        Row(List<Cell> cells) {
            this.cells = cells;
        }

        private int cellWidth() {
            int gap = 4;
            return (getContentWidth() - gap * (cells.size() - 1)) / cells.size();
        }

        private int cellX(int index) {
            return getContentX() + index * (cellWidth() + 4);
        }

        @Nullable Cell cellAt(double mouseX) {
            for (int i = 0; i < cells.size(); i++) {
                int x = cellX(i);
                if (mouseX >= x && mouseX < x + cellWidth()) {
                    return cells.get(i);
                }
            }
            return null;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int w = cellWidth();
            int y = getContentY() + (getContentHeight() - CELL_HEIGHT) / 2;
            for (int i = 0; i < cells.size(); i++) {
                int x = cellX(i);
                Cell c = cells.get(i);
                c.x = x;
                c.y = y;
                c.width = w;
                boolean over = hovered && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CELL_HEIGHT;
                c.draw(graphics, x, y, w, over || c == pressed, mouseX);
            }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            Cell c = cellAt(event.x());
            if (c == null || c instanceof EmptyCell) {
                return false;
            }
            pressed = c;
            c.press(event.button(), minecraft.hasShiftDown());
            if (c instanceof OptionCell o && o.slider) {
                o.dragTo(event.x());
            }
            return true;
        }

        @Override
        public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            if (pressed instanceof OptionCell o && o.slider) {
                o.dragTo(event.x());
                return true;
            }
            return false;
        }

        @Override
        public boolean mouseReleased(MouseButtonEvent event) {
            pressed = null;
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.empty();
        }
    }

    // ---- cells ----------------------------------------------------------------------------

    private abstract class Cell {
        int x;
        int y;
        int width;

        abstract String key();

        abstract void draw(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, int mouseX);

        abstract void press(int button, boolean shift);

        List<FormattedCharSequence> tooltip(net.minecraft.client.gui.Font font) {
            return List.of();
        }

        void background(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered) {
            g.blitSprite(RenderPipelines.GUI_TEXTURED, hovered ? BUTTON_HIGHLIGHTED : BUTTON, x, y, w, CELL_HEIGHT);
        }

        /** Title in the first line, then the comment split at ". " (as Iris shows it), wrapped. */
        List<FormattedCharSequence> commentLines(net.minecraft.client.gui.Font font, Component title, @Nullable String comment) {
            List<FormattedCharSequence> out = new ArrayList<>();
            out.add(title.getVisualOrderText());
            if (comment != null && !comment.isBlank()) {
                for (String sentence : comment.split("\\. ")) {
                    String s = sentence.endsWith(".") || sentence.endsWith("!") || sentence.endsWith("?") ? sentence : sentence + ".";
                    out.addAll(font.split(FormattedText.of(s), 250));
                }
            }
            return out;
        }
    }

    private final class EmptyCell extends Cell {
        @Override
        String key() {
            return "<empty>";
        }

        @Override
        void draw(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, int mouseX) {
        }

        @Override
        void press(int button, boolean shift) {
        }
    }

    private final class LinkCell extends Cell {
        final String target;

        LinkCell(String target) {
            this.target = target;
        }

        @Override
        String key() {
            return "[" + target + "]";
        }

        @Override
        void draw(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, int mouseX) {
            background(g, x, y, w, hovered);
            String label = trim(settings.screenLabel(target), w - 18);
            g.centeredText(font, Component.literal(label), x + w / 2, y + 6, 0xFFFFFFFF);
            g.text(font, ">", x + w - 9, y + 6, 0xFFFFFFFF);
        }

        @Override
        void press(int button, boolean shift) {
            if (button == InputConstants.MOUSE_BUTTON_LEFT) {
                click();
                open(target);
            }
        }

        @Override
        List<FormattedCharSequence> tooltip(net.minecraft.client.gui.Font font) {
            return commentLines(font, Component.literal(settings.screenLabel(target)),
                    settings.lang.get("screen." + target + ".comment").orElse(null));
        }
    }

    private final class ProfileCell extends Cell {
        @Override
        String key() {
            return "<profile>";
        }

        @Override
        void draw(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, int mouseX) {
            PackMenus.ProfileScan scan = settings.profile();
            Component value = scan.current().<Component>map(p -> Component.literal(settings.profileLabel(p.name())))
                    .orElse(Component.translatable("luxium.options.profile.custom").withStyle(ChatFormatting.YELLOW));
            ShaderOptionsScreen.this.labelAndValue(g, x, y, w, hovered, Component.translatable("luxium.options.profile"), value,
                    w - (font.width(Component.translatable("luxium.options.profile")) + 16), null);
        }

        @Override
        void press(int button, boolean shift) {
            PackMenus.ProfileScan scan = settings.profile();
            PackMenus.Profile to = button == InputConstants.MOUSE_BUTTON_RIGHT ? scan.previous() : scan.next();
            if (to != null && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
                settings.applyProfile(to);
                click();
            }
        }

        @Override
        List<FormattedCharSequence> tooltip(net.minecraft.client.gui.Font font) {
            return commentLines(font, Component.translatable("luxium.options.profile"), settings.lang.get("profile.comment").orElse(null));
        }
    }

    private final class OptionCell extends Cell {
        final String name;
        final boolean slider;
        final boolean onOff;

        OptionCell(String name, boolean slider) {
            this.name = name;
            this.onOff = settings.options.booleanOptions().containsKey(name);
            this.slider = slider && !onOff;
        }

        @Override
        String key() {
            return name;
        }

        private List<String> allowed() {
            PackOptions.StringOption s = settings.options.stringOptions().get(name);
            return s == null ? List.of() : s.allowedValues();
        }

        private Component valueText() {
            String v = settings.current(name);
            if (onOff) {
                boolean on = v.equals("true");
                MutableComponent t = Component.translatable(on ? "luxium.options.on" : "luxium.options.off");
                // Iris: colour only when not the default, so neither value looks "bad".
                if (!v.equals(settings.options.defaultValue(name))) {
                    t = t.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
                }
                return t;
            }
            return Component.literal(settings.valueLabel(name, v)).withColor(VALUE_COLOR);
        }

        @Override
        void draw(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, int mouseX) {
            Component label = Component.literal(settings.optionLabel(name));
            Component value = valueText();
            if (slider && hovered) {
                // The whole cell becomes the slider while the mouse is on it (Iris).
                g.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON_DISABLED, x, y, w, CELL_HEIGHT);
                knob(g, x + 2, y, w - 4, 6);
                g.centeredText(font, value, x + w / 2, y + 6, 0xFFFFFFFF);
                return;
            }
            labelAndValue(g, x, y, w, hovered, label, value, onOff ? 28 : slider ? 35 : 0, slider);
        }

        private void knob(GuiGraphicsExtractor g, int x, int y, int w, int knobWidth) {
            List<String> values = allowed();
            int index = Math.max(0, values.indexOf(settings.current(name)));
            float at = values.size() < 2 ? 0 : (float) index / (values.size() - 1);
            int kx = x + 2 + (int) (at * (w - 4 - knobWidth));
            g.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON, kx, y + 4, knobWidth, CELL_HEIGHT - 8);
        }

        @Override
        void press(int button, boolean shift) {
            if (button != InputConstants.MOUSE_BUTTON_LEFT && button != InputConstants.MOUSE_BUTTON_RIGHT) {
                return;
            }
            click();
            if (shift) {
                settings.set(name, settings.options.defaultValue(name));
                return;
            }
            if (slider && button == InputConstants.MOUSE_BUTTON_LEFT) {
                return;   // the drag sets it (Row.mouseClicked calls dragTo)
            }
            int step = button == InputConstants.MOUSE_BUTTON_RIGHT ? -1 : 1;
            if (onOff) {
                settings.set(name, Boolean.toString(!settings.current(name).equals("true")));
                return;
            }
            List<String> values = allowed();
            if (values.isEmpty()) {
                return;
            }
            int index = Math.max(0, values.indexOf(settings.current(name)));
            settings.set(name, values.get(Math.floorMod(index + step, values.size())));
        }

        /** The value under the mouse, one step per allowed value (Iris's SliderElementWidget). */
        void dragTo(double mouseX) {
            if (minecraft.hasShiftDown()) {
                return;
            }
            List<String> values = allowed();
            if (values.isEmpty() || width <= 8) {
                return;
            }
            float across = Mth.clamp((float) (mouseX - (x + 4)) / (width - 8), 0, 1);
            int index = Math.min(values.size() - 1, (int) (across * values.size()));
            if (!values.get(index).equals(settings.current(name))) {
                settings.set(name, values.get(index));
            }
        }

        @Override
        List<FormattedCharSequence> tooltip(net.minecraft.client.gui.Font font) {
            return commentLines(font, Component.literal(settings.optionLabel(name)),
                    settings.lang.get("option." + name + ".comment").orElse(null));
        }

        private void labelAndValue(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, Component label, Component value,
                                   int minValueWidth, boolean withKnob) {
            ShaderOptionsScreen.this.labelAndValue(g, x, y, w, hovered, label.copy().withColor(settings.modified(name) ? MODIFIED_COLOR : 0xFFFFFFFF),
                    value, minValueWidth, withKnob ? this : null);
        }
    }

    /** Label on the left, value in a box on the right (Iris's BaseOptionElementWidget layout). */
    private void labelAndValue(GuiGraphicsExtractor g, int x, int y, int w, boolean hovered, Component label, Component value,
                               int minValueWidth, @Nullable OptionCell knobOf) {
        g.blitSprite(RenderPipelines.GUI_TEXTURED, hovered ? BUTTON_HIGHLIGHTED : BUTTON, x, y, w, CELL_HEIGHT);
        int valueWidth = Math.max(minValueWidth, font.width(value) + 8);
        int boxX = x + w - valueWidth - 2;
        g.blitSprite(RenderPipelines.GUI_TEXTURED, BUTTON_DISABLED, boxX, y + 2, valueWidth, CELL_HEIGHT - 4);
        if (knobOf != null) {
            knobOf.knob(g, boxX, y, valueWidth, 4);
        }
        String text = trim(label.getString() + ": ", w - 8 - valueWidth);
        g.text(font, Component.literal(text).withStyle(label.getStyle()), x + 6, y + 6, 0xFFFFFFFF);
        g.centeredText(font, value, boxX + valueWidth / 2, y + 6, 0xFFFFFFFF);
    }

    private String trim(String text, int room) {
        if (font.width(text) <= room) {
            return text;
        }
        return font.plainSubstrByWidth(text, Math.max(0, room - font.width("..."))) + "...";
    }
}
