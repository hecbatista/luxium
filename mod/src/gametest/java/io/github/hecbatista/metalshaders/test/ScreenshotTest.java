package io.github.hecbatista.metalshaders.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
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
 * full of water, an oak-log block, a gold block, a leaves block, a pig, a chest, a slime, a spider and a beacon. Shot 1 looks at the box from
 * outside; shot 2 is from inside the water; shot 3 is the pig just hurt; shot 4 is rain; shot 5 is the hand holding a sword.
 */
@SuppressWarnings("UnstableApiUsage")
public class ScreenshotTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        copyShaderPacks();

        boolean walkOnly = System.getenv("LUXIUM_TEST_WALK_ONLY") != null;
        // The usual test world is flat (Fabric's consistent settings); the walk-only run uses a
        // normally generated world with a fixed seed, like a player's.
        var builder = walkOnly
                ? context.worldBuilder().setUseConsistentSettings(false).adjustSettings(state -> state.setSeed("20261121"))
                : context.worldBuilder();
        try (TestSingleplayerContext singleplayer = builder.create()) {
            singleplayer.getConnection().waitForChunksRender();
            TestServerContext server = singleplayer.getServer();
            // Spectator: no falling between teleports, and no hand or hotbar in the shot.
            server.runCommand("gamemode spectator @a");
            context.runOnClient(mc -> {
                if (!mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();   // like pressing F1 (Hud.java:213)
                }
                if (io.github.hecbatista.metalshaders.render.PerfStats.ON) {
                    mc.options.framerateLimit().set(net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF);
                    mc.options.enableVsync().set(false);
                    // Otherwise the game drops to 30 fps after a minute without input (AFK).
                    mc.options.inactivityFpsLimit().set(net.minecraft.client.InactivityFpsLimit.MINIMIZED);
                }
            });
            server.runCommand("time set noon");
            server.runCommand("weather clear");
            // LUXIUM_TEST_WALK_ONLY=1: only the walking clip (for chasing flashes quickly).
            if (walkOnly) {
                // As a player plays: Creative, the HUD on, a sword in hand, over land near spawn.
                server.runCommand("gamemode creative @a");
                server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_sword");
                context.runOnClient(mc -> {
                    if (mc.gui.hud.isHidden()) {
                        mc.gui.hud.toggle();
                    }
                    mc.player.getAbilities().flying = true;
                });
                look(context, singleplayer, server, "look-land", 240, 0, 0);
                // A fast turn with the sun low (the user saw the sun "moving crazy" while turning).
                if (System.getenv("LUXIUM_TEST_FREEZE_TIME") != null) {
                    server.runCommand("gamerule advance_time false");   // the sun stays put: only the turn changes
                }
                server.runCommand("time set " + System.getenv().getOrDefault("LUXIUM_TEST_TURN_TIME", "1000"));
                context.waitTicks(10);
                final float turnPitch = Float.parseFloat(System.getenv().getOrDefault("LUXIUM_TEST_TURN_PITCH", "-5"));
                Path turnDir = FabricLoader.getInstance().getGameDir().resolve("screenshots/frames-turn");
                for (int i = 0; i < 60; i++) {
                    final int step = i;
                    context.runOnClient(mc -> {
                        mc.player.setYRot(-90f + step * 12f);
                        mc.player.setXRot(turnPitch);
                    });
                    context.waitTick();
                    context.takeScreenshot(TestScreenshotOptions.of(String.format("%03d", i))
                            .disableCounterPrefix().withDestinationDir(turnDir));
                }
                server.runCommand("time set 5500");
                // Creep: the camera moves 0.02 blocks a tick, looking down at sunlit ground near
                // trees. The picture hardly changes, so shadows that flicker because the camera
                // moved show up in the pixel check (the user saw grey streaks flicker on sand).
                server.runCommand("gamemode spectator @a");
                context.runOnClient(mc -> {
                    double x = 6.5;
                    double z = 6.5;
                    int ground = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 6, 6);
                    mc.player.setPos(x, ground + 2.0, z);
                    mc.player.setYRot(45f);
                    mc.player.setXRot(35f);
                });
                context.waitTicks(40);
                Path creepDir = FabricLoader.getInstance().getGameDir().resolve("screenshots/frames-creep");
                for (int i = 0; i < 48; i++) {
                    final int step = i;
                    context.runOnClient(mc -> mc.player.setPos(6.5 + step * 0.02, mc.player.getY(), 6.5));
                    context.waitTick();
                    context.takeScreenshot(TestScreenshotOptions.of(String.format("%03d", i))
                            .disableCounterPrefix().withDestinationDir(creepDir));
                }
                server.runCommand("gamemode creative @a");
                server.runCommand("time set noon");
                walk(context, singleplayer, server, "walk", 120);
                // Then hold still 4 blocks under the ocean's surface (underwater is where the flash
                // probe found flashes, 2026-11-21).
                context.runOnClient(mc -> {
                    double x = mc.player.getX();
                    double z = mc.player.getZ();
                    int surface = mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z));
                    mc.player.setPos(x, surface - 4.0, z);
                    mc.player.setXRot(10f);
                });
                context.waitTicks(100);
                context.takeScreenshot("luxium-walk-underwater");
                return;
            }
            server.runCommand("fill -12 99 -12 12 99 12 minecraft:stone");
            server.runCommand("fill -6 100 -6 6 106 6 minecraft:glass hollow");
            server.runCommand("fill -5 101 -5 5 105 5 minecraft:water");
            // LUXIUM_TEST_BOX_ONLY=1: just 5 s inside the water-filled glass box, still (where the
            // flash probe found flashes in the full run, 2026-11-21).
            if (System.getenv("LUXIUM_TEST_BOX_ONLY") != null) {
                server.runCommand("tp @a 0 102 0 0 0");
                context.waitTicks(20);
                singleplayer.getConnection().waitForChunksRender();
                context.waitTicks(100);
                context.takeScreenshot("luxium-box-underwater");
                return;
            }
            server.runCommand("fill -12 100 9 -8 102 12 minecraft:oak_log");
            server.runCommand("fill 8 100 9 12 101 12 minecraft:gold_block");
            // Cutout terrain (leaves are drawn in Sodium's cutout pass, not the solid one).
            server.runCommand("fill 8 100 -6 11 101 -3 minecraft:oak_leaves[persistent=true]");
            // Entities: a mob that stands still, and a block entity.
            server.runCommand("summon minecraft:pig -4 100 -7 {NoAI:1b,Rotation:[150f,0f]}");
            server.runCommand("setblock -7 100 -5 minecraft:chest[facing=north]");
            // A translucent entity (a slime's outer layer is drawn blended).
            server.runCommand("summon minecraft:slime 4 100 -8 {NoAI:1b,Size:1,Rotation:[200f,0f]}");
            // A spider (its glowing eyes are their own pipeline) and a beacon (its beam is another).
            server.runCommand("summon minecraft:spider 1 100 -5 {NoAI:1b,Rotation:[180f,0f]}");
            server.runCommand("fill 5 99 -4 7 99 -2 minecraft:iron_block");
            server.runCommand("setblock 6 100 -3 minecraft:beacon");

            // Shot 1: outside, looking down at the box (yaw 0 faces +z, pitch 25 looks down a bit).
            server.runCommand("tp @a 0 104 -14 0 25");
            context.waitTicks(60);
            singleplayer.getConnection().waitForChunksRender();
            // Particles, spawned just before the shot so they're still alive.
            server.runCommand("particle minecraft:flame 0 101 -9 2 0.5 0.5 0 40 force");
            server.runCommand("particle minecraft:happy_villager 0 101.5 -9 2 0.5 0.5 0 40 force");
            context.waitTicks(3);
            context.takeScreenshot("luxium-1-land");

            // Frame sequences for flicker checks (scripts/FlickerCheck.java): a still camera, one
            // frame per tick. Wait for the particles to die first so only the scene's own motion
            // (water, leaves, clouds) is left.
            context.waitTicks(60);
            captureFrames(context, "land", 24);
            measure(context, "land");
            server.runCommand("tp @a 0 102 0 0 0");
            context.waitTicks(40);
            captureFrames(context, "underwater", 24);
            measure(context, "underwater");

            // Shot 3: the pig just hurt (the red hit flash is entityColor in the pack).
            server.runCommand("tp @a -4 101.5 -10.5 0 30");
            context.waitTicks(20);
            server.runCommand("damage @e[type=minecraft:pig,limit=1] 0.5 minecraft:generic");
            // Translucent particles (campfire smoke is in the game's TRANSLUCENT particle layer).
            server.runCommand("particle minecraft:campfire_cosy_smoke -3 101 -7 0.3 0.2 0.3 0.01 6 force");
            context.waitTicks(2);
            context.takeScreenshot("luxium-3-hurt");

            // Shot 2: inside the water.
            server.runCommand("tp @a 0 102 0 0 0");
            context.waitTicks(60);
            context.takeScreenshot("luxium-2-underwater");

            // Shot 4: rain, from shot 1's spot (the rain level ramps up over a few seconds).
            server.runCommand("weather rain");
            server.runCommand("tp @a 0 104 -14 0 25");
            context.waitTicks(140);
            context.takeScreenshot("luxium-4-rain");
            server.runCommand("weather clear");

            // Shot 5: the player's hand holding an item (needs a non-spectator player and the HUD on).
            server.runCommand("gamemode creative @a");
            server.runCommand("item replace entity @a weapon.mainhand with minecraft:iron_sword");
            server.runCommand("tp @a 0 100 -14 0 10");
            context.runOnClient(mc -> {
                if (mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();
                }
            });
            context.waitTicks(40);
            context.takeScreenshot("luxium-5-hand");

            // Shot 5b (step 7e): the player seen from behind (third person), so the player's own
            // body is drawn with the pack's current_player id (log: "Entity draw ... entityId").
            context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK));
            context.waitTicks(10);
            context.takeScreenshot("luxium-5b-third-person");
            context.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));

            // Shots 6-8 (step 7b): the other worlds load their own world folders (world-1, world1),
            // and coming back loads world0 again. Spectator and no HUD, as for the first shots.
            server.runCommand("gamemode spectator @a");
            context.runOnClient(mc -> {
                if (!mc.gui.hud.isHidden()) {
                    mc.gui.hud.toggle();
                }
            });

            // Shot 6: the Nether. Carve a room in whatever was generated, with a floor, a lava
            // pool, glowstone, nether bricks and soul fire, so the shot is the same every run
            // except for the walls behind the fog.
            server.runCommand("execute in minecraft:the_nether run tp @a 0 66 -9 0 15");
            context.waitTicks(20);
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand("execute in minecraft:the_nether run fill -12 60 -12 12 76 12 minecraft:air");
            server.runCommand("execute in minecraft:the_nether run fill -12 59 -12 12 59 12 minecraft:netherrack");
            server.runCommand("execute in minecraft:the_nether run fill -3 59 0 3 59 4 minecraft:lava");
            server.runCommand("execute in minecraft:the_nether run fill 6 60 2 7 63 3 minecraft:glowstone");
            server.runCommand("execute in minecraft:the_nether run fill -8 60 2 -6 62 4 minecraft:nether_bricks");
            server.runCommand("execute in minecraft:the_nether run setblock 4 59 -2 minecraft:soul_sand");
            server.runCommand("execute in minecraft:the_nether run setblock 4 60 -2 minecraft:soul_fire");
            context.waitTicks(60);
            singleplayer.getConnection().waitForChunksRender();
            context.takeScreenshot("luxium-6-nether");
            captureFrames(context, "nether", 24);
            measure(context, "nether");

            // Shot 7: the End, from beside the main island, looking back at it and the sky. Far
            // enough from the centre (more than 192 blocks: EndDragonFight's arena) that the
            // dragon's boss bar and its world fog stay away.
            // A small platform in front of the camera so the shot always has End terrain in it.
            server.runCommand("execute in minecraft:the_end run tp @a 0 72 -205 0 12");
            context.waitTicks(20);
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand("execute in minecraft:the_end run fill -5 64 -196 5 64 -186 minecraft:end_stone");
            server.runCommand("execute in minecraft:the_end run fill -4 65 -190 -3 68 -189 minecraft:purpur_block");
            server.runCommand("execute in minecraft:the_end run fill 3 65 -190 3 67 -190 minecraft:obsidian");
            server.runCommand("execute in minecraft:the_end run setblock 0 65 -188 minecraft:end_rod");
            server.runCommand("execute in minecraft:the_end run setblock 1 65 -192 minecraft:chorus_flower");
            context.waitTicks(60);
            singleplayer.getConnection().waitForChunksRender();
            context.takeScreenshot("luxium-7-end");
            captureFrames(context, "end", 24);
            measure(context, "end");

            // Shot 8: back in the Overworld, from shot 1's spot (world0 is loaded again).
            server.runCommand("execute in minecraft:overworld run tp @a 0 104 -14 0 25");
            context.waitTicks(20);
            singleplayer.getConnection().waitForChunksRender();
            context.waitTicks(40);
            context.takeScreenshot("luxium-8-back");

            // Shot 9 (step 7c): a caster the camera can't see. A tower stands east of a floor in the
            // morning sun (low in the east), and the camera looks west with the tower behind it:
            // the tower's long shadow should still lie on the floor in front. Look at the tower
            // first so Sodium has built its mesh. LUXIUM_SHADOW_CAMERA_LIST=1 shows the old
            // behaviour (no shadow).
            server.runCommand("time set 1500");
            // High up (y 160), above anything the world generated there. Go there first: a fill
            // only works in loaded chunks.
            server.runCommand("tp @a 100 167 100 -90 0");
            context.waitTicks(20);
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand("fill 70 160 85 125 160 115 minecraft:stone");
            server.runCommand("fill 118 161 98 121 191 102 minecraft:stone_bricks");
            context.waitTicks(40);
            singleplayer.getConnection().waitForChunksRender();
            server.runCommand("tp @a 108 167 100 90 40");
            context.waitTicks(40);
            singleplayer.getConnection().waitForChunksRender();
            context.takeScreenshot("luxium-9-offscreen-caster");
            captureFrames(context, "offscreen", 24);

            // A normal run: walking over generated terrain while looking around, one frame per
            // tick (flashes there need FlickerCheck --tiles, since everything moves).
            server.runCommand("time set noon");
            walk(context, singleplayer, server, "walk", 120);

            // Shot 10 (step 7d): a player turns on Improved Transparency. With a pack in use it's
            // switched straight back off (as under Iris), and shot 1's view looks as before.
            server.runCommand("time set noon");
            server.runCommand("tp @a 0 104 -14 0 25");
            context.runOnClient(mc -> mc.options.improvedTransparency().set(true));
            context.waitTicks(40);
            singleplayer.getConnection().waitForChunksRender();
            boolean stillOn = context.computeOnClient(mc -> mc.options.improvedTransparency().get());
            if (stillOn && System.getenv("LUXIUM_TEST_NO_PACK") == null) {
                throw new AssertionError("Improved Transparency should be switched off while a pack is in use");
            }
            context.takeScreenshot("luxium-10-no-oit");
        }
    }

    /**
     * LUXIUM_PERF=1 (step 7f): 5 seconds holding still, then the frame-time summary in the log
     * ("Perf [label]: ..."). Frame cap and vsync are off for these runs (see runTest).
     */
    private static void measure(ClientGameTestContext context, String label) {
        if (!io.github.hecbatista.metalshaders.render.PerfStats.ON) {
            return;
        }
        context.runOnClient(mc -> io.github.hecbatista.metalshaders.render.PerfStats.reset());
        context.waitTicks(100);
        context.runOnClient(mc -> io.github.hecbatista.metalshaders.render.PerfStats.report(label));
    }

    /**
     * Walks over the world's own terrain at x/z 300 (a few blocks above the ground, so the camera
     * never ends up inside it), turning slowly, and saves one frame per tick into
     * screenshots/frames-<name>/. The position is set on the client each tick, so the game blends
     * smoothly between ticks as it does for real movement.
     */
    private static void walk(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server,
                             String name, int ticks) {
        walk(context, singleplayer, server, name, ticks, 300, 300);
    }

    private static void walk(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server,
                             String name, int ticks, int startX, int startZ) {
        server.runCommand("tp @a " + startX + " 140 " + startZ + " 0 20");
        context.waitTicks(20);
        singleplayer.getConnection().waitForChunksRender();
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots/frames-" + name);
        for (int i = 0; i < ticks; i++) {
            final int step = i;
            double x = startX + step * 0.35;
            double z = startZ + step * 0.15;
            // The ground under the camera, from the client's copy of the world; then a server-side
            // teleport, so the server and the client agree on where the player is (moving the
            // player on the client alone gets it pulled back by the server: false flashes).
            int ground = context.computeOnClient(mc -> mc.level.getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(x), (int) Math.floor(z)));
            float yaw = -60f + step * 1.2f;
            float pitch = 15f + 10f * (float) Math.sin(step * 0.08);
            server.runCommand(String.format(java.util.Locale.ROOT, "tp @a %.3f %.3f %.3f %.2f %.2f", x, ground + 3.0, z, yaw, pitch));
            context.waitTick();
            context.takeScreenshot(TestScreenshotOptions.of(String.format("%03d", i))
                    .disableCounterPrefix().withDestinationDir(dir));
        }
    }

    /**
     * Stands still on the ground near (x, z) and only turns the head (rotation is the client's
     * own, so the server never pulls the camera back), one frame per tick.
     */
    private static void look(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server,
                             String name, int ticks, int x, int z) {
        server.runCommand("tp @a " + x + " 140 " + z + " 0 20");
        context.waitTicks(20);
        singleplayer.getConnection().waitForChunksRender();
        int ground = context.computeOnClient(mc -> mc.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z));
        server.runCommand("tp @a " + x + ".5 " + (ground + 1) + " " + z + ".5 0 10");
        context.runOnClient(mc -> mc.player.getAbilities().flying = false);
        context.waitTicks(40);
        singleplayer.getConnection().waitForChunksRender();
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots/frames-" + name);
        for (int i = 0; i < ticks; i++) {
            final int step = i;
            context.runOnClient(mc -> {
                mc.player.setYRot(step * 1.5f);
                mc.player.setXRot(10f + 8f * (float) Math.sin(step * 0.05));
            });
            context.waitTick();
            context.takeScreenshot(TestScreenshotOptions.of(String.format("%03d", i))
                    .disableCounterPrefix().withDestinationDir(dir));
        }
    }

    /** {@code count} consecutive frames, one per tick, into screenshots/frames-<name>/00.png, 01.png... */
    private static void captureFrames(ClientGameTestContext context, String name, int count) {
        Path dir = FabricLoader.getInstance().getGameDir().resolve("screenshots/frames-" + name);
        for (int i = 0; i < count; i++) {
            context.takeScreenshot(TestScreenshotOptions.of(String.format("%02d", i))
                    .disableCounterPrefix().withDestinationDir(dir));
            context.waitTick();
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
        // LUXIUM_TEST_NO_PACK=1: no pack, for a baseline of the game on Metallum alone.
        if (!Files.isDirectory(source) || System.getenv("LUXIUM_TEST_NO_PACK") != null) {
            return;
        }
        try (Stream<Path> packs = Files.list(source)) {
            Files.createDirectories(target);
            for (Path pack : packs.filter(Files::isRegularFile).toList()) {
                Files.copy(pack, target.resolve(pack.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                // LUXIUM_TEST_SETTINGS="NAME=value;NAME2=value2" writes the pack's settings file
                // (shaderpacks/<pack>.txt, as Iris names it), to check that changed settings apply.
                String settings = System.getenv("LUXIUM_TEST_SETTINGS");
                if (settings != null && pack.getFileName().toString().endsWith(".zip")) {
                    Files.writeString(target.resolve(pack.getFileName() + ".txt"), settings.replace(';', '\n') + "\n");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
