package io.github.hecbatista.metalshaders.uniform;

import io.github.hecbatista.metalshaders.pack.PackReader;
import io.github.hecbatista.metalshaders.pack.PropertiesPreprocessor;
import io.github.hecbatista.metalshaders.pack.StandardDefines;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Loads the formulas of every real pack in run/shaderpacks/, without the game. */
class RealPackFormulasTest {
    /** Names GameUniforms supplies (kept in step with it by hand for this test). */
    private static final Set<String> GAME = Set.of(
            "frameCounter", "frameTime", "frameTimeCounter", "viewWidth", "viewHeight", "aspectRatio",
            "screenBrightness", "worldTime", "worldDay", "moonPhase", "sunAngle", "near", "far", "cameraPosition",
            "eyeAltitude", "previousCameraPosition", "cameraPositionInt", "cameraPositionFract",
            "previousCameraPositionInt", "previousCameraPositionFract", "rainStrength", "wetness", "skyColor",
            "isEyeInWater", "blindness", "darknessFactor", "nightVision", "is_invisible", "playerMood",
            "eyeBrightness", "biome", "biome_precipitation", "endFlashIntensity", "eyePosition",
            "relativeEyePosition", "playerLookVector", "cloudHeight", "isElytraFlying", "heavyFog",
            "heldItemId", "heldItemId2", "heldBlockLightValue", "heldBlockLightValue2", "lightningBoltPosition");

    @Test
    void complementaryFormulasLoad() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        for (Path pack : packs) {
            String text = PackReader.open(pack, (name, root, shaders) -> PackReader.readText(shaders, "/shaders.properties").orElse(""));
            Map<String, String> defines = new LinkedHashMap<>(StandardDefines.forMac());
            // Stand-ins for BIOME_* (in the game they come from BiomesMixin); the numbers don't matter here.
            for (String b : List.of("NETHER_WASTES", "CRIMSON_FOREST", "WARPED_FOREST", "BASALT_DELTAS",
                    "SOUL_SAND_VALLEY", "PALE_GARDEN", "SULFUR_CAVES")) {
                defines.put("BIOME_" + b, Integer.toString(defines.size()));
            }
            CustomUniforms c = CustomUniforms.load(PropertiesPreprocessor.preprocess(text, defines), GAME);
            System.out.println(pack.getFileName() + ": " + c.size() + " formulas loaded; dropped: " + c.dropped());
            assertTrue(c.size() > 0);

            var halfLives = io.github.hecbatista.metalshaders.pack.PackDirectives.wetnessHalfLives(
                    pack, "world0", StandardDefines.forMac());
            System.out.println(pack.getFileName() + ": wetness/dryness half-lives in Iris's order: " + halfLives);
        }
    }
}
