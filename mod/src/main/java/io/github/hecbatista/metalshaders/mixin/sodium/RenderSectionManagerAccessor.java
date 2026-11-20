package io.github.hecbatista.metalshaders.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.SortedRenderLists;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * The parts of Sodium 0.9.2's RenderSectionManager (decompiled) the light's walk uses: the walk
 * that ignores the camera's occlusion graph (renderOutOfGraph, which Iris's
 * MixinRenderSectionManagerShadow runs for its shadow pass), and the three fields it overwrites,
 * so the camera's can be put back.
 */
@Mixin(value = RenderSectionManager.class, remap = false)
public interface RenderSectionManagerAccessor {
    @Accessor("renderLists")
    SortedRenderLists luxium$renderLists();

    @Accessor("renderLists")
    void luxium$setRenderLists(SortedRenderLists lists);

    @Accessor("taskLists")
    DeferredTaskList luxium$taskLists();

    @Accessor("taskLists")
    void luxium$setTaskLists(DeferredTaskList lists);

    @Accessor("renderTree")
    SectionTree luxium$renderTree();

    @Accessor("renderTree")
    void luxium$setRenderTree(SectionTree tree);

    @Invoker("renderOutOfGraph")
    void luxium$renderOutOfGraph(Viewport viewport, FogParameters fogParameters);
}
