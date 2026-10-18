package io.github.kaisrtia.cobblemonnotificationtracker.model;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lưu trữ cấu hình và danh sách Pokémon theo dõi của một người chơi cụ thể.
 */
public class PlayerWatchlist {
  private UUID playerUuid;
  private Set<String> trackedPokemon = new HashSet<>();
  private boolean notifyShiny = true;
  private boolean notifyLegendary = true;
  private int maxRadius = 300; // Khoảng cách tối đa (blocks). Giá trị <= 0 nghĩa là không giới hạn.
  private boolean enabled = true;
  private String discordUserId = ""; // Discord User ID để nhận tag mention

  // Bộ đệm thời gian thông báo gần nhất cho mỗi loài (đơn vị ms) nhằm tránh spam khi bầy đàn spawn liên tục.
  private transient Map<String, Long> lastNotifiedTimes = new ConcurrentHashMap<>();

  // Constructor mặc định cho Gson deserialization
  public PlayerWatchlist() {
  }

  public PlayerWatchlist(UUID playerUuid) {
    this.playerUuid = playerUuid;
  }

  public UUID getPlayerUuid() {
    return playerUuid;
  }

  public void setPlayerUuid(UUID playerUuid) {
    this.playerUuid = playerUuid;
  }

  public boolean addPokemon(String species) {
    if (species == null || species.isBlank()) return false;
    return trackedPokemon.add(species.trim().toLowerCase());
  }

  public boolean removePokemon(String species) {
    if (species == null || species.isBlank()) return false;
    return trackedPokemon.remove(species.trim().toLowerCase());
  }

  public boolean isTracking(String species) {
    if (species == null) return false;
    return trackedPokemon.contains(species.trim().toLowerCase());
  }

  public void clear() {
    trackedPokemon.clear();
  }

  public Set<String> getTrackedPokemon() {
    return Collections.unmodifiableSet(trackedPokemon);
  }

  public boolean isNotifyShiny() {
    return notifyShiny;
  }

  public void setNotifyShiny(boolean notifyShiny) {
    this.notifyShiny = notifyShiny;
  }

  public boolean isNotifyLegendary() {
    return notifyLegendary;
  }

  public void setNotifyLegendary(boolean notifyLegendary) {
    this.notifyLegendary = notifyLegendary;
  }

  public int getMaxRadius() {
    return maxRadius;
  }

  public void setMaxRadius(int maxRadius) {
    this.maxRadius = maxRadius;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getDiscordUserId() {
    return discordUserId != null ? discordUserId : "";
  }

  public void setDiscordUserId(String discordUserId) {
    this.discordUserId = discordUserId != null ? discordUserId.trim() : "";
  }

  /**
   * Kiểm tra xem Pokémon spawn có thỏa mãn các điều kiện nhận thông báo của người chơi hay không.
   */
  public boolean shouldNotify(String species, boolean isShiny, boolean isLegendary, double distance) {
    if (!enabled) {
      return false;
    }

    // Kiểm tra bán kính nếu có thiết lập
    if (maxRadius > 0 && distance > maxRadius) {
      return false;
    }

    // Báo nếu là Shiny và người chơi bật tính năng thông báo Shiny
    if (isShiny && notifyShiny) {
      return true;
    }

    // Báo nếu là Huyền thoại và người chơi bật tính năng thông báo Huyền thoại
    if (isLegendary && notifyLegendary) {
      return true;
    }

    // Báo nếu loài nằm trong danh sách theo dõi cụ thể
    return isTracking(species);
  }

  /**
   * Kiểm tra thời gian hồi chiêu (cooldown) để tránh gửi thông báo liên tục khi cùng một loài spawn theo bầy.
   */
  public boolean checkAndSetCooldown(String species, long cooldownMs) {
    if (lastNotifiedTimes == null) {
      lastNotifiedTimes = new ConcurrentHashMap<>();
    }

    long now = System.currentTimeMillis();
    Long lastTime = lastNotifiedTimes.get(species);
    if (lastTime != null && (now - lastTime) < cooldownMs) {
      return false;
    }

    lastNotifiedTimes.put(species, now);
    return true;
  }
}
