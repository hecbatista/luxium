package io.github.hecbatista.metalshaders.gui;

import io.github.hecbatista.metalshaders.MetalShadersClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Luxium's line on the F3 screen (26.3's debug entries, as Iris adds its own: IrisDebugEntry):
 * the version in light purple, then whether shaders are on and with which pack. On by default
 * while F3 is open (DebugScreenEntryListMixin); players can turn it off in Debug Options like any
 * other line.
 */
public final class LuxiumDebugEntry implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("luxium", "luxium");
    private static final String VERSION = FabricLoader.getInstance().getModContainer(MetalShadersClient.MOD_ID)
            .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");

    @Override
    public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk,
                        @Nullable LevelChunk serverChunk) {
        displayer.addToGroup(ID, line());
    }

    /** The line, with Minecraft's formatting codes: §d light purple, §b aqua, §7 grey, §c red. */
    public static String line() {
        String prefix = "§d[Luxium " + VERSION + "]§r ";
        String pack = MetalShadersClient.activePackName();
        if (MetalShadersClient.packActive()) {
            return prefix + "§bShaders on: " + pack;
        }
        if (MetalShadersClient.packFailed()) {
            return prefix + "§c" + pack + " couldn't be loaded: shaders off (see logs/latest.log)";
        }
        if (pack != null) {
            return prefix + "§7Loading " + pack + "...";
        }
        return prefix + "§7Shaders off";
    }
}
