package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.moviesad.TorrentState;

import com.frostwire.jlibtorrent.AddTorrentParams;
import com.frostwire.jlibtorrent.AlertListener;
import com.frostwire.jlibtorrent.AnnounceEntry;
import com.frostwire.jlibtorrent.InfoHash;
import com.frostwire.jlibtorrent.SessionHandle;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.SessionParams;
import com.frostwire.jlibtorrent.SessionStats;
import com.frostwire.jlibtorrent.SettingsPack;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.PeerInfo;
import com.frostwire.jlibtorrent.TcpEndpoint;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentFlags;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert;
import com.frostwire.jlibtorrent.alerts.StateChangedAlert;
import com.frostwire.jlibtorrent.alerts.StateUpdateAlert;
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.swig.settings_pack;
import com.frostwire.jlibtorrent.swig.settings_pack.bandwidth_mixed_algo_t;
import com.frostwire.jlibtorrent.swig.settings_pack.choking_algorithm_t;
import com.frostwire.jlibtorrent.swig.settings_pack.seed_choking_algorithm_t;
import com.frostwire.jlibtorrent.swig.settings_pack.suggest_mode_t;
import com.frostwire.jlibtorrent.swig.error_code;
import com.frostwire.jlibtorrent.swig.torrent_flags_t;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;

/**
 * Minimal torrent downloader built on top of jlibtorrent.
 * <p>
 * The original class had grown to thousands of lines and mixed many
 * responsibilities.  This implementation focuses on providing a reliable
 * torrent download manager that integrates cleanly with the existing UI
 * components while exposing the same public API surface that the
 * application relies on.
 */
public class TorrentDownloader {

    /** Listener for torrent related events. */
    public interface TorrentNotificationListener {
        void onTorrentComplete(TorrentState torrentState);

        void onTorrentError(TorrentState torrentState, String errorMessage);

        void onDiskSpaceLow(long availableSpace, long requiredSpace);

        void onDebugMessage(String message, Level level);

        void onTorrentStatusUpdate(TorrentState torrentState, TorrentStats stats);
    }

    private static final Logger LOGGER = Logger.getLogger(TorrentDownloader.class.getName());

    /** Minimum free disk space (in bytes) required to start a download. */
    private static final long MIN_DISK_SPACE = 500L * 1024L * 1024L; // 500 MB

    private static final long STATUS_UPDATE_PERIOD_SECONDS = 1L;
    private static final Duration SESSION_AUTOTUNE_INTERVAL = Duration.ofSeconds(15);
    private static final Duration SESSION_THROUGHPUT_WINDOW = Duration.ofSeconds(30);
    private static final Duration TRACKER_REANNOUNCE_INTERVAL = Duration.ofMinutes(2);
    private static final Duration DHT_REANNOUNCE_INTERVAL = Duration.ofSeconds(45);
    private static final Duration DHT_REBOOT_INTERVAL = Duration.ofMinutes(5);
    private static final Duration BANDWIDTH_REBALANCE_INTERVAL = Duration.ofSeconds(10);
    private static final Duration TORRENT_OPTIMIZATION_INTERVAL = Duration.ofSeconds(5);
    private static final Duration TRACKER_REFRESH_INTERVAL = Duration.ofMinutes(3);
    private static final Duration DHT_PEER_FETCH_INTERVAL = Duration.ofSeconds(40);
    private static final long SLOW_PEER_SAMPLE_GRACE_MS = Duration.ofSeconds(30).toMillis();
    private static final long SLOW_PEER_BACKOFF_MS = Duration.ofMinutes(5).toMillis();
    private static final int MINIMUM_ACTIVE_PEERS = 6;
    private static final int MINIMUM_ACTIVE_SEEDS = 1;
    private static final long STALLED_DOWNLOAD_RATE_BYTES = 64L * 1024L;
    private static final long STALLED_UPLOAD_RATE_BYTES = 16L * 1024L;
    private static final int STALLED_CHECKS_THRESHOLD = 3;
    private static final int LOW_DHT_NODE_THRESHOLD = 12;
    private static final int MAX_DYNAMIC_CONNECTIONS = 2500;
    private static final int MIN_DYNAMIC_REQUEST_QUEUE = 512;
    private static final int MAX_DYNAMIC_REQUEST_QUEUE = 4000;
    private static final int MIN_AUTO_DOWNLOAD_LIMIT = 128 * 1024;
    private static final int MIN_AUTO_UPLOAD_LIMIT = 64 * 1024;
    private static final int MAX_EXTRA_DHT_CONNECTIONS = 12;
    private static final int MIN_PEER_SAMPLE_SPEED_BYTES = 8 * 1024;
    private static final int MIN_PEER_SAMPLE_COUNT = 2;
    private static final double PEAK_RATE_DECAY = 0.85;
    private static final int MAX_LOG_ENTRIES_PER_TORRENT = 250;
    private static final String[] DEFAULT_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://open.stealth.si:80/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://tracker.ccp.ovh:6969/announce",
            "udp://tracker1.bt.moack.co.kr:80/announce",
            "udp://tracker.qu.ax:6969/announce",
            "udp://opentracker.i2p.rocks:6969/announce",
            "https://tracker.tamersunion.org:443/announce",
            "https://tracker.nitrix.me:443/announce"
    };

    private static final String[] DEFAULT_DHT_NODES = {
            "router.bittorrent.com:6881",
            "router.utorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "dht.aelitis.com:6881",
            "router.bitcomet.com:6881",
            "dht.libtorrent.org:25401"
    };

    private final SessionManager sessionManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workerExecutor;
    private final Deque<PendingTorrent> pendingQueue;
    private final Map<TorrentState, PendingTorrent> pendingByState;
    private final Map<TorrentState, ManagedTorrent> managedByState;
    private final ConcurrentHashMap<String, ManagedTorrent> managedByHash;
    private final ConcurrentHashMap<TorrentState, TorrentLogBook> logsByState;
    private final List<TorrentNotificationListener> listeners;
    private final List<Path> temporaryTorrentFiles;
    private final Path temporaryDirectory;

    private final Object lock = new Object();

    private volatile boolean running;

    private int maxConcurrentDownloads;
    private boolean extractArchives;
    private int downloadSpeedLimit;
    private int uploadSpeedLimit;
    private boolean autoStartDownloads;
    private volatile long lastSessionAutotuneNanos;
    private volatile int lastAutoConnectionsLimit;
    private volatile int lastAutoConnectionSpeed;
    private volatile int lastAutoRequestQueue;
    private final AtomicInteger consecutiveLowDhtSamples;
    private volatile long lastDhtBootstrapTimeMs;
    private volatile long lastBandwidthRebalanceNanos;
    private volatile long lastObservedDownloadRate;
    private final AtomicLong peakObservedDownloadRate;
    private final ThroughputAverager sessionDownloadThroughput;
    private final ThroughputAverager sessionUploadThroughput;


    /**
     * Primary constructor used in the application. Additional parameters for
     * verbose or console logging are kept for backwards compatibility, but the
     * current implementation delegates logging to {@link java.util.logging}.
     */
    public TorrentDownloader(int maxConcurrentDownloads,
                              boolean extractArchives,
                              int downloadSpeedLimit,
                              int uploadSpeedLimit,
                              boolean verboseLogging,
                              boolean consoleLogging) {
        this.maxConcurrentDownloads = Math.max(1, maxConcurrentDownloads);
        this.extractArchives = extractArchives;
        this.downloadSpeedLimit = Math.max(0, downloadSpeedLimit);
        this.uploadSpeedLimit = Math.max(0, uploadSpeedLimit);
        this.autoStartDownloads = true;

        configureLogging(verboseLogging, consoleLogging);

        this.sessionManager = new SessionManager();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("torrent-status"));
        this.workerExecutor = Executors.newCachedThreadPool(daemonThreadFactory("torrent-worker"));
        this.pendingQueue = new ArrayDeque<>();
        this.pendingByState = new java.util.HashMap<>();
        this.managedByState = new java.util.HashMap<>();
        this.managedByHash = new ConcurrentHashMap<>();
        this.logsByState = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.temporaryTorrentFiles = new CopyOnWriteArrayList<>();
        this.temporaryDirectory = createTemporaryDirectory();
        this.lastSessionAutotuneNanos = 0L;
        this.lastAutoConnectionsLimit = -1;
        this.lastAutoConnectionSpeed = -1;
        this.lastAutoRequestQueue = -1;
        this.consecutiveLowDhtSamples = new AtomicInteger(0);
        this.lastDhtBootstrapTimeMs = 0L;
        this.lastBandwidthRebalanceNanos = 0L;
        this.lastObservedDownloadRate = 0L;
        this.peakObservedDownloadRate = new AtomicLong(0L);
        this.sessionDownloadThroughput = new ThroughputAverager(SESSION_THROUGHPUT_WINDOW.toMillis());
        this.sessionUploadThroughput = new ThroughputAverager(SESSION_THROUGHPUT_WINDOW.toMillis());

        startSession();
        this.running = true;
        scheduler.scheduleAtFixedRate(this::refreshStatuses,
                STATUS_UPDATE_PERIOD_SECONDS,
                STATUS_UPDATE_PERIOD_SECONDS,
                TimeUnit.SECONDS);

        log(Level.INFO, "TorrentDownloader inicializado con soporte para "
                + this.maxConcurrentDownloads + " descargas simultáneas.");
    }

    private void configureLogging(boolean verboseLogging, boolean consoleLogging) {
        Level targetLevel = verboseLogging ? Level.FINE : Level.INFO;
        LOGGER.setLevel(targetLevel);
        if (!consoleLogging) {
            return;
        }
        boolean hasConsoleHandler = false;
        for (Handler handler : LOGGER.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                handler.setLevel(targetLevel);
                hasConsoleHandler = true;
                break;
            }
        }
        if (!hasConsoleHandler) {
            ConsoleHandler handler = new ConsoleHandler();
            handler.setLevel(targetLevel);
            LOGGER.addHandler(handler);
        }
    }

    public TorrentDownloader(int maxConcurrentDownloads,
                              boolean extractArchives,
                              int downloadSpeedLimit,
                              int uploadSpeedLimit) {
        this(maxConcurrentDownloads, extractArchives, downloadSpeedLimit, uploadSpeedLimit, false, false);
    }

    private void startSession() {
        SettingsPack settings = buildDefaultSettings();
        sessionManager.addListener(alertListener);
        sessionManager.start(new SessionParams(settings));
        sessionManager.resume();
        try {
            sessionManager.startDht();
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo iniciar la DHT: " + t.getMessage());
        }
        applyRateLimits();
        requestTorrentStatusUpdates();
    }

    private SettingsPack buildDefaultSettings() {
        SettingsPack settings = new SettingsPack();
        settings.listenInterfaces("0.0.0.0:6881,[::]:6881");
        settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true);
        populatePerformanceSettings(settings);
        settings.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), String.join(",", DEFAULT_DHT_NODES));
        applyAggressiveNetworking(settings);
        return settings;
    }

    private void applyAggressiveNetworking(SettingsPack settings) {
        settings.setBoolean(settings_pack.bool_types.rate_limit_ip_overhead.swigValue(), false);
        settings.setBoolean(settings_pack.bool_types.report_true_downloaded.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.report_redundant_bytes.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false);
        settings.setInteger(settings_pack.int_types.send_buffer_low_watermark.swigValue(), 1024 * 1024);
        settings.setInteger(settings_pack.int_types.send_buffer_watermark.swigValue(), 8 * 1024 * 1024);
        settings.setInteger(settings_pack.int_types.send_buffer_watermark_factor.swigValue(), 200);
        settings.setInteger(settings_pack.int_types.max_queued_disk_bytes.swigValue(), 32 * 1024 * 1024);
        settings.setInteger(settings_pack.int_types.max_peer_recv_buffer_size.swigValue(), 4 * 1024 * 1024);
        settings.setInteger(settings_pack.int_types.recv_socket_buffer_size.swigValue(), 2 * 1024 * 1024);
        settings.setInteger(settings_pack.int_types.send_socket_buffer_size.swigValue(), 2 * 1024 * 1024);
        settings.setInteger(settings_pack.int_types.handshake_timeout.swigValue(), 10);
        settings.setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 180);
        settings.setInteger(settings_pack.int_types.unchoke_slots_limit.swigValue(), 0);
    }

    private void populatePerformanceSettings(SettingsPack settings) {
        if (settings == null) {
            return;
        }

        int active = Math.max(1, maxConcurrentDownloads);
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), active);
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), active);
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), Math.max(active * 3, 16));
        settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), computeConnectionsLimit());
        settings.setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 5000);
        settings.setInteger(settings_pack.int_types.max_paused_peerlist_size.swigValue(), 2000);
        settings.setInteger(settings_pack.int_types.connection_speed.swigValue(), computeConnectionSpeed());
        settings.setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500);
        settings.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 15);

        settings.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.prefer_udp_trackers.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.smooth_connects.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.send_redundant_have.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.auto_manage_prefer_seeds.swigValue(), true);

        settings.setInteger(settings_pack.int_types.choking_algorithm.swigValue(),
                choking_algorithm_t.rate_based_choker.swigValue());
        settings.setInteger(settings_pack.int_types.seed_choking_algorithm.swigValue(),
                seed_choking_algorithm_t.fastest_upload.swigValue());
        settings.setInteger(settings_pack.int_types.suggest_mode.swigValue(),
                suggest_mode_t.suggest_read_cache.swigValue());
        settings.setInteger(settings_pack.int_types.mixed_mode_algorithm.swigValue(),
                bandwidth_mixed_algo_t.peer_proportional.swigValue());
        settings.setInteger(settings_pack.int_types.num_optimistic_unchoke_slots.swigValue(), Math.max(2, active));
    }

    private int computeConnectionsLimit() {
        int active = Math.max(1, maxConcurrentDownloads);
        int base = active * 80;
        if (downloadSpeedLimit > 0) {
            base = Math.max(base, (downloadSpeedLimit / 64) * 40);
        } else {
            base = Math.max(base, 600);
        }
        return clamp(base, 150, 2000);
    }

    private int computeConnectionSpeed() {
        int active = Math.max(1, maxConcurrentDownloads);
        int base = active * 30;
        if (downloadSpeedLimit > 0) {
            base = Math.max(base, Math.min(400, downloadSpeedLimit / 16));
        } else {
            base = Math.max(base, 80);
        }
        return clamp(base, 30, 400);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void applyRateLimits() {
        if (downloadSpeedLimit > 0) {
            sessionManager.downloadRateLimit(downloadSpeedLimit * 1024);
        } else {
            sessionManager.downloadRateLimit(0);
        }

        if (uploadSpeedLimit > 0) {
            sessionManager.uploadRateLimit(uploadSpeedLimit * 1024);
        } else {
            sessionManager.uploadRateLimit(0);
        }
    }

    private void applySessionSettings() {
        SettingsPack pack = new SettingsPack();
        populatePerformanceSettings(pack);
        try {
            sessionManager.applySettings(pack);
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo aplicar la configuración dinámica: " + t.getMessage());
        }
        applyRateLimits();
    }

    /** Update downloader configuration at runtime. */
    public void updateConfig(int maxConcurrentDownloads,
                              boolean extractArchives,
                              int downloadSpeedLimit,
                              int uploadSpeedLimit,
                              boolean autoStartDownloads) {
        synchronized (lock) {
            this.maxConcurrentDownloads = Math.max(1, maxConcurrentDownloads);
            this.extractArchives = extractArchives;
            this.downloadSpeedLimit = Math.max(0, downloadSpeedLimit);
            this.uploadSpeedLimit = Math.max(0, uploadSpeedLimit);
            this.autoStartDownloads = autoStartDownloads;
        }
        applySessionSettings();
        lastBandwidthRebalanceNanos = 0L;
        if (autoStartDownloads) {
            startNextIfPossible();
        }
        log(Level.INFO, "Configuración del TorrentDownloader actualizada.");
    }

    public void setAutoStartDownloads(boolean autoStartDownloads) {
        synchronized (lock) {
            this.autoStartDownloads = autoStartDownloads;
        }
        if (autoStartDownloads) {
            startNextIfPossible();
        }
    }

    /** Ajusta la prioridad del torrent y reordena la cola de inicio. */
    public void reprioritize(TorrentState torrentState, int priority) {
        if (torrentState == null) {
            return;
        }
        torrentState.setPriority(priority);
        synchronized (lock) {
            PendingTorrent pending = pendingByState.get(torrentState);
            if (pending != null) {
                pendingQueue.remove(pending);
                pendingQueue.offerFirst(pending);
            }
        }
        if (autoStartDownloads) {
            startNextIfPossible();
        }
    }

    /** Habilita o deshabilita la descarga secuencial para un torrent concreto. */
    public void setSequentialDownload(TorrentState torrentState, boolean sequential) {
        if (torrentState == null) {
            return;
        }
        ManagedTorrent managed;
        synchronized (lock) {
            PendingTorrent pending = pendingByState.get(torrentState);
            if (pending != null) {
                pending.setSequentialDownload(sequential);
                if (sequential) {
                    pendingQueue.remove(pending);
                    pendingQueue.offerFirst(pending);
                }
                return;
            }
            managed = managedByState.get(torrentState);
        }
        if (managed != null) {
            updateSequentialDownload(managed, sequential);
        }
    }

    /** Aplica límites de velocidad individuales (en KiB/s) a un torrent. */
    public void setTorrentRateLimits(TorrentState torrentState, int downloadLimitKiB, int uploadLimitKiB) {
        if (torrentState == null) {
            return;
        }
        int downloadBytes = downloadLimitKiB <= 0 ? -1 : downloadLimitKiB * 1024;
        int uploadBytes = uploadLimitKiB <= 0 ? -1 : uploadLimitKiB * 1024;

        ManagedTorrent managed;
        synchronized (lock) {
            PendingTorrent pending = pendingByState.get(torrentState);
            if (pending != null) {
                pending.setDownloadLimitBytes(downloadBytes);
                pending.setUploadLimitBytes(uploadBytes);
                return;
            }
            managed = managedByState.get(torrentState);
        }

        if (managed != null && managed.handle.isValid()) {
            try {
                managed.handle.setDownloadLimit(downloadBytes);
                managed.downloadLimitBytes = downloadBytes;
                managed.lastAutoDownloadLimit = -1;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo establecer el límite de descarga del torrent: " + t.getMessage());
            }
            try {
                managed.handle.setUploadLimit(uploadBytes);
                managed.uploadLimitBytes = uploadBytes;
                managed.lastAutoUploadLimit = -1;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo establecer el límite de subida del torrent: " + t.getMessage());
            }
        }
        lastBandwidthRebalanceNanos = 0L;
    }

    /** Devuelve las estadísticas vivas asociadas a un torrent gestionado. */
    public TorrentStats getTorrentStats(TorrentState torrentState) {
        if (torrentState == null) {
            return null;
        }
        synchronized (lock) {
            ManagedTorrent managed = managedByState.get(torrentState);
            return managed != null ? managed.stats : null;
        }
    }

    public List<TorrentLogEntry> getTorrentLog(TorrentState torrentState) {
        if (torrentState == null) {
            return Collections.emptyList();
        }
        TorrentLogBook book = logsByState.get(torrentState);
        return book != null ? book.snapshot() : Collections.emptyList();
    }

    public TorrentHealthReport runHealthCheck(TorrentState torrentState) {
        if (torrentState == null) {
            return null;
        }

        ManagedTorrent managed;
        synchronized (lock) {
            managed = managedByState.get(torrentState);
        }

        List<TorrentHealthReport.Check> checks = new ArrayList<>();
        boolean sessionActive = sessionManager.isRunning();
        checks.add(new TorrentHealthReport.Check(
                "Sesión BitTorrent",
                sessionActive,
                sessionActive ? "La sesión está activa." : "La sesión no está en ejecución."));

        boolean handleValid = managed != null && managed.handle != null && managed.handle.isValid();
        checks.add(new TorrentHealthReport.Check(
                "Manejador del torrent",
                handleValid,
                handleValid ? "Se puede interactuar con el torrent." : "El torrent no está activo."));

        TorrentStatus status = null;
        if (handleValid) {
            try {
                status = managed.handle.status();
            } catch (Throwable t) {
                checks.add(new TorrentHealthReport.Check(
                        "Estado del torrent",
                        false,
                        "No se pudo leer el estado actual: " + t.getMessage()));
            }
        }

        boolean hasMetadata = status != null && status.hasMetadata();
        checks.add(new TorrentHealthReport.Check(
                "Metadatos",
                hasMetadata,
                hasMetadata ? "Se recibieron los metadatos del torrent." : "Aún no se han recibido los metadatos."));

        boolean destinationOk = false;
        String destinationPath = torrentState.getDestinationPath();
        if (destinationPath != null && !destinationPath.isBlank()) {
            try {
                Path destination = Paths.get(destinationPath);
                Path parent = destination.getParent();
                destinationOk = Files.exists(destination) || (parent != null && Files.exists(parent));
            } catch (Exception ignored) {
                destinationOk = false;
            }
        }
        checks.add(new TorrentHealthReport.Check(
                "Ruta de descarga",
                destinationOk,
                destinationOk ? "La ruta de destino es accesible." : "No se pudo comprobar la ruta de destino."));

        boolean torrentSourceOk = true;
        String source = torrentState.getTorrentSource();
        if (source != null && source.toLowerCase().endsWith(".torrent")) {
            try {
                torrentSourceOk = Files.exists(Paths.get(source));
            } catch (Exception ignored) {
                torrentSourceOk = false;
            }
        }
        checks.add(new TorrentHealthReport.Check(
                "Archivo torrent",
                torrentSourceOk,
                torrentSourceOk ? "Archivo disponible." : "No se encontró el archivo .torrent."));

        boolean trackersOk = status != null && status.errorCode().value() == 0;
        checks.add(new TorrentHealthReport.Check(
                "Trackers",
                trackersOk,
                trackersOk ? "Los trackers responden correctamente." : "Los trackers informan errores."));

        SessionStats sessionStats = sessionManager.stats();
        long dhtNodes = sessionStats != null ? sessionStats.dhtNodes() : 0L;
        boolean dhtOk = sessionManager.isDhtRunning() && dhtNodes > 0;
        checks.add(new TorrentHealthReport.Check(
                "DHT",
                dhtOk,
                dhtOk ? "Nodos detectados: " + dhtNodes : "No se detectan nodos DHT."));

        boolean hasPeers = status != null && status.numPeers() > 0;
        checks.add(new TorrentHealthReport.Check(
                "Peers",
                hasPeers,
                hasPeers ? "Conectado a " + status.numPeers() + " peers." : "Sin peers conectados."));

        boolean downloadingData = status != null && (status.downloadRate() > 0 || status.totalDone() > 0);
        checks.add(new TorrentHealthReport.Check(
                "Transferencia",
                downloadingData,
                downloadingData ? "Se están recibiendo datos." : "No se detecta tráfico de descarga."));

        TorrentHealthReport report = new TorrentHealthReport(
                managed != null ? managed.infoHash.toHex() : null,
                checks,
                System.currentTimeMillis());

        for (TorrentHealthReport.Check check : checks) {
            Level level = check.isPassed() ? Level.INFO : Level.WARNING;
            recordEvent(torrentState, TorrentLogEntry.Step.HEALTHCHECK, level,
                    check.getName() + ": " + check.getDetails(), true);
        }

        Level summaryLevel = report.isHealthy() ? Level.INFO : Level.WARNING;
        recordEvent(torrentState, TorrentLogEntry.Step.HEALTHCHECK, summaryLevel,
                report.isHealthy() ? "Diagnóstico completado sin incidencias." : "Se detectaron incidencias en el diagnóstico.",
                true);

        return report;
    }

    public void addNotificationListener(TorrentNotificationListener listener) {
        if (listener != null) {
            removeNotificationListener(listener);
            listeners.add(listener);
        }
    }

    public void removeNotificationListener(TorrentNotificationListener listener) {
        listeners.remove(listener);
    }

    /** Start a torrent download from a local .torrent file. */
    public void downloadTorrent(String torrentFilePath, TorrentState torrentState) {
        Objects.requireNonNull(torrentState, "torrentState");
        if (torrentFilePath == null || torrentFilePath.isBlank()) {
            notifyError(torrentState, "La ruta del archivo torrent es inválida.");
            return;
        }
        torrentState.setTorrentSource(torrentFilePath);
        enqueueTorrent(new PendingTorrent(torrentState,
                () -> buildParamsFromFile(Paths.get(torrentFilePath), torrentState)));
    }

    /** Add a torrent from the {@link TorrentState#getTorrentSource()} value. */
    public void addTorrent(TorrentState torrentState) {
        Objects.requireNonNull(torrentState, "torrentState");
        String source = torrentState.getTorrentSource();
        if (source == null || source.isBlank()) {
            notifyError(torrentState, "No se especificó la fuente del torrent.");
            return;
        }

        if (source.startsWith("magnet:")) {
            enqueueTorrent(new PendingTorrent(torrentState,
                    () -> buildParamsFromMagnet(source, torrentState)));
        } else if (source.startsWith("http://") || source.startsWith("https://")) {
            workerExecutor.submit(() -> {
                try {
                    Path file = downloadRemoteTorrent(source, torrentState.getName());
                    enqueueTorrent(new PendingTorrent(torrentState,
                            () -> buildParamsFromFile(file, torrentState)));
                } catch (IOException e) {
                    notifyError(torrentState, "Error descargando el archivo torrent: " + e.getMessage());
                }
            });
        } else {
            downloadTorrent(source, torrentState);
        }
    }

    /** Pause a running torrent or remove it from the start queue. */
    public void pauseDownload(TorrentState torrentState) {
        if (torrentState == null) {
            return;
        }
        synchronized (lock) {
            ManagedTorrent managed = managedByState.get(torrentState);
            if (managed != null && !managed.completed) {
                if (!managed.paused && managed.handle.isValid()) {
                    managed.handle.pause();
                    managed.paused = true;
                    torrentState.setStatus("Pausado");
                    recordEvent(torrentState, TorrentLogEntry.Step.DOWNLOAD, Level.INFO,
                            "Descarga pausada por el usuario.");
                }
            } else {
                PendingTorrent pending = pendingByState.get(torrentState);
                if (pending != null) {
                    pendingQueue.remove(pending);
                    torrentState.setStatus("Pausado");
                    recordEvent(torrentState, TorrentLogEntry.Step.DOWNLOAD, Level.INFO,
                            "Descarga retirada de la cola de inicio automático.");
                }
            }
        }
        lastBandwidthRebalanceNanos = 0L;
        startNextIfPossible();
    }

    /** Resume a paused torrent or move a queued torrent to the front of the queue. */
    public void resumeDownload(TorrentState torrentState) {
        if (torrentState == null) {
            return;
        }
        boolean shouldStart = false;
        synchronized (lock) {
            ManagedTorrent managed = managedByState.get(torrentState);
            if (managed != null) {
                if (managed.completed) {
                    return;
                }
                if (managed.paused && managed.handle.isValid()) {
                    managed.handle.resume();
                    managed.paused = false;
                    torrentState.setStatus("Descargando");
                    recordEvent(torrentState, TorrentLogEntry.Step.DOWNLOAD, Level.INFO,
                            "Descarga reanudada.");
                }
            } else {
                PendingTorrent pending = pendingByState.get(torrentState);
                if (pending != null && !pendingQueue.contains(pending)) {
                    pendingQueue.offerFirst(pending);
                    torrentState.setStatus("En espera");
                    recordEvent(torrentState, TorrentLogEntry.Step.DOWNLOAD, Level.INFO,
                            "Descarga priorizada para inicio inmediato.");
                    shouldStart = true;
                }
            }
        }
        lastBandwidthRebalanceNanos = 0L;
        if (shouldStart || autoStartDownloads) {
            startNextIfPossible();
        }
    }

    /** Remove a torrent from the session and optionally delete its data. */
    public void removeDownload(TorrentState torrentState, boolean deleteFiles) {
        if (torrentState == null) {
            return;
        }
        ManagedTorrent managed;
        synchronized (lock) {
            PendingTorrent pending = pendingByState.remove(torrentState);
            if (pending != null) {
                pendingQueue.remove(pending);
            }
            managed = managedByState.remove(torrentState);
            if (managed != null) {
                managedByHash.remove(managed.infoHashKey);
            }
        }
        if (managed != null && managed.handle.isValid()) {
            if (deleteFiles) {
                sessionManager.remove(managed.handle, SessionHandle.DELETE_FILES);
            } else {
                sessionManager.remove(managed.handle);
            }
        }
        torrentState.setStatus("Eliminado");
        recordEvent(torrentState, TorrentLogEntry.Step.DOWNLOAD, Level.INFO,
                deleteFiles ? "Descarga eliminada y datos borrados." : "Descarga eliminada conservando los archivos.");
        logsByState.remove(torrentState);
        lastBandwidthRebalanceNanos = 0L;
        startNextIfPossible();
    }

    /** Gracefully shutdown jlibtorrent and background executors. */
    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
        workerExecutor.shutdownNow();
        sessionManager.removeListener(alertListener);
        sessionManager.stop();
        cleanupTemporaryFiles();
        log(Level.INFO, "TorrentDownloader detenido.");
    }

    public byte[] saveSessionState() {
        try {
            return sessionManager.saveState();
        } catch (Exception e) {
            log(Level.WARNING, "No se pudo guardar el estado de la sesión: " + e.getMessage());
            return null;
        }
    }

    public void restoreSessionState(byte[] sessionState) {
        if (sessionState == null || sessionState.length == 0) {
            return;
        }
        try {
            sessionManager.stop();
            sessionManager.start(new SessionParams(sessionState));
            applySessionSettings();
            running = true;
            startNextIfPossible();
            log(Level.INFO, "Estado de sesión restaurado correctamente.");
        } catch (Exception e) {
            log(Level.SEVERE, "Error al restaurar la sesión: " + e.getMessage());
        }
    }

    private void enqueueTorrent(PendingTorrent pendingTorrent) {
        TorrentState state = pendingTorrent.state;
        synchronized (lock) {
            if (managedByState.containsKey(state)) {
                recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.WARNING,
                        "El torrent ya se está descargando: " + state.getName());
                return;
            }
            PendingTorrent previous = pendingByState.put(state, pendingTorrent);
            if (previous != null) {
                pendingQueue.remove(previous);
            }
            if (autoStartDownloads) {
                pendingQueue.offerLast(pendingTorrent);
                state.setStatus("En espera");
                recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.INFO,
                        "Torrent añadido a la cola con prioridad " + pendingTorrent.getPriority() + '.');
            } else {
                state.setStatus("Pausado");
                recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.INFO,
                        "Torrent en pausa manual hasta que se inicie desde la interfaz.");
            }
        }
        if (autoStartDownloads) {
            startNextIfPossible();
        }
    }

    private void startNextIfPossible() {
        if (!running) {
            return;
        }
        List<PendingTorrent> toStart = new ArrayList<>();
        synchronized (lock) {
            if (!autoStartDownloads) {
                return;
            }
            while (countActiveDownloadsInternal() < maxConcurrentDownloads && !pendingQueue.isEmpty()) {
                PendingTorrent pending = pollNextPendingUnlocked();
                if (pending == null) {
                    break;
                }
                toStart.add(pending);
            }
        }
        for (PendingTorrent pending : toStart) {
            workerExecutor.submit(() -> startTorrent(pending));
        }
    }

    private PendingTorrent pollNextPendingUnlocked() {
        PendingTorrent selected = null;
        int selectedPriority = Integer.MIN_VALUE;
        int selectedIndex = Integer.MAX_VALUE;
        int index = 0;
        for (PendingTorrent candidate : pendingQueue) {
            int candidatePriority = candidate.getPriority();
            if (selected == null || candidatePriority > selectedPriority
                    || (candidatePriority == selectedPriority && index < selectedIndex)) {
                selected = candidate;
                selectedPriority = candidatePriority;
                selectedIndex = index;
            }
            index++;
        }
        if (selected != null) {
            pendingQueue.remove(selected);
        }
        return selected;
    }

    private int countActiveDownloadsInternal() {
        int count = 0;
        for (ManagedTorrent managed : managedByState.values()) {
            if (!managed.paused && !managed.completed && managed.handle.isValid()) {
                count++;
            }
        }
        return count;
    }

    private void startTorrent(PendingTorrent pending) {
        TorrentState state = pending.state;
        try {
            AddTorrentParams params = pending.paramsSupplier.get();
            if (params == null) {
                throw new IllegalStateException("No se pudo construir la configuración del torrent.");
            }

            String destinationPath = state.getDestinationPath();
            if (destinationPath == null || destinationPath.isBlank()) {
                throw new IllegalStateException("La ruta de descarga no está configurada.");
            }

            recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.INFO,
                    "Preparando descarga en " + destinationPath);

            Path destination = ensureDestinationDirectory(destinationPath);
            recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.INFO,
                    "Directorio de destino verificado: " + destination.toAbsolutePath());
            long requiredSpace = calculateRequiredSpace(state);
            if (!hasEnoughDiskSpace(destination, requiredSpace)) {
                state.setStatus("Pausado (Espacio insuficiente)");
                recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.WARNING,
                        "Espacio insuficiente en el destino. Se requieren " + formatSize(requiredSpace) + '.');
                notifyDiskSpace(destination, requiredSpace);
                return;
            }
            recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.INFO,
                    "Espacio disponible suficiente para la descarga (" + formatSize(requiredSpace) + ").");

            params.savePath(destination.toAbsolutePath().toString());
            if (params.name() == null || params.name().isBlank()) {
                params.name(state.getName());
            }
            applyDefaultTrackers(params);
            applyPerformanceHints(params);
            TorrentHandle handle = addTorrentToSession(params);
            Sha1Hash bestHash = resolveInfoHash(handle, params);
            ManagedTorrent managed = new ManagedTorrent(state, handle, bestHash);
            recordEvent(state, TorrentLogEntry.Step.PREPARATION, Level.INFO,
                    "Torrent registrado en la sesión con hash " + bestHash.toHex());
            managed.sequentialDownload = pending.isSequentialDownload();
            if (managed.sequentialDownload) {
                applySequentialDownloadFlag(handle, true);
            }
            if (pending.hasCustomDownloadLimit()) {
                int limitBytes = pending.getDownloadLimitBytes();
                try {
                    handle.setDownloadLimit(limitBytes);
                } catch (Throwable t) {
                    log(Level.FINEST, "No se pudo aplicar el límite de descarga: " + t.getMessage());
                }
                managed.downloadLimitBytes = limitBytes;
            }
            if (pending.hasCustomUploadLimit()) {
                int limitBytes = pending.getUploadLimitBytes();
                try {
                    handle.setUploadLimit(limitBytes);
                } catch (Throwable t) {
                    log(Level.FINEST, "No se pudo aplicar el límite de subida: " + t.getMessage());
                }
                managed.uploadLimitBytes = limitBytes;
            }
            synchronized (lock) {
                pendingByState.remove(state);
                managedByState.put(state, managed);
                managedByHash.put(managed.infoHashKey, managed);
            }
            primeNewTorrent(managed, params);
            lastBandwidthRebalanceNanos = 0L;

            state.setStatus("Descargando");
            state.setTorrentId(bestHash.toHex());
            state.setHash(bestHash.toHex());
            updateStateFromHandle(managed);
            notifyStatusUpdate(state, managed.stats);
            recordEvent(state, TorrentLogEntry.Step.DOWNLOAD, Level.INFO,
                    "Descarga iniciada para " + state.getName());
            requestTorrentStatusUpdates();
        } catch (Exception e) {
            state.setStatus("Error");
            notifyError(state, "Error al iniciar el torrent: " + e.getMessage());
            synchronized (lock) {
                pendingByState.remove(state);
            }
        }
    }

    private TorrentHandle addTorrentToSession(AddTorrentParams params) {
        if (!sessionManager.isRunning()) {
            throw new IllegalStateException("La sesión de BitTorrent no está activa.");
        }
        if (params == null) {
            throw new IllegalArgumentException("Los parámetros del torrent no pueden ser nulos.");
        }

        error_code nativeError = new error_code();
        TorrentHandle handle;
        try {
            handle = new TorrentHandle(sessionManager.swig().add_torrent(params.swig(), nativeError));
        } catch (Throwable t) {
            throw new IllegalStateException("No se pudo registrar el torrent en la sesión activa: " + t.getMessage(), t);
        }

        if (nativeError.value() != 0) {
            throw new IllegalStateException("No se pudo registrar el torrent en la sesión activa: " + nativeError.message());
        }
        if (handle == null || !handle.isValid()) {
            throw new IllegalStateException("El manejador del torrent devuelto no es válido.");
        }
        return handle;
    }

    private Sha1Hash resolveInfoHash(TorrentHandle handle, AddTorrentParams params) {
        Sha1Hash hash = null;
        if (handle != null && handle.isValid()) {
            try {
                hash = handle.infoHash();
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo obtener el hash desde el manejador: " + t.getMessage());
            }
        }
        if (hash == null || hash.isAllZeros()) {
            InfoHash infoHashes = null;
            try {
                infoHashes = params != null ? params.getInfoHashes() : null;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo obtener el hash desde los parámetros: " + t.getMessage());
            }
            if (infoHashes != null) {
                Sha1Hash best = infoHashes.getBest();
                if (best != null && !best.isAllZeros()) {
                    hash = best;
                }
            }
        }
        if (hash == null || hash.isAllZeros()) {
            throw new IllegalStateException("El torrent no proporcionó un infohash válido.");
        }
        return hash;
    }

    private void refreshStatuses() {
        if (!running) {
            return;
        }
        requestTorrentStatusUpdates();
        List<ManagedTorrent> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(managedByState.values());
        }
        int activeCount = countActiveTorrents(snapshot);
        for (ManagedTorrent managed : snapshot) {
            if (!managed.handle.isValid()) {
                synchronized (lock) {
                    managedByState.remove(managed.state);
                    managedByHash.remove(managed.infoHashKey);
                }
                lastBandwidthRebalanceNanos = 0L;
                continue;
            }
            TorrentStatus status = null;
            try {
                status = managed.handle.status();
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo actualizar el estado del torrent: " + t.getMessage());
            }
            if (status != null) {
                updateManagedTorrent(managed, status);
                maybeOptimizeTorrentConnections(managed, status, activeCount);
            }
        }
        autoTuneSessionIfNeeded();
        rebalanceActiveTorrentBandwidth(snapshot);
    }

    private void requestTorrentStatusUpdates() {
        if (!running) {
            return;
        }
        try {
            sessionManager.postTorrentUpdates();
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo solicitar la actualización de torrents: " + t.getMessage());
        }
        try {
            sessionManager.postSessionStats();
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo solicitar las estadísticas de sesión: " + t.getMessage());
        }
        try {
            sessionManager.postDhtStats();
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo solicitar las estadísticas de la DHT: " + t.getMessage());
        }
    }

    private void updateManagedTorrent(ManagedTorrent managed, TorrentStatus status) {
        if (status == null) {
            return;
        }
        managed.stats.update(status);

        TorrentState state = managed.state;
        double progress = Math.max(0, Math.min(1.0, status.progress()));
        state.setProgress(progress * 100);
        state.setDownloadSpeed(status.downloadRate() / 1024.0);
        state.setUploadSpeed(status.uploadRate() / 1024.0);
        state.setPeers(status.numPeers());
        state.setSeeds(status.numSeeds());
        state.setFileSize(status.total());
        state.setRemainingTime(calculateRemainingSeconds(status));

        if (status.name() != null && !status.name().isBlank()) {
            state.setFileName(status.name());
            state.setName(status.name());
        }

        if (!managed.completed) {
            if (status.isFinished() || status.isSeeding()) {
                completeTorrent(managed);
            } else if (!managed.paused) {
                state.setStatus(describeState(status.state()));
            }
        }

        improvePeerDiscovery(managed, status);
        notifyStatusUpdate(state, managed.stats);
    }

    private void improvePeerDiscovery(ManagedTorrent managed, TorrentStatus status) {
        if (managed == null || status == null) {
            return;
        }
        if (managed.completed || managed.paused || !sessionManager.isRunning()) {
            managed.stalledPeerChecks.set(0);
            return;
        }

        long downloadRate = status.downloadRate();
        long uploadRate = status.uploadRate();
        if (downloadRate >= STALLED_DOWNLOAD_RATE_BYTES || uploadRate >= STALLED_UPLOAD_RATE_BYTES) {
            managed.stalledPeerChecks.set(0);
            return;
        }

        int peers = Math.max(0, status.numPeers());
        int seeds = Math.max(0, status.numSeeds());
        if (peers >= MINIMUM_ACTIVE_PEERS || seeds >= MINIMUM_ACTIVE_SEEDS) {
            managed.stalledPeerChecks.set(0);
            return;
        }

        if (status.progress() >= 1.0) {
            managed.stalledPeerChecks.set(0);
            return;
        }

        int stalledChecks = managed.stalledPeerChecks.incrementAndGet();
        long now = System.currentTimeMillis();
        maybeRefreshTrackers(managed, status, now);
        requestAdditionalPeers(managed, status, now);
        if (stalledChecks < STALLED_CHECKS_THRESHOLD) {
            return;
        }

        if (now - managed.lastTrackerAnnounceMs >= TRACKER_REANNOUNCE_INTERVAL.toMillis() && managed.handle.isValid()) {
            try {
                managed.handle.forceReannounce();
                managed.handle.scrapeTracker();
                log(Level.FINE, "Reanunciando el torrent " + managed.state.getName() + " a los trackers por conectividad baja.");
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo reanunciar el torrent: " + t.getMessage());
            }
            managed.lastTrackerAnnounceMs = now;
        }

        if (now - managed.lastDhtAnnounceMs >= DHT_REANNOUNCE_INTERVAL.toMillis()) {
            try {
                sessionManager.dhtAnnounce(managed.infoHash);
                log(Level.FINE, "Forzando anuncio DHT para " + managed.state.getName() + ".");
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo anunciar en la DHT: " + t.getMessage());
            }
            managed.lastDhtAnnounceMs = now;
        }

        managed.stalledPeerChecks.set(0);
    }
    private void maybeRefreshTrackers(ManagedTorrent managed, TorrentStatus status, long now) {
        if (managed == null || status == null) {
            return;
        }
        if (status.numPeers() >= MINIMUM_ACTIVE_PEERS) {
            return;
        }
        if (managed.handle == null || !managed.handle.isValid()) {
            return;
        }
        if (now - managed.lastTrackerInjectionMs < TRACKER_REFRESH_INTERVAL.toMillis()) {
            return;
        }

        managed.lastTrackerInjectionMs = now;
        workerExecutor.submit(() -> {
            if (!managed.handle.isValid()) {
                return;
            }
            try {
                List<AnnounceEntry> existing = managed.handle.trackers();
                Set<String> urls = new LinkedHashSet<>();
                if (existing != null) {
                    for (AnnounceEntry entry : existing) {
                        if (entry == null) {
                            continue;
                        }
                        String url = entry.url();
                        if (url != null && !url.isBlank()) {
                            urls.add(url.trim());
                        }
                    }
                }

                List<AnnounceEntry> toAdd = new ArrayList<>();
                for (String tracker : DEFAULT_TRACKERS) {
                    String sanitized = Objects.requireNonNull(tracker, "defaultTracker").trim();
                    if (sanitized.isEmpty()) {
                        continue;
                    }
                    if (!urls.contains(sanitized)) {
                        toAdd.add(new AnnounceEntry(sanitized));
                        urls.add(sanitized);
                    }
                }

                for (AnnounceEntry entry : toAdd) {
                    try {
                        managed.handle.addTracker(entry);
                    } catch (Throwable t) {
                        log(Level.FINEST, "No se pudo añadir el tracker " + entry.url() + ": " + t.getMessage());
                    }
                }
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudieron refrescar los trackers: " + t.getMessage());
            }
        });
    }

    private void requestAdditionalPeers(ManagedTorrent managed, TorrentStatus status, long now) {
        if (managed == null || status == null) {
            return;
        }
        if (status.numPeers() >= MINIMUM_ACTIVE_PEERS) {
            return;
        }
        if (!sessionManager.isRunning() || managed.handle == null || !managed.handle.isValid()) {
            return;
        }
        if (now - managed.lastPeerFetchMs < DHT_PEER_FETCH_INTERVAL.toMillis()) {
            return;
        }

        managed.lastPeerFetchMs = now;
        workerExecutor.submit(() -> {
            TorrentHandle handle = managed.handle;
            if (!sessionManager.isRunning() || handle == null || !handle.isValid()) {
                return;
            }
            try {
                purgeExpiredSlowPeers(managed);
                List<PeerSample> peerSamples = snapshotPeers(managed);
                rememberContactedPeers(managed, peerSamples);

                List<TcpEndpoint> dhtPeers = sessionManager.dhtGetPeers(managed.infoHash, 6);
                if (dhtPeers == null || dhtPeers.isEmpty()) {
                    sessionManager.dhtAnnounce(managed.infoHash);
                    return;
                }

                int connected = 0;
                long iterationStart = System.currentTimeMillis();
                for (TcpEndpoint endpoint : dhtPeers) {
                    if (endpoint == null) {
                        continue;
                    }
                    String key = endpoint.toString();
                    if (key == null || key.isBlank()) {
                        continue;
                    }
                    SlowPeerRecord slowRecord = managed.slowPeers.get(key);
                    if (slowRecord != null) {
                        if (slowRecord.isExpired(iterationStart)) {
                            managed.slowPeers.remove(key);
                        } else if (slowRecord.isConfirmed(iterationStart)) {
                            continue;
                        }
                    }
                    if (!managed.contactedPeers.add(key)) {
                        continue;
                    }
                    try {
                        handle.swig().connect_peer(endpoint.swig());
                        connected++;
                    } catch (Throwable t) {
                        managed.contactedPeers.remove(key);
                        log(Level.FINEST, "No se pudo conectar al peer " + key + ": " + t.getMessage());
                    }
                    if (connected >= MAX_EXTRA_DHT_CONNECTIONS) {
                        break;
                    }
                }

                if (connected == 0) {
                    sessionManager.dhtAnnounce(managed.infoHash);
                }

                if (managed.contactedPeers.size() > 512) {
                    managed.contactedPeers.clear();
                }
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudieron obtener peers adicionales desde la DHT: " + t.getMessage());
            }
        });
    }

    private List<PeerSample> snapshotPeers(ManagedTorrent managed) {
        if (managed == null || managed.handle == null || !managed.handle.isValid()) {
            if (managed != null) {
                managed.lastPeerSamples = Collections.emptyList();
            }
            return Collections.emptyList();
        }
        try {
            List<PeerInfo> peers = managed.handle.peerInfo();
            if (peers == null || peers.isEmpty()) {
                managed.lastPeerSamples = Collections.emptyList();
                return managed.lastPeerSamples;
            }
            List<PeerSample> samples = new ArrayList<>(peers.size());
            for (PeerInfo peer : peers) {
                if (peer == null) {
                    continue;
                }
                String endpoint = peer.ip();
                if (endpoint == null || endpoint.isBlank()) {
                    continue;
                }
                samples.add(new PeerSample(endpoint, peer.downSpeed(), peer.upSpeed(), peer.progress()));
            }
            updatePeerSpeedRecords(managed, peers);
            if (samples.isEmpty()) {
                managed.lastPeerSamples = Collections.emptyList();
            } else {
                managed.lastPeerSamples = samples;
            }
            return managed.lastPeerSamples;
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudieron capturar los peers actuales: " + t.getMessage());
            managed.lastPeerSamples = Collections.emptyList();
            return managed.lastPeerSamples;
        }
    }

    private void rememberContactedPeers(ManagedTorrent managed, List<PeerSample> peerSamples) {
        if (managed == null || peerSamples == null || peerSamples.isEmpty()) {
            return;
        }
        for (PeerSample sample : peerSamples) {
            if (sample == null || sample.endpoint == null || sample.endpoint.isBlank()) {
                continue;
            }
            managed.contactedPeers.add(sample.endpoint);
        }
    }

    private void updatePeerSpeedRecords(ManagedTorrent managed, List<PeerInfo> peers) {
        if (managed == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (peers != null && !peers.isEmpty()) {
            for (PeerInfo peer : peers) {
                if (peer == null) {
                    continue;
                }
                String endpoint = peer.ip();
                if (endpoint == null || endpoint.isBlank()) {
                    continue;
                }
                int throughput = Math.max(peer.downSpeed(), peer.upSpeed());
                if (throughput >= MIN_PEER_SAMPLE_SPEED_BYTES) {
                    managed.slowPeers.remove(endpoint);
                } else {
                    SlowPeerRecord record = managed.slowPeers.computeIfAbsent(endpoint, key -> new SlowPeerRecord(now));
                    record.markSeen(now);
                }
            }
        }
        managed.slowPeers.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private void purgeExpiredSlowPeers(ManagedTorrent managed) {
        if (managed == null) {
            return;
        }
        long now = System.currentTimeMillis();
        managed.slowPeers.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }
    private void maybeOptimizeTorrentConnections(ManagedTorrent managed, TorrentStatus status, int activeCount) {
        if (managed == null || status == null) {
            return;
        }
        if (!isTorrentActive(managed)) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - managed.lastPeerOptimizationMs < TORRENT_OPTIMIZATION_INTERVAL.toMillis()) {
            return;
        }
        managed.lastPeerOptimizationMs = now;

        int globalConnections = lastAutoConnectionsLimit > 0 ? lastAutoConnectionsLimit : computeConnectionsLimit();
        globalConnections = clamp(globalConnections, 150, MAX_DYNAMIC_CONNECTIONS);

        int baseline = globalConnections / Math.max(1, activeCount);
        baseline = clamp(baseline, 80, globalConnections);

        int peerCandidates = Math.max(status.listPeers(), status.numPeers());
        int desiredConnections = Math.max(baseline, peerCandidates + MINIMUM_ACTIVE_PEERS);
        desiredConnections = clamp(desiredConnections, baseline, globalConnections);
        long sessionTargetRate = Math.max(expectedSessionDownloadRate(),
                Math.max(status.downloadRate(), smoothedDownloadRate()));

        List<PeerSample> peerSamples = managed.lastPeerSamples != null ? managed.lastPeerSamples : Collections.emptyList();
        if (peerSamples.isEmpty()) {
            peerSamples = snapshotPeers(managed);
        }
        int sampleSuggestedSlots = estimateSlotsForTargetRate(peerSamples, sessionTargetRate);
        if (sampleSuggestedSlots > 0) {
            desiredConnections = clamp(Math.max(desiredConnections, sampleSuggestedSlots + MINIMUM_ACTIVE_PEERS),
                    baseline, globalConnections);
        }

        if (status.downloadRate() < STALLED_DOWNLOAD_RATE_BYTES && status.numPeers() < MINIMUM_ACTIVE_PEERS) {
            desiredConnections = clamp(desiredConnections + 80, baseline, globalConnections);
        }

        if (desiredConnections > 0 && desiredConnections != managed.lastMaxConnections) {
            TorrentHandle handle = managed.handle;
            try {
                Objects.requireNonNull(handle, "torrentHandle").swig().set_max_connections(desiredConnections);
                managed.lastMaxConnections = desiredConnections;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo ajustar el número de conexiones del torrent: " + t.getMessage());
            }
        }

        int desiredUploads = Math.max(MINIMUM_ACTIVE_SEEDS + 4, desiredConnections / 4);
        desiredUploads = clamp(desiredUploads, MINIMUM_ACTIVE_SEEDS + 4, 128);
        if (desiredUploads != managed.lastMaxUploads) {
            TorrentHandle handle = managed.handle;
            try {
                Objects.requireNonNull(handle, "torrentHandle").swig().set_max_uploads(desiredUploads);
                managed.lastMaxUploads = desiredUploads;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo ajustar el número de subidas simultáneas: " + t.getMessage());
            }
        }
    }

    private int countActiveTorrents(List<ManagedTorrent> torrents) {
        if (torrents == null || torrents.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (ManagedTorrent managed : torrents) {
            if (isTorrentActive(managed)) {
                count++;
            }
        }
        return count;
    }

    private boolean isTorrentActive(ManagedTorrent managed) {
        return managed != null && managed.handle != null && managed.handle.isValid() && !managed.paused;
    }

    private void autoTuneSessionIfNeeded() {
        if (!sessionManager.isRunning()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSessionAutotuneNanos < SESSION_AUTOTUNE_INTERVAL.toNanos()) {
            return;
        }
        lastSessionAutotuneNanos = now;

        SessionStats stats = sessionManager.stats();
        if (stats == null) {
            return;
        }

        long downloadRate = stats.downloadRate();
        long uploadRate = stats.uploadRate();
        long dhtNodes = stats.dhtNodes();

        lastObservedDownloadRate = downloadRate;
        updatePeakDownloadRate(downloadRate);
        long expectedRate = expectedSessionDownloadRate();
        sessionDownloadThroughput.addSample(downloadRate);
        sessionUploadThroughput.addSample(uploadRate);
        long smoothedDownload = Math.max(downloadRate, smoothedDownloadRate());
        long smoothedUpload = Math.max(uploadRate, smoothedUploadRate());
        long effectiveRate = Math.max(expectedRate, smoothedDownload);

        int desiredConnections = clamp(Math.max(computeConnectionsLimit(),
                dynamicConnectionsFromRate(effectiveRate, smoothedUpload)),
                150, MAX_DYNAMIC_CONNECTIONS);
        int desiredConnectionSpeed = clamp(Math.max(computeConnectionSpeed(),
                dynamicConnectionSpeed(effectiveRate)), 30, 500);
        int desiredRequestQueue = clamp(dynamicRequestQueue(effectiveRate),
                MIN_DYNAMIC_REQUEST_QUEUE, MAX_DYNAMIC_REQUEST_QUEUE);

        SettingsPack dynamic = new SettingsPack();
        boolean changed = false;
        if (shouldUpdate(desiredConnections, lastAutoConnectionsLimit, 15)) {
            dynamic.connectionsLimit(desiredConnections);
            lastAutoConnectionsLimit = desiredConnections;
            changed = true;
        }
        if (shouldUpdate(desiredConnectionSpeed, lastAutoConnectionSpeed, 5)) {
            dynamic.setInteger(settings_pack.int_types.connection_speed.swigValue(), desiredConnectionSpeed);
            lastAutoConnectionSpeed = desiredConnectionSpeed;
            changed = true;
        }
        if (shouldUpdate(desiredRequestQueue, lastAutoRequestQueue, 100)) {
            dynamic.setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), desiredRequestQueue);
            lastAutoRequestQueue = desiredRequestQueue;
            changed = true;
        }

        if (changed) {
            try {
                sessionManager.applySettings(dynamic);
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo ajustar dinámicamente la sesión: " + t.getMessage());
            }
        }

        rebootstrapDhtIfNeeded(dhtNodes);
    }
    private void updatePeakDownloadRate(long downloadRate) {
        peakObservedDownloadRate.updateAndGet(previous -> {
            long sanitizedPrevious = Math.max(0L, previous);
            if (downloadRate <= 0) {
                return (long) (sanitizedPrevious * PEAK_RATE_DECAY);
            }
            if (sanitizedPrevious <= 0) {
                return downloadRate;
            }
            return (long) (sanitizedPrevious * PEAK_RATE_DECAY
                    + downloadRate * (1.0 - PEAK_RATE_DECAY));
        });
    }

    private long expectedSessionDownloadRate() {
        long limit = downloadSpeedLimit > 0 ? downloadSpeedLimit * 1024L : 0L;
        long observed = Math.max(Math.max(lastObservedDownloadRate, peakObservedDownloadRate.get()), smoothedDownloadRate());
        if (limit > 0) {
            return Math.max(limit, observed);
        }
        return observed;
    }
    private long smoothedDownloadRate() {
        return sessionDownloadThroughput != null ? sessionDownloadThroughput.getAverage() : 0L;
    }

    private long smoothedUploadRate() {
        return sessionUploadThroughput != null ? sessionUploadThroughput.getAverage() : 0L;
    }
    private void rebalanceActiveTorrentBandwidth(List<ManagedTorrent> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastBandwidthRebalanceNanos < BANDWIDTH_REBALANCE_INTERVAL.toNanos()) {
            return;
        }
        lastBandwidthRebalanceNanos = now;

        List<ManagedTorrent> active = new ArrayList<>();
        for (ManagedTorrent managed : snapshot) {
            if (isTorrentActive(managed)) {
                active.add(managed);
            }
        }
        if (active.isEmpty()) {
            return;
        }

        int downloadBudget = downloadSpeedLimit > 0 ? downloadSpeedLimit * 1024 : -1;
        int uploadBudget = uploadSpeedLimit > 0 ? uploadSpeedLimit * 1024 : -1;

        if (downloadBudget > 0) {
            double totalDemand = 0.0;
            for (ManagedTorrent managed : active) {
                if (managed.downloadLimitBytes >= 0) {
                    continue;
                }
                totalDemand += computeDownloadDemand(managed);
            }
            if (totalDemand > 0.0) {
                int minShare = Math.min(MIN_AUTO_DOWNLOAD_LIMIT,
                        Math.max(1, downloadBudget / Math.max(1, active.size())));
                for (ManagedTorrent managed : active) {
                    if (managed.downloadLimitBytes >= 0) {
                        continue;
                    }
                    double demand = computeDownloadDemand(managed);
                    int share = (int) Math.round(downloadBudget * (demand / totalDemand));
                    if (share < minShare) {
                        share = minShare;
                    }
                    if (share > downloadBudget) {
                        share = downloadBudget;
                    }
                    if (managed.lastAutoDownloadLimit != share) {
                        try {
                            managed.handle.setDownloadLimit(share);
                            managed.lastAutoDownloadLimit = share;
                        } catch (Throwable t) {
                            log(Level.FINEST, "No se pudo ajustar el límite dinámico de descarga: " + t.getMessage());
                        }
                    }
                }
            }
        } else {
            for (ManagedTorrent managed : active) {
                if (managed.downloadLimitBytes >= 0) {
                    continue;
                }
                if (managed.lastAutoDownloadLimit != -1) {
                    try {
                        managed.handle.setDownloadLimit(-1);
                    } catch (Throwable t) {
                        log(Level.FINEST, "No se pudo restablecer el límite de descarga: " + t.getMessage());
                    }
                    managed.lastAutoDownloadLimit = -1;
                }
            }
        }

        if (uploadBudget > 0) {
            double totalDemand = 0.0;
            for (ManagedTorrent managed : active) {
                if (managed.uploadLimitBytes >= 0) {
                    continue;
                }
                totalDemand += computeUploadDemand(managed);
            }
            if (totalDemand > 0.0) {
                int minShare = Math.min(MIN_AUTO_UPLOAD_LIMIT,
                        Math.max(1, uploadBudget / Math.max(1, active.size())));
                for (ManagedTorrent managed : active) {
                    if (managed.uploadLimitBytes >= 0) {
                        continue;
                    }
                    double demand = computeUploadDemand(managed);
                    int share = (int) Math.round(uploadBudget * (demand / totalDemand));
                    if (share < minShare) {
                        share = minShare;
                    }
                    if (share > uploadBudget) {
                        share = uploadBudget;
                    }
                    if (managed.lastAutoUploadLimit != share) {
                        try {
                            managed.handle.setUploadLimit(share);
                            managed.lastAutoUploadLimit = share;
                        } catch (Throwable t) {
                            log(Level.FINEST, "No se pudo ajustar el límite dinámico de subida: " + t.getMessage());
                        }
                    }
                }
            }
        } else {
            for (ManagedTorrent managed : active) {
                if (managed.uploadLimitBytes >= 0) {
                    continue;
                }
                if (managed.lastAutoUploadLimit != -1) {
                    try {
                        managed.handle.setUploadLimit(-1);
                    } catch (Throwable t) {
                        log(Level.FINEST, "No se pudo restablecer el límite de subida: " + t.getMessage());
                    }
                    managed.lastAutoUploadLimit = -1;
                }
            }
        }
    }

    private double computeDownloadDemand(ManagedTorrent managed) {
        long remaining = Math.max(1L, managed.stats.totalWanted() - managed.stats.totalWantedDone());
        double backlog = Math.log1p(remaining / (1024.0 * 1024.0));
        double liveRate = Math.log1p(Math.max(0, managed.stats.downloadRate()) / 1024.0);
        return Math.max(1.0, backlog + liveRate);
    }

    private double computeUploadDemand(ManagedTorrent managed) {
        double liveRate = Math.log1p(Math.max(0, managed.stats.uploadRate()) / 1024.0);
        double peers = Math.log1p(Math.max(1, managed.stats.numPeers()));
        return Math.max(1.0, liveRate + (peers * 0.5));
    }

    private int dynamicConnectionsFromRate(long downloadRate, long uploadRate) {
        long basis = Math.max(downloadRate, uploadRate);
        if (downloadSpeedLimit > 0) {
            basis = Math.max(basis, downloadSpeedLimit * 1024L);
        }
        if (basis <= 0) {
            return computeConnectionsLimit();
        }
        long scaled = basis / (64L * 1024L);
        int dynamic = (int) (300 + scaled * 4);
        return clamp(dynamic, 150, MAX_DYNAMIC_CONNECTIONS);
    }

    private int dynamicConnectionSpeed(long downloadRate) {
        long basis = downloadRate;
        if (downloadSpeedLimit > 0) {
            basis = Math.max(basis, downloadSpeedLimit * 1024L);
        }
        if (basis <= 0) {
            return computeConnectionSpeed();
        }
        long scaled = Math.max(1, basis / (128L * 1024L));
        int dynamic = (int) Math.min(500, 30 + scaled * 5);
        return clamp(dynamic, 30, 500);
    }

    private int dynamicRequestQueue(long downloadRate) {
        long basis = downloadRate;
        if (downloadSpeedLimit > 0) {
            basis = Math.max(basis, downloadSpeedLimit * 1024L);
        }
        basis = Math.max(basis, 512L * 1024L);
        int queue = (int) (basis / 1024L);
        return clamp(queue, MIN_DYNAMIC_REQUEST_QUEUE, MAX_DYNAMIC_REQUEST_QUEUE);
    }
    private int estimateSlotsForTargetRate(List<PeerSample> peers, long targetRate) {
        if (peers == null || peers.isEmpty() || targetRate <= 0) {
            return 0;
        }
        long aggregate = 0;
        int counted = 0;
        for (PeerSample sample : peers) {
            if (sample == null) {
                continue;
            }
            if (sample.progress >= 0.999f) {
                continue;
            }
            int observed = Math.max(sample.downSpeed, sample.upSpeed);
            if (observed <= MIN_PEER_SAMPLE_SPEED_BYTES) {
                continue;
            }
            aggregate += observed;
            counted++;
        }
        if (counted < MIN_PEER_SAMPLE_COUNT) {
            return 0;
        }
        long average = Math.max(MIN_PEER_SAMPLE_SPEED_BYTES, aggregate / counted);
        return (int) Math.ceil(targetRate / (double) average);
    }
    private boolean shouldUpdate(int desired, int last, int tolerance) {
        if (desired <= 0) {
            return false;
        }
        if (last < 0) {
            return true;
        }
        return Math.abs(desired - last) >= tolerance;
    }

    private void rebootstrapDhtIfNeeded(long dhtNodes) {
        if (dhtNodes >= LOW_DHT_NODE_THRESHOLD) {
            consecutiveLowDhtSamples.set(0);
            return;
        }

        int samples = consecutiveLowDhtSamples.incrementAndGet();
        if (samples < 3) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastDhtBootstrapTimeMs < DHT_REBOOT_INTERVAL.toMillis()) {
            return;
        }

        lastDhtBootstrapTimeMs = now;
        consecutiveLowDhtSamples.set(0);

        workerExecutor.submit(() -> {
            if (!sessionManager.isRunning()) {
                return;
            }
            try {
                log(Level.INFO, "Reiniciando la DHT para mejorar la conexión de pares (" + dhtNodes + " nodos detectados).");
                if (sessionManager.isDhtRunning()) {
                    sessionManager.stopDht();
                }
                SettingsPack reset = new SettingsPack();
                reset.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), String.join(",", DEFAULT_DHT_NODES));
                sessionManager.applySettings(reset);
                sessionManager.startDht();
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo reiniciar la DHT: " + t.getMessage());
            }
        });
    }

    private void completeTorrent(ManagedTorrent managed) {
        managed.completed = true;
        managed.paused = false;
        managed.stalledPeerChecks.set(0);
        managed.lastAutoDownloadLimit = -1;
        managed.lastAutoUploadLimit = -1;
        TorrentState state = managed.state;
        state.setProgress(100);
        state.setDownloadSpeed(0);
        state.setUploadSpeed(0);
        state.setRemainingTime(0);
        state.setStatus("Completado");
        recordEvent(state, TorrentLogEntry.Step.COMPLETED, Level.INFO,
                "Descarga completada. El torrent ha pasado a estado de compartición.");
        notifyComplete(state);
        if (extractArchives) {
            log(Level.INFO, "Extracción automática no implementada: " + state.getName());
        }
        lastBandwidthRebalanceNanos = 0L;
        startNextIfPossible();
    }

    private long calculateRemainingSeconds(TorrentStatus status) {
        long remaining = Math.max(0L, status.total() - status.totalDone());
        int rate = status.downloadRate();
        if (rate <= 0) {
            return -1;
        }
        return remaining / rate;
    }

    private void updateSequentialDownload(ManagedTorrent managed, boolean sequential) {
        if (managed == null || managed.handle == null || !managed.handle.isValid()) {
            return;
        }
        if (applySequentialDownloadFlag(managed.handle, sequential)) {
            managed.sequentialDownload = sequential;
        }
    }

    private boolean applySequentialDownloadFlag(TorrentHandle handle, boolean sequential) {
        if (handle == null || !handle.isValid()) {
            return false;
        }
        try {
            if (sequential) {
                handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            } else {
                handle.unsetFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD);
            }
            return true;
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo actualizar la descarga secuencial: " + t.getMessage());
            return false;
        }
    }

    private long calculateRequiredSpace(TorrentState state) {
        if (state == null) {
            return MIN_DISK_SPACE;
        }
        long size = state.getFileSize();
        if (size <= 0) {
            return MIN_DISK_SPACE;
        }
        return Math.max(MIN_DISK_SPACE, size);
    }

    private void updateStateFromHandle(ManagedTorrent managed) {
        try {
            TorrentInfo info = managed.handle.torrentFile();
            if (info != null && info.isValid()) {
                managed.state.setFileName(info.name());
                managed.state.setName(info.name());
                managed.state.setFileSize(info.totalSize());
            }
        } catch (Throwable ignored) {
            // Metadata may not be available yet, ignore.
        }
    }

    private boolean hasEnoughDiskSpace(Path destination, long requiredSpace) {
        try {
            FileStore store = Files.getFileStore(destination);
            long free = store.getUsableSpace();
            return free >= Math.max(MIN_DISK_SPACE, requiredSpace);
        } catch (IOException e) {
            log(Level.WARNING, "No se pudo comprobar el espacio en disco: " + e.getMessage());
            return true;
        }
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        double size = bytes / Math.pow(1024, digitGroups);
        return String.format(Locale.ROOT, "%.2f %s", size, units[digitGroups]);
    }

    private void notifyDiskSpace(Path destination, long requiredSpace) {
        try {
            long free = Files.getFileStore(destination).getUsableSpace();
            for (TorrentNotificationListener listener : listeners) {
                listener.onDiskSpaceLow(free, Math.max(MIN_DISK_SPACE, requiredSpace));
            }
        } catch (IOException e) {
            log(Level.WARNING, "No se pudo determinar el espacio en disco: " + e.getMessage());
        }
    }

    private Path ensureDestinationDirectory(String destinationPath) throws IOException {
        Path destination = Paths.get(destinationPath);
        Files.createDirectories(destination);
        return destination;
    }

    private void applyDefaultTrackers(AddTorrentParams params) {
        if (params == null) {
            return;
        }
        List<String> mergedTrackers = mergeTrackers(params.trackers());
        if (!mergedTrackers.isEmpty()) {
            params.trackers(mergedTrackers);
            List<Integer> tiers = new ArrayList<>(mergedTrackers.size());
            for (int i = 0; i < mergedTrackers.size(); i++) {
                tiers.add(i < DEFAULT_TRACKERS.length ? i : DEFAULT_TRACKERS.length);
            }
            try {
                params.trackerTiers(tiers);
            } catch (Throwable ignored) {
                // Algunos builds pueden no permitir modificar los tiers, ignoramos el error.
            }
        }
    }

    private void applyPerformanceHints(AddTorrentParams params) {
        if (params == null) {
            return;
        }
        int active = Math.max(1, maxConcurrentDownloads);
        int globalConnections = clamp(computeConnectionsLimit(), 300, MAX_DYNAMIC_CONNECTIONS);
        int perTorrentConnections = globalConnections / Math.max(1, Math.min(active, 4));
        perTorrentConnections = clamp(perTorrentConnections, 120, globalConnections);
        params.maxConnections(perTorrentConnections);
        params.maxUploads(Math.max(MINIMUM_ACTIVE_SEEDS + 4, perTorrentConnections / 4));

        if (downloadSpeedLimit > 0 && params.downloadLimit() <= 0) {
            int share = Math.max(MIN_AUTO_DOWNLOAD_LIMIT, (downloadSpeedLimit * 1024) / active);
            params.downloadLimit(share);
        }
        if (uploadSpeedLimit > 0 && params.uploadLimit() <= 0) {
            int share = Math.max(MIN_AUTO_UPLOAD_LIMIT, (uploadSpeedLimit * 1024) / active);
            params.uploadLimit(share);
        }
        try {
            torrent_flags_t flags = params.flags();
            if (flags != null) {
                flags = flags.and_(TorrentFlags.AUTO_MANAGED.inv());
                flags = flags.and_(TorrentFlags.STOP_WHEN_READY.inv());
                flags = flags.and_(TorrentFlags.UPLOAD_MODE.inv());
                flags = flags.and_(TorrentFlags.PAUSED.inv());
                params.flags(flags);
            }
        } catch (Throwable ignored) {
            // Algunos builds de libtorrent pueden no exponer las banderas, ignoramos el fallo.
        }
    }
    private int initialConnectionBudget() {
        long baseline = Math.max(smoothedDownloadRate(), expectedSessionDownloadRate());
        if (baseline <= 0 && downloadSpeedLimit <= 0) {
            return clamp(computeConnectionsLimit(), 300, MAX_DYNAMIC_CONNECTIONS);
        }
        long referenceDownload = baseline > 0 ? baseline : downloadSpeedLimit * 1024L;
        long referenceUpload = Math.max(smoothedUploadRate(), 0L);
        return clamp(dynamicConnectionsFromRate(referenceDownload, referenceUpload), 300, MAX_DYNAMIC_CONNECTIONS);
    }

    private void primeNewTorrent(ManagedTorrent managed, AddTorrentParams params) {
        if (managed == null || managed.handle == null || !managed.handle.isValid()) {
            return;
        }
        TorrentHandle handle = managed.handle;
        int connectionBudget = params != null ? params.maxConnections() : 0;
        if (connectionBudget <= 0) {
            connectionBudget = initialConnectionBudget();
        }
        connectionBudget = clamp(connectionBudget, 120, MAX_DYNAMIC_CONNECTIONS);
        try {
            handle.swig().set_max_connections(connectionBudget);
            managed.lastMaxConnections = connectionBudget;
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo preparar el número inicial de conexiones: " + t.getMessage());
        }
        int uploads = Math.max(MINIMUM_ACTIVE_SEEDS + 4, connectionBudget / 4);
        try {
            handle.swig().set_max_uploads(uploads);
            managed.lastMaxUploads = uploads;
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo preparar el número inicial de subidas: " + t.getMessage());
        }
        long now = System.currentTimeMillis();
        managed.lastTrackerAnnounceMs = now;
        managed.lastDhtAnnounceMs = now;
        managed.lastPeerFetchMs = now - DHT_PEER_FETCH_INTERVAL.toMillis();
        try {
            handle.forceReannounce(1);
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo forzar el primer announce: " + t.getMessage());
        }
        try {
            handle.forceDHTAnnounce();
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo forzar el announce en la DHT: " + t.getMessage());
        }
        try {
            sessionManager.dhtAnnounce(managed.infoHash);
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo anunciar el torrent en la DHT global: " + t.getMessage());
        }
        try {
            sessionManager.postTorrentUpdates();
        } catch (Throwable ignored) {
        }
    }

    private List<String> mergeTrackers(List<String> current) {
        Set<String> merged = new LinkedHashSet<>();
        if (current != null) {
            for (String tracker : current) {
                String sanitized = tracker == null ? "" : tracker.trim();
                if (!sanitized.isBlank()) {
                    merged.add(sanitized);
                }
            }
        }
        for (String tracker : DEFAULT_TRACKERS) {
            merged.add(Objects.requireNonNull(tracker, "defaultTracker").trim());
        }
        return new ArrayList<>(merged);
    }

    private AddTorrentParams buildParamsFromFile(Path file, TorrentState state) {
        TorrentInfo info = new TorrentInfo(file.toFile());
        AddTorrentParams params = new AddTorrentParams();
        params.torrentInfo(info);
        params.name(info.name());
        applyDefaultTrackers(params);
        if (state != null) {
            state.setFileSize(info.totalSize());
            state.setFileName(info.name());
            state.setName(info.name());
        }
        return params;
    }

    private AddTorrentParams buildParamsFromMagnet(String magnet, TorrentState state) {
        AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnet);
        params.name(state.getName());
        applyDefaultTrackers(params);
        return params;
    }

    private Path downloadRemoteTorrent(String url, String suggestedName) throws IOException {
        String fileName = (suggestedName == null || suggestedName.isBlank())
                ? "download"
                : suggestedName.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path target = Files.createTempFile(temporaryDirectory, fileName + "-", ".torrent");
        try (InputStream input = new URL(url).openStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
        temporaryTorrentFiles.add(target);
        return target;
    }

    private Path createTemporaryDirectory() {
        try {
            return Files.createTempDirectory("filmoteca-torrents");
        } catch (IOException e) {
            log(Level.WARNING, "No se pudo crear un directorio temporal dedicado: " + e.getMessage());
            return Paths.get(System.getProperty("java.io.tmpdir"));
        }
    }

    private void cleanupTemporaryFiles() {
        for (Path path : temporaryTorrentFiles) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
            }
        }
        temporaryTorrentFiles.clear();
    }

    private String describeState(TorrentStatus.State state) {
        switch (state) {
            case CHECKING_FILES:
            case CHECKING_RESUME_DATA:
                return "Verificando";
            case DOWNLOADING_METADATA:
                return "Obteniendo metadatos";
            case DOWNLOADING:
                return "Descargando";
            case FINISHED:
            case SEEDING:
                return "Completado";
            default:
                return state.name();
        }
    }

    private void notifyComplete(TorrentState state) {
        for (TorrentNotificationListener listener : listeners) {
            listener.onTorrentComplete(state);
        }
    }

    private void notifyError(TorrentState state, String error) {
        if (state != null) {
            state.setStatus("Error");
            recordEvent(state, TorrentLogEntry.Step.ERROR, Level.SEVERE,
                    error != null ? error : "Se produjo un error no especificado.");
        }
        for (TorrentNotificationListener listener : listeners) {
            listener.onTorrentError(state, error);
        }
    }

    private void notifyStatusUpdate(TorrentState state, TorrentStats stats) {
        TorrentStats currentStats = stats != null ? stats : getTorrentStats(state);
        for (TorrentNotificationListener listener : listeners) {
            listener.onTorrentStatusUpdate(state, currentStats);
        }
    }

    private TorrentLogBook logBookForState(TorrentState state) {
        return logsByState.computeIfAbsent(state, ignored -> new TorrentLogBook(MAX_LOG_ENTRIES_PER_TORRENT));
    }

    private void recordEvent(TorrentState state,
                             TorrentLogEntry.Step step,
                             Level level,
                             String message) {
        recordEvent(state, step, level, message, false);
    }

    private void recordEvent(TorrentState state,
                             TorrentLogEntry.Step step,
                             Level level,
                             String message,
                             boolean fromHealthCheck) {
        if (state != null) {
            TorrentLogBook book = logBookForState(state);
            book.add(new TorrentLogEntry(System.currentTimeMillis(), level, step, message, fromHealthCheck));
        }
        log(level, '[' + step.getDisplayName() + "] " + message);
    }

    private void log(Level level, String message) {
        LOGGER.log(level, message);
        for (TorrentNotificationListener listener : listeners) {
            listener.onDebugMessage(message, level);
        }
    }

    private final AlertListener alertListener = new AlertListener() {
        @Override
        public int[] types() {
            return null; // Listen to all alerts.
        }

        @Override
        public void alert(Alert<?> alert) {
            if (alert instanceof TorrentFinishedAlert) {
                TorrentHandle handle = ((TorrentFinishedAlert) alert).handle();
                ManagedTorrent managed = findManagedTorrent(handle);
                if (managed != null) {
                    completeTorrent(managed);
                }
            } else if (alert instanceof TorrentErrorAlert) {
                TorrentErrorAlert errorAlert = (TorrentErrorAlert) alert;
                ManagedTorrent managed = findManagedTorrent(errorAlert.handle());
                if (managed != null) {
                    managed.state.setStatus("Error");
                    notifyError(managed.state, errorAlert.message());
                }
            } else if (alert instanceof MetadataReceivedAlert) {
                MetadataReceivedAlert metadataAlert = (MetadataReceivedAlert) alert;
                ManagedTorrent managed = findManagedTorrent(metadataAlert.handle());
                if (managed != null) {
                    recordEvent(managed.state, TorrentLogEntry.Step.VALIDATION, Level.INFO,
                            "Metadatos recibidos correctamente.");
                    updateStateFromHandle(managed);
                }
            } else if (alert instanceof StateChangedAlert) {
                StateChangedAlert stateAlert = (StateChangedAlert) alert;
                ManagedTorrent managed = findManagedTorrent(stateAlert.handle());
                if (managed != null && !managed.paused) {
                    managed.state.setStatus(describeState(stateAlert.getState()));
                }
            } else if (alert instanceof StateUpdateAlert) {
                StateUpdateAlert updateAlert = (StateUpdateAlert) alert;
                for (TorrentStatus status : updateAlert.status()) {
                    ManagedTorrent managed = findManagedTorrent(status);
                    if (managed != null) {
                        updateManagedTorrent(managed, status);
                    }
                }
            }
        }
    };

    private ManagedTorrent findManagedTorrent(TorrentStatus status) {
        if (status == null) {
            return null;
        }
        Sha1Hash hash = safeStatusHash(status);
        if (hash != null) {
            ManagedTorrent managed = findManagedTorrent(hash);
            if (managed != null) {
                return managed;
            }
        }

        String statusName = status.name();
        if (statusName != null && !statusName.isBlank()) {
            synchronized (lock) {
                for (ManagedTorrent candidate : managedByState.values()) {
                    String candidateName = candidate.state.getName();
                    if (candidateName != null && candidateName.equals(statusName)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    private Sha1Hash safeStatusHash(TorrentStatus status) {
        if (status == null) {
            return null;
        }
        try {
            Sha1Hash hash = status.infoHash();
            if (hash != null && !hash.isAllZeros()) {
                return hash;
            }
        } catch (Throwable t) {
            log(Level.FINEST, "No se pudo leer el hash del estado del torrent: " + t.getMessage());
        }
        return null;
    }

    private ManagedTorrent findManagedTorrent(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) {
            return null;
        }
        return findManagedTorrent(handle.infoHash());
    }

    private ManagedTorrent findManagedTorrent(Sha1Hash hash) {
        if (hash == null) {
            return null;
        }
        return managedByHash.get(hash.toString());
    }

    private ManagedTorrent findManagedTorrent(InfoHash infoHash) {
        if (infoHash == null) {
            return null;
        }
        Sha1Hash best = infoHash.getBest();
        if (best == null) {
            return null;
        }
        return managedByHash.get(best.toString());
    }

    private static ThreadFactory daemonThreadFactory(String baseName) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, baseName + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Wraps a queued torrent prior to being added to jlibtorrent. */
    private static final class PendingTorrent {
        private final TorrentState state;
        private final Supplier<AddTorrentParams> paramsSupplier;
        private volatile boolean sequentialDownload;
        private volatile int downloadLimitBytes = -1;
        private volatile int uploadLimitBytes = -1;

        private PendingTorrent(TorrentState state, Supplier<AddTorrentParams> paramsSupplier) {
            this.state = state;
            this.paramsSupplier = paramsSupplier;
        }

        private int getPriority() {
            return state.getPriority();
        }

        private boolean isSequentialDownload() {
            return sequentialDownload;
        }

        private void setSequentialDownload(boolean sequentialDownload) {
            this.sequentialDownload = sequentialDownload;
        }

        private void setDownloadLimitBytes(int downloadLimitBytes) {
            this.downloadLimitBytes = downloadLimitBytes;
        }

        private int getDownloadLimitBytes() {
            return downloadLimitBytes;
        }

        private boolean hasCustomDownloadLimit() {
            return downloadLimitBytes >= 0;
        }

        private void setUploadLimitBytes(int uploadLimitBytes) {
            this.uploadLimitBytes = uploadLimitBytes;
        }

        private int getUploadLimitBytes() {
            return uploadLimitBytes;
        }

        private boolean hasCustomUploadLimit() {
            return uploadLimitBytes >= 0;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            PendingTorrent that = (PendingTorrent) obj;
            return state == that.state;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(state);
        }
    }

    private static final class PeerSample {
        private final String endpoint;
        private final int downSpeed;
        private final int upSpeed;
        private final float progress;

        private PeerSample(String endpoint, int downSpeed, int upSpeed, float progress) {
            this.endpoint = endpoint;
            this.downSpeed = downSpeed;
            this.upSpeed = upSpeed;
            this.progress = progress;
        }
    }
    private static final class SlowPeerRecord {
        private final long firstSlow;
        private volatile long lastSlow;

        private SlowPeerRecord(long timestamp) {
            this.firstSlow = timestamp;
            this.lastSlow = timestamp;
        }

        private void markSeen(long timestamp) {
            this.lastSlow = timestamp;
        }

        private boolean isConfirmed(long timestamp) {
            return timestamp - firstSlow >= SLOW_PEER_SAMPLE_GRACE_MS;
        }

        private boolean isExpired(long timestamp) {
            return timestamp - lastSlow > SLOW_PEER_BACKOFF_MS;
        }
    }
    private static final class ThroughputAverager {
        private final long windowMillis;
        private final Deque<Sample> samples;
        private long total;

        private ThroughputAverager(long windowMillis) {
            this.windowMillis = Math.max(1L, windowMillis);
            this.samples = new ArrayDeque<>();
            this.total = 0L;
        }

        private synchronized void addSample(long value) {
            long sanitized = Math.max(0L, value);
            long now = System.currentTimeMillis();
            samples.addLast(new Sample(now, sanitized));
            total += sanitized;
            trim(now);
        }

        private synchronized long getAverage() {
            trim(System.currentTimeMillis());
            if (samples.isEmpty()) {
                return 0L;
            }
            return total / samples.size();
        }

        private void trim(long now) {
            while (!samples.isEmpty()) {
                Sample head = samples.peekFirst();
                if (head == null || now - head.timestamp <= windowMillis) {
                    break;
                }
                samples.removeFirst();
                total -= head.value;
            }
        }

        private static final class Sample {
            private final long timestamp;
            private final long value;

            private Sample(long timestamp, long value) {
                this.timestamp = timestamp;
                this.value = value;
            }
        }
    }

    /** Holds runtime information of an active torrent. */
    private static final class ManagedTorrent {
        private final TorrentState state;
        private final TorrentHandle handle;
        private final TorrentStats stats;
        private final Sha1Hash infoHash;
        private final String infoHashKey;
        private volatile boolean paused;
        private volatile boolean completed;
        private volatile boolean sequentialDownload;
        private volatile int downloadLimitBytes = -1;
        private volatile int uploadLimitBytes = -1;
        private volatile int lastMaxConnections;
        private volatile int lastMaxUploads;
        private volatile int lastAutoDownloadLimit;
        private volatile int lastAutoUploadLimit;
        private volatile long lastPeerOptimizationMs;
        private final AtomicInteger stalledPeerChecks;
        private volatile long lastTrackerAnnounceMs;
        private volatile long lastDhtAnnounceMs;
        private volatile long lastTrackerInjectionMs;
        private volatile long lastPeerFetchMs;
        private final Set<String> contactedPeers;
        private final ConcurrentHashMap<String, SlowPeerRecord> slowPeers;
        private volatile List<PeerSample> lastPeerSamples;
        private ManagedTorrent(TorrentState state, TorrentHandle handle, Sha1Hash infoHash) {
            this.state = state;
            this.handle = handle;
            this.infoHash = infoHash;
            this.infoHashKey = infoHash.toString();
            this.stats = new TorrentStats(infoHash, 120);
            this.paused = false;
            this.completed = false;
            this.sequentialDownload = false;
            this.lastMaxConnections = -1;
            this.lastMaxUploads = -1;
            this.lastAutoDownloadLimit = -1;
            this.lastAutoUploadLimit = -1;
            this.lastPeerOptimizationMs = 0L;
            this.stalledPeerChecks = new AtomicInteger();
            this.lastTrackerAnnounceMs = 0L;
            this.lastDhtAnnounceMs = 0L;
            this.lastTrackerInjectionMs = 0L;
            this.lastPeerFetchMs = 0L;
            this.contactedPeers = ConcurrentHashMap.newKeySet();
            this.slowPeers = new ConcurrentHashMap<>();
            this.lastPeerSamples = Collections.emptyList();
        }
    }
}
