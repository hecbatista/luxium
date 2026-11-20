package io.github.hecbatista.metalshaders.uniform;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
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
 * Not here yet: matrices and per-draw values (step 6), pack formulas and {@code wetness}
 * (they need the smoothing in step 5's formula part).
 */
public final class GameUniforms {
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

        // CameraUniforms
        camera.update(cam.position());
        values.put("near", 0.05f);
        values.put("far", (float) (mc.options.getEffectiveRenderDistance() * 16));
        values.put("cameraPosition", toFloats(camera.current));
        values.put("eyeAltitude", (float) camera.current[1]);
        values.put("previousCameraPosition", toFloats(camera.previous));
        values.put("cameraPositionInt", floorInts(camera.currentUnshifted));
        values.put("cameraPositionFract", fract(camera.currentUnshifted));
        values.put("previousCameraPositionInt", floorInts(camera.previousUnshifted));
        values.put("previousCameraPositionFract", fract(camera.previousUnshifted));

        // CommonUniforms: weather and sky
        values.put("rainStrength", clamp01(level.getRainLevel(partialTick)));
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
        values.put("eyeBrightness", eyeBrightness(level, viewer));
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
