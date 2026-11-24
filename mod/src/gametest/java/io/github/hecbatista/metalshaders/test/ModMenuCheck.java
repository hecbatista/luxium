package io.github.hecbatista.metalshaders.test;

import com.terraformersmc.modmenu.ModMenu;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.hecbatista.metalshaders.gui.ShaderPackScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;

/**
 * With Mod Menu installed (LUXIUM_TEST_MODMENU=1): Luxium's "Configure" button exists and opens
 * the Shader Packs screen, and Done goes back to Mod Menu's list. Only loaded when Mod Menu is.
 */
@SuppressWarnings("UnstableApiUsage")
final class ModMenuCheck {
    private ModMenuCheck() {
    }

    /** From wherever the game is now (title screen or world). */
    static void run(ClientGameTestContext context, String shotPrefix) {
        if (!context.computeOnClient(mc -> ModMenu.hasConfigScreen("luxium"))) {
            throw new AssertionError("Mod Menu has no Configure screen for Luxium");
        }
        context.setScreen(() -> ModMenuApi.createModsScreen(net.minecraft.client.Minecraft.getInstance().gui.screen()));
        context.waitTicks(5);
        context.takeScreenshot(shotPrefix + "-mods-list");
        Screen mods = context.computeOnClient(mc -> mc.gui.screen());
        context.setScreen(() -> ModMenu.getConfigScreen("luxium", mods));
        context.waitForScreen(ShaderPackScreen.class);
        context.waitTicks(3);
        context.takeScreenshot(shotPrefix + "-configure");
        context.clickScreenButton("gui.done");
        context.waitTicks(3);
        if (context.computeOnClient(mc -> mc.gui.screen() != mods)) {
            throw new AssertionError("Done should go back to Mod Menu's list");
        }
        System.out.println("[Luxium menus] Mod Menu Configure opens the Shader Packs screen (" + shotPrefix + ")");
    }
}
