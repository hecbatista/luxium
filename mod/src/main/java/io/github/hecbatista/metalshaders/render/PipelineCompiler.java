package io.github.hecbatista.metalshaders.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import io.github.hecbatista.metalshaders.MetalShadersClient;
import io.github.kokodio.metaljvm.metal.MTLDevice;
import io.github.kokodio.metaljvm.metal.MTLLibrary;
import io.github.kokodio.metaljvm.metal.Metal;
import io.github.kokodio.metaljvm.objc.AutoreleasePool;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Step 7g: every Luxium pipeline compile goes through here. A compile has two halves:
 * <ul>
 * <li><b>cross</b>: {@code GpuDevice.compilePipeline}'s future: GLSL to SPIR-V (shaderc) and
 * reflection in the game's frontend ({@code PipelineBuilder}), then SPIR-V to MSL in Metallum's
 * backend ({@code MetalCrossShaderCompiler}). Measured on Complementary with Metal's shader cache
 * warm, this is about 95% of a compile (an entity program: cross 84 ms, metal 3 ms). Vanilla runs
 * it on its background executor, several pipelines at once ({@code ShaderManager.compilePipelines}),
 * so running it on worker threads is the API's intended use.</li>
 * <li><b>metal</b>: {@code Pending.finishCompile}: Metallum builds each stage's MTLLibrary (MSL to
 * AIR, behind a lock in {@code MetalDevice.getOrCompileFunction}) and the pipeline state. It stays on
 * the render thread, as vanilla's does ({@code reloadExecutor}).</li>
 * </ul>
 * A batch {@link #start}s every compile on the pool, then {@link #finish}es them in order on the
 * render thread. With Metal's shader cache cold (a player's first launch, or after a Luxium, pack or
 * macOS update) the metal half is the slow one instead (measured: 7-8 s per world, about 0.2-0.4 s
 * per program, without validation), so each pool task also compiles its MSL into a throwaway
 * library ({@link #warmMetal}, from {@code MetalCrossShaderCompilerMixin}); Metal caches it on disk,
 * and the render thread's own compile of the same source then hits that cache.
 * <p>
 * Both ideas (a sized compile pool, and compiling to warm Metal's cache then dropping the result)
 * come from GaelQuinta/minecraft-metal-shaders ({@code ShadeCompile}, {@code mccompile.m},
 * Apache-2.0); no code is taken from it.
 */
public final class PipelineCompiler {
    /** Worker count: the cores minus two, kept for the render and server threads (at least 2). */
    static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() - 2);
    private static final String THREAD_PREFIX = "Luxium compile ";

    private static final ThreadPoolExecutor POOL;

    static {
        AtomicInteger n = new AtomicInteger();
        POOL = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, THREAD_PREFIX + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        POOL.allowCoreThreadTimeOut(true);
    }

    private static final LongAdder crossNanos = new LongAdder();
    private static final LongAdder warmNanos = new LongAdder();
    /** {@code LUXIUM_NO_METAL_WARM=1} turns the warm-up off, for before/after timings. */
    private static final boolean WARM = System.getenv("LUXIUM_NO_METAL_WARM") == null;
    private static volatile @Nullable MTLDevice warmDevice;

    /** The pool, with each task's run time added to {@link #crossNanos}. */
    private static final Executor TIMED = task -> POOL.execute(() -> {
        long t0 = System.nanoTime();
        try {
            task.run();
        } finally {
            crossNanos.add(System.nanoTime() - t0);
        }
    });
    private static long metalNanos;
    private static int count;
    private static long lastCross;
    private static long lastMetal;

    private PipelineCompiler() {
    }

    /** Starts a compile's cross half on the pool. Finish it with {@link #finish} on the render thread. */
    public static CompletableFuture<CompiledRenderPipeline.Pending> start(RenderPipeline pipeline, ShaderSource source) {
        return RenderSystem.getDevice().compilePipeline(pipeline, source, TIMED);
    }

    /**
     * Waits for a started compile's cross half, then builds the Metal pipeline (render thread).
     * Null if the device returned no pipeline; a cross-half failure is rethrown unchecked.
     */
    public static @Nullable CompiledRenderPipeline finish(CompletableFuture<CompiledRenderPipeline.Pending> started) {
        CompiledRenderPipeline.Pending pending = started.join();
        long t0 = System.nanoTime();
        CompiledRenderPipeline compiled = pending.finishCompile();
        lastMetal = System.nanoTime() - t0;
        metalNanos += lastMetal;
        count++;
        return compiled;
    }

    /** Compiles one pipeline entirely on the calling (render) thread: a first-use compile. */
    public static @Nullable CompiledRenderPipeline compileNow(RenderPipeline pipeline, ShaderSource source) {
        long t0 = System.nanoTime();
        CompiledRenderPipeline.Pending pending = RenderSystem.getDevice().compilePipeline(pipeline, source, Runnable::run).join();
        lastCross = System.nanoTime() - t0;
        crossNanos.add(lastCross);
        long t1 = System.nanoTime();
        CompiledRenderPipeline compiled = pending.finishCompile();
        lastMetal = System.nanoTime() - t1;
        metalNanos += lastMetal;
        count++;
        return compiled;
    }

    /** Whether the calling thread is one of the pool's workers. */
    public static boolean onPoolThread() {
        return Thread.currentThread().getName().startsWith(THREAD_PREFIX);
    }

    /**
     * Compiles MSL into a Metal library and drops it, to fill Metal's shader cache (pool threads
     * only). Metallum compiles with default options ({@code newLibraryWithSource(source, null)}), so
     * this does too: the cache is keyed by source and options. A failure is ignored here; Metallum's
     * own compile reports it.
     */
    public static void warmMetal(String msl) {
        if (!WARM) {
            return;
        }
        long t0 = System.nanoTime();
        try (AutoreleasePool _ = AutoreleasePool.push()) {
            MTLLibrary library = device().newLibraryWithSource(msl, null);
            if (library != null) {
                library.release();
            }
        } catch (RuntimeException ignored) {
            // NSErrorException is one: a compile error, reported by Metallum's own compile of the same source
        } finally {
            warmNanos.add(System.nanoTime() - t0);
        }
    }

    /**
     * The system default device, the same GPU Metallum draws with (MetalBackend uses
     * MTLCreateSystemDefaultDevice too). Asked once to run as many compiles at once as it can
     * ({@code shouldMaximizeConcurrentCompilation}, macOS 13.3+), which GaelQuinta's
     * minecraft-metal-shaders also does ({@code mccompile.m}).
     */
    private static MTLDevice device() {
        MTLDevice d = warmDevice;
        if (d == null) {
            synchronized (PipelineCompiler.class) {
                d = warmDevice;
                if (d == null) {
                    d = Metal.MTLCreateSystemDefaultDevice();
                    d.setShouldMaximizeConcurrentCompilation(true);
                    MetalShadersClient.LOGGER.info("Compile pool: {} workers; Metal compiles up to {} at once",
                            THREADS, d.maximumConcurrentCompilationTaskCount());
                    warmDevice = d;
                }
            }
        }
        return d;
    }

    /** The last {@link #compileNow}'s split, for a log line: "cross N ms + metal M ms". */
    public static String lastSplit() {
        return "cross " + lastCross / 1_000_000 + " ms + metal " + lastMetal / 1_000_000 + " ms";
    }

    /**
     * Totals since the last call, then resets them. Cross is summed over the workers, so in a batch
     * it exceeds the wall time it took.
     */
    public static String drainSummary() {
        String s = count + " pipelines: cross " + crossNanos.sumThenReset() / 1_000_000 + " ms (summed over "
                + THREADS + " workers, of which Metal warm-up " + warmNanos.sumThenReset() / 1_000_000 + " ms), metal "
                + metalNanos / 1_000_000 + " ms";
        metalNanos = 0;
        count = 0;
        return s;
    }
}
