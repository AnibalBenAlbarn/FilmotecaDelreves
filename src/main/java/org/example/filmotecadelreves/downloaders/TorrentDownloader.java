package org.example.filmotecadelreves.downloaders;

import org.libtorrent4j.*;
import org.libtorrent4j.alerts.*;
import org.libtorrent4j.swig.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.example.filmotecadelreves.moviesad.TorrentState;
//ver1.3
// Apache Commons IO and Commons Compress imports
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;

// For RAR files
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * TorrentDownloader - Clase para gestionar descargas de torrents
 *
 * Esta clase proporciona funcionalidades para:
 * - Descargar archivos torrent desde URLs o enlaces magnet
 * - Gestionar una cola de descargas
 * - Monitorear el progreso de las descargas
 * - Extraer archivos comprimidos automáticamente
 * - Gestionar el espacio en disco
 * - Notificar eventos importantes
 * - Descargar múltiples torrents simultáneamente
 * - Proporcionar información detallada de depuración
 */
public class TorrentDownloader {

//==========================================================================
// CONSTANTES Y VARIABLES DE CLASE
//==========================================================================

    /** Interfaz para notificaciones de eventos de torrent */
    public interface TorrentNotificationListener {
        void onTorrentComplete(TorrentState torrentState);
        void onTorrentError(TorrentState torrentState, String errorMessage);
        void onDiskSpaceLow(long availableSpace, long requiredSpace);
        void onDebugMessage(String message, Level level);
        void onTorrentStatusUpdate(TorrentState torrentState, TorrentStats stats);
    }

    /** Espacio mínimo en disco requerido (en bytes) - 500 MB por defecto */
    private static final long MIN_DISK_SPACE = 500 * 1024 * 1024;

    /** Intervalo de comprobación de espacio en disco (en segundos) */
    private static final int DISK_CHECK_INTERVAL = 60;

    /** Intervalo para actualizar el estado de los torrents (en milisegundos) */
    private static final int STATUS_UPDATE_INTERVAL = 1000;

    /** Intervalo para reintento de trackers con error (en segundos) */
    private static final int TRACKER_RETRY_INTERVAL = 300; // 5 minutos

    /** Número máximo de entradas de log a mantener en memoria */
    private static final int MAX_LOG_ENTRIES = 1000;

    /** Niveles de detalle para el logging */
    public enum LogLevel {
        DEBUG, INFO, WARNING, ERROR, CRITICAL
    }

    /** Logger personalizado */
    private static final Logger LOGGER = Logger.getLogger(TorrentDownloader.class.getName());

    /** Lista de trackers públicos actualizados */
    private static final String[] PUBLIC_TRACKERS = {
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.demonii.com:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "http://tracker.skyts.net:6969/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "udp://tracker.ololosh.space:6969/announce",
            "udp://explodie.org:6969/announce",
            "http://www.torrentsnipe.info:2701/announce",
            "http://tracker810.xyz:11450/announce",
            "http://tracker.vanitycore.co:6969/announce",
            "http://tracker.lintk.me:2710/announce",
            "http://share.hkg-fansub.info:80/announce.php",
            "http://retracker.spark-rostov.ru:80/announce",
            "http://open.trackerlist.xyz:80/announce",
            "http://buny.uk:6969/announce",
            "http://bt1.xxxxbt.cc:6969/announce",
            "udp://wepzone.net:6969/announce",
            "udp://ttk2.nbaonlineservice.com:6969/announce",
            "udp://tracker.openbittorrent.com:80/announce",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.bt4g.com:2710/announce",
            "udp://tracker.dler.org:6969/announce",
            "udp://9.rarbg.me:2810/announce",
            "udp://tracker.internetwarriors.net:1337/announce",
// Nuevos trackers adicionales para mejorar la conectividad
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.leechers-paradise.org:6969/announce",
            "udp://tracker.coppersurfer.tk:6969/announce",
            "udp://tracker.pirateparty.gr:6969/announce",
            "udp://tracker.cyberia.is:6969/announce",
            "udp://tracker.birkenwald.de:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://retracker.lanta-net.ru:2710/announce",
            "udp://tracker.nyaa.uk:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.pomf.se:80/announce",
            "udp://tracker.altrosky.nl:6969/announce",
            "udp://tracker.bitsearch.to:1337/announce",
            "udp://tracker.theoks.net:6969/announce",
            "udp://tracker.auctor.tv:6969/announce",
            "udp://tracker.leech.ie:1337/announce",
            "udp://tracker.moeking.me:6969/announce",
            "udp://tracker.openbittorrent.com:6969/announce",
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://tracker.tiny-vps.com:6969/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://tracker.zerobytes.xyz:1337/announce",
            "udp://valakas.rollo.dnsabr.com:2710/announce",
            "udp://zephir.monocul.us:6969/announce"
    };

    /** Nodos DHT para mejorar la conectividad */
    private static final String[] DHT_BOOTSTRAP_NODES = {
            "router.bittorrent.com:6881",
            "dht.libtorrent.org:25401",
            "router.utorrent.com:6881",
            "dht.transmissionbt.com:6881",
            "dht.aelitis.com:6881",
            "router.bitcomet.com:6881"
    };

//==========================================================================
// VARIABLES DE INSTANCIA
//==========================================================================

    private SessionManager sessionManager;
    private ExecutorService executorService;
    private ScheduledExecutorService scheduledExecutorService;
    private List<TorrentState> queue;
    private AtomicBoolean isRunning;
    private boolean autoStartDownloads;
    private boolean extractArchives;
    private int downloadSpeedLimit;
    private int uploadSpeedLimit;
    private TorrentState currentDownload;
    private String torrentFilePath;
    private List<TorrentNotificationListener> notificationListeners;

    // Sistema de logging mejorado
    private List<LogEntry> logEntries;
    private boolean verboseLogging;
    private boolean consoleLogging;
    private int debugLevel;

//==========================================================================
// SOPORTE PARA DESCARGAS SIMULTÁNEAS
//==========================================================================

    /** Número máximo de descargas simultáneas permitidas */
    private int maxConcurrentDownloads;

    /** Mapa para rastrear las descargas activas por infoHash */
    private Map<String, TorrentState> activeDownloads;

    /** Mapa para rastrear las estadísticas detalladas por infoHash */
    private Map<String, TorrentStats> torrentStats;

    /** Contador de descargas activas */
    private int activeDownloadsCount;

    /** Lock para sincronizar el acceso al contador de descargas activas */
    private final Object activeDownloadsLock = new Object();

//==========================================================================
// SOPORTE PARA GESTIÓN DE TRACKERS
//==========================================================================

    /** Mapa para rastrear trackers problemáticos por infoHash */
    private Map<String, Set<String>> problematicTrackers;

    /** Mapa para rastrear los trackers originales de cada torrent */
    private Map<String, List<String>> originalTrackers;

//==========================================================================
// CLASE PARA ENTRADAS DE LOG
//==========================================================================

    public static class LogEntry {
        private final long timestamp;
        private final Level level;
        private final String message;
        private final String source;

        public LogEntry(Level level, String message, String source) {
            this.timestamp = System.currentTimeMillis();
            this.level = level;
            this.message = message;
            this.source = source;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public Level getLevel() {
            return level;
        }

        public String getMessage() {
            return message;
        }

        public String getSource() {
            return source;
        }

        @Override
        public String toString() {
            return String.format("[%tF %tT] [%s] [%s] %s",
                    timestamp, timestamp, level.getName(), source, message);
        }
    }

//==========================================================================
// CONSTRUCTOR
//==========================================================================

    /**
     * Constructor de TorrentDownloader
     *
     * @param maxConcurrentDownloads Número máximo de descargas concurrentes
     * @param extractArchives Si se deben extraer archivos comprimidos automáticamente
     * @param downloadSpeedLimit Límite de velocidad de descarga en KB/s (0 = sin límite)
     * @param uploadSpeedLimit Límite de velocidad de subida en KB/s (0 = sin límite)
     * @param verboseLogging Si se debe activar el logging detallado
     * @param consoleLogging Si se debe mostrar el log en consola además de notificarlo
     */
    public TorrentDownloader(int maxConcurrentDownloads, boolean extractArchives,
                             int downloadSpeedLimit, int uploadSpeedLimit, boolean verboseLogging, boolean consoleLogging) {
        this.maxConcurrentDownloads = Math.max(1, maxConcurrentDownloads); // Asegurar al menos 1
        this.extractArchives = extractArchives;
        this.downloadSpeedLimit = downloadSpeedLimit;
        this.uploadSpeedLimit = uploadSpeedLimit;
        this.autoStartDownloads = true;
        this.queue = new ArrayList<>();
        this.isRunning = new AtomicBoolean(false);
        this.executorService = Executors.newFixedThreadPool(this.maxConcurrentDownloads); // Usar un pool con el número máximo de descargas
        this.scheduledExecutorService = Executors.newScheduledThreadPool(2); // Uno para monitoreo de disco y otro para actualizaciones de estado
        this.currentDownload = null;
        this.notificationListeners = new ArrayList<>();
        this.activeDownloads = new HashMap<>();
        this.activeDownloadsCount = 0;
        this.problematicTrackers = new HashMap<>();
        this.originalTrackers = new HashMap<>();
        this.torrentStats = new HashMap<>();
        this.logEntries = new ArrayList<>();
        this.verboseLogging = verboseLogging;
        this.consoleLogging = consoleLogging;
        this.debugLevel = 2; // Nivel bajo por defecto para reducir logs

        initSessionManager();
        startDiskSpaceMonitoring();
        startStatusUpdateScheduler();

// Registrar el listener de alertas para gestionar múltiples descargas
        registerAlertListener();

// Log de inicialización
        log(Level.INFO, "TorrentDownloader inicializado con soporte para " + maxConcurrentDownloads +
                " descargas simultáneas. Logging detallado: " + (verboseLogging ? "activado" : "desactivado"), "init");
    }

    /**
     * Constructor simplificado
     */
    public TorrentDownloader(int maxConcurrentDownloads, boolean extractArchives,
                             int downloadSpeedLimit, int uploadSpeedLimit) {
        this(maxConcurrentDownloads, extractArchives, downloadSpeedLimit, uploadSpeedLimit, false, false);
    }

//==========================================================================
// INICIALIZACIÓN Y CONFIGURACIÓN
//==========================================================================

    /**
     * Inicializa el SessionManager de libtorrent con la configuración básica
     */
    private void initSessionManager() {
        try {
            log(Level.INFO, "Inicializando SessionManager con configuración optimizada...", "init");

// Configurar SettingsPack básico
            SettingsPack settings = new SettingsPack();

// Configuración de red
            settings.listenInterfaces("0.0.0.0:6881,[::]:6881");

// Habilitar protocolos de descubrimiento de pares
            settings.setBoolean(settings_pack.bool_types.enable_dht.swigValue(), true);
            settings.setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), true);
            settings.setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true);
            settings.setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true);

// Configurar nodos bootstrap para DHT
            StringBuilder dhtNodes = new StringBuilder();
            for (int i = 0; i < DHT_BOOTSTRAP_NODES.length; i++) {
                dhtNodes.append(DHT_BOOTSTRAP_NODES[i]);
                if (i < DHT_BOOTSTRAP_NODES.length - 1) {
                    dhtNodes.append(",");
                }
            }
            settings.setString(settings_pack.string_types.dht_bootstrap_nodes.swigValue(), dhtNodes.toString());

// Configuración de rendimiento - usando métodos correctos
            settings.setInteger(settings_pack.int_types.connections_limit.swigValue(), 200); // Reducido para menos logs
            settings.setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 2000); // Reducido para menos logs
            settings.setInteger(settings_pack.int_types.tick_interval.swigValue(), 1000); // Aumentado para menos logs
            settings.setInteger(settings_pack.int_types.inactivity_timeout.swigValue(), 600);
            settings.setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 20);
            settings.setInteger(settings_pack.int_types.request_timeout.swigValue(), 10);
            settings.setInteger(settings_pack.int_types.peer_turnover.swigValue(), 5);
            settings.setInteger(settings_pack.int_types.peer_turnover_cutoff.swigValue(), 90);
            settings.setInteger(settings_pack.int_types.peer_turnover_interval.swigValue(), 300);

// Configurar límites para descargas simultáneas
            settings.setInteger(settings_pack.int_types.active_limit.swigValue(), maxConcurrentDownloads * 2);
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), maxConcurrentDownloads);
            settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), maxConcurrentDownloads);
            settings.setInteger(settings_pack.int_types.dht_announce_interval.swigValue(), 60);
            settings.setInteger(settings_pack.int_types.dht_upload_rate_limit.swigValue(), 8000);

// Configuración de trackers
            settings.setInteger(settings_pack.int_types.tracker_completion_timeout.swigValue(), 60);
            settings.setInteger(settings_pack.int_types.tracker_receive_timeout.swigValue(), 40);

// Configurar límites de velocidad
            if (downloadSpeedLimit > 0) {
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), downloadSpeedLimit * 1024);
            }
            if (uploadSpeedLimit > 0) {
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), uploadSpeedLimit * 1024);
            }

// build the alert mask
            int alertMask = alert.status_notification.to_int()
                    | alert.error_notification.to_int()
                    | alert.storage_notification.to_int();

// apply settings
            settings.setInteger(
                    settings_pack.int_types.alert_mask.swigValue(),
                    alertMask
            );
            settings.setInteger(
                    settings_pack.int_types.disk_io_write_mode.swigValue(),
                    settings_pack.io_buffer_mode_t.enable_os_cache.swigValue()
            );
            settings.setInteger(
                    settings_pack.int_types.send_buffer_watermark.swigValue(),
                    500 * 1024
            );

// Crear y iniciar el SessionManager
            SessionParams params = new SessionParams(settings);
            sessionManager = new SessionManager();
            sessionManager.start(params);

            log(Level.INFO, "SessionManager iniciado correctamente con soporte para " + maxConcurrentDownloads + " descargas simultáneas", "init");
        } catch (Exception e) {
            log(Level.SEVERE, "Error al inicializar SessionManager: " + e.getMessage(), "init");
            e.printStackTrace();
        }
    }

    /**
     * Inicia el monitoreo periódico del espacio en disco
     */
    private void startDiskSpaceMonitoring() {
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            checkDiskSpace();
        }, 0, DISK_CHECK_INTERVAL, TimeUnit.SECONDS);
    }

    /**
     * Inicia el programador de actualizaciones de estado
     */
    private void startStatusUpdateScheduler() {
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            updateTorrentStates();
        }, 0, STATUS_UPDATE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * Comprueba si hay suficiente espacio en disco para las descargas actuales
     */
    private void checkDiskSpace() {
        try {
// Verificar espacio para todas las descargas activas
            synchronized (activeDownloadsLock) {
                for (TorrentState torrentState : activeDownloads.values()) {
                    String destinationPath = torrentState.getDestinationPath();
                    if (destinationPath != null && !destinationPath.isEmpty()) {
                        File destDir = new File(destinationPath);
                        long availableSpace = destDir.getUsableSpace();

// Si el espacio disponible es menor que el mínimo requerido
                        if (availableSpace < MIN_DISK_SPACE) {
                            log(Level.WARNING, "¡ADVERTENCIA! Espacio en disco bajo: " + formatSize(availableSpace), "disk");

// Pausar la descarga
                            TorrentHandle th = findTorrentHandle(torrentState);
                            if (th != null && th.isValid()) {
// Usar flags para pausar en lugar de isPaused()
                                th.setFlags(TorrentFlags.PAUSED);
                                torrentState.setStatus("Pausado (Espacio insuficiente)");

// Notificar a los listeners
                                for (TorrentNotificationListener listener : notificationListeners) {
                                    listener.onDiskSpaceLow(availableSpace, MIN_DISK_SPACE);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log(Level.SEVERE, "Error al comprobar el espacio en disco: " + e.getMessage(), "disk");
            e.printStackTrace();
        }
    }

//==========================================================================
// GESTIÓN DE NOTIFICACIONES Y LOGGING
//==========================================================================

    /**
     * Añade un listener para recibir notificaciones de eventos de torrent
     *
     * @param listener El listener a añadir
     */
    public void addNotificationListener(TorrentNotificationListener listener) {
        if (listener != null && !notificationListeners.contains(listener)) {
            notificationListeners.add(listener);
        }
    }

    /**
     * Elimina un listener de notificaciones
     *
     * @param listener El listener a eliminar
     */
    public void removeNotificationListener(TorrentNotificationListener listener) {
        notificationListeners.remove(listener);
    }

    /**
     * Notifica a todos los listeners que un torrent se ha completado
     *
     * @param torrentState El estado del torrent completado
     */
    private void notifyTorrentComplete(TorrentState torrentState) {
        for (TorrentNotificationListener listener : notificationListeners) {
            listener.onTorrentComplete(torrentState);
        }
    }

    /**
     * Notifica a todos los listeners que ha ocurrido un error en un torrent
     *
     * @param torrentState El estado del torrent con error
     * @param errorMessage El mensaje de error
     */
    private void notifyTorrentError(TorrentState torrentState, String errorMessage) {
        for (TorrentNotificationListener listener : notificationListeners) {
            listener.onTorrentError(torrentState, errorMessage);
        }
    }

    /**
     * Notifica un mensaje de depuración a todos los listeners
     *
     * @param message El mensaje de depuración
     * @param level El nivel del mensaje
     */
    private void notifyDebugMessage(String message, Level level) {
        for (TorrentNotificationListener listener : notificationListeners) {
            listener.onDebugMessage(message, level);
        }
    }

    /**
     * Notifica una actualización de estado de torrent a todos los listeners
     *
     * @param torrentState El estado del torrent
     * @param stats Las estadísticas detalladas del torrent
     */
    private void notifyTorrentStatusUpdate(TorrentState torrentState, TorrentStats stats) {
        for (TorrentNotificationListener listener : notificationListeners) {
            listener.onTorrentStatusUpdate(torrentState, stats);
        }
    }

    /**
     * Registra un mensaje en el log
     *
     * @param level Nivel del mensaje
     * @param message El mensaje
     * @param source La fuente del mensaje
     */
    private void log(Level level, String message, String source) {
// Solo registrar si el nivel es suficientemente importante o si estamos en modo verbose
        if (!verboseLogging && level.intValue() < Level.INFO.intValue()) {
            return; // Ignorar logs de bajo nivel si no estamos en modo verbose
        }

// Crear entrada de log
        LogEntry entry = new LogEntry(level, message, source);

// Añadir a la lista de logs, manteniendo un tamaño máximo
        synchronized (logEntries) {
            logEntries.add(entry);
            while (logEntries.size() > MAX_LOG_ENTRIES) {
                logEntries.remove(0);
            }
        }

// Mostrar en consola si está habilitado
        if (consoleLogging) {
            if (level == Level.SEVERE) {
                System.err.println(entry.toString());
            } else if (level.intValue() >= Level.INFO.intValue()) {
                System.out.println(entry.toString());
            }
        }

// Notificar a los listeners si es un nivel relevante
        if (level.intValue() >= Level.INFO.intValue()) {
            notifyDebugMessage(message, level);
        }

// Registrar en el logger de Java solo mensajes importantes
        if (level.intValue() >= Level.INFO.intValue()) {
            LOGGER.log(level, message);
        }
    }

    /**
     * Obtiene las entradas de log
     *
     * @param maxEntries Número máximo de entradas a devolver (0 para todas)
     * @param minLevel Nivel mínimo de las entradas
     * @return Lista de entradas de log
     */
    public List<LogEntry> getLogEntries(int maxEntries, Level minLevel) {
        List<LogEntry> result = new ArrayList<>();

        synchronized (logEntries) {
            for (LogEntry entry : logEntries) {
                if (entry.getLevel().intValue() >= minLevel.intValue()) {
                    result.add(entry);
                }
            }
        }

// Limitar el número de entradas si es necesario
        if (maxEntries > 0 && result.size() > maxEntries) {
            return result.subList(result.size() - maxEntries, result.size());
        }

        return result;
    }

    /**
     * Establece el nivel de depuración
     *
     * @param level Nivel de depuración (1-5, donde 5 es el más detallado)
     */
    public void setDebugLevel(int level) {
        this.debugLevel = Math.max(1, Math.min(5, level));
        this.verboseLogging = (debugLevel >= 4);
        log(Level.INFO, "Nivel de depuración establecido a " + debugLevel, "config");
    }

    /**
     * Activa o desactiva el logging en consola
     *
     * @param enabled true para activar, false para desactivar
     */
    public void setConsoleLogging(boolean enabled) {
        this.consoleLogging = enabled;
        log(Level.INFO, "Logging en consola " + (enabled ? "activado" : "desactivado"), "config");
    }

//==========================================================================
// MÉTODOS PÚBLICOS PARA GESTIÓN DE DESCARGAS
//==========================================================================

    /**
     * Método para descargar un torrent a partir de un archivo .torrent ya descargado
     * Este método es llamado desde la UI
     *
     * @param torrentFilePath Ruta al archivo .torrent
     * @param torrentState Estado del torrent
     */
    public void downloadTorrent(String torrentFilePath, TorrentState torrentState) {
        try {
            log(Level.INFO, "Iniciando descarga con archivo torrent: " + torrentFilePath, "download");
            log(Level.INFO, "Destino: " + torrentState.getDestinationPath(), "download");

// Establecer la fuente del torrent
            torrentState.setTorrentSource(torrentFilePath);

// Añadir a la cola de descargas
            addTorrent(torrentState);

        } catch (Exception e) {
            log(Level.SEVERE, "Error al iniciar descarga: " + e.getMessage(), "download");
            e.printStackTrace();
        }
    }

    /**
     * Añade un torrent a la cola de descargas
     *
     * @param torrentState El estado del torrent a añadir
     */
    public void addTorrent(TorrentState torrentState) {
        synchronized (queue) {
            queue.add(torrentState);
            torrentState.setStatus("En espera");
            log(Level.INFO, "Torrent añadido a la cola: " + torrentState.getName(), "queue");

// Iniciar procesamiento si hay espacio para más descargas
            startQueueProcessingIfPossible();
        }
    }

    /**
     * Pausa la descarga de un torrent
     *
     * @param torrentState El estado del torrent a pausar
     */
    public void pauseDownload(TorrentState torrentState) {
        try {
            synchronized (activeDownloadsLock) {
// Buscar en las descargas activas
                if (activeDownloads.containsValue(torrentState)) {
                    TorrentHandle th = findTorrentHandle(torrentState);
                    if (th != null && th.isValid()) {
// Usar flags para pausar en lugar de isPaused()
                        th.setFlags(TorrentFlags.PAUSED);
                        torrentState.setStatus("Pausado");
                        log(Level.INFO, "Torrent pausado: " + torrentState.getName(), "control");
                    }
                } else {
// Si está en la cola, marcar como pausado
                    synchronized (queue) {
                        if (queue.contains(torrentState)) {
                            torrentState.setStatus("Pausado");
                        }
                    }
                }
            }
        } catch (Exception e) {
            log(Level.SEVERE, "Error al pausar la descarga: " + e.getMessage(), "control");
            e.printStackTrace();
        }
    }

    /**
     * Reanuda la descarga de un torrent pausado
     *
     * @param torrentState El estado del torrent a reanudar
     */
    public void resumeDownload(TorrentState torrentState) {
        try {
// Comprobar primero si hay suficiente espacio en disco
            String destinationPath = torrentState.getDestinationPath();
            if (destinationPath != null && !destinationPath.isEmpty()) {
                File destDir = new File(destinationPath);
                long availableSpace = destDir.getUsableSpace();
                if (availableSpace < MIN_DISK_SPACE) {
                    log(Level.WARNING, "No se puede reanudar: espacio en disco insuficiente", "control");
                    torrentState.setStatus("Pausado (Espacio insuficiente)");

// Notificar a los listeners
                    for (TorrentNotificationListener listener : notificationListeners) {
                        listener.onDiskSpaceLow(availableSpace, MIN_DISK_SPACE);
                    }
                    return;
                }
            }

            synchronized (activeDownloadsLock) {
// Si está en las descargas activas, reanudar
                if (activeDownloads.containsValue(torrentState)) {
                    TorrentHandle th = findTorrentHandle(torrentState);
                    if (th != null && th.isValid()) {
// Usar unsetFlags para quitar la bandera de pausa
                        th.unsetFlags(TorrentFlags.PAUSED);
                        torrentState.setStatus("Descargando");
                        log(Level.INFO, "Torrent reanudado: " + torrentState.getName(), "control");
                    }
                } else {
// Si está en la cola, actualizar estado
                    synchronized (queue) {
                        if (queue.contains(torrentState)) {
                            torrentState.setStatus("En espera");
                        } else {
// Si no está en ningún lado, añadirlo a la cola
                            queue.add(torrentState);
                            torrentState.setStatus("En espera");

// Intentar iniciar la descarga si hay espacio
                            startQueueProcessingIfPossible();
                        }
                    }
                }
            }
        } catch (Exception e) {
            log(Level.SEVERE, "Error al reanudar la descarga: " + e.getMessage(), "control");
            e.printStackTrace();
        }
    }

    /**
     * Elimina un torrent de la sesión y opcionalmente sus archivos
     *
     * @param torrentState El estado del torrent a eliminar
     * @param deleteFiles Si se deben eliminar también los archivos descargados
     */
    public void removeDownload(TorrentState torrentState, boolean deleteFiles) {
        try {
            synchronized (activeDownloadsLock) {
// Buscar en las descargas activas
                if (activeDownloads.containsValue(torrentState)) {
                    TorrentHandle th = findTorrentHandle(torrentState);
                    if (th != null && th.isValid()) {
// Create remove flags with delete_files option
                        remove_flags_t flags = new remove_flags_t();
                        if (deleteFiles) {
                            flags = flags.or_(remove_flags_t.all());
                        }

// Obtener el infoHash para eliminar del mapa
                        String infoHash = th.infoHash().toHex();

// Eliminar el torrent
                        sessionManager.swig().remove_torrent(th.swig(), flags);

// Eliminar del mapa de descargas activas
                        activeDownloads.remove(infoHash);

// Eliminar estadísticas
                        torrentStats.remove(infoHash);

// Eliminar de los mapas de trackers
                        problematicTrackers.remove(infoHash);
                        originalTrackers.remove(infoHash);

// Decrementar contador de descargas activas
                        decrementActiveDownloadsCount();

                        log(Level.INFO, "Torrent eliminado" + (deleteFiles ? " con archivos" : "") + ": " + torrentState.getName(), "control");
                    }
                } else {
// Si está en la cola, eliminarlo
                    synchronized (queue) {
                        queue.remove(torrentState);
                    }
                    log(Level.INFO, "Torrent eliminado de la cola: " + torrentState.getName(), "control");
                }
            }
        } catch (Exception e) {
            log(Level.SEVERE, "Error al eliminar la descarga: " + e.getMessage(), "control");
            e.printStackTrace();
        }
    }

    /**
     * Actualiza la configuración del downloader
     *
     * @param maxConcurrentDownloads Número máximo de descargas concurrentes
     * @param extractArchives Si se deben extraer archivos comprimidos automáticamente
     * @param downloadSpeedLimit Límite de velocidad de descarga en KB/s
     * @param uploadSpeedLimit Límite de velocidad de subida en KB/s
     * @param autoStartDownloads Si se deben iniciar las descargas automáticamente
     */
    public void updateConfig(int maxConcurrentDownloads, boolean extractArchives, int downloadSpeedLimit, int uploadSpeedLimit, boolean autoStartDownloads) {
// Actualizar el número máximo de descargas concurrentes
        if (this.maxConcurrentDownloads != maxConcurrentDownloads) {
            this.maxConcurrentDownloads = Math.max(1, maxConcurrentDownloads);

// Recrear el ExecutorService con el nuevo tamaño
            ExecutorService oldExecutor = this.executorService;
            this.executorService = Executors.newFixedThreadPool(this.maxConcurrentDownloads);

// Apagar el antiguo executor después de un tiempo para permitir que las tareas terminen
            scheduledExecutorService.schedule(() -> {
                oldExecutor.shutdown();
            }, 5, TimeUnit.SECONDS);
        }

// Actualizar otras configuraciones
        this.extractArchives = extractArchives;
        this.downloadSpeedLimit = downloadSpeedLimit;
        this.uploadSpeedLimit = uploadSpeedLimit;
        this.autoStartDownloads = autoStartDownloads;

// Actualizar la configuración de libtorrent
        if (sessionManager != null) {
            SettingsPack settings = new SettingsPack();
            settings.setInteger(settings_pack.int_types.active_limit.swigValue(), this.maxConcurrentDownloads * 2);
            settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), this.maxConcurrentDownloads);
            settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), this.maxConcurrentDownloads);

// Configurar límites de velocidad
            if (downloadSpeedLimit > 0) {
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), downloadSpeedLimit * 1024); // Convertir KB/s a B/s
            } else {
                settings.setInteger(settings_pack.int_types.download_rate_limit.swigValue(), 0); // Sin límite
            }

            if (uploadSpeedLimit > 0) {
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), uploadSpeedLimit * 1024); // Convertir KB/s a B/s
            } else {
                settings.setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 0); // Sin límite
            }

// Aplicar la configuración
            sessionManager.applySettings(settings);
        }

        log(Level.INFO, "Configuración actualizada: maxConcurrentDownloads=" + maxConcurrentDownloads +
                ", extractArchives=" + extractArchives +
                ", downloadSpeedLimit=" + downloadSpeedLimit +
                ", uploadSpeedLimit=" + uploadSpeedLimit +
                ", autoStartDownloads=" + autoStartDownloads, "config");
    }

    /**
     * Establece si las descargas deben iniciarse automáticamente
     *
     * @param autoStartDownloads true para iniciar automáticamente, false en caso contrario
     */
    public void setAutoStartDownloads(boolean autoStartDownloads) {
        this.autoStartDownloads = autoStartDownloads;
        log(Level.INFO, "Auto-inicio de descargas: " + (autoStartDownloads ? "activado" : "desactivado"), "config");
    }

    /**
     * Detiene el downloader y libera recursos
     */
    public void shutdown() {
        try {
            log(Level.INFO, "Iniciando proceso de cierre del TorrentDownloader...", "shutdown");

            // Primero marcamos que no estamos ejecutando para que no se inicien nuevas tareas
            isRunning.set(false);

            // Detener los schedulers primero para evitar actualizaciones durante el cierre
            if (scheduledExecutorService != null) {
                log(Level.INFO, "Deteniendo schedulers...", "shutdown");
                scheduledExecutorService.shutdownNow(); // Usar shutdownNow para cancelar tareas en ejecución
                try {
                    // Esperar a que terminen (máximo 5 segundos)
                    if (!scheduledExecutorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        log(Level.WARNING, "No se pudieron detener todas las tareas programadas", "shutdown");
                    }
                } catch (InterruptedException e) {
                    log(Level.WARNING, "Interrupción al esperar cierre de schedulers", "shutdown");
                    Thread.currentThread().interrupt();
                }
            }

            // Pausar todas las descargas activas antes de cerrar
            try {
                synchronized (activeDownloadsLock) {
                    log(Level.INFO, "Pausando descargas activas...", "shutdown");
                    for (Map.Entry<String, TorrentState> entry : activeDownloads.entrySet()) {
                        try {
                            TorrentHandle th = sessionManager.find(new Sha1Hash(entry.getKey()));
                            if (th != null && th.isValid()) {
                                th.setFlags(TorrentFlags.PAUSED);
                                entry.getValue().setStatus("Pausado (Cierre)");
                            }
                        } catch (Exception e) {
                            log(Level.WARNING, "Error al pausar torrent: " + e.getMessage(), "shutdown");
                        }
                    }
                }
            } catch (Exception e) {
                log(Level.WARNING, "Error al pausar descargas activas: " + e.getMessage(), "shutdown");
            }

            // Guardar el estado de la sesión antes de detenerla
            byte[] sessionState = null;
            try {
                sessionState = saveSessionState();
                log(Level.INFO, "Estado de la sesión guardado para restauración futura", "shutdown");
            } catch (Exception e) {
                log(Level.WARNING, "Error al guardar estado de sesión: " + e.getMessage(), "shutdown");
            }

            // Detener el SessionManager
            if (sessionManager != null) {
                try {
                    log(Level.INFO, "Deteniendo SessionManager...", "shutdown");
                    sessionManager.stop();
                    log(Level.INFO, "SessionManager detenido correctamente", "shutdown");
                } catch (Exception e) {
                    log(Level.WARNING, "Error al detener SessionManager: " + e.getMessage(), "shutdown");
                }
            }

            // Detener el executor service
            if (executorService != null && !executorService.isShutdown()) {
                log(Level.INFO, "Deteniendo executor service...", "shutdown");
                executorService.shutdownNow();
                try {
                    // Esperar a que terminen (máximo 5 segundos)
                    if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                        log(Level.WARNING, "No se pudieron detener todas las tareas del executor", "shutdown");
                    }
                } catch (InterruptedException e) {
                    log(Level.WARNING, "Interrupción al esperar cierre del executor", "shutdown");
                    Thread.currentThread().interrupt();
                }
            }

            log(Level.INFO, "TorrentDownloader cerrado correctamente", "shutdown");
        } catch (Exception e) {
            log(Level.SEVERE, "Error durante el cierre del TorrentDownloader: " + e.getMessage(), "shutdown");
            e.printStackTrace();
        }
    }


    /**
     * Establece la fuente del torrent en el TorrentState
     *
     * @param torrentState El estado del torrent
     * @param torrentSource La fuente del torrent (URL, ruta de archivo o enlace magnet)
     */
    public void setTorrentSource(TorrentState torrentState, String torrentSource) {
        torrentState.setTorrentSource(torrentSource);
    }

    /**
     * Guarda el estado de la sesión para poder restaurarlo más tarde
     */
    public byte[] saveSessionState() {
        if (sessionManager != null) {
            try {
                // Obtener el estado de la sesión. Puede devolver null si la sesión ya
                // está cerrada o no se puede serializar.
                byte[] sessionState = sessionManager.saveState();
                if (sessionState != null) {
                    log(Level.INFO, "Estado de la sesión guardado: " + sessionState.length + " bytes", "session");
                } else {
                    log(Level.WARNING, "Estado de la sesión guardado: 0 bytes (nulo)", "session");
                }
                return sessionState;
            } catch (Exception e) {
                log(Level.SEVERE, "Error al guardar el estado de la sesión: " + e.getMessage(), "session");
                e.printStackTrace();
            }
        }
        return null;
    }

    /**
     * Restaura el estado de la sesión a partir de datos guardados previamente
     * @param sessionState Los datos del estado de la sesión
     */
    public void restoreSessionState(byte[] sessionState) {
        if (sessionManager != null && sessionState != null && sessionState.length > 0) {
            try {
// En libtorrent4j 2.x, no existe loadState directamente
// En su lugar, debemos crear un SessionParams con los datos y reiniciar la sesión

// Primero detenemos la sesión actual si está en ejecución
                sessionManager.stop();

// Creamos un nuevo SessionParams con los datos guardados
                SessionParams params = new SessionParams(sessionState);

// Reiniciamos la sesión con los parámetros restaurados
                sessionManager.start(params);

// Opcionalmente, podemos reabrir los sockets de red
                sessionManager.reopenNetworkSockets();

                log(Level.INFO, "Estado de la sesión restaurado correctamente", "session");
            } catch (Exception e) {
                log(Level.SEVERE, "Error al restaurar el estado de la sesión: " + e.getMessage(), "session");
                e.printStackTrace();
            }
        }
    }

    /**
     * Obtiene las estadísticas detalladas de un torrent
     *
     * @param torrentState El estado del torrent
     * @return Las estadísticas detalladas, o null si no se encuentran
     */
    public TorrentStats getTorrentStats(TorrentState torrentState) {
        TorrentHandle th = findTorrentHandle(torrentState);
        if (th != null && th.isValid()) {
            String infoHash = th.infoHash().toHex();
            return torrentStats.get(infoHash);
        }
        return null;
    }

    /**
     * Obtiene información detallada sobre un torrent
     *
     * @param torrentState El estado del torrent
     * @return Mapa con información detallada
     */
    public Map<String, Object> getTorrentDetails(TorrentState torrentState) {
        Map<String, Object> details = new HashMap<>();

        try {
            TorrentHandle th = findTorrentHandle(torrentState);
            if (th != null && th.isValid()) {
                TorrentStatus status = th.status();
                TorrentInfo info = th.torrentFile();

// Información básica
                details.put("name", th.name());
                details.put("infoHash", th.infoHash().toHex());
                details.put("savePath", th.savePath());
                details.put("state", status.state().toString());
                details.put("progress", status.progress() * 100);
                details.put("totalSize", status.total());
                details.put("downloadedBytes", status.totalDone());
                details.put("uploadedBytes", status.totalUpload());
                details.put("downloadRate", status.downloadRate());
                details.put("uploadRate", status.uploadRate());
                details.put("numPeers", status.numPeers());
                details.put("numSeeds", status.numSeeds());
                details.put("totalPeers", status.listPeers());
                details.put("totalSeeds", status.listSeeds());
                details.put("distributed_copies", status.distributedCopies());
                details.put("isPaused", status.flags().and_(TorrentFlags.PAUSED).nonZero());

// Información de piezas
                if (info != null) {
                    details.put("pieceLength", info.pieceLength());
                    details.put("numPieces", info.numPieces());

// Información de archivos
                    FileStorage fs = info.files();
                    List<Map<String, Object>> files = new ArrayList<>();
                    for (int i = 0; i < fs.numFiles(); i++) {
                        Map<String, Object> file = new HashMap<>();
                        file.put("path", fs.filePath(i));
                        file.put("size", fs.fileSize(i));
                        files.add(file);
                    }
                    details.put("files", files);

// Información de trackers
                    List<String> trackers = new ArrayList<>();
                    for (AnnounceEntry tracker : th.trackers()) {
                        trackers.add(tracker.url());
                    }
                    details.put("trackers", trackers);
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "Error al obtener detalles del torrent: " + e.getMessage(), "info");
            e.printStackTrace();
        }

        return details;
    }

//==========================================================================
// MÉTODOS INTERNOS PARA GESTIÓN DE ALERTAS
//==========================================================================

    /**
     * Registra un listener de alertas para manejar eventos de torrents
     * Este método es clave para el soporte de descargas simultáneas
     */
    private void registerAlertListener() {
        if (sessionManager == null) return;

// Definir los tipos de alertas que nos interesan
        final int[] ALERT_TYPES = {
                AlertType.ADD_TORRENT.swig(),
                AlertType.TORRENT_FINISHED.swig(),
                AlertType.TORRENT_ERROR.swig(),
                AlertType.TORRENT_REMOVED.swig(),
                AlertType.TORRENT_CHECKED.swig(),
                AlertType.STATE_UPDATE.swig(),
                AlertType.TRACKER_ERROR.swig(),
                AlertType.TRACKER_REPLY.swig()
// Eliminados los tipos de log para reducir el ruido
        };

// Crear e implementar el AlertListener
        AlertListener listener = new AlertListener() {
            @Override
            public int[] types() {
                return ALERT_TYPES;
            }

            @Override
            public void alert(Alert<?> alert) {
                try {
// Solo registrar alertas importantes
                    if (alert.type() == AlertType.TORRENT_FINISHED) {
                        log(Level.INFO, "Torrent completado: " + alert.message(), "torrent");

// Buscar el torrent completado y notificar
                        if (alert instanceof TorrentFinishedAlert) {
                            TorrentFinishedAlert finishedAlert = (TorrentFinishedAlert) alert;
                            TorrentHandle handle = finishedAlert.handle();
                            if (handle != null && handle.isValid()) {
                                String infoHash = handle.infoHash().toHex();
                                TorrentState state = activeDownloads.get(infoHash);
                                if (state != null) {
                                    state.setStatus("Completado");
                                    state.setProgress(100);
                                    notifyTorrentComplete(state);

// Si está configurado para extraer archivos comprimidos
                                    if (extractArchives) {
                                        extractArchiveFiles(new File(handle.savePath()));
                                    }
                                }
                            }
                        }
                    } else if (alert.type() == AlertType.TORRENT_ERROR) {
                        log(Level.SEVERE, "Error en torrent: " + alert.message(), "torrent");

// Buscar el torrent con error y notificar
                        if (alert instanceof TorrentErrorAlert) {
                            TorrentErrorAlert errorAlert = (TorrentErrorAlert) alert;
                            TorrentHandle handle = errorAlert.handle();
                            if (handle != null && handle.isValid()) {
                                String infoHash = handle.infoHash().toHex();
                                TorrentState state = activeDownloads.get(infoHash);
                                if (state != null) {
                                    state.setStatus("Error: " + errorAlert.error().message());
                                    notifyTorrentError(state, errorAlert.error().message());
                                }
                            }
                        }
                    } else if (alert.type() == AlertType.STATE_UPDATE) {
// Procesar actualización de estado sin generar logs
                        if (alert instanceof StateUpdateAlert) {
                            StateUpdateAlert stateAlert = (StateUpdateAlert) alert;
                            for (TorrentStatus ts : stateAlert.status()) {
                                // 1. Get the native SWIG struct
                                org.libtorrent4j.swig.torrent_status raw = ts.swig();
                                // 2. Extract the raw torrent_handle
                                org.libtorrent4j.swig.torrent_handle rawHandle = raw.getHandle();
                                // 3. Wrap in the high‑level API
                                TorrentHandle handle = new TorrentHandle(rawHandle);
                                if (handle != null && handle.isValid()) {
                                    updateTorrentStats(handle);
                                }
                            }
                        }


                    } else if (alert.type() == AlertType.TRACKER_ERROR) {
// Solo registrar errores de tracker si son persistentes
                        if (alert instanceof TrackerErrorAlert) {
                            TrackerErrorAlert errorAlert = (TrackerErrorAlert) alert;
                            if (errorAlert.timesInRow() > 3) {
                                log(Level.WARNING, "Error de tracker: " + errorAlert.message() + " (intentos: " + errorAlert.timesInRow() + ")", "tracker");

// Marcar tracker como problemático
                                TorrentHandle handle = errorAlert.handle();
                                if (handle != null && handle.isValid()) {
                                    String infoHash = handle.infoHash().toHex();
                                    markProblemTracker(infoHash, errorAlert.trackerUrl());
                                }
                            }
                        }
                    } else if (alert.type() == AlertType.TRACKER_REPLY) {
// No registrar respuestas de tracker para reducir ruido
                    } else if (alert.type() == AlertType.ADD_TORRENT) {
                        log(Level.INFO, "Torrent añadido: " + alert.message(), "torrent");
                    } else if (alert.type() == AlertType.TORRENT_REMOVED) {
                        log(Level.INFO, "Torrent eliminado: " + alert.message(), "torrent");
                    }
                } catch (Exception e) {
                    log(Level.SEVERE, "Error procesando alerta: " + e.getMessage(), "alert");
                    e.printStackTrace();
                }
            }
        };

// Añadir el listener al SessionManager
        sessionManager.addListener(listener);
        log(Level.INFO, "Listener de alertas registrado", "init");
    }

    /**
     * Inicializa las estadísticas para un torrent
     *
     * @param handle El handle del torrent
     */
    private void initTorrentStats(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        try {
            String infoHash = handle.infoHash().toHex();
            TorrentStats stats = new TorrentStats(handle.infoHash(), 100); // 100 muestras para histórico
            torrentStats.put(infoHash, stats);

// Actualizar con el estado inicial
            TorrentStatus status = handle.status();
            stats.update(status);

            log(Level.FINE, "Estadísticas inicializadas para torrent: " + handle.name(), "stats");
        } catch (Exception e) {
            log(Level.WARNING, "Error al inicializar estadísticas: " + e.getMessage(), "stats");
            e.printStackTrace();
        }
    }

    /**
     * Actualiza las estadísticas de todos los torrents
     */
    private void updateTorrentStats() {
        if (sessionManager == null) return;

        try {
// Obtener todos los torrents de la sesión
            SessionHandle sessionHandle = new SessionHandle(sessionManager.swig());
            List<TorrentHandle> handles = sessionHandle.torrents();

            for (TorrentHandle handle : handles) {
                if (handle.isValid()) {
                    updateTorrentStats(handle);
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "Error al actualizar estadísticas de torrents: " + e.getMessage(), "stats");
            e.printStackTrace();
        }
    }

    /**
     * Actualiza las estadísticas de un torrent específico
     *
     * @param handle El handle del torrent
     */
    private void updateTorrentStats(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        try {
            String infoHash = handle.infoHash().toHex();
            TorrentStats stats = torrentStats.get(infoHash);

            if (stats == null) {
// Si no existen estadísticas, inicializarlas
                initTorrentStats(handle);
                stats = torrentStats.get(infoHash);
            }

            if (stats != null) {
// Actualizar estadísticas con el estado actual
                TorrentStatus status = handle.status();
                stats.update(status);

// Notificar actualización de estadísticas
                TorrentState state = activeDownloads.get(infoHash);
                if (state != null) {
                    notifyTorrentStatusUpdate(state, stats);
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "Error al actualizar estadísticas de torrent: " + e.getMessage(), "stats");
            e.printStackTrace();
        }
    }

    /**
     * Actualiza los estados de todos los torrents activos
     */
    private void updateTorrentStates() {
        if (sessionManager == null) {
            return;
        }

        try {
            // Obtener todos los torrents de la sesión usando SessionHandle. La
            // instancia subyacente de sessionManager puede ser nula si la sesión
            // se ha cerrado; en ese caso capturamos NullPointerException.
            SessionHandle sessionHandle;
            try {
                sessionHandle = new SessionHandle(sessionManager.swig());
            } catch (NullPointerException ex) {
                log(Level.WARNING, "No se pudo obtener SessionHandle: la sesión se ha cerrado", "updateStates");
                return;
            }
            List<TorrentHandle> torrents;
            try {
                torrents = sessionHandle.torrents();
            } catch (NullPointerException ex) {
                log(Level.WARNING, "No se pudieron obtener torrents: la sesión se ha cerrado", "updateStates");
                return;
            }

            synchronized (activeDownloadsLock) {
                for (TorrentHandle th : torrents) {
                    if (th.isValid()) {
                        String infoHash = th.infoHash().toHex();
                        TorrentState state = activeDownloads.get(infoHash);

                        if (state != null) {
// Actualizar el estado del torrent
                            TorrentStatus status = th.status();
                            if (status != null) {
                                float progress = (float) status.progress() * 100f;
                                state.setProgress(progress);

// Actualizar velocidades
                                state.setDownloadSpeed(status.downloadRate());
                                state.setUploadSpeed(status.uploadRate());

// Actualizar estado según la situación del torrent
                                if (status.flags().and_(TorrentFlags.PAUSED).nonZero()) {
                                    state.setStatus("Pausado");
                                } else {
                                    switch (status.state()) {
                                        case CHECKING_FILES:
                                            state.setStatus("Verificando archivos");
                                            break;
                                        case DOWNLOADING_METADATA:
                                            state.setStatus("Descargando metadatos");
                                            break;
                                        case DOWNLOADING:
                                            state.setStatus("Descargando");
                                            break;
                                        case FINISHED:
                                            state.setStatus("Finalizado");
                                            break;
                                        case SEEDING:
                                            state.setStatus("Compartiendo");
                                            break;
                                        case CHECKING_RESUME_DATA:
                                            state.setStatus("Verificando datos");
                                            break;
                                        default:
                                            state.setStatus("Desconocido");
                                            break;
                                    }
                                }

// Actualizar información de pares
                                state.setPeers(status.numPeers());
                                state.setSeeds(status.numSeeds());

// Si está completado, notificar
                                if (progress >= 100 && !state.getStatus().equals("Completado") &&
                                        !state.getStatus().equals("Compartiendo")) {
                                    state.setStatus("Completado");
                                    notifyTorrentComplete(state);

// Si está configurado para extraer archivos comprimidos
                                    if (extractArchives) {
                                        extractArchiveFiles(new File(th.savePath()));
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log(Level.WARNING, "Error al actualizar estados de torrents: " + e.getMessage(), "state");
            e.printStackTrace();
        }
    }

//==========================================================================
// MÉTODOS PARA GESTIÓN DE DESCARGAS SIMULTÁNEAS
//==========================================================================

    /**
     * Incrementa el contador de descargas activas de forma segura
     */
    private void incrementActiveDownloadsCount() {
        synchronized (activeDownloadsLock) {
            activeDownloadsCount++;
            log(Level.INFO, "Descargas activas: " + activeDownloadsCount + "/" + maxConcurrentDownloads, "queue");
        }
    }

    /**
     * Decrementa el contador de descargas activas de forma segura
     */
    private void decrementActiveDownloadsCount() {
        synchronized (activeDownloadsLock) {
            if (activeDownloadsCount > 0) {
                activeDownloadsCount--;
            }
            log(Level.INFO, "Descargas activas: " + activeDownloadsCount + "/" + maxConcurrentDownloads, "queue");
        }
    }

    /**
     * Verifica si hay espacio para más descargas e inicia el procesamiento de la cola si es posible
     */
    private void startQueueProcessingIfPossible() {
        if (!autoStartDownloads) return;

        synchronized (activeDownloadsLock) {
// Si hay espacio para más descargas y hay torrents en cola
            if (activeDownloadsCount < maxConcurrentDownloads && !queue.isEmpty()) {
                if (isRunning.compareAndSet(false, true)) {
                    executorService.submit(() -> {
                        try {
                            processQueue();
                        } catch (Exception e) {
                            log(Level.SEVERE, "Error en el procesamiento de la cola: " + e.getMessage(), "queue");
                            e.printStackTrace();
                        } finally {
                            isRunning.set(false);
// Verificar si hay más torrents para procesar
                            startQueueProcessingIfPossible();
                        }
                    });
                }
            }
        }
    }

//==========================================================================
// MÉTODOS PARA GESTIÓN DE TRACKERS
//==========================================================================

    /**
     * Guarda los trackers originales de un torrent
     *
     * @param handle El handle del torrent
     */
    private void saveOriginalTrackers(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        try {
            String infoHash = handle.infoHash().toHex();
            List<AnnounceEntry> trackers = handle.trackers();
            List<String> trackerUrls = new ArrayList<>();

            for (AnnounceEntry tracker : trackers) {
                trackerUrls.add(tracker.url());
            }

// Guardar los trackers originales
            originalTrackers.put(infoHash, trackerUrls);

            log(Level.INFO, "Trackers originales guardados para " + handle.name() + ": " + trackerUrls.size() + " trackers", "tracker");

        } catch (Exception e) {
            log(Level.WARNING, "Error al guardar trackers originales: " + e.getMessage(), "tracker");
            e.printStackTrace();
        }
    }

    /**
     * Añade trackers públicos a un torrent para mejorar la conectividad
     *
     * @param handle El handle del torrent
     */
    private void addPublicTrackers(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        try {
            String infoHash = handle.infoHash().toHex();

// Obtener los trackers actuales
            List<AnnounceEntry> currentTrackers = handle.trackers();
            Set<String> currentTrackerUrls = new HashSet<>();

            for (AnnounceEntry tracker : currentTrackers) {
                currentTrackerUrls.add(tracker.url());
            }

// Añadir trackers públicos que no estén ya en la lista
            int addedCount = 0;
            for (String trackerUrl : PUBLIC_TRACKERS) {
                if (!currentTrackerUrls.contains(trackerUrl)) {
                    handle.addTracker(new AnnounceEntry(trackerUrl));
                    addedCount++;
                }
            }

            if (addedCount > 0) {
                log(Level.INFO, "Añadidos " + addedCount + " trackers públicos a " + handle.name(), "tracker");

// Forzar un reanuncio para conectar con los nuevos trackers
                handle.forceReannounce();
            }

        } catch (Exception e) {
            log(Level.WARNING, "Error al añadir trackers públicos: " + e.getMessage(), "tracker");
            e.printStackTrace();
        }
    }

    /**
     * Marca un tracker como problemático para un torrent específico
     *
     * @param infoHash El hash del torrent
     * @param trackerUrl La URL del tracker problemático
     */
    private void markProblemTracker(String infoHash, String trackerUrl) {
// Obtener o crear el conjunto de trackers problemáticos para este torrent
        Set<String> trackers = problematicTrackers.computeIfAbsent(infoHash, k -> new HashSet<>());

// Añadir el tracker a la lista de problemáticos
        if (trackers.add(trackerUrl)) {
            log(Level.INFO, "Tracker marcado como problemático: " + trackerUrl + " para torrent " + infoHash, "tracker");
        }
    }

    /**
     * Quita un tracker de la lista de problemáticos
     *
     * @param infoHash El hash del torrent
     * @param trackerUrl La URL del tracker
     */
    private void unmarkProblemTracker(String infoHash, String trackerUrl) {
        Set<String> trackers = problematicTrackers.get(infoHash);
        if (trackers != null && trackers.remove(trackerUrl)) {
            log(Level.INFO, "Tracker eliminado de la lista de problemáticos: " + trackerUrl + " para torrent " + infoHash, "tracker");
        }
    }

    /**
     * Elimina los trackers problemáticos de un torrent
     *
     * @param handle El handle del torrent
     */
    private void removeProblematicTrackers(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        try {
            String infoHash = handle.infoHash().toHex();
            Set<String> problemTrackers = problematicTrackers.get(infoHash);

            if (problemTrackers != null && !problemTrackers.isEmpty()) {
// Obtener los trackers actuales
                List<AnnounceEntry> currentTrackers = handle.trackers();
                List<AnnounceEntry> newTrackers = new ArrayList<>();

// Filtrar los trackers problemáticos
                for (AnnounceEntry tracker : currentTrackers) {
                    if (!problemTrackers.contains(tracker.url())) {
                        newTrackers.add(tracker);
                    }
                }

// Establecer la nueva lista de trackers
                if (newTrackers.size() < currentTrackers.size()) {
                    handle.replaceTrackers(newTrackers);
                    log(Level.INFO, "Eliminados " + (currentTrackers.size() - newTrackers.size()) + " trackers problemáticos de " + handle.name(), "tracker");
                }
            }

        } catch (Exception e) {
            log(Level.WARNING, "Error al eliminar trackers problemáticos: " + e.getMessage(), "tracker");
            e.printStackTrace();
        }
    }

    /**
     * Restaura los trackers originales de un torrent
     *
     * @param handle El handle del torrent
     */
    private void restoreOriginalTrackers(TorrentHandle handle) {
        if (handle == null || !handle.isValid()) return;

        try {
            String infoHash = handle.infoHash().toHex();
            List<String> origTrackers = originalTrackers.get(infoHash);

            if (origTrackers != null && !origTrackers.isEmpty()) {
                List<AnnounceEntry> trackers = new ArrayList<>();

                for (String url : origTrackers) {
                    trackers.add(new AnnounceEntry(url));
                }

                handle.replaceTrackers(trackers);
                log(Level.INFO, "Trackers originales restaurados para " + handle.name(), "tracker");
            }

        } catch (Exception e) {
            log(Level.WARNING, "Error al restaurar trackers originales: " + e.getMessage(), "tracker");
            e.printStackTrace();
        }
    }

//==========================================================================
// MÉTODOS INTERNOS DE PROCESAMIENTO DE DESCARGAS
//==========================================================================

    /**
     * Procesa la cola de descargas
     */
    private void processQueue() {
        while (isRunning.get()) {
            TorrentState nextTorrent = null;

            synchronized (activeDownloadsLock) {
// Verificar si podemos añadir más descargas
                if (activeDownloadsCount >= maxConcurrentDownloads) {
                    break;
                }

// Obtener el siguiente torrent de la cola
                synchronized (queue) {
                    if (!queue.isEmpty()) {
                        nextTorrent = queue.remove(0);
                    } else {
                        break;
                    }
                }

// Incrementar contador de descargas activas
                if (nextTorrent != null) {
                    incrementActiveDownloadsCount();
                }
            }

            if (nextTorrent != null) {
                try {
                    downloadTorrent(nextTorrent);
                } catch (Exception e) {
                    log(Level.SEVERE, "Error al descargar torrent: " + e.getMessage(), "download");
                    e.printStackTrace();
                    nextTorrent.setStatus("Error");
                    nextTorrent.setStatus("Error al descargar torrent: " + e.getMessage());
                    notifyTorrentError(nextTorrent, "Error al descargar torrent: " + e.getMessage());

// Decrementar contador si falló
                    decrementActiveDownloadsCount();
                }
            }
        }
    }

    /**
     * Descarga un torrent a partir de su estado
     *
     * @param torrentState El estado del torrent a descargar
     */
    public void downloadTorrent(TorrentState torrentState) {
        try {
            String torrentSource = torrentState.getTorrentSource();
            File torrentFile = null;
            byte[] torrentData = null;

            log(Level.INFO, "Iniciando descarga para: " + torrentState.getName(), "download");
            log(Level.INFO, "Fuente del torrent: " + torrentSource, "download");

// Comprobar espacio en disco antes de iniciar la descarga
            String destinationPath = torrentState.getDestinationPath();
            File downloadDir = new File(destinationPath);
            long availableSpace = downloadDir.getUsableSpace();

            if (availableSpace < MIN_DISK_SPACE) {
                log(Level.WARNING, "Espacio en disco insuficiente para iniciar la descarga", "disk");
                torrentState.setStatus("Error (Espacio insuficiente)");
                torrentState.setStatus("Espacio en disco insuficiente");

// Notificar a los listeners
                for (TorrentNotificationListener listener : notificationListeners) {
                    listener.onDiskSpaceLow(availableSpace, MIN_DISK_SPACE);
                }

// Decrementar contador de descargas activas
                decrementActiveDownloadsCount();
                return;
            }

// Descargar o leer el archivo .torrent
            if (torrentSource != null && !torrentSource.isEmpty()) {
                if (torrentSource.startsWith("http://") || torrentSource.startsWith("https://")) {
// Es una URL, descargar el archivo .torrent
                    log(Level.INFO, "Descargando archivo torrent desde URL: " + torrentSource, "download");
                    torrentFile = downloadTorrentFile(torrentSource, torrentState.getName());
                    if (torrentFile != null && torrentFile.exists()) {
                        torrentData = Files.readAllBytes(torrentFile.toPath());
                        log(Level.INFO, "Archivo torrent descargado y leído: " + torrentFile.getAbsolutePath(), "download");
                    } else {
                        String errorMsg = "No se pudo descargar el archivo torrent desde: " + torrentSource;
                        log(Level.SEVERE, errorMsg, "download");
                        torrentState.setStatus("Error");
                        torrentState.setStatus(errorMsg);
                        notifyTorrentError(torrentState, errorMsg);
                        decrementActiveDownloadsCount();
                        return;
                    }
                } else if (torrentSource.startsWith("magnet:")) {
// Es un enlace magnet, implementar manejo de magnet
                    log(Level.INFO, "Enlace magnet detectado, procesando...", "download");
                    downloadMagnet(torrentSource, torrentState);
                    return;
                } else {
// Es una ruta de archivo local
                    torrentFile = new File(torrentSource);
                    if (torrentFile.exists()) {
                        torrentData = Files.readAllBytes(torrentFile.toPath());
                        log(Level.INFO, "Archivo torrent local leído: " + torrentFile.getAbsolutePath(), "download");
                    } else {
                        String errorMsg = "El archivo torrent local no existe: " + torrentSource;
                        log(Level.SEVERE, errorMsg, "download");
                        torrentState.setStatus("Error");
                        torrentState.setStatus(errorMsg);
                        notifyTorrentError(torrentState, errorMsg);
                        decrementActiveDownloadsCount();
                        return;
                    }
                }
            } else {
                String errorMsg = "Fuente de torrent vacía o nula";
                log(Level.SEVERE, errorMsg, "download");
                torrentState.setStatus("Error");
                torrentState.setStatus(errorMsg);
                notifyTorrentError(torrentState, errorMsg);
                decrementActiveDownloadsCount();
                return;
            }

// Verificar que tenemos datos válidos del torrent
            if (torrentData == null || torrentData.length < 100) {
                String errorMsg = "No se pudo obtener datos válidos del torrent";
                log(Level.SEVERE, errorMsg, "download");
                torrentState.setStatus("Error");
                torrentState.setStatus(errorMsg);
                notifyTorrentError(torrentState, errorMsg);
                decrementActiveDownloadsCount();
                return;
            }

// Decodificar el archivo torrent
            TorrentInfo torrentInfo;
            try {
                torrentInfo = TorrentInfo.bdecode(torrentData);
                log(Level.INFO, "Torrent decodificado correctamente: " + torrentInfo.name(), "download");
            } catch (Exception e) {
                String errorMsg = "Error al decodificar el torrent: " + e.getMessage();
                log(Level.SEVERE, errorMsg, "download");
                torrentState.setStatus("Error");
                torrentState.setStatus(errorMsg);
                notifyTorrentError(torrentState, errorMsg);
                decrementActiveDownloadsCount();
                return;
            }

// Actualizar el nombre del torrent si es necesario
            torrentState.setName(torrentInfo.name());
            torrentState.setFileSize(torrentInfo.totalSize());

// Preparar el directorio de destino
            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                String errorMsg = "No se pudo crear el directorio de destino: " + destinationPath;
                log(Level.SEVERE, errorMsg, "download");
                torrentState.setStatus("Error");
                torrentState.setStatus(errorMsg);
                notifyTorrentError(torrentState, errorMsg);
                decrementActiveDownloadsCount();
                return;
            }

// Configurar los parámetros para añadir el torrent
            AddTorrentParams params = new AddTorrentParams();
            params.torrentInfo(torrentInfo);
            params.savePath(downloadDir.getAbsolutePath());

// Añadir trackers comunes para mejorar la conectividad
            List<String> trackersList = new ArrayList<>();

// Primero añadir los trackers del torrent original
            for (AnnounceEntry tracker : torrentInfo.trackers()) {
                trackersList.add(tracker.url());
            }

// Luego añadir los trackers públicos
            for (String tracker : PUBLIC_TRACKERS) {
                if (!trackersList.contains(tracker)) {
                    trackersList.add(tracker);
                }
            }

            params.trackers(trackersList);

// Configurar flags para el torrent
            params.flags(TorrentFlags.AUTO_MANAGED);

// Añadir el torrent a la sesión usando SessionHandle
            SessionHandle sessionHandle = new SessionHandle(sessionManager.swig());
            sessionHandle.asyncAddTorrent(params);

// Esperar un momento para que el torrent se añada correctamente
            Thread.sleep(1000);

// Buscar el torrent en la sesión
            TorrentHandle th = null;
            for (TorrentHandle handle : sessionHandle.torrents()) {
                if (handle.isValid() && handle.torrentFile() != null &&
                        handle.torrentFile().infoHash().equals(torrentInfo.infoHash())) {
                    th = handle;
                    break;
                }
            }

            if (th != null && th.isValid()) {
                log(Level.INFO, "Torrent añadido correctamente a la sesión: " + torrentInfo.name(), "download");

// Usar unsetFlags para asegurarse de que no está pausado
                th.unsetFlags(TorrentFlags.PAUSED);

// Configurar opciones adicionales para mejorar la descarga
                th.setDownloadLimit(-1);  // Sin límite de descarga individual
                th.setUploadLimit(uploadSpeedLimit > 0 ? uploadSpeedLimit * 1024 / maxConcurrentDownloads : -1);  // Dividir el límite entre las descargas activas

// Guardar los trackers originales
                saveOriginalTrackers(th);

// Añadir trackers públicos para mejorar la conectividad
                addPublicTrackers(th);

// Forzar un reanuncio para conectar con trackers rápidamente
                th.forceReannounce();

// Forzar un anuncio DHT
                th.forceDHTAnnounce();

// Añadir al mapa de descargas activas
                synchronized (activeDownloadsLock) {
                    activeDownloads.put(th.infoHash().toHex(), torrentState);
                }

// Inicializar estadísticas
                initTorrentStats(th);

// Actualizar estado del torrent
                torrentState.setStatus("Descargando");
                torrentState.setProgress(0);

                log(Level.INFO, "Descarga iniciada para: " + torrentInfo.name(), "download");
            } else {
                String errorMsg = "No se pudo añadir el torrent a la sesión";
                log(Level.SEVERE, errorMsg, "download");
                torrentState.setStatus("Error");
                torrentState.setStatus(errorMsg);
                notifyTorrentError(torrentState, errorMsg);
                decrementActiveDownloadsCount();
            }
        } catch (Exception e) {
            log(Level.SEVERE, "Error al descargar torrent: " + e.getMessage(), "download");
            e.printStackTrace();
            torrentState.setStatus("Error");
            torrentState.setStatus("Error al descargar torrent: " + e.getMessage());
            notifyTorrentError(torrentState, "Error al descargar torrent: " + e.getMessage());
            decrementActiveDownloadsCount();
        }
    }

    /**
     * Descarga un torrent a partir de un enlace magnet
     *
     * @param magnetUri El enlace magnet
     * @param torrentState El estado del torrent
     */
    private void downloadMagnet(String magnetUri, TorrentState torrentState) {
        try {
// Preparar el directorio de destino
            String destinationPath = torrentState.getDestinationPath();
            File downloadDir = new File(destinationPath);

            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                String errorMsg = "No se pudo crear el directorio de destino: " + destinationPath;
                log(Level.SEVERE, errorMsg, "download");
                torrentState.setStatus("Error");
                torrentState.setStatus(errorMsg);
                notifyTorrentError(torrentState, errorMsg);
                decrementActiveDownloadsCount();
                return;
            }

// Parsear el enlace magnet para obtener los parámetros
            AddTorrentParams params = AddTorrentParams.parseMagnetUri(magnetUri);
            params.savePath(downloadDir.getAbsolutePath());

// Añadir trackers comunes para mejorar la conectividad
            List<String> trackersList = new ArrayList<>();
            for (String tracker : PUBLIC_TRACKERS) {
                trackersList.add(tracker);
            }
            params.trackers(trackersList);

// Configurar flags para el torrent
            params.flags(TorrentFlags.AUTO_MANAGED);

// Añadir el torrent a la sesión usando SessionHandle
            SessionHandle sessionHandle = new SessionHandle(sessionManager.swig());
            sessionHandle.asyncAddTorrent(params);

// Actualizar el estado
            torrentState.setStatus("Descargando metadatos");
            log(Level.INFO, "Descarga iniciada desde magnet: " + magnetUri, "download");

// Esperar un momento para que el torrent se añada correctamente
            Thread.sleep(1000);

// Buscar el torrent en la sesión por su nombre
            TorrentHandle th = null;
            String magnetName = extractNameFromMagnet(magnetUri);

            for (TorrentHandle handle : sessionHandle.torrents()) {
                if (handle.isValid() && handle.name().equals(magnetName)) {
                    th = handle;
                    break;
                }
            }

            if (th != null && th.isValid()) {
// Configurar opciones adicionales para mejorar la descarga
                th.setDownloadLimit(-1);  // Sin límite de descarga individual
                th.setUploadLimit(uploadSpeedLimit > 0 ? uploadSpeedLimit * 1024 / maxConcurrentDownloads : -1);  // Dividir el límite entre las descargas activas

// Forzar un reanuncio para conectar con trackers rápidamente
                th.forceReannounce();

// Forzar un anuncio DHT
                th.forceDHTAnnounce();

// Añadir al mapa de descargas activas
                synchronized (activeDownloadsLock) {
                    activeDownloads.put(th.infoHash().toHex(), torrentState);
                }

// Inicializar estadísticas
                initTorrentStats(th);

// Actualizar información del torrent
                TorrentInfo ti = th.torrentFile();
                if (ti != null) {
                    torrentState.setName(ti.name());
                    torrentState.setFileSize(ti.totalSize());
                    torrentState.setFileName(ti.name());
                    log(Level.INFO, "Metadatos disponibles inmediatamente: " + ti.name(), "download");
                } else {
                    torrentState.setName(params.name());
                    log(Level.INFO, "Esperando metadatos para: " + params.name(), "download");
                }
            } else {
// Si no se pudo encontrar el torrent, intentar extraer el nombre del magnet
                String name = extractNameFromMagnet(magnetUri);
                if (name != null && !name.isEmpty()) {
                    torrentState.setName(name);
                }

                log(Level.INFO, "Torrent añadido a la sesión, esperando a que esté disponible", "download");
            }

        } catch (Exception e) {
            String errorMsg = "Error al iniciar la descarga magnet: " + e.getMessage();
            log(Level.SEVERE, errorMsg, "download");
            e.printStackTrace();
            torrentState.setStatus("Error");
            torrentState.setStatus(errorMsg);
            notifyTorrentError(torrentState, errorMsg);
            decrementActiveDownloadsCount();
        }
    }

    /**
     * Extrae el nombre de un enlace magnet
     *
     * @param magnetUri El enlace magnet
     * @return El nombre extraído o null si no se encuentra
     */
    private String extractNameFromMagnet(String magnetUri) {
        try {
            if (magnetUri != null && magnetUri.contains("dn=")) {
                int start = magnetUri.indexOf("dn=") + 3;
                int end = magnetUri.indexOf("&", start);
                if (end == -1) end = magnetUri.length();

                String encodedName = magnetUri.substring(start, end);
// Decodificar el nombre (reemplazar %20 por espacios, etc.)
                return URLDecoder.decode(encodedName, "UTF-8");
            }
        } catch (Exception e) {
            log(Level.WARNING, "Error al extraer nombre del magnet: " + e.getMessage(), "magnet");
        }
        return null;
    }

//==========================================================================
// MÉTODOS AUXILIARES
//==========================================================================

    /**
     * Busca el TorrentHandle correspondiente a un TorrentState
     *
     * @param torrentState El estado del torrent
     * @return El TorrentHandle correspondiente, o null si no se encuentra
     */
    private TorrentHandle findTorrentHandle(TorrentState torrentState) {
        try {
// Usar SessionHandle para obtener los torrents
            SessionHandle sessionHandle = new SessionHandle(sessionManager.swig());
            List<TorrentHandle> handles = sessionHandle.torrents();

            for (TorrentHandle th : handles) {
                if (th.isValid() && th.name().equals(torrentState.getName())) {
                    return th;
                }
            }

        } catch (Exception e) {
            log(Level.WARNING, "Error al buscar el torrent handle: " + e.getMessage(), "handle");
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Descarga un archivo .torrent desde una URL
     *
     * @param url La URL del archivo .torrent
     * @param name El nombre para el archivo descargado
     * @return El archivo descargado, o null si ocurre un error
     */
    private File downloadTorrentFile(String url, String name) {
        try {
            log(Level.INFO, "Descargando torrent desde: " + url, "download");

// Crear directorio temporal si no existe
            File tempDir = new File(System.getProperty("java.io.tmpdir"), "torrent_downloader");
            if (!tempDir.exists()) {
                tempDir.mkdirs();
            }

// Crear nombre de archivo seguro
            String safeName = name.replaceAll("[^a-zA-Z0-9.-]", "_");
            String fileName = safeName + "_" + System.currentTimeMillis() + ".torrent";
            File outputFile = new File(tempDir, fileName);

            log(Level.INFO, "Guardando en: " + outputFile.getAbsolutePath(), "download");

// Codificar la URL correctamente
            URL fileUrl;
            try {
// Intentar primero con URI para manejar mejor los caracteres especiales
                URI uri = new URI(url);
                fileUrl = uri.toURL();
            } catch (URISyntaxException e) {
// Si falla, intentar codificar manualmente los componentes problemáticos
                String encodedUrl = url.replace(" ", "%20")
                        .replace("¡", "%C2%A1")
                        .replace("[", "%5B")
                        .replace("]", "%5D")
                        .replace("|", "%7C");
                fileUrl = new URL(encodedUrl);
            }

// Establecer conexión HTTP
            HttpURLConnection connection = (HttpURLConnection) fileUrl.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000); // Aumentado a 15 segundos
            connection.setReadTimeout(45000);    // Aumentado a 45 segundos
            connection.setInstanceFollowRedirects(true); // Seguir redirecciones automáticamente
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

// Verificar respuesta
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                log(Level.SEVERE, "Error al descargar el archivo. Código de respuesta: " + responseCode, "download");
                return null;
            }

// Descargar el archivo
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(outputFile)) {

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

// Verificar que el archivo se descargó correctamente
            if (outputFile.exists() && outputFile.length() > 0) {
                log(Level.INFO, "Archivo torrent descargado correctamente: " + outputFile.getAbsolutePath() + " (" + formatSize(outputFile.length()) + ")", "download");
                return outputFile;
            } else {
                log(Level.SEVERE, "El archivo descargado está vacío o no existe", "download");
                return null;
            }

        } catch (Exception e) {
            log(Level.SEVERE, "Error al descargar el archivo .torrent: " + e.getMessage(), "download");
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Formatea un tamaño en bytes a una representación legible
     *
     * @param bytes El tamaño en bytes
     * @return Una cadena con el tamaño formateado
     */
    public static String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";

        String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));

        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

//==========================================================================
// MÉTODOS PARA EXTRACCIÓN DE ARCHIVOS
//==========================================================================

    /**
     * Busca y extrae archivos comprimidos en un directorio
     *
     * @param directory El directorio donde buscar archivos comprimidos
     */
    private void extractArchiveFiles(File directory) {
        try {
            log(Level.INFO, "Buscando archivos comprimidos en: " + directory.getAbsolutePath(), "extract");

// Buscar archivos comprimidos en el directorio
            File[] files = directory.listFiles();
            if (files == null) {
                log(Level.INFO, "No se encontraron archivos en el directorio", "extract");
                return;
            }

            boolean archivesFound = false;

            for (File file : files) {
                if (file.isFile()) {
                    String extension = FilenameUtils.getExtension(file.getName()).toLowerCase();

                    if (extension.equals("zip") || extension.equals("rar") ||
                            extension.equals("7z") || extension.equals("tar") ||
                            extension.equals("gz") || extension.equals("bz2")) {

                        log(Level.INFO, "Extrayendo archivo comprimido: " + file.getName(), "extract");
                        archivesFound = true;

                        try {
                            extractFile(file, directory);
                            log(Level.INFO, "Extracción completada para: " + file.getName(), "extract");

// Opcionalmente, eliminar el archivo comprimido después de extraerlo
// file.delete();
                        } catch (Exception e) {
                            log(Level.WARNING, "Error al extraer " + file.getName() + ": " + e.getMessage(), "extract");
                            e.printStackTrace();
                        }
                    }
                }
            }

            if (!archivesFound) {
                log(Level.INFO, "No se encontraron archivos comprimidos para extraer", "extract");
            }

        } catch (Exception e) {
            log(Level.SEVERE, "Error al extraer archivos: " + e.getMessage(), "extract");
            e.printStackTrace();
        }
    }

    /**
     * Extrae un archivo comprimido según su extensión
     *
     * @param file El archivo comprimido
     * @param destinationDir El directorio de destino
     * @throws Exception Si ocurre un error durante la extracción
     */
    private void extractFile(File file, File destinationDir) throws Exception {
        String extension = FilenameUtils.getExtension(file.getName()).toLowerCase();

        switch (extension) {
            case "zip":
                extractZip(file, destinationDir);
                break;
            case "rar":
// La extracción de RAR requiere bibliotecas nativas adicionales
// como junrar o usar un proceso externo como unrar
                log(Level.INFO, "Extracción de RAR no implementada directamente. Se recomienda usar unrar.", "extract");
                extractUsingExternalProcess(file, destinationDir);
                break;
            case "7z":
                extract7Zip(file, destinationDir);
                break;
            case "tar":
                extractTar(file, destinationDir);
                break;
            case "gz":
                if (file.getName().endsWith(".tar.gz")) {
                    extractTarGz(file, destinationDir);
                } else {
                    extractGzip(file, destinationDir);
                }
                break;
            case "bz2":
                if (file.getName().endsWith(".tar.bz2")) {
// Implementar extracción de tar.bz2
                    log(Level.INFO, "Extracción de tar.bz2 no implementada", "extract");
                } else {
// Implementar extracción de bz2
                    log(Level.INFO, "Extracción de bz2 no implementada", "extract");
                }
                break;
            default:
                log(Level.INFO, "Formato de archivo no soportado: " + extension, "extract");
        }
    }

    /**
     * Extrae un archivo ZIP
     *
     * @param zipFile El archivo ZIP
     * @param destinationDir El directorio de destino
     * @throws Exception Si ocurre un error durante la extracción
     */
    private void extractZip(File zipFile, File destinationDir) throws Exception {
        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int filesExtracted = 0;
            long totalSize = 0;

            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryDestination = new File(destinationDir, entry.getName());

// Crear directorios padre si no existen
                if (entry.isDirectory()) {
                    entryDestination.mkdirs();
                } else {
                    entryDestination.getParentFile().mkdirs();

                    try (InputStream in = zip.getInputStream(entry);
                         FileOutputStream out = new FileOutputStream(entryDestination)) {

                        IOUtils.copy(in, out);
                        filesExtracted++;
                        totalSize += entry.getSize();
                    }
                }
            }

            log(Level.INFO, "ZIP extraído: " + filesExtracted + " archivos, " + formatSize(totalSize) + " total", "extract");
        }
    }

    /**
     * Extrae un archivo 7Z
     *
     * @param sevenZipFile El archivo 7Z
     * @param destinationDir El directorio de destino
     * @throws Exception Si ocurre un error durante la extracción
     */
    private void extract7Zip(File sevenZipFile, File destinationDir) throws Exception {
        try (SevenZFile sevenZFile = new SevenZFile(sevenZipFile)) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
            int filesExtracted = 0;
            long totalSize = 0;

            while ((entry = sevenZFile.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }

                File entryDestination = new File(destinationDir, entry.getName());
                entryDestination.getParentFile().mkdirs();

                try (FileOutputStream out = new FileOutputStream(entryDestination)) {
                    byte[] content = new byte[(int) entry.getSize()];
                    sevenZFile.read(content, 0, content.length);
                    out.write(content);
                    filesExtracted++;
                    totalSize += entry.getSize();
                }
            }

            log(Level.INFO, "7Z extraído: " + filesExtracted + " archivos, " + formatSize(totalSize) + " total", "extract");
        }
    }

    /**
     * Extrae un archivo TAR
     *
     * @param tarFile El archivo TAR
     * @param destinationDir El directorio de destino
     * @throws Exception Si ocurre un error durante la extracción
     */
    private void extractTar(File tarFile, File destinationDir) throws Exception {
        try (InputStream is = new FileInputStream(tarFile);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(is)) {

            ArchiveEntry entry;
            int filesExtracted = 0;
            long totalSize = 0;

            while ((entry = tarIn.getNextEntry()) != null) {
                File outputFile = new File(destinationDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!outputFile.exists()) {
                        outputFile.mkdirs();
                    }
                } else {
                    outputFile.getParentFile().mkdirs();

                    try (FileOutputStream out = new FileOutputStream(outputFile)) {
                        IOUtils.copy(tarIn, out);
                        filesExtracted++;
                        totalSize += entry.getSize();
                    }
                }
            }

            log(Level.INFO, "TAR extraído: " + filesExtracted + " archivos, " + formatSize(totalSize) + " total", "extract");
        }
    }

    /**
     * Extrae un archivo TAR.GZ
     *
     * @param tarGzFile El archivo TAR.GZ
     * @param destinationDir El directorio de destino
     * @throws Exception Si ocurre un error durante la extracción
     */
    private void extractTarGz(File tarGzFile, File destinationDir) throws Exception {
        try (InputStream fi = new FileInputStream(tarGzFile);
             InputStream gzi = new GzipCompressorInputStream(fi);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(gzi)) {

            ArchiveEntry entry;
            int filesExtracted = 0;
            long totalSize = 0;

            while ((entry = tarIn.getNextEntry()) != null) {
                File outputFile = new File(destinationDir, entry.getName());

                if (entry.isDirectory()) {
                    if (!outputFile.exists()) {
                        outputFile.mkdirs();
                    }
                } else {
                    outputFile.getParentFile().mkdirs();

                    try (FileOutputStream out = new FileOutputStream(outputFile)) {
                        IOUtils.copy(tarIn, out);
                        filesExtracted++;
                        totalSize += entry.getSize();
                    }
                }
            }

            log(Level.INFO, "TAR.GZ extraído: " + filesExtracted + " archivos, " + formatSize(totalSize) + " total", "extract");
        }
    }

    /**
     * Extrae un archivo GZIP
     *
     * @param gzipFile El archivo GZIP
     * @param destinationDir El directorio de destino
     * @throws Exception Si ocurre un error durante la extracción
     */
    private void extractGzip(File gzipFile, File destinationDir) throws Exception {
// Obtener el nombre del archivo sin la extensión .gz
        String fileName = FilenameUtils.getBaseName(gzipFile.getName());
        File outputFile = new File(destinationDir, fileName);

        try (InputStream in = new FileInputStream(gzipFile);
             GzipCompressorInputStream gzIn = new GzipCompressorInputStream(in);
             FileOutputStream out = new FileOutputStream(outputFile)) {

            IOUtils.copy(gzIn, out);
            log(Level.INFO, "GZIP extraído: " + fileName + " (" + formatSize(outputFile.length()) + ")", "extract");
        }
    }

    /**
     * Extrae un archivo usando un proceso externo (unrar, 7z)
     *
     * @param archiveFile El archivo comprimido
     * @param destinationDir El directorio de destino
     */
    private void extractUsingExternalProcess(File archiveFile, File destinationDir) {
        try {
            String extension = FilenameUtils.getExtension(archiveFile.getName()).toLowerCase();
            ProcessBuilder pb = null;

            if (extension.equals("rar")) {
// Intentar usar unrar si está disponible
                pb = new ProcessBuilder("unrar", "x", "-y", archiveFile.getAbsolutePath(), destinationDir.getAbsolutePath());
            } else if (extension.equals("7z")) {
// Intentar usar 7z si está disponible
                pb = new ProcessBuilder("7z", "x", "-y", "-o" + destinationDir.getAbsolutePath(), archiveFile.getAbsolutePath());
            }

            if (pb != null) {
                pb.redirectErrorStream(true);
                Process process = pb.start();

// Leer la salida del proceso
                try (InputStream is = process.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int length;
                    while ((length = is.read(buffer)) != -1) {
                        if (verboseLogging) {
                            System.out.write(buffer, 0, length);
                        }
                    }
                }

                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    log(Level.INFO, "Extracción completada con éxito usando proceso externo", "extract");
                } else {
                    log(Level.WARNING, "Error en la extracción. Código de salida: " + exitCode, "extract");
                }
            } else {
                log(Level.WARNING, "No se pudo determinar el comando para extraer el archivo", "extract");
            }

        } catch (Exception e) {
            log(Level.SEVERE, "Error al ejecutar proceso externo: " + e.getMessage(), "extract");
            e.printStackTrace();
        }
    }

    /**
     * Método para cargar el estado de la sesión
     * @param sessionState Los datos del estado de la sesión
     */
    public void loadState(byte[] sessionState) {
        if (sessionManager != null && sessionState != null && sessionState.length > 0) {
            try {
// En libtorrent4j 2.x, no existe loadState directamente
// En su lugar, debemos crear un SessionParams con los datos y reiniciar la sesión

// Primero detenemos la sesión actual si está en ejecución
                sessionManager.stop();

// Creamos un nuevo SessionParams con los datos guardados
                SessionParams params = new SessionParams(sessionState);

// Reiniciamos la sesión con los parámetros restaurados
                sessionManager.start(params);

// Opcionalmente, podemos reabrir los sockets de red
                sessionManager.reopenNetworkSockets();

                log(Level.INFO, "Estado de la sesión cargado correctamente", "session");
            } catch (Exception e) {
                log(Level.SEVERE, "Error al cargar el estado de la sesión: " + e.getMessage(), "session");
                e.printStackTrace();
            }
        }
    }
}