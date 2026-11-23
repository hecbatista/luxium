package io.github.hecbatista.metalshaders.uniform;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import io.github.hecbatista.metalshaders.pack.ItemIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlockItemStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.HashMap;
import java.util.Map;

/**
 * The per-frame values a pack reads that come straight from the game: time, screen size,
 * camera, player state and weather. {@link #update} runs once per frame, at the end of
 * {@code GameRenderer.extract}, and every pass of that frame reads the same numbers
 * (Vitrail's rule: frame values advance once per frame, at one point).
 * <p>
 * Each value follows the Iris (branch 26.3) rule named next to it, including quirks packs are
 * tuned against. All Minecraft calls were checked in the 26.3 sources. See NOTICE.
 * Not here yet: matrices and per-draw values (step 6). The pack's own formulas are in
 * {@link CustomUniforms}.
 */
public final class GameUniforms {
    /**
     * The game's fog colour this frame (FogRenderer.setupFog's FogData.color, captured by
     * mixin.FogRendererMixin as Iris's MixinFogRenderer does): the {@code fogColor} uniform and
     * colortex0's clear colour (Iris ClearPassCreator).
     */
    private static volatile float[] fogColor = {0, 0, 0};

    public static void setFogColor(float r, float g, float b) {
        fogColor = new float[]{r, g, b};
    }

    public static float[] fogColor() {
        return fogColor;
    }

    private final Map<String, Object> values = new HashMap<>();
    private final CameraTracker camera = new CameraTracker();

    // SystemTimeUniforms.Timer and FrameCounter
    private long lastFrameStart = -1;
    private float frameTime;
    private float frameTimeCounter;
    private int frameCounter;

    /** The value for a name, or null if this engine doesn't supply it (yet). */
    public Object get(String name) {
        return values.get(name);
    }

    public Map<String, Object> snapshot() {
        return Map.copyOf(values);
    }

    public void update(Minecraft mc, float partialTick, long frameStartNanos) {
        updateTime(frameStartNanos);
        values.put("frameCounter", frameCounter);
        values.put("frameTime", frameTime);
        values.put("frameTimeCounter", frameTimeCounter);

        // ViewportUniforms: the main render target's size, as floats.
        var target = mc.gameRenderer.mainRenderTarget();
        values.put("viewWidth", (float) target.width);
        values.put("viewHeight", (float) target.height);
        values.put("aspectRatio", (float) target.width / (float) target.height);

        // CommonUniforms.screenBrightness: the gamma option.
        values.put("screenBrightness", mc.options.gamma().get().floatValue());

        ClientLevel level = mc.level;
        Camera cam = mc.gameRenderer.mainCamera();
        if (level == null) {
            return;
        }

        // WorldTimeUniforms
        long clock = level.getDefaultClockTime();
        boolean netherOrEnd = level.dimension() == Level.NETHER || level.dimension() == Level.END;
        // Quirk kept from Iris: the Nether and the End keep their moving time (packs derive angles from it).
        values.put("worldTime", (int) (netherOrEnd || !level.dimensionType().hasFixedTime() ? clock % 24000L : 0));
        values.put("worldDay", (int) (clock / 24000L));
        values.put("moonPhase", cam.attributeProbe().getValue(EnvironmentAttributes.MOON_PHASE, partialTick).index());

        // CelestialUniforms.getSunAngle: (angle + 90) wrapped into 0..360, then / 360.
        float c = cam.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE, partialTick) + 90.0f;
        if (c < 0) {
            c += 360;
        } else if (c > 360) {
            c -= 360;
        }
        values.put("sunAngle", c / 360.0f);
        // CelestialUniforms.getShadowAngle: the sun's angle by day, the moon's by night.
        float moon = cam.attributeProbe().getValue(EnvironmentAttributes.MOON_ANGLE, partialTick) + 90.0f;
        if (moon < 0) {
            moon += 360;
        } else if (moon > 360) {
            moon -= 360;
        }
        float shadowAngle = (c < 180 ? c : moon) / 360.0f;
        values.put("shadowAngle", shadowAngle);
        // The shadow pass's matrices (Iris ShadowRenderer), from the real camera position.
        org.joml.Matrix4f shadowModelView = ShadowMatrices.modelView(shadowAngle, cam.position().x, cam.position().y, cam.position().z);
        org.joml.Matrix4f shadowProjection = ShadowMatrices.projection();
        values.put("shadowModelView", shadowModelView);
        values.put("shadowModelViewInverse", new org.joml.Matrix4f(shadowModelView).invert());
        values.put("shadowProjection", shadowProjection);
        values.put("shadowProjectionInverse", new org.joml.Matrix4f(shadowProjection).invert());

        // CameraUniforms
        camera.update(cam.position());
        values.put("near", 0.05f);
        values.put("far", (float) (mc.options.getEffectiveRenderDistance() * 16));
        values.put("cameraPosition", toFloats(camera.current));
        values.put("fogColor", fogColor);
        values.put("eyeAltitude", (float) camera.current[1]);
        values.put("previousCameraPosition", toFloats(camera.previous));
        values.put("cameraPositionInt", floorInts(camera.currentUnshifted));
        values.put("cameraPositionFract", fract(camera.currentUnshifted));
        values.put("previousCameraPositionInt", floorInts(camera.previousUnshifted));
        values.put("previousCameraPositionFract", fract(camera.previousUnshifted));

        // CommonUniforms: weather and sky
        float rain = clamp01(level.getRainLevel(partialTick));
        values.put("rainStrength", rain);
        values.put("wetness", wetness(rain));
        Vector3fc sky = cam.attributeProbe().getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
        values.put("skyColor", new float[]{sky.x(), sky.y(), sky.z()});

        // CommonUniforms: player and camera entity
        values.put("isEyeInWater", isEyeInWater(mc, cam));
        Entity viewer = mc.getCameraEntity();
        LivingEntity living = viewer instanceof LivingEntity l ? l : null;
        values.put("blindness", blindness(living));
        values.put("darknessFactor", darkness(living, partialTick));
        values.put("nightVision", nightVision(mc, living, partialTick));
        values.put("is_invisible", mc.player != null && mc.player.isInvisible());
        values.put("playerMood", viewer instanceof LocalPlayer p ? clamp01(p.getCurrentMood()) : 0f);
        int[] eyes = eyeBrightness(level, viewer);
        values.put("eyeBrightness", eyes);
        values.put("eyeBrightnessSmooth", eyeBrightnessSmooth(eyes));

        // IrisExclusiveUniforms
        if (viewer != null) {
            Vec3 eye = viewer.getEyePosition(partialTick);
            values.put("eyePosition", new float[]{(float) eye.x, (float) eye.y, (float) eye.z});
            double[] cam0 = camera.currentUnshifted;
            values.put("relativeEyePosition", new float[]{(float) (cam0[0] - eye.x), (float) (cam0[1] - eye.y), (float) (cam0[2] - eye.z)});
        }
        if (viewer instanceof LivingEntity lv) {
            Vec3 look = lv.getViewVector(partialTick);
            values.put("playerLookVector", new float[]{(float) look.x, (float) look.y, (float) look.z});
        } else {
            values.put("playerLookVector", new float[]{0, 0, 0});
        }
        // IrisExclusiveUniforms.WorldInfoUniforms: the dimension's lowest block height.
        values.put("bedrockLevel", level.dimensionType().minY());
        values.put("cloudHeight", cam.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, partialTick));
        values.put("isElytraFlying", mc.player != null && mc.player.isFallFlying());
        values.put("heavyFog", mc.gui.hud.getBossOverlay().shouldCreateWorldFog());
        var endFlash = level.endFlashState();
        values.put("endFlashIntensity", endFlash == null ? 0f : endFlash.getIntensity(partialTick));
        values.put("lightningBoltPosition", lightningBolt(mc, level));

        // BiomeUniforms: the biome at the player's block, and its precipitation (0 none, 1 rain, 2 snow).
        LocalPlayer player = mc.player;
        if (player != null) {
            var biome = player.level().getBiome(player.blockPosition());
            values.put("biome", BiomeIds.of(biome.unwrapKey().orElse(null)));
            values.put("biome_precipitation", switch (biome.value().getPrecipitationAt(player.blockPosition(), player.level().getSeaLevel())) {
                case NONE -> 0;
                case RAIN -> 1;
                case SNOW -> 2;
            });
        } else {
            values.put("biome", 0);
            values.put("biome_precipitation", 0);
        }

        // IdMapUniforms: held items, numbered by the pack's item.properties.
        heldItem(player, InteractionHand.MAIN_HAND, "heldItemId", "heldBlockLightValue");
        heldItem(player, InteractionHand.OFF_HAND, "heldItemId2", "heldBlockLightValue2");
    }

    /**
     * Names Iris doesn't supply either, though packs declare them. Iris defines velocity in
     * HardcodedCustomUniforms, but nothing in Iris 26.3 calls that, so packs get 0. Reported
     * apart from real gaps so the log doesn't read like a debt (Vitrail's rule).
     */
    public static final java.util.Set<String> NOT_SUPPLIED_BY_IRIS = java.util.Set.of("velocity");

    private ItemIds itemIds = ItemIds.EMPTY;

    public void setItemIds(ItemIds ids) {
        this.itemIds = ids;
    }

    private void heldItem(LocalPlayer player, InteractionHand hand, String idName, String lightName) {
        if (player == null) {
            values.put(idName, -1);
            values.put(lightName, 0);
            return;
        }
        ItemStack stack = player.getItemInHand(hand);
        Identifier id = stack.get(DataComponents.ITEM_MODEL);
        if (id == null) {
            id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        }
        values.put(idName, itemIds.of(id.getNamespace() + ":" + id.getPath()));
        int light = 0;
        if (stack.getItem() instanceof BlockItem blockItem) {
            BlockState state = blockItem.getBlock().defaultBlockState();
            BlockItemStateProperties props = stack.getComponents().get(DataComponents.BLOCK_STATE);
            if (props != null) {
                state = props.apply(state);
            }
            light = state.getLightEmission();
        }
        values.put(lightName, light);
    }

    /** The first lightning bolt being drawn, relative to the camera, w = 1; or all zero. */
    private float[] lightningBolt(Minecraft mc, ClientLevel level) {
        for (Entity e : level.entitiesForRendering()) {
            if (e instanceof LightningBolt bolt) {
                // Iris uses the partial tick that ignores a frozen game here (true), unlike elsewhere.
                Vec3 p = bolt.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(true));
                double[] c = camera.currentUnshifted;
                return new float[]{(float) (p.x - c[0]), (float) (p.y - c[1]), (float) (p.z - c[2]), 1f};
            }
        }
        return new float[]{0, 0, 0, 0};
    }

    // ---- eyeBrightnessSmooth: eyeBrightness smoothed per axis (Iris SmoothedVec2f) -----------

    /** Iris's default eyeBrightnessHalflife (PackDirectives.java:65); the pack's directive isn't read yet. */
    private static final float EYE_BRIGHTNESS_HALF_LIFE = 10.0f;
    private final float[] eyeSmooth = new float[2];
    private boolean eyeSmoothStarted;

    /** Each axis smoothed as Iris's SmoothedFloat does (half-life x 0.1 s), then cut to int as Iris does. */
    private int[] eyeBrightnessSmooth(int[] eyes) {
        if (!eyeSmoothStarted) {
            eyeSmoothStarted = true;
            eyeSmooth[0] = eyes[0];
            eyeSmooth[1] = eyes[1];
        } else {
            float k = (float) (1.0f / (EYE_BRIGHTNESS_HALF_LIFE * 0.1f / Math.log(2.0)));
            float factor = 1.0f - (float) Math.exp(-k * frameTime);
            for (int i = 0; i < 2; i++) {
                eyeSmooth[i] = (1 - factor) * eyeSmooth[i] + factor * eyes[i];
            }
        }
        return new int[]{(int) eyeSmooth[0], (int) eyeSmooth[1]};
    }

    // ---- wetness: rainStrength smoothed, as Iris's SmoothedFloat does it ----------------------

    /** Iris's defaults (PackDirectives.java:63-64), in the pack's units (Iris multiplies by 0.1). */
    private float wetnessHalfLifeUp = 600.0f;
    private float wetnessHalfLifeDown = 200.0f;
    private float wetnessValue;
    private boolean wetnessStarted;

    /**
     * Sets the half-lives from the pack's {@code const float wetnessHalflife} and
     * {@code drynessHalflife}. Copies Iris's bug (PackDirectives.java:280-284): both directives set
     * the rising half-life, the later one winning, and the falling one stays at its default.
     * Packs are tuned against that, so it's reproduced, not fixed.
     *
     * @param inOrder the values in the order the directives appear, as (name, value) pairs
     */
    public void setWetnessDirectives(java.util.List<Map.Entry<String, Float>> inOrder) {
        for (Map.Entry<String, Float> d : inOrder) {
            wetnessHalfLifeUp = d.getValue();
        }
    }

    private float wetness(float rain) {
        if (!wetnessStarted) {
            wetnessStarted = true;
            wetnessValue = rain;
            return wetnessValue;
        }
        float halfLife = (rain > wetnessValue ? wetnessHalfLifeUp : wetnessHalfLifeDown) * 0.1f;
        float k = (float) (1.0f / (halfLife / Math.log(2.0)));
        float factor = 1.0f - (float) Math.exp(-k * frameTime);
        wetnessValue = (1 - factor) * wetnessValue + factor * rain;
        return wetnessValue;
    }

    private void updateTime(long now) {
        // Iris truncates to whole milliseconds before converting to seconds; packs see that.
        long diffMs = lastFrameStart < 0 ? 0 : (now - lastFrameStart) / 1_000_000L;
        frameTime = diffMs / 1000.0f;
        frameTimeCounter += frameTime;
        if (frameTimeCounter >= 3600.0f) {
            frameTimeCounter = 0.0f;
        }
        lastFrameStart = now;
        frameCounter = (frameCounter + 1) % 720720;
    }

    private static int isEyeInWater(Minecraft mc, Camera cam) {
        FogType fluid = cam.getFluidInCamera();
        boolean spectator = mc.player != null && mc.player.isSpectator();
        if (fluid == FogType.WATER) {
            return 1;
        } else if (!spectator && fluid == FogType.LAVA) {
            return 2;
        } else if (fluid == FogType.POWDER_SNOW) {
            return 3;
        }
        return 0;
    }

    private static float blindness(LivingEntity living) {
        MobEffectInstance effect = living == null ? null : living.getEffect(MobEffects.BLINDNESS);
        if (effect == null) {
            return 0f;
        }
        return effect.isInfiniteDuration() ? 1f : clamp01(effect.getDuration() / 20.0f);
    }

    private static float darkness(LivingEntity living, float partialTick) {
        MobEffectInstance effect = living == null ? null : living.getEffect(MobEffects.DARKNESS);
        return effect == null ? 0f : effect.getBlendFactor(living, partialTick);
    }

    private static float nightVision(Minecraft mc, LivingEntity living, float partialTick) {
        if (living != null && living.hasEffect(MobEffects.NIGHT_VISION)) {
            float strength = GameRenderer.nightVisionScale(living, partialTick);
            if (strength > 0) {
                return clamp01(strength);
            }
        }
        // Conduit power counts as night vision underwater, like vanilla's light texture.
        if (mc.player != null && mc.player.hasEffect(MobEffects.CONDUIT_POWER)) {
            float vision = mc.player.getWaterVision();
            if (vision > 0) {
                return clamp01(vision);
            }
        }
        return 0f;
    }

    /** Block and sky light at the eyes, each 0..15 scaled by 16 (so 0..240). */
    private static int[] eyeBrightness(ClientLevel level, Entity viewer) {
        if (viewer == null) {
            return new int[]{0, 0};
        }
        Vec3 feet = viewer.position();
        BlockPos eyes = BlockPos.containing(feet.x, viewer.getEyeY(), feet.z);
        return new int[]{level.getBrightness(LightLayer.BLOCK, eyes) * 16, level.getBrightness(LightLayer.SKY, eyes) * 16};
    }

    private static float clamp01(float v) {
        return Math.clamp(v, 0f, 1f);
    }

    private static float[] toFloats(double[] v) {
        return new float[]{(float) v[0], (float) v[1], (float) v[2]};
    }

    private static int[] floorInts(double[] v) {
        return new int[]{(int) Math.floor(v[0]), (int) Math.floor(v[1]), (int) Math.floor(v[2])};
    }

    private static float[] fract(double[] v) {
        return new float[]{(float) (v[0] - Math.floor(v[0])), (float) (v[1] - Math.floor(v[1])), (float) (v[2] - Math.floor(v[2]))};
    }

    /**
     * cameraPosition as Iris reports it (CameraUniforms.CameraPositionTracker): x and z are shifted
     * by multiples of 30000 once the camera is far out or teleports more than 1000 blocks, so the
     * float a shader gets stays precise. The "unshifted" copies are the real position.
     */
    static final class CameraTracker {
        private static final double WALK_RANGE = 30000;
        private static final double TP_RANGE = 1000;
        final double[] shift = new double[3];
        double[] current = new double[3];
        double[] previous = new double[3];
        double[] currentUnshifted = new double[3];
        double[] previousUnshifted = new double[3];

        void update(Vec3 position) {
            update(position.x, position.y, position.z);
        }

        void update(double x, double y, double z) {
            previous = current;
            previousUnshifted = currentUnshifted;
            current = new double[]{x + shift[0], y + shift[1], z + shift[2]};
            currentUnshifted = new double[]{x, y, z};
            double dx = shiftFor(current[0], previous[0]);
            double dz = shiftFor(current[2], previous[2]);
            if (dx != 0.0 || dz != 0.0) {
                shift[0] += dx;
                shift[2] += dz;
                current = new double[]{current[0] + dx, current[1], current[2] + dz};
                previous = new double[]{previous[0] + dx, previous[1], previous[2] + dz};
            }
        }

        private static double shiftFor(double value, double prev) {
            if (Math.abs(value) > WALK_RANGE || Math.abs(value - prev) > TP_RANGE) {
                return -(value - (value % WALK_RANGE));
            }
            return 0.0;
        }
    }
}
