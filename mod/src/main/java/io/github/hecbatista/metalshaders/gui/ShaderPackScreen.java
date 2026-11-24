package io.github.hecbatista.metalshaders.gui;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.hecbatista.metalshaders.pack.LuxiumConfig;
import io.github.hecbatista.metalshaders.pack.PackReader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.sounds.SoundEvents;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Shader Packs screen: shaders on or off, and which pack, as Iris's ShaderPackScreen and
 * ShaderPackSelectionList behave (see NOTICE): the first row turns shaders on and off (inactive
 * with no packs); clicking a pack selects it and, if shaders were off, turns them on; the applied
 * pack is yellow, and every pack is grey while shaders are off. Cancel drops the changes, Apply
 * writes config/luxium.properties and reloads the pack live, Done (and Esc, as in Iris) applies and
 * goes back. Opened from Video Settings, the I key and Mod Menu.
 */
public class ShaderPackScreen extends Screen {
    private static final Component TITLE = Component.translatable("luxium.shaderPacks.title");
    private static final int APPLIED_COLOR = 0xFFFFF263;   // Iris's colours
    private static final int OFF_COLOR = 0xFFA2A2A2;

    private final @Nullable Screen lastScreen;
    private HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this, 33, 60);
    private @Nullable PackList list;

    /** What config/luxium.properties says now, and what the player has picked but not applied. */
    private boolean appliedEnabled;
    private String appliedPack = "";
    private boolean pendingEnabled;
    private @Nullable String pendingPack;

    public ShaderPackScreen(@Nullable Screen lastScreen) {
        super(TITLE);
        this.lastScreen = lastScreen;
        readApplied();
        pendingEnabled = appliedEnabled;
        pendingPack = appliedPack.isEmpty() ? null : appliedPack;
    }

    private void readApplied() {
        try {
            Path config = MetalShadersClient.configFile();
            appliedEnabled = LuxiumConfig.enabled(config);
            appliedPack = LuxiumConfig.packName(config);
        } catch (IOException | RuntimeException e) {
            MetalShadersClient.LOGGER.warn("Couldn't read {}: {}", MetalShadersClient.configFile(), e.toString());
            appliedEnabled = true;
            appliedPack = "";
        }
    }

    @Override
    protected void init() {
        layout = new HeaderAndFooterLayout(this, 33, 60);   // init runs again when the window is resized
        layout.addTitleHeader(TITLE, font);
        list = layout.addToContents(new PackList(minecraft));
        LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
        footer.defaultCellSetting().alignHorizontallyCenter();
        LinearLayout top = footer.addChild(LinearLayout.horizontal().spacing(4));
        top.addChild(Button.builder(Component.translatable("luxium.shaderPacks.openFolder"), b -> openFolder())
                .width(152).build());
        top.addChild(Button.builder(Component.translatable("luxium.shaderPacks.settings"), b -> openSettings())
                .width(152).build());
        LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(4));
        buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> cancel()).width(100).build());
        buttons.addChild(Button.builder(Component.translatable("luxium.shaderPacks.apply"), b -> apply()).width(100).build());
        buttons.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(100).build());
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

    /** Writes the choice and reloads, if anything changed. Then the list shows it as applied. */
    public void apply() {
        String pack = pendingPack != null ? pendingPack : appliedPack;
        if (pendingEnabled == appliedEnabled && pack.equals(appliedPack)) {
            return;
        }
        if (!pack.equals(appliedPack)) {
            PackSettings.clearPending();   // picked values belong to the old pack (as Iris)
        }
        try {
            LuxiumConfig.write(MetalShadersClient.shaderpacksDir(), MetalShadersClient.configFile(), pendingEnabled, pack);
            MetalShadersClient.LOGGER.info("Shader Packs screen: shaders {}, pack {}", pendingEnabled ? "on" : "off",
                    pack.isEmpty() ? "(first found)" : pack);
            MetalShadersClient.requestReload();
        } catch (IOException | RuntimeException e) {
            MetalShadersClient.LOGGER.error("Couldn't write {}", MetalShadersClient.configFile(), e);
        }
        appliedEnabled = pendingEnabled;
        appliedPack = pack;
    }

    private void cancel() {
        PackSettings.clearPending();
        minecraft.gui.setScreen(lastScreen);
    }

    /**
     * The pack's own option screens. As Iris, the selection is applied first, so the settings
     * opened are the selected pack's. Needs shaders on and a pack.
     */
    public void openSettings() {
        apply();
        if (!appliedEnabled) {
            return;
        }
        Path pack = packPath(appliedPack);
        if (pack == null) {
            return;
        }
        try {
            PackSettings settings = PackSettings.load(pack, minecraft.options.languageCode);
            minecraft.gui.setScreen(new ShaderOptionsScreen(this, lastScreen, settings));
        } catch (IOException | RuntimeException e) {
            MetalShadersClient.LOGGER.error("Couldn't read the settings of {}", pack.getFileName(), e);
        }
    }

    private @Nullable Path packPath(String name) {
        try {
            List<Path> packs = PackReader.findPacks(MetalShadersClient.shaderpacksDir());
            return packs.stream().filter(p -> name.isEmpty() || p.getFileName().toString().equals(name)).findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void onClose() {
        apply();
        minecraft.gui.setScreen(lastScreen);
    }

    private void openFolder() {
        Path dir = MetalShadersClient.shaderpacksDir();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            MetalShadersClient.LOGGER.warn("Couldn't create {}: {}", dir, e.toString());
        }
        com.mojang.blaze3d.Blaze3D.openPath(dir);
    }

    private void click() {
        minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    // ---- for the game test ----------------------------------------------------------------

    /** The pack names listed, in order. */
    public List<String> packNames() {
        return list == null ? List.of() : list.children().stream()
                .filter(e -> e instanceof PackEntry).map(e -> ((PackEntry) e).name).toList();
    }

    /** As if the player clicked the on/off row. */
    public void clickToggle() {
        if (list != null && list.toggle.active) {
            list.toggle.press();
        }
    }

    /** As if the player clicked the named pack. */
    public void clickPack(String name) {
        if (list != null) {
            list.children().stream().filter(e -> e instanceof PackEntry p && p.name.equals(name))
                    .findFirst().ifPresent(e -> ((PackEntry) e).press());
        }
    }

    public boolean pendingEnabled() {
        return pendingEnabled;
    }

    // ---- the list -------------------------------------------------------------------------

    private abstract static class Row extends ObjectSelectionList.Entry<Row> {
    }

    private final class PackList extends ObjectSelectionList<Row> {
        private final ToggleEntry toggle = new ToggleEntry();

        PackList(Minecraft minecraft) {
            super(minecraft, ShaderPackScreen.this.width, ShaderPackScreen.this.layout.getContentHeight(),
                    ShaderPackScreen.this.layout.getHeaderHeight(), 20);
            addEntry(toggle);
            List<Path> packs;
            try {
                packs = PackReader.findPacks(MetalShadersClient.shaderpacksDir());
            } catch (IOException e) {
                MetalShadersClient.LOGGER.error("Couldn't list the shader packs folder", e);
                packs = List.of();
            }
            toggle.active = !packs.isEmpty();
            // An empty shaderPack means the first pack found (LuxiumConfig): show that one as applied.
            if (appliedPack.isEmpty() && !packs.isEmpty()) {
                appliedPack = packs.getFirst().getFileName().toString();
            }
            String chosen = pendingPack != null ? pendingPack : packs.isEmpty() ? "" : packs.getFirst().getFileName().toString();
            for (Path pack : packs) {
                PackEntry entry = new PackEntry(pack.getFileName().toString());
                addEntry(entry);
                if (entry.name.equals(chosen)) {
                    setSelected(entry);
                }
            }
            if (getSelected() != null) {
                centerScrollOn(getSelected());
            }
        }

        /** The on/off row is a button, never the selected row. */
        @Override
        public void setSelected(@Nullable Row row) {
            if (!(row instanceof ToggleEntry)) {
                super.setSelected(row);
            }
        }

        @Override
        public int getRowWidth() {
            return 300;
        }
    }

    private final class ToggleEntry extends Row {
        boolean active;

        Component label() {
            if (!active) {
                return Component.translatable("luxium.shaderPacks.nonePresent").withStyle(ChatFormatting.GRAY);
            }
            return Component.translatable(pendingEnabled ? "luxium.shaderPacks.enabled" : "luxium.shaderPacks.disabled");
        }

        void press() {
            pendingEnabled = !pendingEnabled;
            click();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            int x0 = getContentX();
            int x1 = x0 + getContentWidth();
            int y0 = getContentY();
            int y1 = y0 + getContentHeight();
            if (active && hovered) {
                graphics.fill(x0, y0, x1, y1, 0x30FFFFFF);
            }
            MutableComponent text = label().copy();
            if (active && hovered) {
                text = text.withStyle(ChatFormatting.BOLD);
            }
            graphics.centeredText(font, text, getContentXMiddle(), getContentYMiddle() - 4, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (!active || event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }
            press();
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (active && event.isConfirmation()) {
                press();
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return label();
        }
    }

    private final class PackEntry extends Row {
        final String name;

        PackEntry(String name) {
            this.name = name;
        }

        void press() {
            if (list != null) {
                list.setSelected(this);
            }
            pendingPack = name;
            // As Iris: picking a pack while shaders are off turns them on (players didn't realise
            // they had to turn shaders on first).
            pendingEnabled = true;
            click();
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            String shown = name;
            int room = getContentWidth() - 8;
            if (font.width(Component.literal(shown).withStyle(ChatFormatting.BOLD)) > room) {
                shown = font.plainSubstrByWidth(shown, room - font.width("...")) + "...";
            }
            MutableComponent text = Component.literal(shown);
            if (hovered) {
                text = text.withStyle(ChatFormatting.BOLD);
            }
            int color = 0xFFFFFFFF;
            if (pendingEnabled && name.equals(appliedPack) && appliedEnabled) {
                color = APPLIED_COLOR;
            }
            if (!pendingEnabled && !hovered) {
                color = OFF_COLOR;
            }
            graphics.centeredText(font, text, getContentXMiddle(), getContentYMiddle() - 4, color);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            if (event.button() != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                return false;
            }
            press();
            if (doubleClick) {
                onClose();
            }
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (event.isSelection()) {
                press();
                return true;
            }
            return false;
        }

        @Override
        public Component getNarration() {
            return Component.translatable("narrator.select", name);
        }
    }
}
