package io.github.hecbatista.metalshaders.sodium;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.caffeinemc.mods.sodium.api.memory.MemoryIntrinsics;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder.Vertex;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.minecraft.util.Mth;

/**
 * Luxium's chunk vertex: Sodium 0.9.2's compact vertex (CompactChunkVertex, 20 bytes, written the
 * same way so Sodium's own shader can still draw the mesh) followed by four words packs read and
 * the compact vertex lacks. 36 bytes:
 * <pre>
 *  0 a_Position         RG32_UINT    20-bit x/y/z, interleaved (Sodium)
 *  8 a_Color            RGBA8_UNORM  tint times AO (Sodium), or tint with AO in alpha (separateAo)
 * 12 a_TexCoord         RG16_UINT    15-bit u/v plus a shrink-direction bit (Sodium)
 * 16 a_LightAndData     RGBA8_UINT   block light + 8, sky light + 8, material, section (Sodium)
 * 20 luxium_BlockId     R32_UINT     (block.properties id + 1) << 1 | fluid bit; 0 = unmapped
 * 24 luxium_MidTexCoord RG16_UINT    middle of the quad's texture, 1/32768 units
 * 28 luxium_NormalIn    RGBA8_SNORM  face normal (Newell's method over the quad)
 * 32 luxium_TangentIn   RGBA8_SNORM  texture tangent, w = handedness
 * </pre>
 * The game binds vertex inputs by element name (PipelineBuilder, 26.3), so Sodium's shader reads
 * its four elements and ignores the rest. The format is used from the start of the session, pack
 * or no pack: every holder of the stride (the section manager, the geometry arena, the memoised
 * pipelines) then agrees with every mesh, and nothing ever has to be rebuilt for a stride change.
 * The extra words follow Iris's XHFPTerrainVertex (see NOTICE): the mid-texture encoding, the
 * block-id encoding and separateAo's colour; the normal and tangent are stored as plain signed
 * bytes rather than Iris's octahedral word.
 */
public final class LuxiumChunkVertex implements ChunkVertexType {
    public static final int STRIDE = 36;
    public static final VertexFormat VERTEX_FORMAT = VertexFormat.builder(0)
            .addAttribute("a_Position", GpuFormat.RG32_UINT)
            .addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
            .addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
            .addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
            .addAttribute("luxium_BlockId", GpuFormat.R32_UINT)
            .addAttribute("luxium_MidTexCoord", GpuFormat.RG16_UINT)
            .addAttribute("luxium_NormalIn", GpuFormat.RGBA8_SNORM)
            .addAttribute("luxium_TangentIn", GpuFormat.RGBA8_SNORM)
            .build();
    public static final LuxiumChunkVertex INSTANCE = new LuxiumChunkVertex();

    /**
     * The pack's {@code separateAo}: the AO goes into the colour's alpha instead of its RGB. Read
     * when meshing, so it's set at startup, before any section is built.
     */
    private static volatile boolean separateAo;

    /** Packs' block ids by the BlockState being meshed (filled by BlockRendererMixin). */
    private static final ThreadLocal<int[]> BLOCK_ID = ThreadLocal.withInitial(() -> new int[]{0});

    private LuxiumChunkVertex() {
        if (VERTEX_FORMAT.getVertexSize() != STRIDE) {
            throw new IllegalStateException("Luxium chunk vertex is " + VERTEX_FORMAT.getVertexSize() + " bytes, expected " + STRIDE);
        }
    }

    public static void setSeparateAo(boolean on) {
        separateAo = on;
    }

    public static boolean separateAo() {
        return separateAo;
    }

    /** The encoded block id for quads written on this thread from now on (0 = none). */
    public static void setBlockId(int encoded) {
        BLOCK_ID.get()[0] = encoded;
    }

    /** The block id being meshed on this thread (0 = none). */
    public static int currentBlockId() {
        return BLOCK_ID.get()[0];
    }

    @Override
    public VertexFormat getVertexFormat() {
        return VERTEX_FORMAT;
    }

    @Override
    public ChunkVertexEncoder getEncoder() {
        return new Encoder();
    }

    /** One per meshing buffer; keeps scratch space, so not shared between threads. */
    private static final class Encoder implements ChunkVertexEncoder {
        private final float[] normal = new float[3];
        private final float[] tangent = new float[4];

        @Override
        public long write(long ptr, int materialBits, Vertex[] vertices, int section) {
            float midU = 0;
            float midV = 0;
            for (Vertex v : vertices) {
                midU += v.u;
                midV += v.v;
            }
            midU *= 0.25f;
            midV *= 0.25f;
            int midTex = (Math.round(midU * 32768f) & 0xFFFF) | (Math.round(midV * 32768f) & 0xFFFF) << 16;
            faceNormal(vertices, normal);
            tangent(vertices, normal, tangent);
            int packedNormal = snorm4(normal[0], normal[1], normal[2], 0f);
            int packedTangent = snorm4(tangent[0], tangent[1], tangent[2], tangent[3]);
            // A quad the translucent sorter kept carries its id on the vertex (ChunkVertexMixin);
            // one pushed while its block is meshed reads the thread's current block.
            int carried = vertices[0] instanceof LuxiumVertexData data ? data.luxium$blockId() : 0;
            int id = carried != 0 ? carried : BLOCK_ID.get()[0];
            boolean separate = separateAo;

            for (int i = 0; i < 4; i++) {
                Vertex vertex = vertices[i];
                int x = quantizePosition(vertex.x);
                int y = quantizePosition(vertex.y);
                int z = quantizePosition(vertex.z);
                MemoryIntrinsics.putInt(ptr, packPositionHi(x, y, z));
                MemoryIntrinsics.putInt(ptr + 4L, packPositionLo(x, y, z));
                MemoryIntrinsics.putInt(ptr + 8L, separate ? ColorABGR.withAlpha(vertex.color, vertex.ao) : ColorARGB.mulRGB(vertex.color, vertex.ao));
                MemoryIntrinsics.putInt(ptr + 12L, encodeTexture(midU, vertex.u) | encodeTexture(midV, vertex.v) << 16);
                MemoryIntrinsics.putInt(ptr + 16L, packLightAndData(encodeLight(vertex.light), materialBits, section));
                MemoryIntrinsics.putInt(ptr + 20L, id);
                MemoryIntrinsics.putInt(ptr + 24L, midTex);
                MemoryIntrinsics.putInt(ptr + 28L, packedNormal);
                MemoryIntrinsics.putInt(ptr + 32L, packedTangent);
                ptr += STRIDE;
            }
            return ptr;
        }
    }

    // ---- Sodium's compact encoding, unchanged (CompactChunkVertex, Sodium 0.9.2) -------------

    private static int packPositionHi(int x, int y, int z) {
        return (x >>> 10 & 1023) | (y >>> 10 & 1023) << 10 | (z >>> 10 & 1023) << 20;
    }

    private static int packPositionLo(int x, int y, int z) {
        return (x & 1023) | (y & 1023) << 10 | (z & 1023) << 20;
    }

    private static int quantizePosition(float position) {
        return (int) ((8.0f + position) / 32.0f * 1048576.0f) & 1048575;
    }

    private static int encodeTexture(float center, float x) {
        int bias = x < center ? 1 : -1;
        int quantized = Math.round(x * 32768.0f) + bias;
        return quantized & 32767 | (bias >>> 31) << 15;
    }

    private static int encodeLight(int light) {
        int sky = Mth.clamp((light >>> 16 & 0xFF) + 8, 8, 248);
        int block = Mth.clamp((light & 0xFF) + 8, 8, 248);
        return block | sky << 8;
    }

    private static int packLightAndData(int light, int material, int section) {
        return (light & 65535) | (material & 0xFF) << 16 | (section & 0xFF) << 24;
    }

    // ---- The extra words ----------------------------------------------------------------------

    /** Newell's method: right for any planar loop (crossed plants, sloped fluids), not just boxes. */
    static void faceNormal(Vertex[] v, float[] out) {
        float nx = 0;
        float ny = 0;
        float nz = 0;
        for (int i = 0; i < 4; i++) {
            Vertex a = v[i];
            Vertex b = v[(i + 1) & 3];
            nx += (a.y - b.y) * (a.z + b.z);
            ny += (a.z - b.z) * (a.x + b.x);
            nz += (a.x - b.x) * (a.y + b.y);
        }
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) {
            out[0] = 0;
            out[1] = 1;
            out[2] = 0;
            return;
        }
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
    }

    /**
     * The direction u grows along the face, squared up against the normal, and w = which way the
     * third axis turns (the bitangent's sign). From the first triangle, else the second; a mapping
     * too flat for either gets an axis perpendicular to the normal and w = 1.
     */
    static void tangent(Vertex[] v, float[] n, float[] out) {
        if (!tangentOf(v[0], v[1], v[2], n, out) && !tangentOf(v[2], v[3], v[0], n, out)) {
            float ax = Math.abs(n[0]) < 0.9f ? 1 : 0;
            float ay = ax == 1 ? 0 : 1;
            // a - n (n . a), normalised
            float d = n[0] * ax + n[1] * ay;
            float tx = ax - n[0] * d;
            float ty = ay - n[1] * d;
            float tz = -n[2] * d;
            float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
            out[0] = tx / len;
            out[1] = ty / len;
            out[2] = tz / len;
            out[3] = 1;
        }
    }

    private static boolean tangentOf(Vertex a, Vertex b, Vertex c, float[] n, float[] out) {
        float e1x = b.x - a.x, e1y = b.y - a.y, e1z = b.z - a.z;
        float e2x = c.x - a.x, e2y = c.y - a.y, e2z = c.z - a.z;
        float du1 = b.u - a.u, dv1 = b.v - a.v;
        float du2 = c.u - a.u, dv2 = c.v - a.v;
        float det = du1 * dv2 - du2 * dv1;
        if (Math.abs(det) < 1e-12f) {
            return false;
        }
        float r = 1.0f / det;
        float tx = (e1x * dv2 - e2x * dv1) * r;
        float ty = (e1y * dv2 - e2y * dv1) * r;
        float tz = (e1z * dv2 - e2z * dv1) * r;
        float bx = (e2x * du1 - e1x * du2) * r;
        float by = (e2y * du1 - e1y * du2) * r;
        float bz = (e2z * du1 - e1z * du2) * r;
        float d = n[0] * tx + n[1] * ty + n[2] * tz;
        tx -= n[0] * d;
        ty -= n[1] * d;
        tz -= n[2] * d;
        float len = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (len < 1e-12f) {
            return false;
        }
        tx /= len;
        ty /= len;
        tz /= len;
        // handedness: (n x t) . b
        float cx = n[1] * tz - n[2] * ty;
        float cy = n[2] * tx - n[0] * tz;
        float cz = n[0] * ty - n[1] * tx;
        out[0] = tx;
        out[1] = ty;
        out[2] = tz;
        out[3] = cx * bx + cy * by + cz * bz < 0 ? -1 : 1;
        return true;
    }

    /** Four floats in -1..1 as signed bytes, x in the lowest byte (RGBA8_SNORM, little-endian). */
    static int snorm4(float x, float y, float z, float w) {
        return snorm(x) | snorm(y) << 8 | snorm(z) << 16 | snorm(w) << 24;
    }

    private static int snorm(float v) {
        return Math.round(Mth.clamp(v, -1f, 1f) * 127f) & 0xFF;
    }
}
