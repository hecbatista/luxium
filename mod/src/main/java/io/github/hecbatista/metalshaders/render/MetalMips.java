package io.github.hecbatista.metalshaders.render;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLBlitCommandEncoder;
import io.github.kokodio.metaljvm.metal.MTLTexture;
import io.github.hecbatista.metalshaders.MetalShadersClient;

import java.lang.reflect.Method;

/**
 * Fills a texture's mip chain with one Metal blit ({@code MTLBlitCommandEncoder generateMipmapsForTexture:},
 * the call glGenerateMipmap stands for). The game's GPU API has no mipmap call, so this goes to
 * Metallum's command encoder: the game hands its textures to Metallum unchanged
 * (FrontendCommandEncoder passes them to the backend), and the steps below are the ones Metallum's
 * own blits take (MetalCommandEncoder.copyTextureToTexture, Metallum 0.0.25): settle the texture's
 * pending clear, take the shared blit encoder, then end it.
 * <p>
 * Metallum's encoder and texture classes are package-private, so they are reached by reflection,
 * looked up once. If any of it is missing (another Metallum version), {@link #generate} returns
 * false once and the caller draws the levels itself.
 */
final class MetalMips {
    private static boolean looked;
    private static Method flushForWrite;
    private static Method blitEncoder;
    private static Method endEncoder;
    private static Method metalTexture;

    private MetalMips() {
    }

    /** Fills levels 1..n of {@code texture} from level 0; false if Metallum's internals aren't reachable. */
    static boolean generate(CommandEncoder encoder, GpuTexture texture) {
        if (!(encoder instanceof FrontendCommandEncoder frontend)) {
            return false;
        }
        Object backend = frontend.backend();
        if (!looked) {
            looked = true;
            lookUp(backend, texture);
        }
        if (blitEncoder == null || !blitEncoder.getDeclaringClass().isInstance(backend)
                || !metalTexture.getDeclaringClass().isInstance(texture)) {
            return false;
        }
        try {
            flushForWrite.invoke(backend, texture);
            MTLBlitCommandEncoder blit = (MTLBlitCommandEncoder) blitEncoder.invoke(backend);
            blit.generateMipmapsForTexture((MTLTexture) metalTexture.invoke(texture));
            endEncoder.invoke(backend);
            return true;
        } catch (ReflectiveOperationException | ClassCastException e) {
            MetalShadersClient.LOGGER.warn("Mip chains: Metal blit failed, drawing the levels instead ({})", e.toString());
            blitEncoder = null;
            return false;
        }
    }

    private static void lookUp(Object backend, GpuTexture texture) {
        try {
            Class<?> enc = backend.getClass();
            Class<?> tex = texture.getClass();
            Method f = enc.getDeclaredMethod("flushPendingClearForWrite", tex);
            Method b = enc.getDeclaredMethod("blitCommandEncoder");
            Method e = enc.getDeclaredMethod("endEncoder");
            Method m = tex.getDeclaredMethod("metalTexture");
            for (Method x : new Method[]{f, b, e, m}) {
                x.setAccessible(true);
            }
            flushForWrite = f;
            blitEncoder = b;
            endEncoder = e;
            metalTexture = m;
            MetalShadersClient.LOGGER.info("Mip chains: filled with Metal's blit generateMipmaps ({})", enc.getName());
        } catch (ReflectiveOperationException | RuntimeException e) {
            MetalShadersClient.LOGGER.warn("Mip chains: Metallum's blit encoder isn't reachable ({}); drawing the levels instead", e.toString());
        }
    }
}
