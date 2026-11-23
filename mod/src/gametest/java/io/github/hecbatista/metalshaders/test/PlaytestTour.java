package io.github.hecbatista.metalshaders.test;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * A scripted "play session" through a normally generated world, to look for visual glitches the
 * fixed scene in {@link ScreenshotTest} can't show: survival movement with view bobbing, mining,
 * combat, the hand's items, many block entities and mobs, the day-night cycle, biomes, a cave,
 * fast flight, a portal trip, menus and window resizes.
 * <p>
 * Runs only with {@code LUXIUM_TOUR=1} (then {@link ScreenshotTest} is skipped). Screenshots go to
 * build/run/clientGameTest/screenshots/ as tour-*.png, frame sequences to frames-tour-*.
 */
@SuppressWarnings("UnstableApiUsage")
public class PlaytestTour implements FabricClientGameTest {
    private static final int Y = 200;   // the zoo platform's floor

    private ClientGameTestContext context;
    private TestSingleplayerContext singleplayer;
    private TestServerContext server;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (System.getenv("LUXIUM_TOUR") == null) {
            return;
        }
        copyShaderPacks();
        this.context = context;
        var builder = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed("20261121"));
        try (TestSingleplayerContext sp = builder.create()) {
            this.singleplayer = sp;
            this.server = sp.getServer();
            settle(0);
            // Spectator until a stop says otherwise: a run of only some stops would otherwise
            // start in survival and fall to its death on the first high teleport.
            cmd("gamemode spectator @a");
            cmd("gamerule advance_time false");
            cmd("gamerule advance_weather false");
            cmd("gamerule spawn_mobs false");
            cmd("time set noon");
            cmd("weather clear");
            cmd("effect give @a resistance infinite 3 true");
            cmd("effect give @a regeneration infinite 4 true");
            cmd("effect give @a saturation infinite 0 true");
            cmd("effect give @a fire_resistance infinite 0 true");
            cmd("effect give @a water_breathing infinite 0 true");

            stop("survival walk", this::survivalWalk);
            stop("build zoo", this::buildZoo);
            stop("zoo", this::zoo);
            stop("effects", this::effects);
            stop("survival actions", this::survivalActions);
            stop("menus", this::menus);
            stop("lightning and fireworks", this::lightningAndFireworks);
            stop("night", this::night);
            stop("day cycle", this::dayCycle);
            stop("biomes", this::biomes);
            stop("cave", this::cave);
            stop("fast flight", this::fastFlight);
            stop("portal trip", this::portalTrip);
            stop("window resize", this::windowResize);
        }
    }

    // ---------------------------------------------------------------- stops

    /** Survival, HUD on: sprint and jump over the generated ground near spawn (view bobbing, hand sway). */
    private void survivalWalk() {
        cmd("gamemode survival @a");
        giveKit();
        BlockPos ground = surface(8, 8);
        cmd("tp @a 8 " + (ground.getY() + 1) + " 8 30 5");
        hud(true);
        settle(40);
        shot("01-survival-start");
        var input = context.getInput();
        input.holdKey(o -> o.keyUp);
        input.holdKey(o -> o.keySprint);
        Path dir = frameDir("walk-survival");
        for (int i = 0; i < 60; i++) {
            if (i == 20 || i == 40) {
                input.pressKey(o -> o.keyJump);
            }
            context.waitTick();
            frame(dir, i);
        }
        input.releaseKey(o -> o.keySprint);
        input.releaseKey(o -> o.keyUp);
        context.waitTicks(10);
        shot("02-survival-after-walk");
    }

    /** A grass platform high above spawn with mobs, block entities and translucent blocks. */
    private void buildZoo() {
        cmd("gamemode spectator @a");
        hud(false);
        cmd("tp @a 0 " + (Y + 10) + " -18 0 15");
        settle(20);
        cmd(fill(-22, Y, -22, 22, Y, 22, "grass_block"));
        cmd(fill(-22, Y + 1, -22, 22, Y + 12, 22, "air"));
        // Back wall with item frames, signs and a painting.
        cmd(fill(-14, Y + 1, 10, 14, Y + 6, 10, "stone_bricks"));
        cmd("setblock -10 " + (Y + 3) + " 9 oak_wall_sign[facing=north]{front_text:{messages:[\"Luxium\",\"sign text\",\"line 3\",\"\"]}}");
        cmd("setblock -8 " + (Y + 3) + " 9 oak_wall_sign[facing=north]{front_text:{has_glowing_text:1b,color:\"lime\",messages:[\"Glowing\",\"text\",\"\",\"\"]}}");
        cmd("summon item_frame -5 " + (Y + 3) + " 9 {Facing:2b,Item:{id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:enchantment_glint_override\":true}}}");
        cmd("summon glow_item_frame -3 " + (Y + 3) + " 9 {Facing:2b,Item:{id:\"minecraft:compass\",count:1}}");
        cmd("summon painting 1 " + (Y + 3) + " 9 {facing:2b,variant:\"minecraft:kebab\"}");
        // Block entities in a row at z = 6.
        String[] row = {
                "chest[facing=north]", "ender_chest[facing=north]", "purple_shulker_box", "enchanting_table",
                "end_portal_frame[eye=true,facing=north]", "bell[facing=north]", "lectern[facing=north,has_book=true]",
                "decorated_pot", "brewing_stand", "creeper_head", "player_head", "campfire[lit=true]",
                "soul_campfire[lit=true]", "red_banner", "conduit", "spawner"
        };
        for (int i = 0; i < row.length; i++) {
            cmd("setblock " + (-12 + i * 2) + " " + (Y + 1) + " 6 " + row[i]);
        }
        cmd("setblock 7 " + (Y + 1) + " 3 red_bed[facing=south,part=foot]");
        cmd("setblock 7 " + (Y + 1) + " 4 red_bed[facing=south,part=head]");
        // End portal and gateway (their own pipelines) sunk into the floor.
        cmd(fill(10, Y, 1, 11, Y, 2, "end_portal"));
        cmd("setblock 13 " + (Y + 1) + " 2 end_gateway");
        // Translucent and cutout blocks at z = 0.
        String[] glass = {"red_stained_glass", "blue_stained_glass_pane", "ice", "slime_block", "honey_block",
                "tinted_glass", "glass", "white_stained_glass", "cobweb", "oak_leaves[persistent=true]"};
        for (int i = 0; i < glass.length; i++) {
            cmd(fill(-12 + i * 2, Y + 1, 0, -12 + i * 2, Y + 2, 0, glass[i]));
        }
        cmd("setblock -14 " + (Y + 1) + " 0 water");
        cmd("setblock -16 " + (Y + 1) + " 0 lava");
        // A nether portal at x 15..18, z 14 (fill the frame, then the inside).
        cmd(fill(15, Y + 1, 14, 18, Y + 5, 14, "obsidian"));
        cmd(fill(16, Y + 2, 14, 17, Y + 4, 14, "nether_portal[axis=x]"));
        // Mobs (no AI) at z = -6.
        cmd("summon zombie -10 " + (Y + 1) + " -6 {NoAI:1b,Rotation:[180f,0f],Tags:[\"tour\"]}");
        cmd("summon skeleton -7 " + (Y + 1) + " -6 {NoAI:1b,Rotation:[180f,0f],HandItems:[{id:\"minecraft:bow\",count:1},{}]}");
        cmd("summon creeper -4 " + (Y + 1) + " -6 {NoAI:1b,powered:1b,Rotation:[180f,0f]}");
        cmd("summon iron_golem 0 " + (Y + 1) + " -6 {NoAI:1b,Rotation:[180f,0f]}");
        cmd("summon villager 4 " + (Y + 1) + " -6 {NoAI:1b,Rotation:[180f,0f],CustomName:\"Named villager\",CustomNameVisible:1b}");
        cmd("summon armor_stand 7 " + (Y + 1) + " -6 {Rotation:[180f,0f],ShowArms:1b,equipment:{head:{id:\"minecraft:diamond_helmet\",count:1,components:{\"minecraft:enchantment_glint_override\":true}},chest:{id:\"minecraft:diamond_chestplate\",count:1,components:{\"minecraft:enchantment_glint_override\":true}},mainhand:{id:\"minecraft:trident\",count:1}}}");
        cmd("summon fox 10 " + (Y + 1) + " -6 {NoAI:1b,Rotation:[180f,0f]}");
        cmd("summon horse 13 " + (Y + 1) + " -6 {NoAI:1b,Rotation:[180f,0f],Tame:1b}");
        cmd("summon minecart -13 " + (Y + 1) + " -3");
        cmd("summon oak_boat -15 " + (Y + 1) + " -6");
        cmd("summon end_crystal 16 " + (Y + 1) + " -2 {ShowBottom:1b}");
        cmd("summon allay -2 " + (Y + 3) + " -8 {NoAI:1b}");
        cmd("summon glow_squid 2 " + (Y + 3) + " -8 {NoAI:1b}");
        settle(40);
    }

    private void zoo() {
        cmd("tp @a 0 " + (Y + 5) + " -18 0 15");
        settle(30);
        dropItems();
        context.waitTicks(10);
        shot("03-zoo-wide");
        frames("zoo", 24);
        cmd("tp @a -6 " + (Y + 3) + " 0 0 15");
        settle(20);
        shot("04-zoo-block-entities");
        cmd("tp @a -3 " + (Y + 3) + " -5 0 20");
        settle(10);
        shot("05-zoo-translucents");
        cmd("tp @a 11 " + (Y + 4) + " -1 30 45");
        settle(10);
        shot("06-zoo-end-portal");
        cmd("tp @a 16.5 " + (Y + 3) + " 10 0 0");
        settle(10);
        shot("07-zoo-nether-portal");
        frames("nether-portal-block", 16);
        // Close and at an angle: the pack's portal parallax, and its close-up fade (PORTAL_REDUCE_CLOSEUP).
        cmd("tp @a 18.5 " + (Y + 3) + " 12 40 10");
        settle(10);
        shot("07b-zoo-nether-portal-angled");
        cmd("tp @a -6 " + (Y + 3) + " 5 0 5");
        settle(10);
        shot("08-zoo-signs-frames");
        cmd("tp @a 0 " + (Y + 3) + " -12 0 10");
        settle(10);
        shot("09-zoo-mobs-close");
    }

    private void effects() {
        cmd("tp @a 0 " + (Y + 5) + " -18 0 15");
        settle(10);
        cmd("effect give @e[tag=tour] glowing infinite 0 true");
        cmd("effect give @e[type=minecraft:villager] glowing infinite 0 true");
        context.waitTicks(10);
        shot("10-glowing-outlines");
        cmd("effect clear @e[tag=tour] glowing");
        cmd("effect clear @e[type=minecraft:villager] glowing");
        cmd("effect give @a blindness 6 0 true");
        context.waitTicks(40);
        shot("11-blindness");
        cmd("effect clear @a blindness");
        cmd("effect give @a darkness 8 0 true");
        context.waitTicks(40);
        shot("12-darkness");
        frames("darkness", 16);
        cmd("effect clear @a darkness");
        cmd("effect give @a nausea 10 0 true");
        context.waitTicks(80);
        shot("13-nausea");
        frames("nausea", 16);
        cmd("effect clear @a nausea");
        context.waitTicks(20);
    }

    /** Survival, HUD on, at the zoo: mining, hitting a mob, bow, shield, spyglass, map, fire, hurt. */
    private void survivalActions() {
        cmd(fill(-2, Y + 1, -12, 2, Y + 3, -12, "air"));
        cmd("setblock 0 " + (Y + 1) + " -12 obsidian");
        cmd("setblock 0 " + (Y + 2) + " -12 obsidian");
        cmd("gamemode survival @a");
        hud(true);
        cmd("tp @a 0 " + (Y + 1) + " -14.5 0 25");
        settle(20);
        var input = context.getInput();
        // Mining: slot 2 = diamond pickaxe; obsidian takes ~9 s, so the cracks stay mid-way.
        selectSlot(1);
        input.holdKey(o -> o.keyAttack);
        context.waitTicks(70);
        shot("14-mining-cracks");
        frames("mining", 16);
        input.releaseKey(o -> o.keyAttack);
        // Attack a zombie in reach.
        cmd("summon zombie 0 " + (Y + 1) + " -11 {NoAI:1b,Rotation:[180f,0f],Tags:[\"tour2\"]}");
        cmd(fill(-1, Y + 1, -12, 1, Y + 3, -12, "air"));
        cmd("tp @a 0 " + (Y + 1) + " -13.5 0 10");
        selectSlot(0);
        context.waitTicks(10);
        input.pressKey(o -> o.keyAttack);
        context.waitTicks(2);
        shot("15-hit-zombie");
        cmd("kill @e[tag=tour2]");
        // Bow drawn (slot 4).
        cmd("tp @a 0 " + (Y + 1) + " -14 0 0");
        selectSlot(3);
        input.holdKey(o -> o.keyUse);
        context.waitTicks(25);
        shot("16-bow-drawn");
        input.releaseKey(o -> o.keyUse);
        context.waitTicks(3);
        shot("17-arrow-flying");
        // Shield block (sword in main hand, shield in offhand).
        selectSlot(0);
        input.holdKey(o -> o.keyUse);
        context.waitTicks(10);
        shot("18-shield-block");
        input.releaseKey(o -> o.keyUse);
        // Spyglass (slot 5).
        selectSlot(4);
        input.holdKey(o -> o.keyUse);
        context.waitTicks(15);
        shot("19-spyglass");
        input.releaseKey(o -> o.keyUse);
        // Empty map (slot 6) -> filled map in hand.
        selectSlot(5);
        input.pressKey(o -> o.keyUse);
        context.waitTicks(40);
        shot("20-map-in-hand");
        // Torch in hand (slot 3), at the platform.
        selectSlot(2);
        context.waitTicks(5);
        shot("21-torch-in-hand");
        // On fire (first-person fire overlay; fire resistance keeps the player alive).
        server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> p.setRemainingFireTicks(200)));
        context.waitTicks(10);
        shot("22-on-fire");
        frames("on-fire", 12);
        server.runOnServer(s -> s.getPlayerList().getPlayers().forEach(p -> p.setRemainingFireTicks(0)));
        // Hurt (camera tilt, red tint).
        cmd("damage @p 2 minecraft:generic");
        context.waitTicks(2);
        shot("23-hurt");
        context.waitTicks(20);
        // Third person, front and back.
        context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        context.waitTicks(10);
        shot("24-third-person-front");
        context.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
        context.waitTicks(10);
        shot("25-third-person-back");
        context.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
        // Swimming on the platform's water? Too shallow; swimming is covered under the ocean.
        selectSlot(0);
    }

    private void menus() {
        var input = context.getInput();
        input.pressKey(o -> o.keyInventory);
        context.waitTicks(10);
        shot("26-inventory");
        input.pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(5);
        input.pressKey(o -> o.keyChat);
        context.waitTicks(5);
        input.typeChars("hello from the tour");
        context.waitTicks(5);
        shot("27-chat");
        input.pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(5);
        input.pressKey(InputConstants.KEY_F3);
        context.waitTicks(10);
        shot("28-debug-screen");
        input.pressKey(InputConstants.KEY_F3);
        context.waitTicks(5);
        input.pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(20);
        shot("29-pause-menu");
        input.pressKey(InputConstants.KEY_ESCAPE);
        context.waitTicks(10);
        shot("30-after-pause");
    }

    private void lightningAndFireworks() {
        cmd("gamemode spectator @a");
        hud(false);
        cmd("tp @a 0 " + (Y + 5) + " -18 0 15");
        settle(10);
        cmd("time set 13000");
        context.waitTicks(10);
        cmd("summon lightning_bolt 6 " + (Y + 1) + " 2");
        context.waitTick();
        shot("31-lightning");
        frames("lightning", 12);
        cmd("summon firework_rocket 0 " + (Y + 2) + " 4 {LifeTime:20,FireworksItem:{id:\"minecraft:firework_rocket\",count:1,components:{\"minecraft:fireworks\":{flight_duration:1,explosions:[{shape:\"large_ball\",colors:[I;16711680,65280,255],has_trail:true,has_twinkle:true}]}}}}");
        context.waitTicks(23);
        shot("32-firework");
        frames("firework", 16);
        cmd("time set noon");
    }

    private void night() {
        // A stone-brick hut with lights at the platform's corner.
        cmd(fill(-21, Y + 1, 12, -14, Y + 5, 20, "stone_bricks hollow"));
        cmd(fill(-18, Y + 1, 12, -17, Y + 2, 12, "air"));
        cmd("setblock -20 " + (Y + 2) + " 19 wall_torch[facing=north]");
        cmd("setblock -15 " + (Y + 2) + " 19 soul_wall_torch[facing=north]");
        cmd("setblock -17 " + (Y + 4) + " 16 lantern[hanging=true]");
        cmd("setblock -20 " + (Y + 1) + " 15 glowstone");
        cmd("setblock -15 " + (Y + 1) + " 15 sea_lantern");
        cmd("setblock -15 " + (Y + 1) + " 17 candle[lit=true,candles=4]");
        cmd("setblock -20 " + (Y + 1) + " 17 redstone_lamp[lit=true]");
        cmd("setblock -18 " + (Y + 1) + " 18 jack_o_lantern[facing=north]");
        cmd("time set 18000");
        cmd("tp @a 0 " + (Y + 5) + " -18 0 15");
        settle(20);
        shot("33-night-zoo");
        frames("night-zoo", 24);
        cmd("tp @a -17.5 " + (Y + 2) + " 13.5 0 10");
        settle(10);
        shot("34-night-hut-inside");
        cmd("tp @a -17.5 " + (Y + 2) + " 8 0 0");
        settle(10);
        shot("35-night-hut-outside");
        cmd("tp @a 0 " + (Y + 5) + " -18 0 -80");
        context.waitTicks(10);
        shot("36-night-sky-moon");
        frames("night-sky", 24);
        cmd("tp @a 0 " + (Y + 5) + " -18 0 -10");
        context.waitTicks(5);
        shot("37-night-horizon");
        cmd("effect give @a night_vision 30 0 true");
        context.waitTicks(10);
        shot("38-night-vision");
        cmd("effect clear @a night_vision");
        cmd("time set noon");
    }

    /** Plains (or forest) near spawn through the day. Yaw -90 faces east (+x), 90 west. */
    private void dayCycle() {
        BlockPos at = findBiome(Biomes.PLAINS, Biomes.FOREST);
        // Load the chunk first: the heightmap of a chunk that isn't generated yet reads the world's bottom.
        cmd("tp @a " + at.getX() + " 200 " + at.getZ());
        settle(10);
        BlockPos ground = surface(at.getX(), at.getZ());
        String pos = at.getX() + " " + (ground.getY() + 6) + " " + at.getZ();
        Object[][] times = {
                {23200, -90f, -5f, "39-sunrise"}, {1000, -90f, 0f, "40-morning"}, {6000, 0f, -60f, "41-noon-up"},
                {6000, 0f, 15f, "42-noon-ground"}, {11500, 90f, -5f, "43-sunset"}, {12800, 90f, 0f, "44-dusk"},
                {14000, 0f, -40f, "45-early-night-stars"}, {22500, -90f, 0f, "46-pre-dawn"}
        };
        cmd("tp @a " + pos + " 0 0");
        settle(40);
        for (Object[] t : times) {
            cmd("time set " + t[0]);
            cmd("tp @a " + pos + " " + t[1] + " " + t[2]);
            context.waitTicks(15);
            shot((String) t[3]);
        }
        cmd("time set 11500");
        cmd("tp @a " + pos + " 90 -5");
        frames("sunset", 24);
        cmd("time set noon");
    }

    private void biomes() {
        biome(Biomes.DESERT, "47-desert", true);
        biome(Biomes.JUNGLE, "48-jungle", true);
        biome(Biomes.SWAMP, "49-swamp", true);
        biome(Biomes.CHERRY_GROVE, "50-cherry-grove", true);
        biome(Biomes.BADLANDS, "51-badlands", true);
        biome(Biomes.DARK_FOREST, "52-dark-forest", false);
        // Snow: clear, then snowfall, then inside powder snow.
        BlockPos snow = biome(Biomes.SNOWY_PLAINS, "53-snowy-plains", true);
        // Without a pack, vanilla's zero-index snow draw in rain aborts Metal validation (known; see CLAUDE.md 6d-4).
        if (snow != null && System.getenv("LUXIUM_TEST_NO_PACK") == null) {
            cmd("weather rain");
            context.waitTicks(160);
            shot("54-snowfall");
            frames("snowfall", 24);
            cmd("weather thunder");
            context.waitTicks(60);
            shot("55-snow-thunder");
            cmd("weather clear");
            cmd("time set noon");
        }
        // Ocean: surface, under water looking up and level, and swimming.
        BlockPos ocean = findBiome(Biomes.WARM_OCEAN, Biomes.OCEAN);
        if (ocean != null) {
            String xz = ocean.getX() + " %s " + ocean.getZ();
            cmd("tp @a " + String.format(xz, 66) + " 0 10");
            settle(40);
            shot("56-ocean-surface");
            frames("ocean-surface", 24);
            cmd("tp @a " + String.format(xz, 58) + " 0 -50");
            context.waitTicks(20);
            shot("57-underwater-looking-up");
            frames("underwater-up", 24);
            cmd("tp @a " + String.format(xz, 56) + " 0 10");
            context.waitTicks(10);
            shot("58-underwater-level");
            cmd("tp @a " + String.format(xz, 63.4) + " 0 5");
            context.waitTicks(10);
            shot("59-eye-at-waterline");
            frames("waterline", 24);
        }
    }

    /** A carved cave 60 blocks under spawn: torches, glow lichen, lava, ores. No sky light. */
    private void cave() {
        cmd("tp @a 0 -30 0 0 10");
        settle(20);
        cmd(fill(-10, -36, -10, 10, -28, 10, "deepslate"));
        cmd(fill(-8, -34, -8, 8, -29, 8, "air"));
        cmd(fill(-3, -35, 3, 2, -35, 6, "lava"));
        cmd("setblock -8 -32 0 wall_torch[facing=east]");
        cmd("setblock 8 -32 0 wall_torch[facing=west]");
        cmd(fill(-8, -33, 6, -8, -30, 8, "glow_lichen[east=true]"));
        cmd(fill(4, -34, 8, 6, -32, 8, "deepslate_diamond_ore"));
        cmd(fill(-6, -34, 8, -5, -33, 8, "deepslate_redstone_ore"));
        cmd("setblock 0 -29 -2 pointed_dripstone[vertical_direction=down]");
        cmd("summon zombie 3 -34 4 {NoAI:1b,Rotation:[180f,0f]}");
        cmd("tp @a 0 -32 -7 0 15");
        settle(20);
        shot("60-cave");
        frames("cave", 24);
        cmd("gamemode survival @a");
        hud(true);
        cmd("tp @a 0 -34 -7 0 15");
        selectSlot(2);
        context.waitTicks(20);
        shot("61-cave-survival-torch");
        cmd("gamemode spectator @a");
        hud(false);
    }

    /** Spectator flight, sprinting forward over new terrain (chunk loading, shadows, LOD of distance). */
    private void fastFlight() {
        cmd("tp @a 400 130 400 -45 20");
        settle(20);
        context.runOnClient(mc -> mc.player.getAbilities().setFlyingSpeed(0.15f));
        var input = context.getInput();
        input.holdKey(o -> o.keyUp);
        input.holdKey(o -> o.keySprint);
        Path dir = frameDir("flight");
        for (int i = 0; i < 100; i++) {
            context.waitTick();
            frame(dir, i);
        }
        input.releaseKey(o -> o.keySprint);
        input.releaseKey(o -> o.keyUp);
        context.runOnClient(mc -> mc.player.getAbilities().setFlyingSpeed(0.05f));
        context.waitTicks(10);
        shot("62-after-flight");
    }

    /** Walk into the zoo's nether portal in survival, look around the Nether, stand in lava, come back. */
    private void portalTrip() {
        cmd("gamemode survival @a");
        hud(true);
        cmd("tp @a 16.5 " + (Y + 2) + " 14.5 0 0");
        Path dir = frameDir("portal");
        for (int i = 0; i < 60; i++) {
            context.waitTick();
            if (i % 2 == 0) {
                frame(dir, i / 2);
            }
        }
        shot("63-in-portal");
        int waited = context.waitFor(mc -> mc.level != null && mc.level.dimension() == net.minecraft.world.level.Level.NETHER, 600);
        settle(40);
        shot("64-nether-arrival");
        frames("nether-arrival", 24);
        cmd("gamemode spectator @a");
        hud(false);
        cmd("execute at @p run tp @p ~ ~ ~ 120 10");
        context.waitTicks(10);
        shot("65-nether-look");
        cmd("execute at @p run fill ~3 ~-1 ~3 ~7 ~3 ~7 lava");
        cmd("execute at @p run tp @p ~5 ~1 ~5 0 0");
        context.waitTicks(20);
        shot("66-inside-lava");
        cmd("execute in minecraft:overworld run tp @a 0 " + (Y + 5) + " -18 0 15");
        settle(40);
        shot("67-back-overworld");
    }

    private void windowResize() {
        var input = context.getInput();
        input.resizeWindow(1280, 720);
        context.waitTicks(20);
        shot("68-resized-1280x720");
        input.resizeWindow(480, 800);
        context.waitTicks(20);
        shot("69-resized-portrait");
        input.resizeWindow(854, 480);
        context.waitTicks(20);
        shot("70-resized-back");
    }

    // ---------------------------------------------------------------- helpers

    private void giveKit() {
        cmd("clear @a");
        cmd("item replace entity @a hotbar.0 with minecraft:diamond_sword[minecraft:enchantment_glint_override=true]");
        cmd("item replace entity @a hotbar.1 with minecraft:diamond_pickaxe");
        cmd("item replace entity @a hotbar.2 with minecraft:torch 64");
        cmd("item replace entity @a hotbar.3 with minecraft:bow");
        cmd("item replace entity @a hotbar.4 with minecraft:spyglass");
        cmd("item replace entity @a hotbar.5 with minecraft:map");
        cmd("item replace entity @a hotbar.6 with minecraft:grass_block 64");
        cmd("item replace entity @a hotbar.7 with minecraft:water_bucket");
        cmd("item replace entity @a weapon.offhand with minecraft:shield");
        cmd("item replace entity @a inventory.0 with minecraft:arrow 64");
        cmd("item replace entity @a armor.head with minecraft:iron_helmet");
        cmd("item replace entity @a armor.chest with minecraft:golden_chestplate");
    }

    private void dropItems() {
        cmd("summon item -2 " + (Y + 1) + " -10 {Item:{id:\"minecraft:diamond\",count:3},PickupDelay:32767}");
        cmd("summon item 0 " + (Y + 1) + " -10 {Item:{id:\"minecraft:grass_block\",count:1},PickupDelay:32767}");
        cmd("summon item 2 " + (Y + 1) + " -10 {Item:{id:\"minecraft:golden_apple\",count:1},PickupDelay:32767}");
        cmd("summon experience_orb 4 " + (Y + 1) + " -10 {Value:20}");
    }

    private void selectSlot(int slot) {
        context.runOnClient(mc -> mc.player.getInventory().setSelectedSlot(slot));
        context.waitTicks(2);
    }

    private BlockPos biome(ResourceKey<Biome> key, String name, boolean frames) {
        BlockPos at = findBiome(key);
        if (at == null) {
            log("biome " + key.identifier() + " not found nearby; skipped");
            return null;
        }
        cmd("tp @a " + at.getX() + " 200 " + at.getZ());
        settle(10);
        BlockPos ground = surface(at.getX(), at.getZ());
        cmd("tp @a " + at.getX() + " " + (ground.getY() + 4) + " " + at.getZ() + " 30 10");
        settle(40);
        shot(name);
        if (frames) {
            frames(name.substring(3), 12);
        }
        return at;
    }

    @SafeVarargs
    private BlockPos findBiome(ResourceKey<Biome>... keys) {
        for (ResourceKey<Biome> key : keys) {
            BlockPos found = server.computeOnServer(s -> {
                var pair = s.overworld().findClosestBiome3d(h -> h.is(key), new BlockPos(0, 64, 0), 6400, 32, 64);
                return pair == null ? null : pair.getFirst();
            });
            if (found != null) {
                log("biome " + key.identifier() + " at " + found.toShortString());
                return found;
            }
        }
        return null;
    }

    private BlockPos surface(int x, int z) {
        return server.computeOnServer(s -> {
            var level = s.overworld();
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
        });
    }

    private static String fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        return "fill " + x1 + " " + y1 + " " + z1 + " " + x2 + " " + y2 + " " + z2 + " minecraft:" + block;
    }

    private void cmd(String command) {
        try {
            server.runCommand(command);
        } catch (Throwable t) {
            log("command failed: " + command + " -> " + t);
        }
    }

    private void hud(boolean on) {
        context.runOnClient(mc -> {
            if (mc.gui.hud.isHidden() == on) {
                mc.gui.hud.toggle();
            }
        });
    }

    private void settle(int ticks) {
        context.waitTicks(Math.max(ticks / 2, 1));
        singleplayer.getConnection().waitForChunksRender();
        context.waitTicks(Math.max(ticks - ticks / 2, 1));
    }

    private void shot(String name) {
        context.takeScreenshot("tour-" + name);
    }

    private Path frameDir(String name) {
        return FabricLoader.getInstance().getGameDir().resolve("screenshots/frames-tour-" + name);
    }

    private void frame(Path dir, int i) {
        context.takeScreenshot(TestScreenshotOptions.of(String.format("%03d", i)).disableCounterPrefix().withDestinationDir(dir));
    }

    private void frames(String name, int count) {
        Path dir = frameDir(name);
        for (int i = 0; i < count; i++) {
            frame(dir, i);
            context.waitTick();
        }
    }

    private void stop(String name, Runnable body) {
        String only = System.getenv("LUXIUM_TOUR_ONLY");   // e.g. "build zoo,zoo"
        if (only != null && !java.util.Arrays.asList(only.split(",")).contains(name)) {
            return;
        }
        log("== stop: " + name);
        long t0 = System.nanoTime();
        try {
            body.run();
        } catch (Throwable t) {
            log("stop '" + name + "' failed: " + t);
            t.printStackTrace();
            // Put the input back in a neutral state for the next stop.
            try {
                context.setScreen(() -> null);
            } catch (Throwable ignored) {
            }
        }
        log("== stop done: " + name + " (" + (System.nanoTime() - t0) / 1_000_000 + " ms)");
    }

    private static void log(String message) {
        System.out.println("[Luxium tour] " + message);
    }

    /**
     * The test run's game folder (build/run/clientGameTest) is cleared before each run, so copy
     * the developer's packs from mod/run/shaderpacks/ into it. Shared by ScreenshotTest (mod/)
     * and the tour, which iris-compare/ also compiles, so it must not use any Luxium class.
     */
    public static void copyShaderPacks() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        // mod/: build/run/clientGameTest/../../../run/shaderpacks. iris-compare/ has no packs of
        // its own and reads mod/run/shaderpacks (one directory up, then mod/).
        Path source = gameDir.resolve("../../../run/shaderpacks").normalize();
        if (!Files.isDirectory(source)) {
            source = gameDir.resolve("../../../../mod/run/shaderpacks").normalize();
        }
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
