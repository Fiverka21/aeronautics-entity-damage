# Aeronautics Entity Damage

Aeronautics Entity Damage is a NeoForge addon for Create Aeronautics that makes moving Sable physics sublevels damage mobs and players when they crash into them.

## How it works

The mod uses its own server-side collision check after each Sable physics step. It does not depend on Create's entity-collision system or replace Sable's physics collision handling.

For every moving sublevel, the mod:

- Sweeps the sublevel's bounds between its previous and current positions.
- Finds living entities touched by that swept area.
- Calculates the relative impact speed using the sublevel's observed movement, Sable's velocity, and the target's movement.
- Applies damage when a new contact is detected.

A target is damaged once while it remains in contact. After the sublevel separates from the target, touching it again can cause another hit. Fast-moving sublevels can still hit targets that they pass through between physics steps because the previous and current bounds are swept together.

## Damage formula

```text
damage =
  damageMultiplier
  × (mass / massReference)
  × (impactSpeed / speedReference)^speedDamageExponent
```

Where:

- `mass` is the Sable sublevel's mass in kpg.
- `impactSpeed` is the relative collision speed in blocks per second.
- `damageMultiplier` controls the overall amount of damage.
- `massReference` defines the mass that represents one mass unit.
- `speedReference` defines the speed that represents one speed unit.
- `speedDamageExponent` controls how strongly speed affects damage.

The default `speedDamageExponent` is `2`, so speed uses quadratic scaling. For example:

- `1.0` gives linear speed scaling.
- `2.0` gives quadratic speed scaling.
- `3.0` makes high-speed impacts scale much more sharply.

Damage is limited by `maximumDamage`, and impacts below `minimumSpeed` do not deal damage.

## Configuration

Settings are stored in the common NeoForge configuration file:

```text
config/aeronautocsentitydamage-common.toml
```

| Setting | Default | Description |
| --- | ---: | --- |
| `enabled` | `true` | Enables or disables collision damage. |
| `damageMultiplier` | `1.0` | Overall damage multiplier. |
| `massReference` | `1000.0` | Reference sublevel mass in kpg. |
| `speedReference` | `10.0` | Reference impact speed in blocks per second. |
| `speedDamageExponent` | `2.0` | Controls how sharply damage scales with impact speed. |
| `minimumSpeed` | `0.05` | Minimum relative speed required to deal damage. |
| `maximumDamage` | `40.0` | Maximum damage from one impact. |

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1.251 or newer
- Create 6.0.0 or newer
- Create Aeronautics 1.0 or newer
- Sable 2.0.5 or newer

## Development

Run the following command to compile and package the addon:

```bash
./gradlew build
```
