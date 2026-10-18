package io.github.kaisrtia.cobblemonnotificationtracker;

import net.fabricmc.api.ModInitializer;
import io.github.kaisrtia.cobblemonnotificationtracker.command.TrackCommand;
import io.github.kaisrtia.cobblemonnotificationtracker.config.ModConfig;
import io.github.kaisrtia.cobblemonnotificationtracker.listener.PokemonSpawnListener;
import io.github.kaisrtia.cobblemonnotificationtracker.storage.WatchlistManager;
import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CobblemonNotificationTracker implements ModInitializer {
  public static final String MOD_ID = "cobblemonnotificationtracker";

  // This logger is used to write text to the console and the log file.
  // It is considered best practice to use your mod id as the logger's name.
  // That way, it's clear which mod wrote info, warnings, and errors.
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitialize() {
    LOGGER.info("Initializing Cobblemon Notification Tracker...");
    ModConfig.load();
    WatchlistManager.getInstance().initialize();
    TrackCommand.register();
    PokemonSpawnListener.register();
    LOGGER.info("Cobblemon Notification Tracker initialized successfully.");
  }

  public static ResourceLocation id(String path) {
    return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
  }
}
