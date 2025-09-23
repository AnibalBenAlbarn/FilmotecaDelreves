package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.moviesad.TorrentState;
import org.libtorrent4j.AddTorrentParams;
import org.libtorrent4j.AlertListener;
import org.libtorrent4j.InfoHash;
import org.libtorrent4j.SessionManager;
import org.libtorrent4j.SessionParams;
import org.libtorrent4j.SettingsPack;
import org.libtorrent4j.Sha1Hash;
import org.libtorrent4j.TorrentHandle;
import org.libtorrent4j.TorrentInfo;
import org.libtorrent4j.TorrentStatus;
import org.libtorrent4j.alerts.Alert;
import org.libtorrent4j.alerts.MetadataReceivedAlert;
import org.libtorrent4j.alerts.StateChangedAlert;
import org.libtorrent4j.alerts.StateUpdateAlert;
import org.libtorrent4j.alerts.TorrentErrorAlert;
import org.libtorrent4j.alerts.TorrentFinishedAlert;
import org.libtorrent4j.swig.error_code;
import org.libtorrent4j.swig.settings_pack;

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
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal torrent downloader built on top of libtorrent4j.
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

    private static final String[] DEFAULT_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce"
    };

    private static final String[] DEFAULT_DHT_NODES = {
            "router.bittorrent.com:6881",
            "router.utorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "dht.aelitis.com:6881"
    };

    private final SessionManager sessionManager;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workerExecutor;
    private final Deque<PendingTorrent> pendingQueue;
    private final Map<TorrentState, PendingTorrent> pendingByState;
    private final Map<TorrentState, ManagedTorrent> managedByState;
    private final Map<String, ManagedTorrent> managedByHash;
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

        this.sessionManager = new SessionManager();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("torrent-status"));
        this.workerExecutor = Executors.newCachedThreadPool(daemonThreadFactory("torrent-worker"));
        this.pendingQueue = new ArrayDeque<>();
        this.pendingByState = new java.util.HashMap<>();
        this.managedByState = new java.util.HashMap<>();
        this.managedByHash = new java.util.HashMap<>();
        this.listeners = new CopyOnWriteArrayList<>();
        this.temporaryTorrentFiles = new CopyOnWriteArrayList<>();
        this.temporaryDirectory = createTemporaryDirectory();

        startSession();
        this.running = true;
        scheduler.scheduleAtFixedRate(this::refreshStatuses,
                STATUS_UPDATE_PERIOD_SECONDS,
                STATUS_UPDATE_PERIOD_SECONDS,
                TimeUnit.SECONDS);

        log(Level.INFO, "TorrentDownloader inicializado con soporte para "
                + this.maxConcurrentDownloads + " descargas simultáneas.");
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
        applyRateLimits();
    }

    private SettingsPack buildDefaultSettings() {
        SettingsPack settings = new SettingsPack();
        settings.listenInterfaces("0.0.0.0:6881,[::]:6881");
        settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true);
        settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true);
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), maxConcurrentDownloads);
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), maxConcurrentDownloads);
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), maxConcurrentDownloads * 2);
        settings.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), String.join(",", DEFAULT_DHT_NODES));
        return settings;
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
        applyRateLimits();
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

    public void addNotificationListener(TorrentNotificationListener listener) {
        if (listener != null) {
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
                }
            } else {
                PendingTorrent pending = pendingByState.get(torrentState);
                if (pending != null) {
                    pendingQueue.remove(pending);
                    torrentState.setStatus("Pausado");
                }
            }
        }
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
                }
            } else {
                PendingTorrent pending = pendingByState.get(torrentState);
                if (pending != null && !pendingQueue.contains(pending)) {
                    pendingQueue.offerFirst(pending);
                    torrentState.setStatus("En espera");
                    shouldStart = true;
                }
            }
        }
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
            if (managed != null && managed.handle.isValid()) {
                managedByHash.remove(managed.handle.infoHash().toString());
            }
        }
        if (managed != null && managed.handle.isValid()) {
            if (deleteFiles) {
                sessionManager.remove(managed.handle, org.libtorrent4j.SessionHandle.DELETE_FILES);
            } else {
                sessionManager.remove(managed.handle);
            }
        }
        torrentState.setStatus("Eliminado");
        startNextIfPossible();
    }

    /** Gracefully shutdown libtorrent and background executors. */
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
            applyRateLimits();
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
                log(Level.WARNING, "El torrent ya se está descargando: " + state.getName());
                return;
            }
            PendingTorrent previous = pendingByState.put(state, pendingTorrent);
            if (previous != null) {
                pendingQueue.remove(previous);
            }
            if (autoStartDownloads) {
                pendingQueue.offer(pendingTorrent);
                state.setStatus("En espera");
            } else {
                state.setStatus("Pausado");
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
                PendingTorrent pending = pendingQueue.poll();
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
            Path destination = ensureDestinationDirectory(state.getDestinationPath());
            if (!hasEnoughDiskSpace(destination)) {
                state.setStatus("Pausado (Espacio insuficiente)");
                notifyDiskSpace(destination);
                return;
            }

            AddTorrentParams params = pending.paramsSupplier.get();
            params.setSavePath(destination.toAbsolutePath().toString());
            if (params.getName() == null || params.getName().isBlank()) {
                params.setName(state.getName());
            }
            if (params.getTrackers().isEmpty()) {
                params.setTrackers(Arrays.asList(DEFAULT_TRACKERS));
            }

            error_code ec = new error_code();
            TorrentHandle handle = new TorrentHandle(sessionManager.swig().add_torrent(params.swig(), ec));
            if (ec.value() != 0 || !handle.isValid()) {
                throw new IllegalStateException(ec.message());
            }

            ManagedTorrent managed = new ManagedTorrent(state, handle);
            synchronized (lock) {
                pendingByState.remove(state);
                managedByState.put(state, managed);
                managedByHash.put(handle.infoHash().toString(), managed);
            }

            state.setStatus("Descargando");
            state.setTorrentId(handle.infoHash().toString());
            state.setHash(handle.infoHash().toHex());
            updateStateFromHandle(managed);
            notifyStatusUpdate(state, managed.stats);
            log(Level.INFO, "Torrent iniciado: " + state.getName());
        } catch (Exception e) {
            log(Level.SEVERE, "Error al iniciar el torrent " + state.getName() + ": " + e.getMessage());
            notifyError(state, e.getMessage());
            synchronized (lock) {
                pendingByState.remove(state);
            }
        }
    }

    private void refreshStatuses() {
        if (!running) {
            return;
        }
        List<ManagedTorrent> snapshot = new ArrayList<>(managedByState.values());
        for (ManagedTorrent managed : snapshot) {
            if (!managed.handle.isValid()) {
                continue;
            }
            try {
                TorrentStatus status = managed.handle.status();
                updateManagedTorrent(managed, status);
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo actualizar el estado del torrent: " + t.getMessage());
            }
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

        notifyStatusUpdate(state, managed.stats);
    }

    private void completeTorrent(ManagedTorrent managed) {
        managed.completed = true;
        managed.paused = false;
        TorrentState state = managed.state;
        state.setProgress(100);
        state.setDownloadSpeed(0);
        state.setUploadSpeed(0);
        state.setRemainingTime(0);
        state.setStatus("Completado");
        notifyComplete(state);
        if (extractArchives) {
            log(Level.INFO, "Extracción automática no implementada: " + state.getName());
        }
        startNextIfPossible();
    }

    private long calculateRemainingSeconds(TorrentStatus status) {
        long remaining = Math.max(0L, status.total() - status.totalDone());
        int rate = status.downloadRate();
        if (rate <= 0) {
            return -1;
        }
        return Duration.ofSeconds(remaining / Math.max(1, rate)).getSeconds();
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

    private boolean hasEnoughDiskSpace(Path destination) {
        try {
            FileStore store = Files.getFileStore(destination);
            long free = store.getUsableSpace();
            return free >= MIN_DISK_SPACE;
        } catch (IOException e) {
            log(Level.WARNING, "No se pudo comprobar el espacio en disco: " + e.getMessage());
            return true;
        }
    }

    private void notifyDiskSpace(Path destination) {
        try {
            long free = Files.getFileStore(destination).getUsableSpace();
            for (TorrentNotificationListener listener : listeners) {
                listener.onDiskSpaceLow(free, MIN_DISK_SPACE);
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

    private AddTorrentParams buildParamsFromFile(Path file, TorrentState state) {
        TorrentInfo info = new TorrentInfo(file.toFile());
        AddTorrentParams params = new AddTorrentParams();
        params.setTorrentInfo(info);
        params.setName(info.name());
        params.setTrackers(Arrays.asList(DEFAULT_TRACKERS));
        return params;
    }

    private AddTorrentParams buildParamsFromMagnet(String magnet, TorrentState state) {
        AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnet);
        params.setName(state.getName());
        params.setTrackers(Arrays.asList(DEFAULT_TRACKERS));
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
        for (TorrentNotificationListener listener : listeners) {
            listener.onTorrentError(state, error);
        }
    }

    private void notifyStatusUpdate(TorrentState state, TorrentStats stats) {
        for (TorrentNotificationListener listener : listeners) {
            listener.onTorrentStatusUpdate(state, stats);
        }
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
                    updateStateFromHandle(managed);
                }
            } else if (alert instanceof StateChangedAlert) {
                StateChangedAlert stateAlert = (StateChangedAlert) alert;
                ManagedTorrent managed = findManagedTorrent(stateAlert.handle());
                if (managed != null && !managed.paused) {
                    managed.state.setStatus(describeState(stateAlert.state()));
                }
            } else if (alert instanceof StateUpdateAlert) {
                StateUpdateAlert updateAlert = (StateUpdateAlert) alert;
                for (TorrentStatus status : updateAlert.status()) {
                    ManagedTorrent managed = findManagedTorrent(status.getInfoHashes().getBest());
                    if (managed != null) {
                        updateManagedTorrent(managed, status);
                    }
                }
            }
        }
    };

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
        return managedByHash.get(infoHash.getBest().toString());
    }

    private static ThreadFactory daemonThreadFactory(String baseName) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, baseName + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Wraps a queued torrent prior to being added to libtorrent. */
    private static final class PendingTorrent {
        private final TorrentState state;
        private final Supplier<AddTorrentParams> paramsSupplier;

        private PendingTorrent(TorrentState state, Supplier<AddTorrentParams> paramsSupplier) {
            this.state = state;
            this.paramsSupplier = paramsSupplier;
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

    /** Holds runtime information of an active torrent. */
    private static final class ManagedTorrent {
        private final TorrentState state;
        private final TorrentHandle handle;
        private final TorrentStats stats;
        private volatile boolean paused;
        private volatile boolean completed;

        private ManagedTorrent(TorrentState state, TorrentHandle handle) {
            this.state = state;
            this.handle = handle;
            this.stats = new TorrentStats(handle.infoHash(), 120);
            this.paused = false;
            this.completed = false;
        }
    }
}
