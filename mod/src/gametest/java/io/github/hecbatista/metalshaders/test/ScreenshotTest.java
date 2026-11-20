package io.github.hecbatista.metalshaders.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Loads a world, builds the same small scene every time, and saves screenshots, so changes to
 * the render pipeline can be checked without anyone playing. Run with
 * {@code ./gradlew runClientGameTest}; screenshots land in build/run/clientGameTest/screenshots/.
 * <p>
 * The scene, made with commands at noon in clear weather: a stone floor and a sealed glass box
 * full of water, an oak-log block, a gold block, a leaves block, a pig and a chest. Shot 1 looks at the box from
 * outside; shot 2 is from inside the water; shot 3 is the pig just hurt.
 */
@SuppressWarnings("UnstableApiUsage")
public class ScreenshotTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        copyShaderPacks();

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getConnection().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            // Spectator: no falling between teleports, and no hand or hotbar in the shot.
            server.runCommand("gamemode spectator @a");
            context.runOnClient(mc -> {
                if (!mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();   // like pressing F1 (Hud.java:213)
                }
            });
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            server.runCommand("fill -12 99 -12 12 99 12 minecraft:stone");
            server.runCommand("fill -6 100 -6 6 106 6 minecraft:glass hollow");
            server.runCommand("fill -5 101 -5 5 105 5 minecraft:water");
            server.runCommand("fill -12 100 9 -8 102 12 minecraft:oak_log");
            server.runCommand("fill 8 100 9 12 101 12 minecraft:gold_block");
            // Cutout terrain (leaves are drawn in Sodium's cutout pass, not the solid one).
            server.runCommand("fill 8 100 -6 11 101 -3 minecraft:oak_leaves[persistent=true]");
            // Entities: a mob that stands still, and a block entity.
            server.runCommand("summon minecraft:pig -4 100 -7 {NoAI:1b,Rotation:[150f,0f]}");
            server.runCommand("setblock -7 100 -5 minecraft:chest[facing=north]");
            // A translucent entity (a slime's outer layer is drawn blended).
            server.runCommand("summon minecraft:slime 4 100 -8 {NoAI:1b,Size:1,Rotation:[200f,0f]}");

            // Shot 1: outside, looking down at the box (yaw 0 faces +z, pitch 25 looks down a bit).
            server.runCommand("tp @a 0 104 -14 0 25");
            context.waitTicks(60);
            singleplayer.getConnection().waitForChunksRender();
            context.takeScreenshot("luxium-1-land");

            // Shot 3: the pig just hurt (the red hit flash is entityColor in the pack).
            server.runCommand("tp @a -4 101.5 -10.5 0 30");
            context.waitTicks(20);
            server.runCommand("damage @e[type=minecraft:pig,limit=1] 0.5 minecraft:generic");
            context.waitTicks(2);
            context.takeScreenshot("luxium-3-hurt");

            // Shot 2: inside the water.
            server.runCommand("tp @a 0 102 0 0 0");
            context.waitTicks(60);
            context.takeScreenshot("luxium-2-underwater");
        }
    }

    /**
     * The test run's game folder (build/run/clientGameTest) is cleared before each run, so copy
     * the developer's packs from mod/run/shaderpacks/ into it.
     */
    private static void copyShaderPacks() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path source = gameDir.resolve("../../../run/shaderpacks").normalize();
        Path target = gameDir.resolve("shaderpacks");
        if (!Files.isDirectory(source)) {
            return;
        }
        try (Stream<Path> packs = Files.list(source)) {
            Files.createDirectories(target);
            for (Path pack : packs.filter(Files::isRegularFile).toList()) {
                Files.copy(pack, target.resolve(pack.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
