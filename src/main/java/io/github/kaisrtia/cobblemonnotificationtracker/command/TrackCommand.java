package io.github.kaisrtia.cobblemonnotificationtracker.command;

import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.pokemon.Species;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.kaisrtia.cobblemonnotificationtracker.config.ModConfig;
import io.github.kaisrtia.cobblemonnotificationtracker.model.PlayerWatchlist;
import io.github.kaisrtia.cobblemonnotificationtracker.storage.WatchlistManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Xử lý lệnh /track cho người chơi cấu hình danh sách theo dõi Pokémon cá nhân.
 * Các lệnh quản trị (webhook, reload) được phân quyền nghiêm ngặt chỉ dành cho Admin (OP level >= 2).
 */
public class TrackCommand {

  private static final MutableComponent PREFIX = Component.literal("[")
          .withStyle(ChatFormatting.DARK_GRAY)
          .append(Component.literal("CobblemonTracker").withStyle(ChatFormatting.AQUA))
          .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));

  public static void register() {
    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
  }

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
    dispatcher.register(
            Commands.literal("track")
                    // Lệnh gốc người chơi thường và Admin đều dùng được
                    .executes(TrackCommand::showHelp)
                    .then(Commands.literal("help").executes(TrackCommand::showHelp))
                    .then(Commands.literal("list").executes(TrackCommand::listTracked))
                    .then(Commands.literal("clear").executes(TrackCommand::clearTracked))
                    .then(Commands.literal("toggle").executes(TrackCommand::toggleEnabled))
                    .then(Commands.literal("add")
                            .then(Commands.argument("pokemon", StringArgumentType.word())
                                    .suggests(TrackCommand::suggestAllSpecies)
                                    .executes(ctx -> addPokemon(ctx, StringArgumentType.getString(ctx, "pokemon")))
                            )
                    )
                    .then(Commands.literal("remove")
                            .then(Commands.argument("pokemon", StringArgumentType.word())
                                    .suggests(TrackCommand::suggestPlayerTrackedSpecies)
                                    .executes(ctx -> removePokemon(ctx, StringArgumentType.getString(ctx, "pokemon")))
                            )
                    )
                    .then(Commands.literal("radius")
                            .executes(TrackCommand::showRadius)
                            .then(Commands.argument("blocks", IntegerArgumentType.integer(-1, 50000))
                                    .executes(ctx -> setRadius(ctx, IntegerArgumentType.getInteger(ctx, "blocks")))
                            )
                    )
                    .then(Commands.literal("shiny")
                            .executes(TrackCommand::toggleShiny)
                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                    .executes(ctx -> setShiny(ctx, BoolArgumentType.getBool(ctx, "enabled")))
                            )
                    )
                    .then(Commands.literal("legendary")
                            .executes(TrackCommand::toggleLegendary)
                            .then(Commands.argument("enabled", BoolArgumentType.bool())
                                    .executes(ctx -> setLegendary(ctx, BoolArgumentType.getBool(ctx, "enabled")))
                            )
                    )
                    .then(Commands.literal("discord")
                            .executes(TrackCommand::showDiscord)
                            .then(Commands.literal("clear").executes(TrackCommand::clearDiscord))
                            .then(Commands.argument("userId", StringArgumentType.word())
                                    .executes(ctx -> setDiscord(ctx, StringArgumentType.getString(ctx, "userId")))
                            )
                    )
                    // CÁC LỆNH QUẢN TRỊ - CHỈ ADMIN / OP LEVEL >= 2 MỚI ĐƯỢC PHÉP DÙNG
                    .then(Commands.literal("webhook")
                            .requires(src -> src.hasPermission(2))
                            .then(Commands.argument("url", StringArgumentType.greedyString())
                                    .executes(ctx -> setWebhook(ctx, StringArgumentType.getString(ctx, "url")))
                            )
                    )
                    .then(Commands.literal("reload")
                            .requires(src -> src.hasPermission(2))
                            .executes(TrackCommand::reloadConfig)
                    )
    );
  }

  private static int addPokemon(CommandContext<CommandSourceStack> ctx, String pokemonName) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    String speciesQuery = pokemonName.trim().toLowerCase();

    // Kiểm tra xem tên Pokémon có tồn tại trong Cobblemon không
    Species species = PokemonSpecies.getByName(speciesQuery);
    if (species == null) {
      ctx.getSource().sendFailure(PREFIX.copy().append(
              Component.literal("Pokémon '" + pokemonName + "' không tồn tại trong Cobblemon!").withStyle(ChatFormatting.RED)
      ));
      return 0;
    }

    String canonicalName = species.getName();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    boolean added = watchlist.addPokemon(canonicalName);

    if (added) {
      WatchlistManager.getInstance().saveAsync(player.getUUID());
      ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
              Component.literal("Đã thêm ").withStyle(ChatFormatting.GREEN)
      ).append(
              Component.literal(canonicalName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
      ).append(
              Component.literal(" vào danh sách theo dõi của bạn.").withStyle(ChatFormatting.GREEN)
      ), false);
      return 1;
    } else {
      ctx.getSource().sendFailure(PREFIX.copy().append(
              Component.literal(canonicalName + " đã có trong danh sách theo dõi của bạn từ trước!").withStyle(ChatFormatting.YELLOW)
      ));
      return 0;
    }
  }

  private static int removePokemon(CommandContext<CommandSourceStack> ctx, String pokemonName) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());

    boolean removed = watchlist.removePokemon(pokemonName);
    if (removed) {
      WatchlistManager.getInstance().saveAsync(player.getUUID());
      ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
              Component.literal("Đã xóa ").withStyle(ChatFormatting.GREEN)
      ).append(
              Component.literal(pokemonName).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
      ).append(
              Component.literal(" khỏi danh sách theo dõi của bạn.").withStyle(ChatFormatting.GREEN)
      ), false);
      return 1;
    } else {
      ctx.getSource().sendFailure(PREFIX.copy().append(
              Component.literal("Pokémon '" + pokemonName + "' không nằm trong danh sách theo dõi của bạn!").withStyle(ChatFormatting.RED)
      ));
      return 0;
    }
  }

  private static int listTracked(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());

    MutableComponent msg = Component.empty();
    msg.append(Component.literal("========== ").withStyle(ChatFormatting.GOLD));
    msg.append(Component.literal("Cobblemon Tracker").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    msg.append(Component.literal(" ==========\n").withStyle(ChatFormatting.GOLD));

    // Trạng thái tổng quan
    msg.append(Component.literal("▶ Thông báo: ").withStyle(ChatFormatting.GRAY));
    msg.append(watchlist.isEnabled()
            ? Component.literal("BẬT ").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
            : Component.literal("TẮT ").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));

    msg.append(Component.literal("| Bán kính: ").withStyle(ChatFormatting.GRAY));
    msg.append(watchlist.getMaxRadius() <= 0
            ? Component.literal("Vô hạn ").withStyle(ChatFormatting.LIGHT_PURPLE)
            : Component.literal(watchlist.getMaxRadius() + "m ").withStyle(ChatFormatting.AQUA));

    String discordId = watchlist.getDiscordUserId();
    msg.append(Component.literal("\n▶ Discord: ").withStyle(ChatFormatting.GRAY));
    msg.append(discordId.isBlank()
            ? Component.literal("Chưa liên kết (/track discord <id>) ").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)
            : Component.literal("<@" + discordId + "> (" + discordId + ") ").withStyle(ChatFormatting.AQUA));

    // Chỉ Admin mới thấy trạng thái Webhook để bảo mật
    if (ctx.getSource().hasPermission(2)) {
      msg.append(Component.literal("| Webhook: ").withStyle(ChatFormatting.GRAY));
      msg.append(ModConfig.get().getWebhookUrl().isBlank()
              ? Component.literal("Chưa đặt ").withStyle(ChatFormatting.RED)
              : Component.literal("ĐÃ KẾT NỐI ").withStyle(ChatFormatting.GREEN));
    }

    msg.append(Component.literal("\n▶ Báo Shiny: ").withStyle(ChatFormatting.GRAY));
    msg.append(watchlist.isNotifyShiny()
            ? Component.literal("BẬT ").withStyle(ChatFormatting.GREEN)
            : Component.literal("TẮT ").withStyle(ChatFormatting.RED));

    msg.append(Component.literal("| Báo Huyền Thoại: ").withStyle(ChatFormatting.GRAY));
    msg.append(watchlist.isNotifyLegendary()
            ? Component.literal("BẬT\n").withStyle(ChatFormatting.GREEN)
            : Component.literal("TẮT\n").withStyle(ChatFormatting.RED));

    // Danh sách Pokémon
    Set<String> tracked = watchlist.getTrackedPokemon();
    msg.append(Component.literal("▶ Đang theo dõi (").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(String.valueOf(tracked.size())).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD))
            .append(Component.literal(" loài): ").withStyle(ChatFormatting.GRAY));

    if (tracked.isEmpty()) {
      msg.append(Component.literal("(Chưa có Pokémon nào, dùng /track add <tên>)").withStyle(ChatFormatting.ITALIC, ChatFormatting.DARK_GRAY));
    } else {
      boolean first = true;
      for (String p : tracked) {
        if (!first) {
          msg.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
        }
        first = false;
        String pName = p.substring(0, 1).toUpperCase() + p.substring(1);
        MutableComponent pComp = Component.literal(pName)
                .withStyle(style -> style
                        .withColor(ChatFormatting.YELLOW)
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Nhấp để xóa '" + pName + "' khỏi danh sách theo dõi")))
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/track remove " + p))
                );
        msg.append(pComp);
      }
    }

    ctx.getSource().sendSuccess(() -> msg, false);
    return 1;
  }

  private static int clearTracked(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    int count = watchlist.getTrackedPokemon().size();
    watchlist.clear();
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã xóa sạch " + count + " Pokémon khỏi danh sách theo dõi của bạn!").withStyle(ChatFormatting.GREEN)
    ), false);
    return count;
  }

  private static int toggleEnabled(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    boolean newState = !watchlist.isEnabled();
    watchlist.setEnabled(newState);
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã " + (newState ? "BẬT" : "TẮT") + " thông báo Pokémon của bạn.").withStyle(
                    newState ? ChatFormatting.GREEN : ChatFormatting.RED, ChatFormatting.BOLD
            )
    ), false);
    return 1;
  }

  private static int showRadius(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    int radius = watchlist.getMaxRadius();

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Bán kính phát hiện hiện tại của bạn: ").withStyle(ChatFormatting.GRAY)
    ).append(
            radius <= 0 ? Component.literal("Vô hạn").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                    : Component.literal(radius + " blocks").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
    ), false);
    return 1;
  }

  private static int setRadius(CommandContext<CommandSourceStack> ctx, int blocks) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    watchlist.setMaxRadius(blocks);
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã cập nhật bán kính theo dõi thành: ").withStyle(ChatFormatting.GREEN)
    ).append(
            blocks <= 0 ? Component.literal("Vô hạn").withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD)
                    : Component.literal(blocks + " blocks").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
    ), false);
    return 1;
  }

  private static int toggleShiny(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    boolean newState = !watchlist.isNotifyShiny();
    return setShiny(ctx, newState);
  }

  private static int setShiny(CommandContext<CommandSourceStack> ctx, boolean enabled) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    watchlist.setNotifyShiny(enabled);
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Thông báo Pokémon Shiny: ").withStyle(ChatFormatting.GRAY)
    ).append(
            enabled ? Component.literal("BẬT").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                    : Component.literal("TẮT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
    ), false);
    return 1;
  }

  private static int toggleLegendary(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    boolean newState = !watchlist.isNotifyLegendary();
    return setLegendary(ctx, newState);
  }

  private static int setLegendary(CommandContext<CommandSourceStack> ctx, boolean enabled) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    watchlist.setNotifyLegendary(enabled);
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Thông báo Pokémon Huyền Thoại: ").withStyle(ChatFormatting.GRAY)
    ).append(
            enabled ? Component.literal("BẬT").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                    : Component.literal("TẮT").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
    ), false);
    return 1;
  }

  private static int showDiscord(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    String id = watchlist.getDiscordUserId();

    if (id.isBlank()) {
      ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
              Component.literal("Bạn chưa liên kết Discord ID! Hãy dùng: ").withStyle(ChatFormatting.GRAY)
      ).append(
              Component.literal("/track discord <User_ID>").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
      ).append(
              Component.literal(" để nhận tag mention trên Discord.").withStyle(ChatFormatting.GRAY)
      ), false);
    } else {
      ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
              Component.literal("Discord ID hiện tại của bạn: ").withStyle(ChatFormatting.GRAY)
      ).append(
              Component.literal("<@" + id + "> (" + id + ")").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
      ), false);
    }
    return 1;
  }

  private static int clearDiscord(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    watchlist.setDiscordUserId("");
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã hủy liên kết Discord ID của bạn.").withStyle(ChatFormatting.GREEN)
    ), false);
    return 1;
  }

  private static int setDiscord(CommandContext<CommandSourceStack> ctx, String userId) throws CommandSyntaxException {
    ServerPlayer player = ctx.getSource().getPlayerOrException();
    String cleanId = userId.replaceAll("[^0-9]", "").trim();

    if (cleanId.isEmpty()) {
      ctx.getSource().sendFailure(PREFIX.copy().append(
              Component.literal("Discord User ID không hợp lệ! Vui lòng nhập dãy số ID Discord của bạn.").withStyle(ChatFormatting.RED)
      ));
      return 0;
    }

    PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
    watchlist.setDiscordUserId(cleanId);
    WatchlistManager.getInstance().saveAsync(player.getUUID());

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã liên kết Discord ID: ").withStyle(ChatFormatting.GREEN)
    ).append(
            Component.literal("<@" + cleanId + "> (" + cleanId + ")").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD)
    ).append(
            Component.literal(". Bạn sẽ được tag mention trên Discord khi Pokémon xuất hiện!").withStyle(ChatFormatting.GREEN)
    ), false);
    return 1;
  }

  // LỆNH DÀNH RIÊNG CHO ADMIN (OP LEVEL >= 2)
  private static int setWebhook(CommandContext<CommandSourceStack> ctx, String url) {
    ModConfig.get().setWebhookUrl(url);
    ModConfig.save();

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã cập nhật Discord Webhook URL thành công!").withStyle(ChatFormatting.GREEN)
    ), true);
    return 1;
  }

  private static int reloadConfig(CommandContext<CommandSourceStack> ctx) {
    ModConfig.load();

    ctx.getSource().sendSuccess(() -> PREFIX.copy().append(
            Component.literal("Đã nạp lại cấu hình mod thành công!").withStyle(ChatFormatting.GREEN)
    ), true);
    return 1;
  }

  private static int showHelp(CommandContext<CommandSourceStack> ctx) {
    boolean isAdmin = ctx.getSource().hasPermission(2);

    MutableComponent help = Component.empty();
    help.append(Component.literal("========== ").withStyle(ChatFormatting.GOLD));
    help.append(Component.literal("Cobblemon Tracker Commands").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
    help.append(Component.literal(" ==========\n").withStyle(ChatFormatting.GOLD));

    // Lệnh cho mọi người chơi
    appendHelpLine(help, "/track add <tên>", "Thêm Pokémon vào danh sách theo dõi");
    appendHelpLine(help, "/track remove <tên>", "Xóa Pokémon khỏi danh sách theo dõi");
    appendHelpLine(help, "/track list", "Xem danh sách và cài đặt hiện tại");
    appendHelpLine(help, "/track clear", "Xóa sạch danh sách theo dõi");
    appendHelpLine(help, "/track radius <số_m>", "Đặt bán kính quét (-1 là không giới hạn)");
    appendHelpLine(help, "/track shiny [true/false]", "Bật/tắt thông báo khi có Shiny xuất hiện");
    appendHelpLine(help, "/track legendary [true/false]", "Bật/tắt thông báo khi có Huyền Thoại xuất hiện");
    appendHelpLine(help, "/track toggle", "Bật hoặc tạm tắt toàn bộ thông báo");
    appendHelpLine(help, "/track discord <id>", "Liên kết Discord User ID để được tag mention");
    appendHelpLine(help, "/track discord clear", "Hủy liên kết Discord User ID");

    // Chỉ hiển thị các lệnh Admin nếu người gõ lệnh có quyền OP >= 2
    if (isAdmin) {
      help.append(Component.literal("\n--- Lệnh Quản trị (Admin Server) ---\n").withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
      appendHelpLine(help, "/track webhook <url>", "Cài đặt URL Discord Webhook");
      appendHelpLine(help, "/track reload", "Nạp lại file cấu hình mod");
    }

    ctx.getSource().sendSuccess(() -> help, false);
    return 1;
  }

  private static void appendHelpLine(MutableComponent comp, String cmd, String desc) {
    comp.append(Component.literal("▶ ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(cmd).withStyle(ChatFormatting.YELLOW))
            .append(Component.literal(" : ").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(desc).withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\n"));
  }

  /**
   * Gợi ý tab-complete tất cả các loài Pokémon có trong Cobblemon cho lệnh /track add
   */
  private static CompletableFuture<Suggestions> suggestAllSpecies(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    Collection<Species> speciesList = PokemonSpecies.getImplemented();
    return SharedSuggestionProvider.suggest(
            speciesList.stream().map(s -> s.getName().toLowerCase()),
            builder
    );
  }

  /**
   * Gợi ý tab-complete chỉ những Pokémon người chơi đó ĐANG THEO DÕI cho lệnh /track remove
   */
  private static CompletableFuture<Suggestions> suggestPlayerTrackedSpecies(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
    try {
      ServerPlayer player = ctx.getSource().getPlayerOrException();
      PlayerWatchlist watchlist = WatchlistManager.getInstance().getOrCreate(player.getUUID());
      return SharedSuggestionProvider.suggest(watchlist.getTrackedPokemon(), builder);
    } catch (CommandSyntaxException e) {
      return Suggestions.empty();
    }
  }
}
