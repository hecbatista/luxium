package io.github.hecbatista.metalshaders.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import io.github.hecbatista.metalshaders.gui.ShaderPackScreen;

/**
 * Mod Menu's "Configure" button for Luxium opens the Shader Packs screen. Loaded only by Mod Menu
 * (fabric.mod.json "modmenu" entrypoint), so Luxium runs without it.
 */
public final class ModMenuEntry implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ShaderPackScreen::new;
    }
}
