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
    void irisScalarFunctions() throws Exception {
        assertEquals(0.25f, (float) eval("frac(2.25)"), 1e-6f);
        assertEquals(0.75f, (float) eval("frac(-0.25)"), 1e-6f);      // x - floor(x), as Iris
        assertEquals(1, eval("fmod(-3, 4)"));                          // floorMod for whole numbers
        assertEquals(1.0f, (float) eval("fmod(-3.0, 4.0)"), 1e-6f);
        assertEquals(-3, eval("remainder(-3, 4)"));
        assertEquals(-1.0f, eval("sign(-2.5)"));
        assertEquals(8.0f, (float) eval("exp2(3)"), 1e-5f);
        assertEquals(3.0f, (float) eval("log2(8.0)"), 1e-5f);
        assertEquals(2.0f, (float) eval("log10(100.0)"), 1e-5f);
        assertEquals((float) Math.PI, (float) eval("torad(180.0)"), 1e-5f);
        assertEquals(90.0f, (float) eval("degrees(atan2(1.0, 0.0))"), 1e-4f);
        assertEquals(1.5f, (float) eval("mix(1.0, 2.0, 0.5)"), 1e-6f);
        assertEquals(1, eval("edge(2, 3)"));
        assertEquals(true, eval("between(biome, 5, 7)"));
        assertEquals(true, eval("equals(0.1, 0.11, 0.02)"));
    }

    /** BSL v10.1.8's time-of-day chain (shaders.properties), at noon: sunAngle 0.25. */
    @Test
    void bslTimeAngleAtNoon() throws Exception {
        Map<String, Object> noon = new java.util.HashMap<>(GAME);
        noon.put("sunAngle", 0.25f);
        float tAmin = (float) Formula.parse("frac(sunAngle - 0.033333333)").evaluate(noon::get, 0.016f);
        noon.put("tAmin", tAmin);
        float tAlin = (float) Formula.parse("if(tAmin < 0.433333333, tAmin * 1.15384615385, tAmin * 0.882352941176 + 0.117647058824)")
                .evaluate(noon::get, 0.016f);
        assertEquals(0.25f, tAlin, 1e-4f);   // noon maps to a quarter of the day
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
                uniform.ivec3.w = 1
                """, Set.of("cameraPosition", "frameCounter", "biome", "rainStrength"));
        c.update(GAME::get, 0.016f);

        assertEquals(1.0f, c.get("starter"));                 // (1.5 - 1.0) * 2, first frame unsmoothed
        assertEquals(null, c.get("moved"));                   // a variable: not sent to shaders
        assertEquals(5.0f, c.get("framemod8"));               // int result stored as the declared float
        Map<String, String> dropped = c.dropped();
        assertTrue(dropped.get("inIceCaves").contains("BIOME_ICE_CAVES"), dropped.toString());
        assertTrue(dropped.get("a").contains("cycle"), dropped.toString());
        assertTrue(dropped.get("frameCounter").contains("hide"), dropped.toString());
        assertTrue(dropped.get("w").contains("ivec3"), dropped.toString());
        assertEquals(new float[]{1f, 2f}[1], ((float[]) c.get("v"))[1]);
        assertEquals(5, c.size());   // starter, moved, difX, framemod8, v (variables count too)
    }

    @Test
    void blissFormulas() throws Exception {
        // Column-major like JOML: m.col.row. Translation sits in column 3.
        org.joml.Matrix4f m = new org.joml.Matrix4f().translation(4f, 5f, 6f).m11(2f);
        Map<String, Object> game = Map.of("gbufferProjection", m, "gbufferModelViewInverse", m,
                "sunPosition", new float[]{0f, 100f, 0f}, "viewWidth", 800f, "viewHeight", 400f);
        Formula.Names names = game::get;
        assertEquals(2f, Formula.parse("gbufferProjection.1.1").evaluate(names, 0f));
        assertEquals(4f, Formula.parse("gbufferModelViewInverse.3.0").evaluate(names, 0f));
        assertEquals(1f, Formula.parse("sunPosition.y / sqrt(sunPosition.x*sunPosition.x + sunPosition.1*sunPosition.1)")
                .evaluate(names, 0f));
        assertEquals(1f, Formula.parse("clamp(0.5 * 1000000000000000000, 0.0, 1.0)").evaluate(names, 0f));
        float[] v = (float[]) Formula.parse("vec2(1.0/viewWidth, 1.0/viewHeight)").evaluate(names, 0f);
        assertEquals(1f / 400f, v[1]);
        assertEquals(6f, ((float[]) Formula.parse("gbufferProjection.3").evaluate(names, 0f))[2]);
        assertThrows(Formula.FormulaException.class, () -> Formula.parse("vec3(1, 2)").evaluate(names, 0f));
    }
}
