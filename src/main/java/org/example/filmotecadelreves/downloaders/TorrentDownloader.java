package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.moviesad.TorrentState;

import com.frostwire.jlibtorrent.AddTorrentParams;
import com.frostwire.jlibtorrent.AlertListener;
import com.frostwire.jlibtorrent.InfoHash;
import com.frostwire.jlibtorrent.SessionHandle;
import com.frostwire.jlibtorrent.SessionManager;
import com.frostwire.jlibtorrent.SessionParams;
import com.frostwire.jlibtorrent.SettingsPack;
import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.TorrentHandle;
import com.frostwire.jlibtorrent.TorrentInfo;
import com.frostwire.jlibtorrent.TorrentStatus;
import com.frostwire.jlibtorrent.alerts.Alert;
import com.frostwire.jlibtorrent.alerts.MetadataReceivedAlert;
import com.frostwire.jlibtorrent.alerts.StateChangedAlert;
import com.frostwire.jlibtorrent.alerts.StateUpdateAlert;
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert;
import com.frostwire.jlibtorrent.alerts.TorrentFinishedAlert;
import com.frostwire.jlibtorrent.swig.settings_pack;
import com.frostwire.jlibtorrent.swig.error_code;

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
import java.util.concurrent.ConcurrentHashMap;
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
    private final ConcurrentHashMap<String, ManagedTorrent> managedByHash;
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
        this.managedByHash = new ConcurrentHashMap<>();
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
        return settings;
    }

    private void populatePerformanceSettings(SettingsPack settings) {
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), maxConcurrentDownloads);
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), maxConcurrentDownloads);
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), maxConcurrentDownloads * 2);
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
        if (managed != null && managed.handle.isValid()) {
            try {
                managed.handle.setSequentialDownload(sequential);
                managed.sequentialDownload = sequential;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo actualizar la descarga secuencial: " + t.getMessage());
            }
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
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo establecer el límite de descarga del torrent: " + t.getMessage());
            }
            try {
                managed.handle.setUploadLimit(uploadBytes);
                managed.uploadLimitBytes = uploadBytes;
            } catch (Throwable t) {
                log(Level.FINEST, "No se pudo establecer el límite de subida del torrent: " + t.getMessage());
            }
        }
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
                log(Level.WARNING, "El torrent ya se está descargando: " + state.getName());
                return;
            }
            PendingTorrent previous = pendingByState.put(state, pendingTorrent);
            if (previous != null) {
                pendingQueue.remove(previous);
            }
            if (autoStartDownloads) {
                pendingQueue.offerLast(pendingTorrent);
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

            Path destination = ensureDestinationDirectory(destinationPath);
            long requiredSpace = calculateRequiredSpace(state);
            if (!hasEnoughDiskSpace(destination, requiredSpace)) {
                state.setStatus("Pausado (Espacio insuficiente)");
                notifyDiskSpace(destination, requiredSpace);
                return;
            }

            params.setSavePath(destination.toAbsolutePath().toString());
            if (params.getName() == null || params.getName().isBlank()) {
                params.setName(state.getName());
            }
            if (params.getTrackers().isEmpty()) {
                params.setTrackers(Arrays.asList(DEFAULT_TRACKERS));
            }

            TorrentHandle handle = addTorrentToSession(params);
            Sha1Hash bestHash = resolveInfoHash(handle, params);
            ManagedTorrent managed = new ManagedTorrent(state, handle, bestHash);
            managed.sequentialDownload = pending.isSequentialDownload();
            if (managed.sequentialDownload) {
                try {
                    handle.setSequentialDownload(true);
                } catch (Throwable t) {
                    log(Level.FINEST, "No se pudo activar la descarga secuencial: " + t.getMessage());
                }
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

            state.setStatus("Descargando");
            state.setTorrentId(bestHash.toHex());
            state.setHash(bestHash.toHex());
            updateStateFromHandle(managed);
            notifyStatusUpdate(state, managed.stats);
            log(Level.INFO, "Torrent iniciado: " + state.getName());
            requestTorrentStatusUpdates();
        } catch (Exception e) {
            state.setStatus("Error");
            log(Level.SEVERE, "Error al iniciar el torrent " + state.getName() + ": " + e.getMessage());
            notifyError(state, e.getMessage());
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
        for (ManagedTorrent managed : snapshot) {
            if (!managed.handle.isValid()) {
                synchronized (lock) {
                    managedByState.remove(managed.state);
                    managedByHash.remove(managed.infoHashKey);
                }
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

    private AddTorrentParams buildParamsFromFile(Path file, TorrentState state) {
        TorrentInfo info = new TorrentInfo(file.toFile());
        AddTorrentParams params = new AddTorrentParams();
        params.setTorrentInfo(info);
        params.setName(info.name());
        params.setTrackers(Arrays.asList(DEFAULT_TRACKERS));
        if (state != null) {
            state.setFileSize(info.totalSize());
            state.setFileName(info.name());
            state.setName(info.name());
        }
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
        if (state != null) {
            state.setStatus("Error");
        }
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

        private ManagedTorrent(TorrentState state, TorrentHandle handle, Sha1Hash infoHash) {
            this.state = state;
            this.handle = handle;
            this.infoHash = infoHash;
            this.infoHashKey = infoHash.toString();
            this.stats = new TorrentStats(infoHash, 120);
            this.paused = false;
            this.completed = false;
            this.sequentialDownload = false;
        }
    }
}
