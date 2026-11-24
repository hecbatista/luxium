package io.github.hecbatista.metalshaders.test;

import com.mojang.blaze3d.platform.InputConstants;
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
        PlaytestTour.copyShaderPacks();
        // LUXIUM_TOUR=1 runs PlaytestTour instead.
        if (System.getenv("LUXIUM_TOUR") != null) {
            return;
        }

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
            if (System.getenv("LUXIUM_TEST_TOGGLE") != null) {
                toggleTest(context, singleplayer, server);
                return;
            }
            if (System.getenv("LUXIUM_TEST_OPTIONS") != null) {
                optionsTest(context, singleplayer, server);
                return;
            }
            if (System.getenv("LUXIUM_TEST_MENUS") != null) {
                menuTest(context, singleplayer, server);
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

    /**
     * LUXIUM_TEST_TOGGLE=1 (menus slice A): shaders off and on while playing, through the same
     * calls the Shader Packs screen makes (LuxiumConfig.write, then MetalShadersClient.requestReload),
     * and a pack setting changed live. A still scene with nothing moving (time frozen, no entities,
     * no water, no sky in view), so a shot taken with shaders turned off live can be compared pixel
     * for pixel with the same shot from a run started with enableShaders=false
     * (scripts/ImageDiff.java). Shots: toggle-1-start, toggle-2-off, toggle-3-on, toggle-4-rapid,
     * toggle-5-grey (T_SATURATION=0.00), toggle-6-defaults; clip frames-toggle-still after it all.
     */
    private static void toggleTest(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server) {
        stillScene(context, singleplayer, server);
        context.takeScreenshot("toggle-1-start");

        setShaders(context, singleplayer, false);
        assertActive(context, false);
        context.takeScreenshot("toggle-2-off");
        context.waitTicks(200);
        assertActive(context, false);
        context.takeScreenshot("toggle-2b-off-later");

        setShaders(context, singleplayer, true);
        assertActive(context, true);
        context.takeScreenshot("toggle-3-on");

        // Off and on again on consecutive frames, ending on.
        for (int i = 0; i < 3; i++) {
            final boolean on = i % 2 == 1;
            context.runOnClient(mc -> write(on));
            context.waitTick();
        }
        setShaders(context, singleplayer, true);
        assertActive(context, true);
        context.takeScreenshot("toggle-4-rapid");

        // A pack setting changed live: the picture turns grey, then back.
        Path settings = context.computeOnClient(mc -> io.github.hecbatista.metalshaders.pack.PackOptions.settingsFile(firstPack()));
        try {
            Files.writeString(settings, "T_SATURATION=0.00\n");
            setShaders(context, singleplayer, true);
            context.takeScreenshot("toggle-5-grey");
            Files.deleteIfExists(settings);
            setShaders(context, singleplayer, true);
            context.takeScreenshot("toggle-6-defaults");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        context.waitTicks(20);
        captureFrames(context, "toggle-still", 24);
        setShaders(context, singleplayer, false);
        assertActive(context, false);
        context.takeScreenshot("toggle-7-off-again");
    }

    /** Time frozen, no entities or water, looking down at the floor (no sky or clouds in view). */
    private static void stillScene(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server) {
        server.runCommand("gamerule advance_time false");
        server.runCommand("fill -12 99 -12 12 99 12 minecraft:stone");
        server.runCommand("fill -12 100 9 -8 102 12 minecraft:oak_log");
        server.runCommand("fill 8 100 9 12 101 12 minecraft:gold_block");
        server.runCommand("fill -3 100 4 3 102 6 minecraft:glass");
        server.runCommand("fill 8 100 -6 11 101 -3 minecraft:oak_leaves[persistent=true]");
        server.runCommand("setblock 0 100 0 minecraft:chest[facing=north]");
        // LUXIUM_TEST_NO_OIT=1: start with the game's Improved Transparency off (a pack in use turns it off).
        if (System.getenv("LUXIUM_TEST_NO_OIT") != null) {
            context.runOnClient(mc -> {
                mc.options.improvedTransparency().set(false);
                mc.levelExtractor.allChanged();
            });
        }
        // Looking down at the floor: no sky or clouds in the picture.
        server.runCommand("tp @a 0 106 -10 0 40");
        context.waitTicks(40);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(40);
    }

    /**
     * LUXIUM_TEST_MENUS=1 (menus slice B): the Shader Packs screen through its entry points, with
     * real input: the Video Settings button, the I key, the on/off row, Apply, Done and Cancel.
     * Shots menu-1 to menu-7; every step checks what the screen wrote and what is drawn.
     */
    private static void menuTest(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server) {
        stillScene(context, singleplayer, server);
        context.runOnClient(mc -> {
            if (mc.gui.hud.isHidden()) {
                mc.gui.hud.toggle();
            }
        });
        assertActive(context, true);
        context.setScreen(() -> {
            var mc = net.minecraft.client.Minecraft.getInstance();
            return new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, mc, mc.options);
        });
        context.waitTicks(5);
        // Fabric's clickScreenButton doesn't look inside option lists: find the button ourselves,
        // scroll it into view for the shot, then press it.
        context.runOnClient(mc -> {
            net.minecraft.client.gui.components.Button b = findButton(mc.gui.screen(), "luxium.shaderPacks.button");
            if (b == null) {
                throw new AssertionError("No Shader Packs button in Video Settings");
            }
            // The entry's place (its widgets are only placed when drawn, and this one is off screen).
            var list = (net.minecraft.client.gui.components.OptionsList) mc.gui.screen().children().stream()
                    .filter(c -> c instanceof net.minecraft.client.gui.components.OptionsList).findFirst().orElseThrow();
            java.util.List<? extends net.minecraft.client.gui.components.events.GuiEventListener> rows = list.children();
            var entry = (net.minecraft.client.gui.layouts.LayoutElement) rows.stream()
                    .filter(e -> ((net.minecraft.client.gui.components.events.ContainerEventHandler) e).children().contains(b))
                    .findFirst().orElseThrow();
            list.setScrollAmount(list.scrollAmount() + entry.getY() - list.getY() - 40);
        });
        context.waitTicks(5);
        context.takeScreenshot("menu-1-video-settings");
        context.runOnClient(mc -> findButton(mc.gui.screen(), "luxium.shaderPacks.button").onPress(
                new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, 0, 0)));
        context.waitForScreen(io.github.hecbatista.metalshaders.gui.ShaderPackScreen.class);
        context.waitTicks(5);
        java.util.List<String> names = context.computeOnClient(mc -> ((io.github.hecbatista.metalshaders.gui.ShaderPackScreen) mc.gui.screen()).packNames());
        System.out.println("[Luxium menus] packs listed: " + names);
        if (names.isEmpty()) {
            throw new AssertionError("The Shader Packs screen lists no packs");
        }
        context.takeScreenshot("menu-2-pack-list");

        // Off, then Apply: the world behind the screen is drawn without the pack.
        context.runOnClient(mc -> ((io.github.hecbatista.metalshaders.gui.ShaderPackScreen) mc.gui.screen()).clickToggle());
        context.waitTicks(2);
        context.takeScreenshot("menu-3-toggled-off");
        context.clickScreenButton("luxium.shaderPacks.apply");
        context.waitTicks(5);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(20);
        assertActive(context, false);
        assertConfig(context, false);
        context.takeScreenshot("menu-4-applied-off");

        // Done goes back to Video Settings; then to the world.
        context.clickScreenButton("gui.done");
        context.waitForScreen(net.minecraft.client.gui.screens.options.VideoSettingsScreen.class);
        context.setScreen(() -> null);
        context.waitTicks(20);
        context.takeScreenshot("menu-5-world-off");

        // The I key, in the world: pick the pack (which turns shaders on, as in Iris), then Done.
        context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_I);
        context.waitForScreen(io.github.hecbatista.metalshaders.gui.ShaderPackScreen.class);
        context.waitTicks(2);
        context.runOnClient(mc -> ((io.github.hecbatista.metalshaders.gui.ShaderPackScreen) mc.gui.screen()).clickPack(names.getFirst()));
        context.waitTicks(2);
        context.takeScreenshot("menu-6-from-key-pack-picked");
        context.clickScreenButton("gui.done");
        context.waitTicks(5);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(40);
        if (context.computeOnClient(mc -> mc.gui.screen() != null)) {
            throw new AssertionError("Done should close the screen opened in the world");
        }
        assertActive(context, true);
        assertConfig(context, true);
        context.takeScreenshot("menu-7-world-on");

        // Cancel drops a change.
        context.getInput().pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_I);
        context.waitForScreen(io.github.hecbatista.metalshaders.gui.ShaderPackScreen.class);
        context.runOnClient(mc -> ((io.github.hecbatista.metalshaders.gui.ShaderPackScreen) mc.gui.screen()).clickToggle());
        context.clickScreenButton("gui.cancel");
        context.waitTicks(20);
        assertActive(context, true);
        assertConfig(context, true);
        // The F3 screen shows Luxium's line (on by default).
        context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(true));
        context.waitTicks(5);
        boolean shown = context.computeOnClient(mc -> mc.debugEntries.isCurrentlyEnabled(io.github.hecbatista.metalshaders.gui.LuxiumDebugEntry.ID));
        String line = context.computeOnClient(mc -> io.github.hecbatista.metalshaders.gui.LuxiumDebugEntry.line());
        System.out.println("[Luxium menus] F3 line shown " + shown + ": " + line);
        if (!shown || !line.contains("Shaders on")) {
            throw new AssertionError("F3 should show Luxium's line with shaders on");
        }
        context.takeScreenshot("menu-8-f3");
        context.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));
        System.out.println("[Luxium menus] all steps passed");
    }

    /**
     * LUXIUM_TEST_OPTIONS=1 (menus slice C): the pack's own option screens through the Shader Packs
     * screen, with real clicks: a sub-screen, the profile row, a slider dragged with the mouse, a
     * value cycled with right clicks, Shift-click to one default, Apply (the settings file and the
     * picture change), Esc back, Shift + Reset (the file goes away), and Done.
     */
    private static void optionsTest(ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server) {
        stillScene(context, singleplayer, server);
        Path settingsFile = context.computeOnClient(mc -> io.github.hecbatista.metalshaders.pack.PackOptions.settingsFile(firstPack()));
        context.setScreen(() -> new io.github.hecbatista.metalshaders.gui.ShaderPackScreen(null));
        context.waitTicks(3);
        context.clickScreenButton("luxium.shaderPacks.settings");
        context.waitForScreen(io.github.hecbatista.metalshaders.gui.ShaderOptionsScreen.class);
        context.waitTicks(5);
        context.takeScreenshot("options-1-main");

        clickCell(context, "[PERFORMANCE_SETTINGS]", false);
        context.waitTicks(5);
        String before = profileName(context);
        clickCell(context, "<profile>", false);
        context.waitTicks(3);
        String after = profileName(context);
        System.out.println("[Luxium options] profile " + before + " -> " + after);
        if (before.equals(after)) {
            throw new AssertionError("Clicking the profile row should change the profile");
        }
        // Hover a cell with a comment until its tooltip shows.
        hover(context, "SHADOW_QUALITY");
        context.waitTicks(15);
        context.takeScreenshot("options-2-performance-profile-tooltip");

        // Esc goes back to the main screen; then into the tone map screen.
        context.getInput().pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(3);
        if (context.computeOnClient(mc -> options(mc).screenId()) != null) {
            throw new AssertionError("Esc should go back to the main option screen");
        }
        clickCell(context, "[CAMERA_SETTINGS]", false);
        clickCell(context, "[TONEMAP_SETTINGS]", false);
        context.waitTicks(5);
        // Drag T_SATURATION's slider to the far left with the real mouse.
        int[] b = context.computeOnClient(mc -> options(mc).cellBounds("T_SATURATION"));
        if (b == null) {
            throw new AssertionError("T_SATURATION isn't drawn on the tone map screen");
        }
        moveTo(context, b[0] + b[2] / 2.0, b[1] + 10);
        context.getInput().holdMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        moveTo(context, b[0] + 1, b[1] + 10);
        context.waitTicks(2);
        context.getInput().releaseMouse(InputConstants.MOUSE_BUTTON_LEFT);
        context.waitTicks(2);
        assertValue(context, "T_SATURATION", "0.00");
        // TM_EXPOSURE: two right clicks, then Shift-click back to the default.
        clickCell(context, "TM_EXPOSURE", true);
        clickCell(context, "TM_EXPOSURE", true);
        String exposureDefault = context.computeOnClient(mc -> options(mc).settings().options.defaultValue("TM_EXPOSURE"));
        if (context.computeOnClient(mc -> options(mc).settings().current("TM_EXPOSURE")).equals(exposureDefault)) {
            throw new AssertionError("Right clicks should change TM_EXPOSURE");
        }
        context.getInput().holdKey(InputConstants.KEY_LSHIFT);
        clickCell(context, "TM_EXPOSURE", false);
        context.getInput().releaseKey(InputConstants.KEY_LSHIFT);
        assertValue(context, "TM_EXPOSURE", exposureDefault);
        moveTo(context, 2, 2);
        context.waitTicks(2);
        context.takeScreenshot("options-3-tonemap-pending");

        context.clickScreenButton("luxium.shaderPacks.apply");
        context.waitTicks(5);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(30);
        java.util.Map<String, String> written;
        try {
            written = io.github.hecbatista.metalshaders.pack.PackOptions.readSettings(settingsFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println("[Luxium options] settings file after Apply: " + written);
        if (!"0.00".equals(written.get("T_SATURATION")) || written.containsKey("TM_EXPOSURE")) {
            throw new AssertionError("The settings file should hold T_SATURATION=0.00 and no TM_EXPOSURE: " + written);
        }
        assertActive(context, true);
        context.takeScreenshot("options-4-applied-grey");

        // Shift + Reset: every option back to its default, applied; the file goes away.
        context.getInput().holdKey(InputConstants.KEY_LSHIFT);
        context.waitTicks(2);
        context.clickScreenButton("luxium.options.reset");
        context.getInput().releaseKey(InputConstants.KEY_LSHIFT);
        context.waitTicks(5);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(30);
        if (Files.exists(settingsFile)) {
            throw new AssertionError("Shift + Reset should delete " + settingsFile);
        }
        context.takeScreenshot("options-5-reset");
        context.clickScreenButton("gui.done");
        context.waitTicks(5);
        if (context.computeOnClient(mc -> mc.gui.screen() != null)) {
            throw new AssertionError("Done should close the option screens");
        }
        context.takeScreenshot("options-6-world");
        System.out.println("[Luxium options] all steps passed");
    }

    private static io.github.hecbatista.metalshaders.gui.ShaderOptionsScreen options(net.minecraft.client.Minecraft mc) {
        return (io.github.hecbatista.metalshaders.gui.ShaderOptionsScreen) mc.gui.screen();
    }

    private static void clickCell(ClientGameTestContext context, String key, boolean right) {
        if (!context.computeOnClient(mc -> options(mc).clickCell(key, right))) {
            throw new AssertionError("No " + key + " on the option screen " + context.computeOnClient(mc -> options(mc).screenId()));
        }
        context.waitTick();
    }

    private static String profileName(ClientGameTestContext context) {
        return context.computeOnClient(mc -> options(mc).settings().profile().current().map(p -> p.name()).orElse("Custom"));
    }

    private static void assertValue(ClientGameTestContext context, String name, String value) {
        String now = context.computeOnClient(mc -> options(mc).settings().current(name));
        if (!now.equals(value)) {
            throw new AssertionError(name + " should be " + value + ", is " + now);
        }
    }

    /** Moves the real cursor to GUI coordinates (MouseHandler scales window coordinates by gui/screen). */
    private static void moveTo(ClientGameTestContext context, double guiX, double guiY) {
        double[] p = context.computeOnClient(mc -> new double[]{
                guiX * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
                guiY * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight()});
        context.getInput().setCursorPos(p[0], p[1]);
    }

    private static void hover(ClientGameTestContext context, String key) {
        int[] b = context.computeOnClient(mc -> options(mc).cellBounds(key));
        if (b == null) {
            throw new AssertionError(key + " isn't drawn");
        }
        moveTo(context, b[0] + b[2] / 2.0, b[1] + 10);
    }

    /** The button showing {@code key}'s text anywhere in the screen's widget tree. */
    private static net.minecraft.client.gui.components.Button findButton(net.minecraft.client.gui.components.events.GuiEventListener node, String key) {
        if (node instanceof net.minecraft.client.gui.components.Button b
                && b.getMessage().getString().equals(net.minecraft.network.chat.Component.translatable(key).getString())) {
            return b;
        }
        if (node instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
            for (var child : c.children()) {
                var found = findButton(child, key);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void assertConfig(ClientGameTestContext context, boolean on) {
        boolean written;
        try {
            written = io.github.hecbatista.metalshaders.pack.LuxiumConfig.enabled(io.github.hecbatista.metalshaders.MetalShadersClient.configFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (written != on) {
            throw new AssertionError("config/luxium.properties should say enableShaders=" + on);
        }
    }

    /** Writes the choice as the Shader Packs screen does, reloads, and waits for the chunks. */
    private static void setShaders(ClientGameTestContext context, TestSingleplayerContext singleplayer, boolean on) {
        context.runOnClient(mc -> write(on));
        context.waitTicks(5);
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(40);
    }

    private static void write(boolean on) {
        FabricLoader loader = FabricLoader.getInstance();
        try {
            io.github.hecbatista.metalshaders.pack.LuxiumConfig.write(loader.getGameDir().resolve("shaderpacks"),
                    loader.getConfigDir().resolve("luxium.properties"), on, "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        io.github.hecbatista.metalshaders.MetalShadersClient.requestReload();
    }

    private static Path firstPack() {
        try {
            return io.github.hecbatista.metalshaders.pack.PackReader.findPacks(
                    FabricLoader.getInstance().getGameDir().resolve("shaderpacks")).getFirst();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertActive(ClientGameTestContext context, boolean on) {
        String chunks = context.computeOnClient(mc -> {
            var r = net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer.instanceNullable();
            return r == null ? "no Sodium renderer" : r.getChunksDebugString() + " | " + String.join(" | ", r.getDebugStrings(false));
        });
        System.out.println("[Luxium toggle] shaders " + (on ? "on" : "off") + ": " + chunks);
        boolean active = context.computeOnClient(mc -> io.github.hecbatista.metalshaders.MetalShadersClient.packActive());
        boolean format = context.computeOnClient(mc -> io.github.hecbatista.metalshaders.MetalShadersClient.luxiumMeshFormat());
        if (active != on || format != on) {
            throw new AssertionError("Shaders should be " + (on ? "on" : "off") + ": pack active " + active + ", Luxium chunk format " + format);
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
}
