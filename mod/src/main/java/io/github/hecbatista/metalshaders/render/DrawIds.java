package io.github.hecbatista.metalshaders.render;

import io.github.hecbatista.metalshaders.pack.ItemIds;
import io.github.hecbatista.metalshaders.sodium.BlockStateIds;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The pack's entityId / blockEntityId for each of the game's entity draws.
 * <p>
 * The game queues an entity's or block entity's geometry first (EntityRenderDispatcher.submit,
 * BlockEntityRenderDispatcher.submit, each into SubmitNodeCollection.submitModel and friends) and
 * builds the draws later, one draw per PreparedRenderType (pipeline + textures,
 * RenderTypeFeatureRenderer.Group). So the id travels as: the thing being submitted (a window
 * around the dispatcher's submit) -> the RenderType it submits with -> the PreparedRenderType
 * made from that type when the draw list is built -> the draw. Different entity types have
 * different textures and so different render types, which is what makes one value per draw
 * right; two types sharing one texture would share the last one's id (Vitrail carries the ids on
 * the vertex instead, docs/internals/entities.md, and never meets that).
 * <p>
 * Values follow Iris: 0 outside any entity, -1 for one the pack's file doesn't list.
 */
public final class DrawIds {
    /** What a draw belongs to. */
    public record Ids(int entityId, int blockEntityId, boolean blockEntity) {
    }

    private static volatile ItemIds entityIds = ItemIds.EMPTY;
    private static Ids current;
    private static final Map<RenderType, Ids> byRenderType = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PreparedRenderType, Ids> byPrepared = new IdentityHashMap<>();

    private DrawIds() {
    }

    public static void setEntityIds(ItemIds ids) {
        entityIds = ids;
    }

    // ---- Submission windows (EntityRenderDispatcherMixin, BlockEntityRenderDispatcherMixin) ----

    public static void enterEntity(EntityType<?> type) {
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        current = new Ids(entityIds.of(name), 0, false);
    }

    public static void enterBlockEntity(BlockState state) {
        current = new Ids(0, (BlockStateIds.of(state) >> 1) - 1, true);
    }

    public static void leave() {
        current = null;
    }

    /** From SubmitNodeCollectionMixin: the thing being submitted draws with this type. */
    public static void noteRenderType(RenderType type) {
        Ids ids = current;
        if (ids != null) {
            byRenderType.put(type, ids);
        }
    }

    /** From RenderTypeFeatureRendererGroupMixin: a draw is being built for {@code prepared}. */
    public static void notePrepared(RenderType type, PreparedRenderType prepared) {
        Ids ids = byRenderType.get(type);
        if (ids != null) {
            byPrepared.put(prepared, ids);
        }
    }

    /** The ids of a draw, or null if it isn't an entity's or a block entity's. */
    public static Ids of(PreparedRenderType prepared) {
        return byPrepared.get(prepared);
    }

    /** Frame start: the draws of the previous frame are gone. */
    static void beginFrame() {
        byPrepared.clear();
    }
}
