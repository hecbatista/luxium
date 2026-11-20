package io.github.hecbatista.metalshaders.uniform;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormulaTest {
    private static final Map<String, Object> GAME = Map.of(
            "frameCounter", 13, "eyeBrightness", new int[]{16, 120}, "isEyeInWater", 0,
            "eyeAltitude", 64.0f, "rainStrength", 1.0f, "blindness", 0.2f, "darknessFactor", 0.5f,
            "biome", 7, "cameraPosition", new float[]{1.5f, 70f, -2f});

    private static Object eval(String text) throws Exception {
        return Formula.parse(text).evaluate(GAME::get, 0.016f);
    }

    @Test
    void wholeNumberMathStaysWhole() throws Exception {
        assertEquals(5, eval("frameCounter % 8"));
        assertEquals(3, eval("7 / 2"));
        assertEquals(3.5f, eval("7 / 2.0"));
    }

    @Test
    void vectorComponents() throws Exception {
        assertEquals(0.5f, eval("eyeBrightness.y / 240.0"));
        assertEquals(70f, eval("cameraPosition.y"));
    }

    @Test
    void ifInAndComparisons() throws Exception {
        assertEquals(1, eval("if(in(biome, 3, 7, 9), 1, 0)"));
        assertEquals(0, eval("if(in(biome, 3), 1, 0)"));
        assertEquals(2, eval("if(isEyeInWater == 1, 1, eyeAltitude > 60.0 && rainStrength >= 1.0, 2, 3)"));
        assertEquals(0.5f, eval("max(blindness, darknessFactor)"));
        assertEquals(true, eval("!(1 > 2) || false"));
    }

    @Test
    void precedenceIsTheUsualOne() throws Exception {
        assertEquals(7, eval("1 + 2 * 3"));
        assertEquals(9, eval("(1 + 2) * 3"));
        assertEquals(-1.0f, eval("-cameraPosition.z / 2.0 - 2"));
    }

    @Test
    void smoothStartsAtTheValueThenEasesTowardsIt() throws Exception {
        Formula f = Formula.parse("smooth(1, rainStrength, 3, 3)");
        Map<String, Object> rain = new java.util.HashMap<>(GAME);
        rain.put("rainStrength", 0.0f);
        assertEquals(0.0f, f.evaluate(rain::get, 0.016f));        // first value: no smoothing
        rain.put("rainStrength", 1.0f);
        float after = (float) f.evaluate(rain::get, 0.5f);
        // Half-life is 3 * 0.1 = 0.3 s (Iris's quirk), so after 0.5 s: 1 - 2^(-0.5/0.3) = 0.685
        assertEquals(0.685f, after, 0.002f);
    }

    @Test
    void unknownNamesAreReported() throws Exception {
        Formula.FormulaException e = assertThrows(Formula.FormulaException.class, () -> eval("nope + 1"));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void deepNestingIsRefusedInsteadOfCrashing() {
        assertThrows(Formula.FormulaException.class, () -> Formula.parse("(".repeat(500) + "1" + ")".repeat(500)));
    }

    @Test
    void customUniformsAreOrderedAndBrokenOnesDropped() throws Exception {
        CustomUniforms c = CustomUniforms.load("""
                uniform.float.starter = smooth(3, moved, 20, 20)
                variable.float.moved = difX * 2
                variable.float.difX = cameraPosition.x - 1.0
                uniform.float.framemod8 = frameCounter % 8
                uniform.float.inIceCaves = if(in(biome, BIOME_ICE_CAVES), 1, 0)
                uniform.float.a = b
                uniform.float.b = a
                uniform.float.frameCounter = 1
                uniform.vec2.v = vec2(1, 2)
                """, Set.of("cameraPosition", "frameCounter", "biome", "rainStrength"));
        c.update(GAME::get, 0.016f);

        assertEquals(1.0f, c.get("starter"));                 // (1.5 - 1.0) * 2, first frame unsmoothed
        assertEquals(null, c.get("moved"));                   // a variable: not sent to shaders
        assertEquals(5.0f, c.get("framemod8"));               // int result stored as the declared float
        Map<String, String> dropped = c.dropped();
        assertTrue(dropped.get("inIceCaves").contains("BIOME_ICE_CAVES"), dropped.toString());
        assertTrue(dropped.get("a").contains("cycle"), dropped.toString());
        assertTrue(dropped.get("frameCounter").contains("hide"), dropped.toString());
        assertTrue(dropped.get("v").contains("vec2"), dropped.toString());
        assertEquals(4, c.size());   // starter, moved, difX, framemod8 (variables count too)
    }
}
