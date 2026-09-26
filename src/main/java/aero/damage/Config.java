package aero.damage;

import net.neoforged.neoforge.common.ModConfigSpec;

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Enable damage from moving Sable sublevels.")
            .define("enabled", true);

    public static final ModConfigSpec.DoubleValue DAMAGE_MULTIPLIER = BUILDER
            .comment("Damage multiplier; damage scales with sublevel mass and the square of impact speed.")
            .defineInRange("damageMultiplier", 1.0D, 0.0D, 100.0D);

    public static final ModConfigSpec.DoubleValue MASS_REFERENCE = BUILDER
            .comment("Mass in kpg that represents one mass unit in the damage formula.")
            .defineInRange("massReference", 1000.0D, 0.001D, Double.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue SPEED_REFERENCE = BUILDER
            .comment("Impact speed in blocks per second that represents one speed unit.")
            .defineInRange("speedReference", 10.0D, 0.001D, Double.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue SPEED_DAMAGE_EXPONENT = BUILDER
            .comment("Controls how sharply damage scales with impact speed; 1 is linear and 2 is quadratic.")
            .defineInRange("speedDamageExponent", 2.0D, 0.0D, 10.0D);

    public static final ModConfigSpec.DoubleValue MINIMUM_SPEED = BUILDER
            .comment("Minimum movement speed in blocks per second before damage is applied.")
            .defineInRange("minimumSpeed", 0.05D, 0.0D, Double.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue MAXIMUM_DAMAGE = BUILDER
            .comment("Maximum damage dealt by one sublevel impact.")
            .defineInRange("maximumDamage", 40.0D, 0.0D, Double.MAX_VALUE);

    static final ModConfigSpec SPEC = BUILDER.build();

}
