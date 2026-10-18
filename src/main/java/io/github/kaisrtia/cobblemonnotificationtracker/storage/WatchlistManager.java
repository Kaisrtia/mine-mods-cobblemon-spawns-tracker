package io.github.kaisrtia.cobblemonnotificationtracker.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.kaisrtia.cobblemonnotificationtracker.CobblemonNotificationTracker;
import io.github.kaisrtia.cobblemonnotificationtracker.model.PlayerWatchlist;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Quản lý việc nạp, lưu trữ và lưu đệm (cache) danh sách theo dõi của người chơi ra file JSON.
 */
public class WatchlistManager {
    private static final WatchlistManager INSTANCE = new WatchlistManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path storageDirectory;
    private final Map<UUID, PlayerWatchlist> cache = new ConcurrentHashMap<>();
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "CobblemonNotificationTracker-IO");
        thread.setDaemon(true);
        return thread;
    });

    private WatchlistManager() {
        this.storageDirectory = FabricLoader.getInstance().getConfigDir()
                .resolve("cobblemonnotificationtracker")
                .resolve("players");
    }

    public static WatchlistManager getInstance() {
        return INSTANCE;
    }

    /**
     * Khởi tạo thư mục và đăng ký các sự kiện vòng đời máy chủ (join/leave/stop).
     */
    public void initialize() {
        try {
            Files.createDirectories(storageDirectory);
        } catch (IOException e) {
            CobblemonNotificationTracker.LOGGER.error("Failed to create storage directory: {}", storageDirectory, e);
        }

        // Khi người chơi tham gia máy chủ: nạp danh sách vào cache
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            load(uuid);
        });

        // Khi người chơi rời máy chủ: lưu ra ổ cứng và giải phóng khỏi bộ nhớ đệm
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID uuid = handler.getPlayer().getUUID();
            saveAsync(uuid).thenRun(() -> cache.remove(uuid));
        });

        // Khi máy chủ dừng: lưu đồng bộ toàn bộ người chơi đang trong cache
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CobblemonNotificationTracker.LOGGER.info("Saving all player watchlists...");
            saveAllSync();
            ioExecutor.shutdown();
        });
    }

    /**
     * Lấy cấu hình theo dõi của người chơi từ cache, hoặc nạp/tạo mới nếu chưa có.
     */
    public PlayerWatchlist getOrCreate(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadFromDiskOrCreate);
    }

    /**
     * Nạp dữ liệu của người chơi vào bộ nhớ cache.
     */
    public PlayerWatchlist load(UUID uuid) {
        PlayerWatchlist watchlist = loadFromDiskOrCreate(uuid);
        cache.put(uuid, watchlist);
        return watchlist;
    }

    private PlayerWatchlist loadFromDiskOrCreate(UUID uuid) {
        Path playerFile = getPlayerFile(uuid);
        if (Files.exists(playerFile)) {
            try (BufferedReader reader = Files.newBufferedReader(playerFile)) {
                PlayerWatchlist watchlist = GSON.fromJson(reader, PlayerWatchlist.class);
                if (watchlist != null) {
                    watchlist.setPlayerUuid(uuid);
                    return watchlist;
                }
            } catch (Exception e) {
                CobblemonNotificationTracker.LOGGER.error("Failed to read watchlist for player {}", uuid, e);
            }
        }

        // Tạo mới nếu chưa có file hoặc file bị lỗi
        PlayerWatchlist newWatchlist = new PlayerWatchlist(uuid);
        saveAsync(newWatchlist);
        return newWatchlist;
    }

    /**
     * Lưu bất đồng bộ để tránh làm chậm tick của server.
     */
    public CompletableFuture<Void> saveAsync(UUID uuid) {
        PlayerWatchlist watchlist = cache.get(uuid);
        if (watchlist != null) {
            return saveAsync(watchlist);
        }
        return CompletableFuture.completedFuture(null);
    }

    public CompletableFuture<Void> saveAsync(PlayerWatchlist watchlist) {
        return CompletableFuture.runAsync(() -> saveSync(watchlist), ioExecutor);
    }

    /**
     * Lưu đồng bộ ra file JSON.
     */
    public void saveSync(PlayerWatchlist watchlist) {
        if (watchlist == null || watchlist.getPlayerUuid() == null) {
            return;
        }

        Path playerFile = getPlayerFile(watchlist.getPlayerUuid());
        try {
            Files.createDirectories(storageDirectory);
            try (BufferedWriter writer = Files.newBufferedWriter(playerFile)) {
                GSON.toJson(watchlist, writer);
            }
        } catch (IOException e) {
            CobblemonNotificationTracker.LOGGER.error("Failed to save watchlist for player {}", watchlist.getPlayerUuid(), e);
        }
    }

    /**
     * Lưu toàn bộ các bản ghi đang có trong cache khi server tắt.
     */
    public void saveAllSync() {
        for (PlayerWatchlist watchlist : cache.values()) {
            saveSync(watchlist);
        }
    }

    private Path getPlayerFile(UUID uuid) {
        return storageDirectory.resolve(uuid.toString() + ".json");
    }
}
