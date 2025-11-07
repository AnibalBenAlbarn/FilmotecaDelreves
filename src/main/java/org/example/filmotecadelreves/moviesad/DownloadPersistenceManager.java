package org.example.filmotecadelreves.moviesad;

import org.example.filmotecadelreves.UI.DescargasUI;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralised manager that persists the state of direct and torrent downloads
 * into a lightweight embedded SQLite database. The manager exposes a
 * thread-safe API that can be safely invoked from background download threads
 * in order to keep the UI lists in sync after restarting the application.
 */
public final class DownloadPersistenceManager {

    private static final Logger LOGGER = Logger.getLogger(DownloadPersistenceManager.class.getName());
    private static final String DB_FILENAME = "download_state.db";
    private static final String DB_PATH_PROPERTY = "filmoteca.download.db.path";
    private static volatile DownloadPersistenceManager INSTANCE;

    private final Path databasePath;
    private final String jdbcUrl;

    private DownloadPersistenceManager(Path databasePath) {
        this.databasePath = databasePath.toAbsolutePath();
        this.jdbcUrl = "jdbc:sqlite:" + this.databasePath;
        initializeDatabase();
    }

    public static DownloadPersistenceManager getInstance() {
        DownloadPersistenceManager localInstance = INSTANCE;
        if (localInstance == null) {
            synchronized (DownloadPersistenceManager.class) {
                localInstance = INSTANCE;
                if (localInstance == null) {
                    localInstance = new DownloadPersistenceManager(resolveDatabasePath());
                    INSTANCE = localInstance;
                }
            }
        }
        return localInstance;
    }

    static void resetForTests() {
        synchronized (DownloadPersistenceManager.class) {
            INSTANCE = null;
        }
    }

    private static Path resolveDatabasePath() {
        String override = System.getProperty(DB_PATH_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        return Paths.get("DB", DB_FILENAME);
    }

    private void initializeDatabase() {
        try {
            Path parent = databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Class.forName("org.sqlite.JDBC");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "No se pudo inicializar el controlador SQLite", e);
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");

            statement.execute("CREATE TABLE IF NOT EXISTS direct_downloads (" +
                    "id TEXT PRIMARY KEY, " +
                    "name TEXT NOT NULL, " +
                    "url TEXT NOT NULL, " +
                    "server TEXT, " +
                    "destination_path TEXT, " +
                    "actual_file_path TEXT, " +
                    "status TEXT NOT NULL, " +
                    "progress REAL NOT NULL, " +
                    "file_size INTEGER, " +
                    "downloaded_bytes INTEGER, " +
                    "download_speed REAL, " +
                    "remaining_time INTEGER, " +
                    "manually_paused INTEGER NOT NULL DEFAULT 0, " +
                    "etag TEXT, " +
                    "last_modified TEXT, " +
                    "resume_supported INTEGER NOT NULL DEFAULT 0, " +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            ensureColumnExists("direct_downloads", "actual_file_path", "TEXT");
            ensureColumnExists("direct_downloads", "etag", "TEXT");
            ensureColumnExists("direct_downloads", "last_modified", "TEXT");
            ensureColumnExists("direct_downloads", "resume_supported", "INTEGER NOT NULL DEFAULT 0");

            statement.execute("CREATE TABLE IF NOT EXISTS torrent_sessions (" +
                    "id TEXT PRIMARY KEY, " +
                    "source TEXT NOT NULL, " +
                    "destination_path TEXT, " +
                    "name TEXT, " +
                    "status TEXT NOT NULL, " +
                    "progress REAL NOT NULL, " +
                    "file_size INTEGER, " +
                    "download_limit_kib INTEGER, " +
                    "upload_limit_kib INTEGER, " +
                    "priority INTEGER, " +
                    "sequential INTEGER NOT NULL DEFAULT 0, " +
                    "manually_paused INTEGER NOT NULL DEFAULT 0, " +
                    "info_hash TEXT, " +
                    "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                    "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            statement.execute("CREATE INDEX IF NOT EXISTS idx_direct_downloads_status ON direct_downloads(status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_torrent_sessions_status ON torrent_sessions(status)");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "No se pudo inicializar la base de datos de descargas", e);
        }
    }

    public List<DirectDownloadRecord> loadDirectDownloads() {
        String query = "SELECT id, name, url, server, destination_path, actual_file_path, status, progress, file_size, downloaded_bytes, " +
                "download_speed, remaining_time, manually_paused, etag, last_modified, resume_supported " +
                "FROM direct_downloads ORDER BY created_at";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            List<DirectDownloadRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(new DirectDownloadRecord(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("url"),
                        rs.getString("server"),
                        rs.getString("destination_path"),
                        rs.getString("actual_file_path"),
                        rs.getString("status"),
                        rs.getDouble("progress"),
                        rs.getLong("file_size"),
                        rs.getLong("downloaded_bytes"),
                        rs.getDouble("download_speed"),
                        rs.getLong("remaining_time"),
                        rs.getInt("manually_paused") == 1,
                        rs.getString("etag"),
                        rs.getString("last_modified"),
                        rs.getInt("resume_supported") == 1
                ));
            }
            return records;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al cargar descargas directas guardadas", e);
            return Collections.emptyList();
        }
    }

    public List<TorrentDownloadRecord> loadTorrentDownloads() {
        String query = "SELECT id, source, destination_path, name, status, progress, file_size, download_limit_kib, " +
                "upload_limit_kib, priority, sequential, manually_paused, info_hash " +
                "FROM torrent_sessions ORDER BY created_at";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(query);
             ResultSet rs = ps.executeQuery()) {
            List<TorrentDownloadRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(new TorrentDownloadRecord(
                        rs.getString("id"),
                        rs.getString("source"),
                        rs.getString("destination_path"),
                        rs.getString("name"),
                        rs.getString("status"),
                        rs.getDouble("progress"),
                        rs.getLong("file_size"),
                        rs.getInt("download_limit_kib"),
                        rs.getInt("upload_limit_kib"),
                        rs.getInt("priority"),
                        rs.getInt("sequential") == 1,
                        rs.getInt("manually_paused") == 1,
                        rs.getString("info_hash")
                ));
            }
            return records;
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al cargar descargas torrent guardadas", e);
            return Collections.emptyList();
        }
    }

    public void upsertDirectDownload(DescargasUI.DirectDownload download) {
        if (download == null) {
            return;
        }
        upsertDirectDownload(
                download.getId(),
                download.getName(),
                download.getUrl(),
                download.getServer(),
                download.getDestinationPath(),
                download.getActualFilePath(),
                download.getStatus(),
                download.progressProperty().get(),
                download.getFileSize(),
                download.getDownloadedBytes(),
                download.getDownloadSpeed(),
                download.getRemainingTime(),
                download.isUserPaused(),
                download.getEtag(),
                download.getLastModified(),
                download.isResumeSupported()
        );
    }

    public void upsertDirectDownload(String id,
                                      String name,
                                      String url,
                                      String server,
                                      String destinationPath,
                                      String actualFilePath,
                                      String status,
                                      double progress,
                                      long fileSize,
                                      long downloadedBytes,
                                      double downloadSpeed,
                                      long remainingTime,
                                      boolean manuallyPaused,
                                      String etag,
                                      String lastModified,
                                      boolean resumeSupported) {
        String sql = "INSERT INTO direct_downloads (id, name, url, server, destination_path, actual_file_path, status, progress, file_size, " +
                "downloaded_bytes, download_speed, remaining_time, manually_paused, etag, last_modified, resume_supported) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "name = excluded.name, " +
                "url = excluded.url, " +
                "server = excluded.server, " +
                "destination_path = excluded.destination_path, " +
                "actual_file_path = excluded.actual_file_path, " +
                "status = excluded.status, " +
                "progress = excluded.progress, " +
                "file_size = excluded.file_size, " +
                "downloaded_bytes = excluded.downloaded_bytes, " +
                "download_speed = excluded.download_speed, " +
                "remaining_time = excluded.remaining_time, " +
                "manually_paused = excluded.manually_paused, " +
                "etag = excluded.etag, " +
                "last_modified = excluded.last_modified, " +
                "resume_supported = excluded.resume_supported, " +
                "updated_at = CURRENT_TIMESTAMP";

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, Objects.toString(name, ""));
            ps.setString(3, Objects.toString(url, ""));
            ps.setString(4, server);
            ps.setString(5, destinationPath);
            ps.setString(6, actualFilePath);
            ps.setString(7, Objects.toString(status, "Waiting"));
            ps.setDouble(8, progress);
            ps.setLong(9, fileSize);
            ps.setLong(10, downloadedBytes);
            ps.setDouble(11, downloadSpeed);
            ps.setLong(12, remainingTime);
            ps.setInt(13, manuallyPaused ? 1 : 0);
            ps.setString(14, etag);
            ps.setString(15, lastModified);
            ps.setInt(16, resumeSupported ? 1 : 0);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al guardar el estado de una descarga directa", e);
        }
    }

    public void deleteDirectDownload(String id) {
        if (id == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement("DELETE FROM direct_downloads WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al eliminar una descarga directa", e);
        }
    }

    public void upsertTorrent(TorrentState state) {
        if (state == null) {
            return;
        }
        upsertTorrent(
                state.getInstanceId(),
                state.getTorrentSource(),
                state.getDestinationPath(),
                state.getName(),
                state.getStatus(),
                state.getProgress(),
                state.getFileSize(),
                state.getDownloadLimitKiB(),
                state.getUploadLimitKiB(),
                state.getPriority(),
                state.isSequentialDownload(),
                state.isUserPaused(),
                state.getHash()
        );
    }

    public void upsertTorrent(String id,
                               String source,
                               String destinationPath,
                               String name,
                               String status,
                               double progress,
                               long fileSize,
                               int downloadLimit,
                               int uploadLimit,
                               int priority,
                               boolean sequential,
                               boolean manuallyPaused,
                               String infoHash) {
        String sql = "INSERT INTO torrent_sessions (id, source, destination_path, name, status, progress, file_size, " +
                "download_limit_kib, upload_limit_kib, priority, sequential, manually_paused, info_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "source = excluded.source, " +
                "destination_path = excluded.destination_path, " +
                "name = excluded.name, " +
                "status = excluded.status, " +
                "progress = excluded.progress, " +
                "file_size = excluded.file_size, " +
                "download_limit_kib = excluded.download_limit_kib, " +
                "upload_limit_kib = excluded.upload_limit_kib, " +
                "priority = excluded.priority, " +
                "sequential = excluded.sequential, " +
                "manually_paused = excluded.manually_paused, " +
                "info_hash = excluded.info_hash, " +
                "updated_at = CURRENT_TIMESTAMP";

        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, Objects.toString(source, ""));
            ps.setString(3, destinationPath);
            ps.setString(4, name);
            ps.setString(5, Objects.toString(status, "En espera"));
            ps.setDouble(6, progress);
            ps.setLong(7, fileSize);
            if (downloadLimit > 0) {
                ps.setInt(8, downloadLimit);
            } else {
                ps.setNull(8, java.sql.Types.INTEGER);
            }
            if (uploadLimit > 0) {
                ps.setInt(9, uploadLimit);
            } else {
                ps.setNull(9, java.sql.Types.INTEGER);
            }
            if (priority > 0) {
                ps.setInt(10, priority);
            } else {
                ps.setNull(10, java.sql.Types.INTEGER);
            }
            ps.setInt(11, sequential ? 1 : 0);
            ps.setInt(12, manuallyPaused ? 1 : 0);
            ps.setString(13, infoHash);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al guardar el estado de un torrent", e);
        }
    }

    public void deleteTorrent(String id) {
        if (id == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement("DELETE FROM torrent_sessions WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al eliminar un torrent guardado", e);
        }
    }

    public void clearAllData() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM direct_downloads");
            statement.executeUpdate("DELETE FROM torrent_sessions");
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "Error al limpiar la base de datos de descargas", e);
        }
    }

    public void close() {
        // Connections are managed per-operation; nothing to close explicitly.
        LOGGER.fine("Gestor de persistencia de descargas cerrado a las " + Instant.now());
    }

    public static final class DirectDownloadRecord {
        private final String id;
        private final String name;
        private final String url;
        private final String server;
        private final String destinationPath;
        private final String actualFilePath;
        private final String status;
        private final double progress;
        private final long fileSize;
        private final long downloadedBytes;
        private final double downloadSpeed;
        private final long remainingTime;
        private final boolean manuallyPaused;
        private final String etag;
        private final String lastModified;
        private final boolean resumeSupported;

        public DirectDownloadRecord(String id,
                                    String name,
                                    String url,
                                    String server,
                                    String destinationPath,
                                    String actualFilePath,
                                    String status,
                                    double progress,
                                    long fileSize,
                                    long downloadedBytes,
                                    double downloadSpeed,
                                    long remainingTime,
                                    boolean manuallyPaused,
                                    String etag,
                                    String lastModified,
                                    boolean resumeSupported) {
            this.id = id;
            this.name = name;
            this.url = url;
            this.server = server;
            this.destinationPath = destinationPath;
            this.actualFilePath = actualFilePath;
            this.status = status;
            this.progress = progress;
            this.fileSize = fileSize;
            this.downloadedBytes = downloadedBytes;
            this.downloadSpeed = downloadSpeed;
            this.remainingTime = remainingTime;
            this.manuallyPaused = manuallyPaused;
            this.etag = etag;
            this.lastModified = lastModified;
            this.resumeSupported = resumeSupported;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getUrl() {
            return url;
        }

        public String getServer() {
            return server;
        }

        public String getDestinationPath() {
            return destinationPath;
        }

        public String getActualFilePath() {
            return actualFilePath;
        }

        public String getStatus() {
            return status;
        }

        public double getProgress() {
            return progress;
        }

        public long getFileSize() {
            return fileSize;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public double getDownloadSpeed() {
            return downloadSpeed;
        }

        public long getRemainingTime() {
            return remainingTime;
        }

        public boolean isManuallyPaused() {
            return manuallyPaused;
        }

        public String getEtag() {
            return etag;
        }

        public String getLastModified() {
            return lastModified;
        }

        public boolean isResumeSupported() {
            return resumeSupported;
        }
    }

    private void ensureColumnExists(String tableName, String columnName, String definition) {
        String pragma = "PRAGMA table_info(" + tableName + ")";
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement ps = connection.prepareStatement(pragma);
             ResultSet rs = ps.executeQuery()) {
            boolean found = false;
            while (rs.next()) {
                if (columnName.equalsIgnoreCase(rs.getString("name"))) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "No se pudo verificar o añadir la columna " + columnName + " en " + tableName, e);
        }
    }

    public static final class TorrentDownloadRecord {
        private final String id;
        private final String source;
        private final String destinationPath;
        private final String name;
        private final String status;
        private final double progress;
        private final long fileSize;
        private final int downloadLimitKiB;
        private final int uploadLimitKiB;
        private final int priority;
        private final boolean sequential;
        private final boolean manuallyPaused;
        private final String infoHash;

        public TorrentDownloadRecord(String id,
                                     String source,
                                     String destinationPath,
                                     String name,
                                     String status,
                                     double progress,
                                     long fileSize,
                                     int downloadLimitKiB,
                                     int uploadLimitKiB,
                                     int priority,
                                     boolean sequential,
                                     boolean manuallyPaused,
                                     String infoHash) {
            this.id = id;
            this.source = source;
            this.destinationPath = destinationPath;
            this.name = name;
            this.status = status;
            this.progress = progress;
            this.fileSize = fileSize;
            this.downloadLimitKiB = downloadLimitKiB;
            this.uploadLimitKiB = uploadLimitKiB;
            this.priority = priority;
            this.sequential = sequential;
            this.manuallyPaused = manuallyPaused;
            this.infoHash = infoHash;
        }

        public String getId() {
            return id;
        }

        public String getSource() {
            return source;
        }

        public String getDestinationPath() {
            return destinationPath;
        }

        public String getName() {
            return name;
        }

        public String getStatus() {
            return status;
        }

        public double getProgress() {
            return progress;
        }

        public long getFileSize() {
            return fileSize;
        }

        public int getDownloadLimitKiB() {
            return downloadLimitKiB;
        }

        public int getUploadLimitKiB() {
            return uploadLimitKiB;
        }

        public int getPriority() {
            return priority;
        }

        public boolean isSequential() {
            return sequential;
        }

        public boolean isManuallyPaused() {
            return manuallyPaused;
        }

        public String getInfoHash() {
            return infoHash;
        }
    }
}
