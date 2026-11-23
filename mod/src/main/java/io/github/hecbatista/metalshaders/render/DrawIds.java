package io.github.hecbatista.metalshaders.render;

import io.github.hecbatista.metalshaders.pack.ItemIds;
import io.github.hecbatista.metalshaders.sodium.BlockStateIds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ZombieVillagerRenderState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SolidBucketItem;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
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
 * made from that type when the draw list is built -> the draw.
 * <p>
 * One render type is often shared by different things: the bell, the enchanting table's book,
 * the lectern's book and the conduit all draw entity_solid on the blocks atlas, and dropped items
 * share the item atlas. So model, custom-geometry and item submits keep the ids current when they
 * were made, those ids apply while each is built ({@link #buildSubmit}), and the game's draw list
 * starts a new draw wherever they change (RenderTypeFeatureRendererGroupMixin), so each draw has
 * one set of ids. Vitrail carries the ids on the vertex instead (docs/internals/entities.md).
 * <p>
 * Values follow Iris: 0 outside any entity, -1 for one the pack's file doesn't list.
 */
public final class DrawIds {
    /** What a draw belongs to. */
    public record Ids(int entityId, int blockEntityId, boolean blockEntity, int itemId) {
        Ids(int entityId, int blockEntityId, boolean blockEntity) {
            this(entityId, blockEntityId, blockEntity, 0);
        }
    }

    private static final String CURRENT_PLAYER = "minecraft:current_player";
    private static final String CONVERTING_VILLAGER = "minecraft:zombie_villager_converting";
    private static volatile ItemIds itemIds = ItemIds.EMPTY;
    /** The ids an item's draws get while ItemFeatureRenderer builds them (null otherwise). */
    private static Ids building;
    /** Whether {@link #building} is authoritative (even when null) for the draw being built. */
    private static boolean buildingKnown;

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

    /**
     * Iris MixinEntityRenderDispatcher: a converting zombie villager and the camera's own player
     * take the pack's special names if it lists them, else the entity type's name.
     */
    public static void enterEntity(EntityRenderState state) {
        ItemIds ids = entityIds;
        String name = BuiltInRegistries.ENTITY_TYPE.getKey(state.entityType).toString();
        if (state instanceof ZombieVillagerRenderState zombie && zombie.isConverting && ids.has(CONVERTING_VILLAGER)) {
            name = CONVERTING_VILLAGER;
        } else if (state instanceof AvatarRenderState avatar && ids.has(CURRENT_PLAYER)
                && Minecraft.getInstance().getCameraEntity() instanceof AbstractClientPlayer player && player.getId() == avatar.id) {
            name = CURRENT_PLAYER;
        }
        current = new Ids(ids.of(name), 0, false);
    }

    public static void setItemIds(ItemIds ids) {
        itemIds = ids;
    }

    /**
     * Iris ItemStackStateLayerMixin: while an item layer is submitted, currentRenderedItemId is the
     * item's id. A block item (not a solid bucket) uses its block's block.properties id and sets
     * blockEntityId to 1; any other item uses item.properties under its model's name if it has one,
     * else its own. 0 when unlisted. The entity holding it keeps its entityId.
     *
     * @return the ids before, for {@link #leaveItem}
     */
    public static Ids enterItem(Item item, Identifier model) {
        Ids before = current;
        if (item == null) {
            return before;
        }
        int itemId;
        int blockEntityId = before == null ? 0 : before.blockEntityId();
        if (item instanceof BlockItem blockItem && !(item instanceof SolidBucketItem)) {
            int word = BlockStateIds.of(blockItem.getBlock().defaultBlockState());
            itemId = word == 0 ? 0 : (word >> 1) - 1;
            blockEntityId = 1;
        } else {
            Identifier name = model != null ? model : BuiltInRegistries.ITEM.getKey(item);
            itemId = Math.max(0, itemIds.of(name.getNamespace() + ":" + name.getPath()));
        }
        current = new Ids(before == null ? 0 : before.entityId(), blockEntityId, before != null && before.blockEntity(), itemId);
        return before;
    }

    public static void leaveItem(Ids before) {
        current = before;
    }

    /** The ids being submitted right now (for an item's submit record to keep). */
    public static Ids current() {
        return current;
    }

    /** ItemFeatureRenderer builds one item's draws with these ids (null: done). */
    public static void buildWith(Ids ids) {
        building = ids;
        buildingKnown = ids != null;
    }

    /**
     * A model or custom-geometry submit is being built into a draw: the ids it was submitted with
     * (kept on the submit, null outside any entity) decide the draw's ids, not its render type.
     */
    public static void buildSubmit(Ids ids) {
        building = ids;
        buildingKnown = true;
    }

    public static void endSubmit() {
        building = null;
        buildingKnown = false;
    }

    /** The ids the geometry being built now belongs to (draws are split where these differ). */
    public static Ids buildingIds(RenderType type) {
        return buildingKnown ? building : byRenderType.get(type);
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
        Ids ids = buildingIds(type);
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
