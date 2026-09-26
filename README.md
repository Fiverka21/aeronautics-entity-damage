# Aeronautics Entity Damage

NeoForge 1.21.1 addon for Create Aeronautics. Moving Sable physics sublevels damage mobs and players when they crash into them.

Damage is calculated from the sublevel’s Sable mass and impact speed:

`damage = damageMultiplier × (mass / massReference) × (speed / speedReference)²`

The mod runs its own server-side swept-bounds collision check after each Sable physics step. The defaults use 1,000 kpg and 10 blocks/second as reference values, require at least 0.05 blocks/second of relative impact speed, and cap a hit at 40 damage. A mob is damaged once when a contraption first touches it, and can be damaged again after separating and touching it again.

All values are configurable in the common NeoForge config at `config/aeronautocsentitydamage-common.toml`.

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1.251 or newer
- Create 6.0.0 or newer
- Create Aeronautics 1.0 or newer
- Sable 2.0.5 or newer

## Development

Run `./gradlew build` to compile and package the addon.
