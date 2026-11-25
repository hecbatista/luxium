package io.github.hecbatista.metalshaders.gui;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Luxium's key: I opens the Shader Packs screen, as in Iris 26.3 (Iris.java: KEY_I; vanilla 26.3
 * binds O to its friends list); rebindable in Controls under
 * "Luxium". Added to Options.keyMappings by OptionsKeysMixin, so it needs no Fabric API.
 */
public final class LuxiumKeys {
    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("luxium", "luxium"));
    public static final KeyMapping SHADER_PACKS = new KeyMapping("key.luxium.shaderPacks", InputConstants.KEY_I, CATEGORY);

    private LuxiumKeys() {
    }
}
