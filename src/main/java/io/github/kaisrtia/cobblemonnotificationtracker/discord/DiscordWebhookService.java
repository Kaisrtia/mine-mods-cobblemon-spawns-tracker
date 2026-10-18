package io.github.kaisrtia.cobblemonnotificationtracker.discord;

import com.cobblemon.mod.common.pokemon.Species;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kaisrtia.cobblemonnotificationtracker.CobblemonNotificationTracker;
import io.github.kaisrtia.cobblemonnotificationtracker.config.ModConfig;
import io.github.kaisrtia.cobblemonnotificationtracker.model.PlayerWatchlist;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Gửi thông báo Pokémon xuất hiện qua Discord Webhook bằng HTTP Client tích hợp của Java.
 */
public class DiscordWebhookService {

  private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();

  public record NotifiedPlayer(ServerPlayer player, PlayerWatchlist watchlist,
                               int distance, String direction) {
  }

  /**
   * Gửi tin nhắn Embed lên Discord Webhook khi có Pokémon thỏa mãn điều kiện theo dõi của người chơi.
   */
  public static void sendNotification(Species species, boolean isShiny, boolean isLegendary,
                                      int level, BlockPos pos, ServerLevel world,
                                      List<NotifiedPlayer> notifiedPlayers) {
    String webhookUrl = ModConfig.get().getWebhookUrl();
    if (webhookUrl == null || webhookUrl.isBlank()) {
      return;
    }

    String rawName = species.getName().toLowerCase();
    String displayName = species.getName().substring(0, 1).toUpperCase() + species.getName().substring(1);

    // Gom danh sách mention Discord của các người chơi theo dõi Pokémon này
    List<String> mentions = new ArrayList<>();
    for (NotifiedPlayer np : notifiedPlayers) {
      String discordId = np.watchlist().getDiscordUserId();
      if (!discordId.isBlank()) {
        mentions.add("<@" + discordId + ">");
      }
    }

    // Chọn màu sắc Embed theo độ hiếm
    int color;
    String title;
    if (isShiny) {
      color = 16766720; // Vàng kim (0xFFD700)
      title = "✨ SHINY POKÉMON XUẤT HIỆN!";
    } else if (isLegendary) {
      color = 10180918; // Tím (0x9B59B6)
      title = "⭐ POKÉMON HUYỀN THOẠI XUẤT HIỆN!";
    } else {
      color = 3066993;  // Xanh lục (0x2ECC71)
      title = "🔔 POKÉMON THEO DÕI XUẤT HIỆN!";
    }

    // Link ảnh sprite Pokémon từ Showdown (hỗ trợ cả shiny)
    String spriteUrl = isShiny
            ? "https://play.pokemonshowdown.com/sprites/gen5-shiny/" + rawName + ".png"
            : "https://play.pokemonshowdown.com/sprites/gen5/" + rawName + ".png";

    // Tên dimension thân thiện
    String dimensionName = world.dimension().location().getPath();
    dimensionName = dimensionName.substring(0, 1).toUpperCase() + dimensionName.substring(1);

    // Tạo cấu trúc JSON cho Discord Embed
    JsonObject payload = new JsonObject();

    if (!mentions.isEmpty()) {
      payload.addProperty("content", String.join(" ", mentions));
    }

    JsonArray embeds = new JsonArray();
    JsonObject embed = new JsonObject();
    embed.addProperty("title", title);
    embed.addProperty("description", "**" + displayName + "** *(Lv. " + level + ")* đã xuất hiện trong thế giới!");
    embed.addProperty("color", color);
    embed.addProperty("timestamp", Instant.now().toString());

    // Thumbnail ảnh Pokémon
    JsonObject thumbnail = new JsonObject();
    thumbnail.addProperty("url", spriteUrl);
    embed.add("thumbnail", thumbnail);

    // Các trường chi tiết (Đã bỏ trường người nhận theo yêu cầu)
    JsonArray fields = new JsonArray();

    JsonObject coordField = new JsonObject();
    coordField.addProperty("name", "📍 Tọa độ");
    coordField.addProperty("value", "`" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "`");
    coordField.addProperty("inline", true);
    fields.add(coordField);

    JsonObject dimField = new JsonObject();
    dimField.addProperty("name", "🌍 Thế giới");
    dimField.addProperty("value", dimensionName);
    dimField.addProperty("inline", true);
    fields.add(dimField);

    embed.add("fields", fields);

    // Footer
    JsonObject footer = new JsonObject();
    footer.addProperty("text", "Cobblemon Notification Tracker");
    embed.add("footer", footer);

    embeds.add(embed);
    payload.add("embeds", embeds);

    // Gửi HTTP POST bất đồng bộ đến Discord Webhook
    try {
      HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(webhookUrl))
              .header("Content-Type", "application/json")
              .header("User-Agent", "CobblemonNotificationTracker/1.0")
              .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
              .timeout(Duration.ofSeconds(10))
              .build();

      HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString())
              .thenAccept(response -> {
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                  CobblemonNotificationTracker.LOGGER.info("Sent Discord notification for {} successfully.", displayName);
                } else {
                  CobblemonNotificationTracker.LOGGER.warn("Discord Webhook responded with status code: {} - {}", code, response.body());
                }
              })
              .exceptionally(ex -> {
                CobblemonNotificationTracker.LOGGER.error("Failed to send Discord webhook for {}", displayName, ex);
                return null;
              });
    } catch (Exception e) {
      CobblemonNotificationTracker.LOGGER.error("Error creating Discord webhook request", e);
    }
  }
}
