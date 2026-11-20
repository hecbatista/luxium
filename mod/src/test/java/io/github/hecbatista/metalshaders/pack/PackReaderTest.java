package io.github.hecbatista.metalshaders.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each test builds a tiny fake pack in a temporary folder, then reads it. */
class PackReaderTest {
    @TempDir
    Path tmp;

    /** Writes the given files (path -> text) under a folder. */
    private static Path folderPack(Path dir, Map<String, String> files) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path target = dir.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        return dir;
    }

    /** Writes the given files (path -> text) into a zip. */
    private static Path zipPack(Path zip, Map<String, String> files) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                z.putNextEntry(new ZipEntry(file.getKey()));
                z.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void folderPackWithWorldFoldersButNoDimensionFile() throws IOException {
        Path pack = folderPack(tmp.resolve("MyPack"), Map.of(
                "shaders/world0/composite.fsh", "",
                "shaders/world0/composite.vsh", "",
                "shaders/world0/common.glsl", "",       // not a program: only reached by #include
                "shaders/world-1/final.fsh", "",
                "shaders/lib/gbuffers_basic.fsh", ""));  // a program name, but not in a world folder

        PackInfo info = PackReader.read(pack);

        assertEquals("MyPack", info.name());
        assertEquals("shaders", info.shadersRoot());
        assertFalse(info.fromDimensionFile());
        assertEquals(Map.of(
                "*", "world0",
                "minecraft:overworld", "world0",
                "minecraft:the_nether", "world-1"), info.dimensionFolders());
        assertEquals(List.of("composite.fsh", "composite.vsh"), info.programFiles().get("world0"));
        assertEquals(List.of("final.fsh"), info.programFiles().get("world-1"));
        assertEquals(List.of(), info.programFiles().get(""));
        assertFalse(info.programFiles().containsKey("lib"));
    }

    @Test
    void dimensionFileDecidesTheFolders() throws IOException {
        // Same shape as Complementary's dimension.properties.
        Path pack = folderPack(tmp.resolve("Dims"), Map.of(
                "shaders/dimension.properties", """
                        # comment lines are ignored
                        dimension.world0=*
                        dimension.world-1=minecraft:the_nether minecraft:nether
                        dimension.custom_end=minecraft:the_end
                        """,
                "shaders/world0/final.fsh", "",
                "shaders/world-1/final.fsh", "",
                "shaders/custom_end/final.fsh", "",
                "shaders/custom_end/final.vsh", ""));

        PackInfo info = PackReader.read(pack);

        assertTrue(info.fromDimensionFile());
        assertEquals(Map.of(
                "*", "world0",
                "minecraft:the_nether", "world-1",
                "minecraft:nether", "world-1",
                "minecraft:the_end", "custom_end"), info.dimensionFolders());
        assertEquals(List.of("final.fsh", "final.vsh"), info.programFiles().get("custom_end"));
    }

    @Test
    void zipPackReadsTheSameAsAFolder() throws IOException {
        Path zip = zipPack(tmp.resolve("Zipped.zip"), Map.of(
                "shaders/world0/gbuffers_terrain.vsh", "",
                "shaders/world0/gbuffers_terrain.fsh", "",
                "shaders/shadowcomp.csh", ""));

        PackInfo info = PackReader.read(zip);

        assertEquals("Zipped", info.name());
        assertEquals("shaders", info.shadersRoot());
        assertEquals(List.of("gbuffers_terrain.fsh", "gbuffers_terrain.vsh"), info.programFiles().get("world0"));
        assertEquals(List.of("shadowcomp.csh"), info.programFiles().get(""));
    }

    @Test
    void zipWithAnExtraWrapperFolder() throws IOException {
        // Some packs are re-zipped from a GitHub download: Pack-main/shaders/...
        Path zip = zipPack(tmp.resolve("Wrapped.zip"), Map.of(
                "Pack-main/README.md", "",
                "Pack-main/shaders/final.fsh", ""));

        PackInfo info = PackReader.read(zip);

        assertEquals("Pack-main/shaders", info.shadersRoot());
        assertEquals(List.of("final.fsh"), info.programFiles().get(""));
        assertEquals(Map.of(), info.dimensionFolders());   // no world folders: everything uses the root
    }

    @Test
    void packWithoutShadersFolderIsAnError() throws IOException {
        Path zip = zipPack(tmp.resolve("NotAPack.zip"), Map.of("readme.txt", "hello"));

        assertThrows(IOException.class, () -> PackReader.read(zip));
    }

    @Test
    void findPacksListsZipsAndPackFoldersOnly() throws IOException {
        Path shaderpacks = Files.createDirectories(tmp.resolve("shaderpacks"));
        zipPack(shaderpacks.resolve("B.zip"), Map.of("shaders/final.fsh", ""));
        folderPack(shaderpacks.resolve("A"), Map.of("shaders/final.fsh", ""));
        folderPack(shaderpacks.resolve("NotAPack"), Map.of("notes.txt", ""));
        Files.writeString(shaderpacks.resolve("B.zip.txt"), "Iris writes settings files like this");

        List<String> names = PackReader.findPacks(shaderpacks).stream()
                .map(p -> p.getFileName().toString())
                .toList();

        assertEquals(List.of("A", "B.zip"), names);
    }

    @Test
    void missingShaderpacksFolderMeansNoPacks() throws IOException {
        assertEquals(List.of(), PackReader.findPacks(tmp.resolve("does-not-exist")));
    }
}
