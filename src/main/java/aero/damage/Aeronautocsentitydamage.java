package aero.damage;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Aeronautocsentitydamage.MODID)
public class Aeronautocsentitydamage {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "aeronautocsentitydamage";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Aeronautocsentitydamage(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register ourselves for server and other game events we are interested in.
        // Note that this is necessary if and only if we want *this* class (Aeronautocsentitydamage) to respond directly to events.
        // Do not add this line if there are no @SubscribeEvent-annotated functions in this class, like onServerStarting() below.
        NeoForge.EVENT_BUS.register(this);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Aeronautics entity damage swept collision system ready");
    }

    @SubscribeEvent
    public void onSablePostPhysicsTick(ForgeSablePostPhysicsTickEvent event) {
        CollisionDamageHandler.checkMovingSublevels(event.getPhysicsSystem(), event.getTimeStep());
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        CollisionDamageHandler.reset();
        LOGGER.info("Aeronautics entity damage enabled for {}", event.getServer().getWorldData().getLevelName());
    }
}
