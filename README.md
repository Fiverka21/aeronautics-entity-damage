# Aeronautics Entity Damage

NeoForge 1.21.1 addon for Create Aeronautics. Moving Aeronautics/Create contraptions damage mobs and players when they collide with them.

Damage is calculated from the contraption’s Sable mass and impact speed:

`damage = damageMultiplier × (mass / massReference) × (speed / speedReference)²`

The defaults use 1,000 kpg and 10 blocks/second as reference values, require at least 2 blocks/second, cap a hit at 40 damage, and prevent the same ship from repeatedly damaging the same target within 10 ticks.

All values are configurable in the common NeoForge config at `config/aeronautocsentitydamage-common.toml`.

## Dependencies

- Minecraft 1.21.1
- NeoForge 21.1.251 or newer
- Create 6.0.0 or newer
- Create Aeronautics 1.0 or newer

## Development

Run `./gradlew build` to compile and package the addon.
