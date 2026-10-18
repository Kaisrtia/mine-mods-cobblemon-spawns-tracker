package io.github.kaisrtia.cobblemonnotificationtracker.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.kaisrtia.cobblemonnotificationtracker.CobblemonNotificationTracker;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Cấu hình chung cho mod trên server (Webhook Discord, chế độ chat in-game, cooldown...).
 */
public class ModConfig {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static ModConfig INSTANCE = new ModConfig();

  private String webhookUrl = "";
  private boolean sendToDiscord = true;
  private boolean sendToInGameChat = false;
  private int cooldownSeconds = 15;

  public static ModConfig get() {
    return INSTANCE;
  }

  public static void load() {
    Path configPath = getConfigPath();
    if (Files.exists(configPath)) {
      try (BufferedReader reader = Files.newBufferedReader(configPath)) {
        ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
        if (loaded != null) {
          INSTANCE = loaded;
          CobblemonNotificationTracker.LOGGER.info("Loaded CobblemonNotificationTracker config successfully.");
          return;
        }
      } catch (Exception e) {
        CobblemonNotificationTracker.LOGGER.error("Failed to load config, using default settings", e);
      }
    }
    // Tạo file config mặc định nếu chưa có
    save();
  }

  public static void save() {
    Path configPath = getConfigPath();
    try {
      Files.createDirectories(configPath.getParent());
      try (BufferedWriter writer = Files.newBufferedWriter(configPath)) {
        GSON.toJson(INSTANCE, writer);
      }
    } catch (IOException e) {
      CobblemonNotificationTracker.LOGGER.error("Failed to save config file: {}", configPath, e);
    }
  }

  private static Path getConfigPath() {
    return FabricLoader.getInstance().getConfigDir()
            .resolve("cobblemonnotificationtracker")
            .resolve("config.json");
  }

  public String getWebhookUrl() {
    return webhookUrl != null ? webhookUrl : "";
  }

  public void setWebhookUrl(String webhookUrl) {
    this.webhookUrl = webhookUrl != null ? webhookUrl.trim() : "";
  }

  public boolean isSendToDiscord() {
    return sendToDiscord;
  }

  public void setSendToDiscord(boolean sendToDiscord) {
    this.sendToDiscord = sendToDiscord;
  }

  public boolean isSendToInGameChat() {
    return sendToInGameChat;
  }

  public void setSendToInGameChat(boolean sendToInGameChat) {
    this.sendToInGameChat = sendToInGameChat;
  }

  public int getCooldownSeconds() {
    return cooldownSeconds;
  }

  public void setCooldownSeconds(int cooldownSeconds) {
    this.cooldownSeconds = cooldownSeconds;
  }
}
