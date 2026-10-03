package aero.damage;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
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
            final MassData massData = subLevel.getMassTracker();
            final double mass = massData == null ? 0.0D : massData.getMass();
            if (mass <= 0.0D) {
                LAST_BOUNDS.put(boundsKey, worldBounds(subLevel));
                continue;
            }
            final AABB currentBounds = worldBounds(subLevel);
            final AABB previousBounds = LAST_BOUNDS.put(boundsKey, currentBounds);
            final AABB sweptBounds = previousBounds == null
                    ? currentBounds
                    : union(previousBounds, currentBounds);
            final List<LivingEntity> targets = findTargets(level, subLevel, sweptBounds);
            if (targets.isEmpty()) {
                continue;
            }

            final CollisionShapes collisionShapes = worldCollisionShapes(subLevel, subLevel.logicalPose(),
                    previousBounds == null ? null : subLevel.lastPose());
            checkEntities(level, subLevel, previousBounds, currentBounds, collisionShapes.previous(),
                    collisionShapes.current(), targets, timeStep, mass, activeContacts);
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
                                      final List<AABB> previousCollisionShapes,
                                      final List<AABB> currentCollisionShapes,
                                      final List<LivingEntity> targets,
                                      final double timeStep,
                                      final double mass,
                                      final Set<HitKey> activeContacts) {
        final Vec3 observedVelocity = observedVelocity(previousBounds, currentBounds, timeStep);
        for (final LivingEntity target : targets) {
            // The sublevel bounds are only a broad-phase query. Test against the collision shape
            // of every non-air block so empty space inside or around a contraption cannot hit.
            if (!intersectsCollisionShapes(previousCollisionShapes, currentCollisionShapes,
                    target.getBoundingBox())) {
                continue;
            }

            final HitKey key = new HitKey(subLevel.getUniqueId(), level.dimension(), target.getId());
            final boolean touchingNow = intersectsCurrentShapes(currentCollisionShapes, target.getBoundingBox());
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
            final double sourceMovementSpeed = Math.max(
                    sourceVelocity.length(),
                    observedVelocity.length());
            // Relative velocity alone is not enough to identify a contraption impact: a target
            // walking or falling into a stationary contraption would otherwise deal damage.
            // Keep the contact active above, but only damage when the sublevel itself moved.
            if (!Double.isFinite(sourceMovementSpeed)
                    || sourceMovementSpeed < Config.MINIMUM_SPEED.get()) {
                continue;
            }

            final Vec3 targetVelocity = target.getDeltaMovement().scale(20.0D);
            final double impactSpeed = Math.max(
                    sourceVelocity.subtract(targetVelocity).length(),
                    observedVelocity.subtract(targetVelocity).length());
            applyDamage(level, target, impactSpeed, mass);
        }
    }

    private static List<LivingEntity> findTargets(final ServerLevel level,
                                                   final ServerSubLevel subLevel,
                                                   final AABB sweptBounds) {
        return level.getEntitiesOfClass(LivingEntity.class, sweptBounds.inflate(0.1D),
                entity -> entity.isAlive()
                        && !entity.isSpectator()
                        && Sable.HELPER.getTrackingSubLevel(entity) != subLevel);
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

    private static boolean intersectsSweptBounds(final AABB previousBounds,
                                                  final AABB currentBounds,
                                                  final AABB targetBounds) {
        final double startX = (previousBounds.minX + previousBounds.maxX) * 0.5D;
        final double startY = (previousBounds.minY + previousBounds.maxY) * 0.5D;
        final double startZ = (previousBounds.minZ + previousBounds.maxZ) * 0.5D;
        final double endX = (currentBounds.minX + currentBounds.maxX) * 0.5D;
        final double endY = (currentBounds.minY + currentBounds.maxY) * 0.5D;
        final double endZ = (currentBounds.minZ + currentBounds.maxZ) * 0.5D;
        final double halfX = Math.max(previousBounds.getXsize(), currentBounds.getXsize()) * 0.5D;
        final double halfY = Math.max(previousBounds.getYsize(), currentBounds.getYsize()) * 0.5D;
        final double halfZ = Math.max(previousBounds.getZsize(), currentBounds.getZsize()) * 0.5D;

        return intersectsSegment(
                targetBounds.minX - halfX, targetBounds.minY - halfY, targetBounds.minZ - halfZ,
                targetBounds.maxX + halfX, targetBounds.maxY + halfY, targetBounds.maxZ + halfZ,
                startX, startY, startZ, endX, endY, endZ);
    }

    private static boolean intersectsCollisionShapes(final List<AABB> previousShapes,
                                                     final List<AABB> currentShapes,
                                                     final AABB targetBounds) {
        for (int i = 0; i < currentShapes.size(); i++) {
            final AABB currentShape = currentShapes.get(i);
            final AABB previousShape = previousShapes.get(i);
            if (intersectsSweptBounds(previousShape, currentShape, targetBounds)) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersectsCurrentShapes(final List<AABB> currentShapes,
                                                   final AABB targetBounds) {
        for (final AABB shape : currentShapes) {
            if (shape.intersects(targetBounds)) {
                return true;
            }
        }
        return false;
    }

    private static CollisionShapes worldCollisionShapes(final ServerSubLevel subLevel,
                                                        final Pose3dc currentPose,
                                                        final Pose3dc previousPose) {
        final List<AABB> currentShapes = new ArrayList<>();
        final List<AABB> previousShapes = previousPose == null ? currentShapes : new ArrayList<>();
        final LevelAccelerator blockGetter = new LevelAccelerator(subLevel.getLevel());
        final var bounds = subLevel.getPlot().getBoundingBox();
        final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    final int blockX = x;
                    final int blockY = y;
                    final int blockZ = z;
                    blockPos.set(x, y, z);
                    final BlockState state = blockGetter.getBlockState(blockPos);
                    if (state.isAir()) {
                        continue;
                    }

                    final VoxelShape shape = state.getCollisionShape(blockGetter, blockPos);
                    shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                        final AABB localShape = new AABB(blockX + minX, blockY + minY, blockZ + minZ,
                                blockX + maxX, blockY + maxY, blockZ + maxZ);
                        currentShapes.add(transformShape(localShape, currentPose));
                        if (previousPose != null) {
                            previousShapes.add(transformShape(localShape, previousPose));
                        }
                    });
                }
            }
        }
        return new CollisionShapes(previousShapes, currentShapes);
    }

    private static AABB transformShape(final AABB shape, final Pose3dc pose) {
        final Vector3d transformed = new Vector3d();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int x = 0; x < 2; x++) {
            final double px = x == 0 ? shape.minX : shape.maxX;
            for (int y = 0; y < 2; y++) {
                final double py = y == 0 ? shape.minY : shape.maxY;
                for (int z = 0; z < 2; z++) {
                    final double pz = z == 0 ? shape.minZ : shape.maxZ;
                    pose.transformPosition(transformed.set(px, py, pz));
                    minX = Math.min(minX, transformed.x);
                    minY = Math.min(minY, transformed.y);
                    minZ = Math.min(minZ, transformed.z);
                    maxX = Math.max(maxX, transformed.x);
                    maxY = Math.max(maxY, transformed.y);
                    maxZ = Math.max(maxZ, transformed.z);
                }
            }
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static boolean intersectsSegment(final double minX,
                                              final double minY,
                                              final double minZ,
                                              final double maxX,
                                              final double maxY,
                                              final double maxZ,
                                              final double startX,
                                              final double startY,
                                              final double startZ,
                                              final double endX,
                                              final double endY,
                                              final double endZ) {
        double minimum = 0.0D;
        double maximum = 1.0D;
        for (int axis = 0; axis < 3; axis++) {
            final double startCoordinate = axis == 0 ? startX : axis == 1 ? startY : startZ;
            final double delta = axis == 0 ? endX - startX : axis == 1 ? endY - startY : endZ - startZ;
            final double minimumCoordinate = axis == 0 ? minX : axis == 1 ? minY : minZ;
            final double maximumCoordinate = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            if (Math.abs(delta) < 1.0E-9D) {
                if (startCoordinate < minimumCoordinate || startCoordinate > maximumCoordinate) {
                    return false;
                }
                continue;
            }

            double entry = (minimumCoordinate - startCoordinate) / delta;
            double exit = (maximumCoordinate - startCoordinate) / delta;
            if (entry > exit) {
                final double swap = entry;
                entry = exit;
                exit = swap;
            }
            minimum = Math.max(minimum, entry);
            maximum = Math.min(maximum, exit);
            if (minimum > maximum) {
                return false;
            }
        }

        return true;
    }

    private static void applyDamage(final ServerLevel level,
                                    final LivingEntity target,
                                    final double speed,
                                    final double mass) {
        if (!Double.isFinite(speed)
                || !Double.isFinite(mass)
                || speed < Config.MINIMUM_SPEED.get()
                || mass <= 0.0D) {
            return;
        }

        final double massFactor = mass / Config.MASS_REFERENCE.get();
        final double speedFactor = speed / Config.SPEED_REFERENCE.get();
        final double scaledSpeedFactor = Math.pow(speedFactor, Config.SPEED_DAMAGE_EXPONENT.get());
        final double calculatedDamage = Math.min(Config.MAXIMUM_DAMAGE.get(),
                Config.DAMAGE_MULTIPLIER.get() * massFactor * scaledSpeedFactor);
        if (!Double.isFinite(calculatedDamage) || calculatedDamage < 0.5D) {
            return;
        }

        final float damage = (float) calculatedDamage;
        if (!Float.isFinite(damage) || damage <= 0.0F) {
            return;
        }

        target.hurt(level.damageSources().generic(), damage);
    }

    private record HitKey(UUID subLevelId, ResourceKey<Level> dimension, int targetId) {
    }

    private record BoundsKey(UUID subLevelId, ResourceKey<Level> dimension) {
    }

    private record CollisionShapes(List<AABB> previous, List<AABB> current) {
    }
}
