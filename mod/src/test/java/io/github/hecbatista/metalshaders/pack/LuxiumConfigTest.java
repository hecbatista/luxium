package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuxiumConfigTest {
    @TempDir
    Path dir;

    private Path shaderpacks() throws IOException {
        Path packs = Files.createDirectories(dir.resolve("shaderpacks"));
        Files.writeString(packs.resolve("Zeta.zip"), "not read here");
        Files.writeString(packs.resolve("Alpha.zip"), "not read here");
        Files.createDirectories(packs.resolve("Folder Pack/shaders"));
        Files.writeString(packs.resolve("Alpha.zip.txt"), "SETTING=1");   // a settings file, not a pack
        return packs;
    }

    private Properties file(Path config) throws IOException {
        Properties p = new Properties();
        p.load(new StringReader(Files.readString(config)));
        return p;
    }

    @Test
    void noFileMeansOnAndTheFirstPackWhichIsThenWritten() throws IOException {
        Path packs = shaderpacks();
        Path config = dir.resolve("config/luxium.properties");
        assertTrue(LuxiumConfig.enabled(config));
        LuxiumConfig.Choice c = LuxiumConfig.choose(packs, config);
        assertEquals("Alpha.zip", c.pack().getFileName().toString());
        Properties written = file(config);
        assertEquals("true", written.getProperty("enableShaders"));
        assertEquals("Alpha.zip", written.getProperty("shaderPack"));
        assertTrue(Files.readString(config).contains("# Packs found: Alpha.zip, Folder Pack, Zeta.zip"));
        // A pack added later (sorting first) doesn't change the choice.
        Files.writeString(packs.resolve("Aardvark.zip"), "x");
        assertEquals("Alpha.zip", LuxiumConfig.choose(packs, config).pack().getFileName().toString());
    }

    @Test
    void aNamedPackIsUsedIncludingFoldersWithSpaces() throws IOException {
        Path packs = shaderpacks();
        Path config = dir.resolve("config/luxium.properties");
        LuxiumConfig.writeOverride(config, "shaderPack=Folder Pack");
        assertEquals("Folder Pack", LuxiumConfig.choose(packs, config).pack().getFileName().toString());
    }

    @Test
    void aMissingNameMeansNoPackNotAnother() throws IOException {
        Path packs = shaderpacks();
        Path config = dir.resolve("config/luxium.properties");
        LuxiumConfig.writeOverride(config, "shaderPack=Gone.zip");
        LuxiumConfig.Choice c = LuxiumConfig.choose(packs, config);
        assertNull(c.pack());
        assertTrue(c.enabled());
        assertTrue(c.reason().contains("Gone.zip") && c.reason().contains("Alpha.zip"), c.reason());
        assertEquals("Gone.zip", file(config).getProperty("shaderPack"), "the player's choice is kept");
    }

    @Test
    void offMeansNoPack() throws IOException {
        Path packs = shaderpacks();
        Path config = dir.resolve("config/luxium.properties");
        LuxiumConfig.writeOverride(config, "enableShaders=false;shaderPack=Zeta.zip");
        assertFalse(LuxiumConfig.enabled(config));
        LuxiumConfig.Choice c = LuxiumConfig.choose(packs, config);
        assertNull(c.pack());
        assertFalse(c.enabled());
        assertEquals("Zeta.zip", file(config).getProperty("shaderPack"));
        assertEquals("false", file(config).getProperty("enableShaders"));
    }

    @Test
    void noPacksAtAll() throws IOException {
        Path config = dir.resolve("config/luxium.properties");
        LuxiumConfig.Choice c = LuxiumConfig.choose(dir.resolve("shaderpacks"), config);
        assertNull(c.pack());
        assertTrue(Files.readString(config).contains("# Packs found: (none)"));
        assertEquals("", file(config).getProperty("shaderPack"));
    }

    @Test
    void writtenChoicesAreWhatTheNextChooseUses() throws IOException {
        Path packs = shaderpacks();
        Path config = dir.resolve("config/luxium.properties");
        LuxiumConfig.write(packs, config, true, "Zeta.zip");
        assertEquals("Zeta.zip", LuxiumConfig.choose(packs, config).pack().getFileName().toString());
        assertTrue(Files.readString(config).contains("# Packs found: Alpha.zip, Folder Pack, Zeta.zip"));
        // Off keeps the pack's name, so turning shaders back on returns to it.
        LuxiumConfig.write(packs, config, false, "Zeta.zip");
        assertFalse(LuxiumConfig.enabled(config));
        assertNull(LuxiumConfig.choose(packs, config).pack());
        assertEquals("Zeta.zip", file(config).getProperty("shaderPack"));
        LuxiumConfig.write(packs, config, true, "Zeta.zip");
        assertEquals("Zeta.zip", LuxiumConfig.choose(packs, config).pack().getFileName().toString());
    }
}
