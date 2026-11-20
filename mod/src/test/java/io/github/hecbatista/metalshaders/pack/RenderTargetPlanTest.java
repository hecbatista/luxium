package io.github.hecbatista.metalshaders.pack;

import com.mojang.renderpearl.api.GpuFormat;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class RenderTargetPlanTest {
    @Test
    void readsDirectivesEvenInsideCommentsAndLastOneWins() {
        RenderTargetPlan plan = RenderTargetPlan.fold(List.of(
                "/*\nconst int colortex2Format = RGB16F;\nconst bool colortex2Clear = false;\n*/",
                "/* const int gnormal = RGBA8; */ /* const int gnormalFormat = RGBA16; */",
                "const vec4 colortex5ClearColor = vec4(1.0, 0.5, 0.0, 1.0);"));

        RenderTargetPlan.Target t2 = plan.target(2);
        assertEquals("RGBA16", t2.requestedFormat());          // gnormal = colortex2, read later
        assertEquals(GpuFormat.RGBA16_UNORM, t2.format());
        assertFalse(t2.clear());
        assertArrayEquals(new float[]{1, 0.5f, 0, 1}, plan.target(5).clearColor());
        assertEquals(GpuFormat.RGBA8_UNORM, plan.target(9).format());   // never mentioned: defaults
        assertTrue(plan.target(9).clear());
    }

    @Test
    void threeChannelFormatsArePromoted() {
        RenderTargetPlan.Target t = RenderTargetPlan.fold(List.of("const int colortex1Format = RGB8_SNORM;")).target(1);
        assertEquals(GpuFormat.RGBA8_SNORM, t.format());
        assertTrue(t.promoted());
    }

    @Test
    void unknownFormatIsReported() {
        RenderTargetPlan plan = RenderTargetPlan.fold(List.of("const int colortex1Format = RGB99;"));
        assertEquals(GpuFormat.RGBA8_UNORM, plan.target(1).format());
        assertEquals(1, plan.warnings().size());
    }

    @Test
    void complementaryTargets() throws Exception {
        List<Path> packs = PackReader.findPacks(Path.of("run/shaderpacks"));
        assumeFalse(packs.isEmpty(), "No packs in run/shaderpacks/");
        Path pack = packs.getFirst();
        List<String> sources = new ArrayList<>();
        for (String program : PackDirectives.foldOrder(pack, "world0")) {
            PackPreprocessor.Program p = PackPreprocessor.preprocessProgram(pack, "/world0/" + program + ".fsh", StandardDefines.forMac());
            if (p.ok()) {
                sources.add(p.result().text());
            }
        }
        RenderTargetPlan plan = RenderTargetPlan.fold(sources);
        System.out.println("Complementary world0 targets:");
        plan.declared().forEach((i, t) -> System.out.printf("  colortex%d: %s -> %s%s, clear=%s%n",
                i, t.requestedFormat(), t.format(), t.promoted() ? " (promoted)" : "", t.clear()));

        Map<Integer, GpuFormat> expected = Map.of(
                0, GpuFormat.RG11B10_FLOAT, 1, GpuFormat.RGBA8_SNORM, 2, GpuFormat.RGBA16_FLOAT,
                3, GpuFormat.RGBA8_UNORM, 4, GpuFormat.RGBA8_SNORM, 6, GpuFormat.RGBA8_UNORM,
                7, GpuFormat.RGBA16_FLOAT, 13, GpuFormat.R8_UNORM, 14, GpuFormat.RGBA16_UNORM);
        expected.forEach((i, f) -> assertEquals(f, plan.target(i).format(), "colortex" + i));
        assertFalse(plan.target(1).clear());
        assertTrue(plan.target(3).clear());
        assertEquals(List.of(), plan.warnings());
    }
}
