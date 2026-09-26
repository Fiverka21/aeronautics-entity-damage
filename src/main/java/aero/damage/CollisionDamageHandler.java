package aero.damage;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns collision detection for moving Sable sublevels.
 *
 * <p>This deliberately does not hook Create's or Sable's entity collision resolution. Sable has
 * already advanced the physics pose when its post-physics event fires, so this class checks the
 * swept bounds of each sublevel against living entities and applies damage on the server.</p>
 */
public final class CollisionDamageHandler {
    private static final Map<BoundsKey, AABB> LAST_BOUNDS = new HashMap<>();
    private static final Set<HitKey> ACTIVE_CONTACTS = new HashSet<>();

    private CollisionDamageHandler() {
    }

    /** Clears state when a server starts or a world is reloaded in the same JVM. */
    public static void reset() {
        LAST_BOUNDS.clear();
        ACTIVE_CONTACTS.clear();
    }

    /** Checks every sublevel after a Sable physics substep has completed. */
    public static void checkMovingSublevels(final SubLevelPhysicsSystem physicsSystem,
                                             final double timeStep) {
        final ServerLevel level = physicsSystem.getLevel();
        if (!Config.ENABLED.get() || level.isClientSide()) {
            return;
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        final Set<BoundsKey> activeSublevels = new HashSet<>();
        final Set<HitKey> activeContacts = new HashSet<>();
        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }

            final UUID subLevelId = subLevel.getUniqueId();
            if (subLevelId == null) {
                continue;
            }

            final BoundsKey boundsKey = new BoundsKey(subLevelId, level.dimension());
            activeSublevels.add(boundsKey);
            final AABB currentBounds = worldBounds(subLevel);
            final AABB previousBounds = LAST_BOUNDS.put(boundsKey, currentBounds);
            final AABB sweptBounds = previousBounds == null
                    ? currentBounds
                    : union(previousBounds, currentBounds);

            checkEntities(level, subLevel, previousBounds, currentBounds, sweptBounds, timeStep, activeContacts);
        }

        LAST_BOUNDS.keySet().removeIf(key -> key.dimension().equals(level.dimension())
                && !activeSublevels.contains(key));
        ACTIVE_CONTACTS.removeIf(key -> key.dimension().equals(level.dimension())
                && !activeContacts.contains(key));
    }

    private static void checkEntities(final ServerLevel level,
                                      final ServerSubLevel subLevel,
                                      final AABB previousBounds,
                                      final AABB currentBounds,
                                      final AABB sweptBounds,
                                      final double timeStep,
                                      final Set<HitKey> activeContacts) {
        final MassData massData = subLevel.getMassTracker();
        final double mass = massData == null ? 0.0D : massData.getMass();
        if (mass <= 0.0D) {
            return;
        }

        final AABB searchBounds = sweptBounds.inflate(0.1D);
        final AABB currentContactBounds = currentBounds.inflate(0.1D);
        final Vec3 observedVelocity = observedVelocity(previousBounds, currentBounds, timeStep);
        final Vec3 latestLinearVelocity = new Vec3(
                subLevel.latestLinearVelocity.x,
                subLevel.latestLinearVelocity.y,
                subLevel.latestLinearVelocity.z);
        for (final LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, searchBounds,
                entity -> entity.isAlive() && !entity.isSpectator())) {
            if (Sable.HELPER.getTrackingSubLevel(target) == subLevel) {
                continue;
            }

            final HitKey key = new HitKey(subLevel.getUniqueId(), level.dimension(), target.getId());
            final boolean touchingNow = target.getBoundingBox().intersects(currentContactBounds);
            final boolean wasTouching = ACTIVE_CONTACTS.contains(key);
            if (touchingNow) {
                activeContacts.add(key);
            }

            // A target remains active while it overlaps the current bounds, but leaving the
            // bounds is not itself a new impact. A target that crossed the swept bounds without
            // being active on the previous step is a new touch, even if it is no longer inside
            // the current bounds because the contraption moved through it in one physics step.
            if (wasTouching) {
                continue;
            }

            final Vec3 sourceVelocity = sourceVelocityAt(subLevel, target.getBoundingBox().getCenter());
            final Vec3 targetVelocity = target.getDeltaMovement().scale(20.0D);
            final double impactSpeed = Math.max(
                    sourceVelocity.subtract(targetVelocity).length(),
                    Math.max(observedVelocity.subtract(targetVelocity).length(),
                            latestLinearVelocity.subtract(targetVelocity).length()));
            applyDamage(level, target, impactSpeed, mass);
        }
    }

    private static Vec3 observedVelocity(final AABB previousBounds,
                                         final AABB currentBounds,
                                         final double timeStep) {
        if (previousBounds == null || timeStep <= 0.0D) {
            return Vec3.ZERO;
        }

        return currentBounds.getCenter()
                .subtract(previousBounds.getCenter())
                .scale(1.0D / timeStep);
    }

    private static Vec3 sourceVelocityAt(final ServerSubLevel subLevel, final Vec3 globalPosition) {
        final Vector3d localPosition = new Vector3d(globalPosition.x, globalPosition.y, globalPosition.z);
        subLevel.logicalPose().transformPositionInverse(localPosition);

        final Vector3d velocity = Sable.HELPER.getVelocity(subLevel.getLevel(), subLevel, localPosition,
                new Vector3d());
        return new Vec3(velocity.x, velocity.y, velocity.z);
    }

    private static AABB worldBounds(final ServerSubLevel subLevel) {
        return new BoundingBox3d(subLevel.getPlot().getBoundingBox())
                .transform(subLevel.logicalPose())
                .toMojang();
    }

    private static AABB union(final AABB first, final AABB second) {
        return new AABB(
                Math.min(first.minX, second.minX),
                Math.min(first.minY, second.minY),
                Math.min(first.minZ, second.minZ),
                Math.max(first.maxX, second.maxX),
                Math.max(first.maxY, second.maxY),
                Math.max(first.maxZ, second.maxZ));
    }

    private static void applyDamage(final ServerLevel level,
                                    final LivingEntity target,
                                    final double speed,
                                    final double mass) {
        if (speed < Config.MINIMUM_SPEED.get()) {
            return;
        }

        final double massFactor = mass / Config.MASS_REFERENCE.get();
        final double speedFactor = speed / Config.SPEED_REFERENCE.get();
        final float damage = (float) Math.min(Config.MAXIMUM_DAMAGE.get(),
                Config.DAMAGE_MULTIPLIER.get() * massFactor * speedFactor * speedFactor);
        if (damage <= 0.0F) {
            return;
        }

        target.hurt(level.damageSources().generic(), damage);
    }

    private record HitKey(UUID subLevelId, ResourceKey<Level> dimension, int targetId) {
    }

    private record BoundsKey(UUID subLevelId, ResourceKey<Level> dimension) {
    }
}
