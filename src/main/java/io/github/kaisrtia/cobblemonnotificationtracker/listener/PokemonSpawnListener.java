package io.github.kaisrtia.cobblemonnotificationtracker.listener;

import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import io.github.kaisrtia.cobblemonnotificationtracker.CobblemonNotificationTracker;
import io.github.kaisrtia.cobblemonnotificationtracker.config.ModConfig;
import io.github.kaisrtia.cobblemonnotificationtracker.discord.DiscordWebhookService;
import io.github.kaisrtia.cobblemonnotificationtracker.model.PlayerWatchlist;
import io.github.kaisrtia.cobblemonnotificationtracker.storage.WatchlistManager;
import kotlin.Unit;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lắng nghe sự kiện Pokémon sinh ra trong thế giới (cả sinh tự nhiên lẫn qua lệnh /pokespawn),
 * kiểm tra các điều kiện Pokémon hoang dã và gửi thông báo tới người chơi phù hợp.
 */
public class PokemonSpawnListener {

  private static final MutableComponent PREFIX = Component.literal("[")
          .withStyle(ChatFormatting.DARK_GRAY)
          .append(Component.literal("CobblemonTracker").withStyle(ChatFormatting.AQUA))
          .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));

  // Bộ nhớ đệm giới hạn 2000 UUID để tránh xử lý lặp lại và không gây rò rỉ bộ nhớ
  private static final Set<UUID> processedUuids = Collections.synchronizedSet(
          Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
              return size() > 2000;
            }
          })
  );

  // Ghi nhận các Pokémon được nạp lại từ ổ cứng (chunk load) để tránh thông báo sai
  private static final Set<UUID> loadedFromDisk = Collections.synchronizedSet(
          Collections.newSetFromMap(new LinkedHashMap<UUID, Boolean>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
              return size() > 2000;
            }
          })
  );

  public static void register() {
    // 1. Lắng nghe khi một Pokémon được nạp từ file lưu trữ NBT của chunk (không phải mới sinh ra)
    CobblemonEvents.POKEMON_ENTITY_LOAD.subscribe(Priority.HIGHEST, event -> {
      if (event != null && event.getPokemonEntity() != null) {
        loadedFromDisk.add(event.getPokemonEntity().getUUID());
      }
      return Unit.INSTANCE;
    });

    // 2. Lắng nghe sự kiện spawn tự nhiên từ Spawning Engine của Cobblemon
    CobblemonEvents.POKEMON_ENTITY_SPAWN.subscribe(Priority.NORMAL, event -> {
      if (event != null && event.getEntity() != null) {
        if (event.getEntity().level() instanceof ServerLevel serverLevel) {
          processPokemon(event.getEntity(), serverLevel);
        }
      }
      return Unit.INSTANCE;
    });

    // 3. Lắng nghe sự kiện Entity nạp vào ServerLevel (bắt được cả lệnh /pokespawn, trứng spawn, summon...)
    ServerEntityEvents.ENTITY_LOAD.register((entity, serverLevel) -> {
      if (entity instanceof PokemonEntity pokemonEntity) {
        processPokemon(pokemonEntity, serverLevel);
      }
    });

    CobblemonNotificationTracker.LOGGER.info("Registered Pokemon spawn & load listeners successfully.");
  }

  private static void processPokemon(PokemonEntity entity, ServerLevel serverLevel) {
    if (entity == null || entity.isRemoved()) return;

    UUID uuid = entity.getUUID();

    // Nếu thực thể này vừa được nạp từ ổ cứng (chunk load) -> bỏ qua không thông báo
    if (loadedFromDisk.remove(uuid)) {
      return;
    }

    // Đảm bảo mỗi thực thể Pokémon chỉ kích hoạt thông báo 1 lần duy nhất
    if (!processedUuids.add(uuid)) {
      return;
    }

    Pokemon pokemon = entity.getPokemon();
    if (pokemon == null) return;

    // KIỂM TRA NGHIÊM NGẶT: CHỈ POKÉMON HOANG DÃ (WILD)
    // - pokemon.isWild(): Chưa thuộc Party / PC của ai
    // - getOwnerUUID() / getOwnerEntity(): Không có chủ sở hữu
    // - isBattleClone() / getBattleId(): Không phải Pokémon bản sao trong trận chiến
    if (!pokemon.isWild() || pokemon.getOwnerUUID() != null || pokemon.getOwnerEntity() != null) {
      return;
    }
    if (entity.isBattleClone() || entity.getBattleId() != null) {
      return;
    }

    Species species = pokemon.getSpecies();
    if (species == null) return;

    String speciesName = species.getName().toLowerCase();
    String displayName = species.getName().substring(0, 1).toUpperCase() + species.getName().substring(1);
    boolean isShiny = pokemon.getShiny();
    boolean isLegendary = species.getLabels().contains("legendary") || species.getLabels().contains("mythical");
    int level = pokemon.getLevel();
    BlockPos pos = entity.blockPosition();

    MinecraftServer server = serverLevel.getServer();
    if (server == null) return;

    List<DiscordWebhookService.NotifiedPlayer> notifiedPlayers = new ArrayList<>();
    long cooldownMs = ModConfig.get().getCooldownSeconds() * 1000L;

    // Duyệt qua danh sách người chơi online trên server
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      // Chỉ thông báo nếu cùng dimension
      if (player.level() != serverLevel) {
        continue;
      }

      PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
      double distance = player.position().distanceTo(entity.position());

      // Kiểm tra các tiêu chí nhận thông báo của người chơi
      if (!watchlist.shouldNotify(speciesName, isShiny, isLegendary, distance)) {
        continue;
      }

      // Cooldown cho cùng 1 loài đối với người chơi này (chống spam bầy đàn)
      if (!watchlist.checkAndSetCooldown(speciesName, cooldownMs)) {
        continue;
      }

      // Tính hướng và khoảng cách
      double dx = entity.getX() - player.getX();
      double dz = entity.getZ() - player.getZ();
      String direction = getDirection(dx, dz);
      int distRounded = (int) Math.round(distance);

      notifiedPlayers.add(new DiscordWebhookService.NotifiedPlayer(player, watchlist, distRounded, direction));

      // Chỉ gửi thông báo chat trong game nếu được bật trong cấu hình
      if (ModConfig.get().isSendToInGameChat()) {
        MutableComponent msg = Component.empty();
        msg.append(PREFIX.copy());

        if (isShiny) {
          msg.append(Component.literal("✨ SHINY ✨ ").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        }
        if (isLegendary) {
          msg.append(Component.literal("⭐ HUYỀN THOẠI ⭐ ").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
        }

        msg.append(Component.literal(displayName).withStyle(isShiny ? ChatFormatting.GOLD : ChatFormatting.YELLOW, ChatFormatting.BOLD));
        msg.append(Component.literal(" (Lv." + level + ")").withStyle(ChatFormatting.WHITE));
        msg.append(Component.literal(" đã xuất hiện! ").withStyle(ChatFormatting.GREEN));

        // Tọa độ click để copy
        String coordText = String.format("%d %d %d", pos.getX(), pos.getY(), pos.getZ());
        MutableComponent coordComp = Component.literal(String.format("[%s]", coordText))
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, coordText))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Nhấp để sao chép tọa độ: " + coordText)))
                );

        msg.append(coordComp);
        msg.append(Component.literal(String.format(" (cách %dm về hướng %s)", distRounded, direction)).withStyle(ChatFormatting.GRAY));

        player.sendSystemMessage(msg);

        // Âm thanh thông báo
        if (isShiny || isLegendary) {
          player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1.0f, 1.0f);
        } else {
          player.playNotifySound(SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8f, 1.2f);
        }
      }
    }

    // Gửi thông báo đến Discord Webhook nếu có người chơi cần nhận tin
    if (!notifiedPlayers.isEmpty() && ModConfig.get().isSendToDiscord()) {
      DiscordWebhookService.sendNotification(species, isShiny, isLegendary, level, pos, serverLevel, notifiedPlayers);
    }
  }

  /**
   * Xác định hướng la bàn từ người chơi đến mục tiêu (Bắc, Nam, Đông, Tây,...)
   */
  public static String getDirection(double dx, double dz) {
    double deg = (Math.toDegrees(Math.atan2(-dx, dz)) + 180.0) % 360.0;
    if (deg >= 337.5 || deg < 22.5) return "Bắc";
    if (deg >= 22.5 && deg < 67.5) return "Đông Bắc";
    if (deg >= 67.5 && deg < 112.5) return "Đông";
    if (deg >= 112.5 && deg < 157.5) return "Đông Nam";
    if (deg >= 157.5 && deg < 202.5) return "Nam";
    if (deg >= 202.5 && deg < 247.5) return "Tây Nam";
    if (deg >= 247.5 && deg < 292.5) return "Tây";
    return "Tây Bắc";
  }
}
