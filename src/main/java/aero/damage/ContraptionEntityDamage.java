package aero.damage;

import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Adds damage to Create's existing contraption collision pass. */
@Mixin(targets = "com.simibubi.create.content.contraptions.ContraptionCollider")
public abstract class ContraptionEntityDamage {
    private static final Map<Long, Long> LAST_HITS = new HashMap<>();

    @Inject(method = "collideEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;isAlive()Z"))
    private static void aeronauticsEntityDamage(CallbackInfo callbackInfo,
                                                @Local(argsOnly = true) AbstractContraptionEntity contraption,
                                                @Local(ordinal = 0) Entity entity) {
        if (!Config.ENABLED.get() || !(entity instanceof LivingEntity target) || entity.level().isClientSide()) return;

        final Level level = contraption.level();
        final Object subLevel = findSubLevel(contraption);
        final double mass = invokeDouble(subLevel, "getMassTracker", "getMass");
        final double speed = Math.max(contraption.getDeltaMovement().length(), poseSpeed(subLevel));
        if (mass <= 0.0D || speed < Config.MINIMUM_SPEED.get()) return;

        final long now = level.getGameTime();
        final long key = ((long) contraption.getId() << 32) ^ (target.getId() & 0xffffffffL);
        if (now - LAST_HITS.getOrDefault(key, Long.MIN_VALUE) < Config.HIT_COOLDOWN_TICKS.get()) return;

        final double massFactor = mass / Config.MASS_REFERENCE.get();
        final double speedFactor = speed / Config.SPEED_REFERENCE.get();
        final float damage = (float) Math.min(Config.MAXIMUM_DAMAGE.get(),
                Config.DAMAGE_MULTIPLIER.get() * massFactor * speedFactor * speedFactor);
        if (damage > 0.0F && target.hurt(level.damageSources().generic(), damage)) {
            LAST_HITS.put(key, now);
            cleanup(now);
        }
    }

    private static Object findSubLevel(final Entity entity) {
        try {
            final Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            final Object helper = sable.getField("HELPER").get(null);
            return helper.getClass().getMethod("getContaining", Entity.class).invoke(helper, entity);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static double poseSpeed(final Object subLevel) {
        try {
            final Object currentPose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            final Object previousPose = subLevel.getClass().getMethod("lastPose").invoke(subLevel);
            final Object current = currentPose.getClass().getMethod("position").invoke(currentPose);
            final Object previous = previousPose.getClass().getMethod("position").invoke(previousPose);
            final double dx = coordinate(current, "x") - coordinate(previous, "x");
            final double dy = coordinate(current, "y") - coordinate(previous, "y");
            final double dz = coordinate(current, "z") - coordinate(previous, "z");
            return Math.sqrt(dx * dx + dy * dy + dz * dz) * 20.0D;
        } catch (ReflectiveOperationException | NullPointerException ignored) {
            return 0.0D;
        }
    }

    private static double coordinate(final Object vector, final String axis) throws ReflectiveOperationException {
        return ((Number) vector.getClass().getMethod(axis).invoke(vector)).doubleValue();
    }

    private static double invokeDouble(final Object receiver, final String... methods) {
        try {
            Object value = receiver;
            for (String method : methods) value = value.getClass().getMethod(method).invoke(value);
            return ((Number) value).doubleValue();
        } catch (ReflectiveOperationException | NullPointerException | ClassCastException ignored) {
            return 0.0D;
        }
    }

    private static void cleanup(final long now) {
        if (LAST_HITS.size() < 2048) return;
        final long oldest = now - Math.max(20, Config.HIT_COOLDOWN_TICKS.get()) * 4L;
        final Iterator<Map.Entry<Long, Long>> iterator = LAST_HITS.entrySet().iterator();
        while (iterator.hasNext()) if (iterator.next().getValue() < oldest) iterator.remove();
    }
}
