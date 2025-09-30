package org.example.filmotecadelreves.moviesad;

import org.example.filmotecadelreves.UI.DirectDownloadUI;
import org.example.filmotecadelreves.UI.TorrentDownloadUI;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.io.File;
import java.util.Date;

public class ConnectDataBase {
    private String dbPath;
    private Connection connection;
    private static final String DB_DIRECTORY = "DB";
    private boolean isUpdating = false;
    private Date lastUpdateDate = null;

    // Cache de calidades para evitar ejecuciones repetidas de la consulta
    private ObservableList<Quality> qualityCache = null;

    public ConnectDataBase(String dbName) {
        // Verificar si dbName ya contiene la ruta completa o parcial
        if (dbName.contains("/") || dbName.contains("\\")) {
            // Si ya contiene separadores de ruta, usarlo como está
            this.dbPath = dbName;
        } else {
            // Si es solo un nombre, construir la ruta correcta
            if (dbName.endsWith(".db")) {
                this.dbPath = DB_DIRECTORY + File.separator + dbName;
            } else {
                this.dbPath = DB_DIRECTORY + File.separator + dbName + ".db";
            }
        }

        System.out.println("Ruta de la base de datos: " + new File(dbPath).getAbsolutePath());

        // Inicializar el directorio de la base de datos y establecer la conexión
        initializeDatabaseDirectory();
        connect();
    }

    private void initializeDatabaseDirectory() {
        try {
            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                return;
            }

            File parentDir = dbFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                System.err.println("El directorio de la base de datos no existe: " + parentDir.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Error al verificar el directorio de la base de datos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public boolean connect() {
        try {
            // Cargar el driver JDBC de SQLite
            Class.forName("org.sqlite.JDBC");

            // Verificar si el directorio existe antes de intentar conectar
            File dbFile = new File(dbPath);
            File parentDir = dbFile.getParentFile();

            if (!dbFile.exists() || dbFile.isDirectory()) {
                System.err.println("La base de datos no existe en la ruta indicada: " + dbFile.getAbsolutePath());
                System.err.println("Selecciona un archivo .db existente creado por los scripts antes de continuar.");
                return false;
            }

            if (parentDir != null && !parentDir.exists()) {
                System.err.println("El directorio no existe: " + parentDir.getAbsolutePath());
                return false;
            }

            // Conectarse únicamente si la base de datos ya fue creada por los scripts externos
            String url = "jdbc:sqlite:" + dbPath;
            System.out.println("Intentando conectar a: " + url);

            connection = DriverManager.getConnection(url);

            if (connection != null) {
                System.out.println("Conexión exitosa a: " + dbPath);
                // Establecer la fecha de última actualización al momento de la conexión
                lastUpdateDate = new Date();
                // Crear tablas si no existen
                initializeTables();
                return true;
            }
            return false;
        } catch (ClassNotFoundException | SQLException e) {
            System.err.println("Error de conexión: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed();
        } catch (SQLException e) {
            System.err.println("Error al verificar conexión: " + e.getMessage());
            return false;
        }
    }

    public Connection getConnection() {
        // Verificar y reconectar si es necesario
        try {
            if (connection == null || connection.isClosed()) {
                connect();
            }
        } catch (SQLException e) {
            System.err.println("Error al obtener conexión: " + e.getMessage());
        }
        return connection;
    }

    /**
     * Crea las tablas necesarias para la base de datos si aún no existen.
     * El esquema se determina según el nombre de la base de datos.
     */
    private void initializeTables() {
        if (connection == null) {
            return;
        }

        String lowerPath = dbPath.toLowerCase();
        try (Statement stmt = connection.createStatement()) {
            if (lowerPath.contains("direct_dw_db")) {
                // Tablas para descargas directas
                String[] directSql = {
                    "CREATE TABLE IF NOT EXISTS episode_update_stats (" +
                        "update_date DATE PRIMARY KEY, " +
                        "duration_minutes REAL, new_series INTEGER, new_seasons INTEGER, " +
                        "new_episodes INTEGER, new_links INTEGER, " +
                        "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS links_files_download (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, movie_id INTEGER, server_id INTEGER, " +
                        "language TEXT, link TEXT, quality_id INTEGER, episode_id INTEGER, " +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY(episode_id) REFERENCES series_episodes(id), " +
                        "FOREIGN KEY(movie_id) REFERENCES media_downloads(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(quality_id) REFERENCES qualities(quality_id), " +
                        "FOREIGN KEY(server_id) REFERENCES servers(id))",
                    "CREATE TABLE IF NOT EXISTS media_downloads (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, year INTEGER, imdb_rating REAL, " +
                        "genre TEXT, type TEXT CHECK(type IN ('movie','serie')), " +
                        "created_at DATETIME DEFAULT CURRENT_TIMESTAMP, " +
                        "updated_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS qualities (" +
                        "quality_id INTEGER PRIMARY KEY AUTOINCREMENT, quality TEXT)",
                    "CREATE TABLE IF NOT EXISTS series_episodes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, season_id INTEGER, episode INTEGER, title TEXT, " +
                        "FOREIGN KEY(season_id) REFERENCES series_seasons(id))",
                    "CREATE TABLE IF NOT EXISTS series_seasons (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, movie_id INTEGER, season INTEGER, " +
                        "FOREIGN KEY(movie_id) REFERENCES media_downloads(id))",
                    "CREATE TABLE IF NOT EXISTS servers (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE)",
                    "CREATE TABLE IF NOT EXISTS update_stats (" +
                        "update_date DATE PRIMARY KEY, duration_minutes REAL, updated_movies INTEGER, " +
                        "new_links INTEGER, created_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE INDEX IF NOT EXISTS idx_episodes_season ON series_episodes(season_id)",
                    "CREATE INDEX IF NOT EXISTS idx_links_episode ON links_files_download(episode_id)",
                    "CREATE INDEX IF NOT EXISTS idx_links_episode_id ON links_files_download(episode_id)",
                    "CREATE INDEX IF NOT EXISTS idx_links_movie ON links_files_download(movie_id)",
                    "CREATE INDEX IF NOT EXISTS idx_links_movie_id ON links_files_download(movie_id)",
                    "CREATE INDEX IF NOT EXISTS idx_links_quality ON links_files_download(quality_id)",
                    "CREATE INDEX IF NOT EXISTS idx_links_server ON links_files_download(server_id)",
                    "CREATE INDEX IF NOT EXISTS idx_media_created ON media_downloads(created_at)",
                    "CREATE INDEX IF NOT EXISTS idx_media_downloads_title ON media_downloads(title, type)",
                    "CREATE INDEX IF NOT EXISTS idx_media_title ON media_downloads(title COLLATE NOCASE)",
                    "CREATE INDEX IF NOT EXISTS idx_media_type ON media_downloads(type)",
                    "CREATE INDEX IF NOT EXISTS idx_media_year ON media_downloads(year)",
                    "CREATE INDEX IF NOT EXISTS idx_seasons_movie ON series_seasons(movie_id)",
                    "CREATE INDEX IF NOT EXISTS idx_series_episodes_season_id ON series_episodes(season_id)",
                    "CREATE INDEX IF NOT EXISTS idx_series_seasons_movie_id ON series_seasons(movie_id)"
                };

                for (String sql : directSql) {
                    stmt.executeUpdate(sql);
                }
            } else if (lowerPath.contains("torrent_dw_db")) {
                // Tablas para descargas por torrent
                String[] torrentSql = {
                    "CREATE TABLE IF NOT EXISTS qualities (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, quality TEXT NOT NULL UNIQUE)",
                    "CREATE TABLE IF NOT EXISTS series_episodes (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, season_id INTEGER NOT NULL, " +
                        "episode_number INTEGER NOT NULL, title TEXT NOT NULL, " +
                        "FOREIGN KEY(season_id) REFERENCES series_seasons(id) ON DELETE CASCADE)",
                    "CREATE TABLE IF NOT EXISTS series_seasons (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, series_id INTEGER NOT NULL, " +
                        "season_number INTEGER NOT NULL, FOREIGN KEY(series_id) REFERENCES torrent_downloads(id) ON DELETE CASCADE)",
                    "CREATE TABLE IF NOT EXISTS torrent_downloads (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, year INTEGER NOT NULL, " +
                        "genre TEXT, director TEXT, type TEXT NOT NULL CHECK(type IN ('movie','series')), " +
                        "added_at DATETIME DEFAULT CURRENT_TIMESTAMP)",
                    "CREATE TABLE IF NOT EXISTS torrent_files (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, torrent_id INTEGER, episode_id INTEGER, " +
                        "quality_id INTEGER NOT NULL, torrent_link TEXT NOT NULL, " +
                        "FOREIGN KEY(episode_id) REFERENCES series_episodes(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(quality_id) REFERENCES qualities(id) ON DELETE CASCADE, " +
                        "FOREIGN KEY(torrent_id) REFERENCES torrent_downloads(id) ON DELETE CASCADE)",
                    "CREATE INDEX IF NOT EXISTS idx_episodes_season ON series_episodes(season_id)",
                    "CREATE INDEX IF NOT EXISTS idx_seasons_series ON series_seasons(series_id)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_added ON torrent_downloads(added_at)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_episode ON torrent_files(episode_id)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_files_episode ON torrent_files(episode_id)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_files_quality ON torrent_files(quality_id)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_quality ON torrent_files(quality_id)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_title ON torrent_downloads(title COLLATE NOCASE)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_type ON torrent_downloads(type)",
                    "CREATE INDEX IF NOT EXISTS idx_torrent_year ON torrent_downloads(year)"
                };

                for (String sql : torrentSql) {
                    stmt.executeUpdate(sql);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error al crear tablas: " + e.getMessage());
        }
    }

    // Methods for direct download database

    // Search movies in direct download database
    public ObservableList<DirectDownloadUI.Movie> searchMovies(String searchTerm) {
        ObservableList<DirectDownloadUI.Movie> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT m.id, m.title, m.year, m.genre, " +
                "l.language, q.quality, s.name as server, l.link " +
                "FROM media_downloads m " +
                "LEFT JOIN links_files_download l ON m.id = l.movie_id " +
                "LEFT JOIN qualities q ON l.quality_id = q.quality_id " +
                "LEFT JOIN servers s ON l.server_id = s.id " +
                "WHERE m.type = 'movie' AND m.title LIKE ? " +
                "GROUP BY m.id " +  // Evita duplicados
                "ORDER BY m.created_at DESC";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, "%" + searchTerm + "%");
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String year = rs.getString("year");
                String genre = rs.getString("genre");
                String language = rs.getString("language");
                String quality = rs.getString("quality");
                String server = rs.getString("server");
                String link = rs.getString("link");

                results.add(new DirectDownloadUI.Movie(id, title, year, genre, language, quality, server, link));
            }
        } catch (SQLException e) {
            System.err.println("Error searching movies: " + e.getMessage());
        }
        return results;
    }

    // Search movies with filters in direct download database
    public ObservableList<DirectDownloadUI.Movie> searchMoviesWithFilters(String searchTerm, String yearFilter, String genre, String language, String quality) {
        ObservableList<DirectDownloadUI.Movie> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        StringBuilder query = new StringBuilder("SELECT m.id, m.title, m.year, m.genre, l.language, q.quality, s.name as server, l.link " +
                "FROM media_downloads m " +
                "JOIN links_files_download l ON m.id = l.movie_id " +
                "JOIN qualities q ON l.quality_id = q.quality_id " +
                "JOIN servers s ON l.server_id = s.id " +
                "WHERE m.type = 'movie'");

        List<Object> params = new ArrayList<>();

        if (searchTerm != null && !searchTerm.isEmpty()) {
            query.append(" AND m.title LIKE ?");
            params.add("%" + searchTerm + "%");
        }

        if (yearFilter != null && !yearFilter.isEmpty()) {
            try {
                query.append(" AND m.year = ?");
                params.add(Integer.parseInt(yearFilter));
            } catch (NumberFormatException e) {
                System.err.println("Invalid year format: " + yearFilter);
            }
        }

        if (genre != null && !genre.isEmpty()) {
            query.append(" AND m.genre LIKE ?");
            params.add("%" + genre + "%");
        }

        if (language != null && !language.isEmpty()) {
            query.append(" AND l.language = ?");
            params.add(language);
        }

        if (quality != null && !quality.isEmpty()) {
            query.append(" AND q.quality = ?");
            params.add(quality);
        }

        query.append(" ORDER BY m.created_at DESC");

        try (PreparedStatement stmt = connection.prepareStatement(query.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            Map<Integer, DirectDownloadUI.Movie> moviesMap = new HashMap<>();

            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String year = String.valueOf(rs.getInt("year"));
                String movieGenre = rs.getString("genre");
                String movieLanguage = rs.getString("language");
                String movieQuality = rs.getString("quality");
                String server = rs.getString("server");
                String link = rs.getString("link");

                // If we haven't seen this movie yet, or if this quality is better than what we have
                if (!moviesMap.containsKey(id)) {
                    moviesMap.put(id, new DirectDownloadUI.Movie(
                            id, title, year, movieGenre, movieLanguage, movieQuality, server, link));
                }
            }

            results.addAll(moviesMap.values());
            System.out.println("Movies found with filters: " + results.size());
        } catch (SQLException e) {
            System.err.println("Error searching with filters: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    // Get latest movies from direct download database
    public ObservableList<DirectDownloadUI.Movie> getLatestMovies(int limit) {
        ObservableList<DirectDownloadUI.Movie> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT m.id, m.title, m.year, m.genre, " +
                "(SELECT GROUP_CONCAT(DISTINCT l.language) FROM links_files_download l WHERE l.movie_id = m.id) AS languages, " +
                "(SELECT GROUP_CONCAT(DISTINCT s.name) FROM links_files_download l JOIN servers s ON l.server_id = s.id WHERE l.movie_id = m.id) AS servers, " +
                "(SELECT l.link FROM links_files_download l WHERE l.movie_id = m.id LIMIT 1) AS link " +
                "FROM media_downloads m " +
                "WHERE m.type = 'movie' " +
                "GROUP BY m.id " +
                "ORDER BY m.created_at DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                DirectDownloadUI.Movie movie = new DirectDownloadUI.Movie(
                        rs.getInt("id"),
                        rs.getString("title"),
                        rs.getString("year"),
                        rs.getString("genre"),
                        "", // Idioma se manejará aparte
                        "", // Calidad se manejará aparte
                        "", // Servidor inicial
                        rs.getString("link")
                );

                // Procesar servidores y lenguajes
                String servers = rs.getString("servers");
                String languages = rs.getString("languages");

                if (servers != null) {
                    movie.setAvailableServers(Arrays.asList(servers.split(",")));
                }

                if (languages != null) {
                    movie.setAvailableLanguages(Arrays.asList(languages.split(",")));
                }

                results.add(movie);
            }
        } catch (SQLException e) {
            System.err.println("Error getting latest movies: " + e.getMessage());
        }
        return results;
    }

    // Methods for series in direct download database
    public ObservableList<DirectDownloadUI.Series> searchSeries(String searchTerm) {
        ObservableList<DirectDownloadUI.Series> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT id, title, year, genre, imdb_rating FROM media_downloads " +
                "WHERE type = 'serie' AND title LIKE ? " +
                "ORDER BY created_at DESC";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, "%" + searchTerm + "%");

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String name = rs.getString("title");
                    String year = String.valueOf(rs.getInt("year"));
                    String genre = rs.getString("genre");
                    String rating = String.valueOf(rs.getDouble("imdb_rating"));

                    results.add(new DirectDownloadUI.Series(id, name, year, rating, genre));
                }
            }

            System.out.println("Results found: " + results.size());
        } catch (SQLException e) {
            System.err.println("Error searching series: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    public ObservableList<DirectDownloadUI.Series> searchSeriesWithFilters(String searchTerm, String year, String genre, String language) {
        ObservableList<DirectDownloadUI.Series> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        StringBuilder queryBuilder = new StringBuilder("SELECT DISTINCT m.id, m.title, m.year, m.genre, m.imdb_rating " +
                "FROM media_downloads m " +
                "LEFT JOIN series_seasons ss ON m.id = ss.movie_id " +
                "LEFT JOIN series_episodes se ON ss.id = se.season_id " +
                "LEFT JOIN links_files_download l ON se.id = l.episode_id " +
                "WHERE m.type = 'serie'");

        List<Object> params = new ArrayList<>();

        if (searchTerm != null && !searchTerm.isEmpty()) {
            queryBuilder.append(" AND m.title LIKE ?");
            params.add("%" + searchTerm + "%");
        }

        if (year != null && !year.isEmpty()) {
            queryBuilder.append(" AND m.year = ?");
            try {
                params.add(Integer.parseInt(year));
            } catch (NumberFormatException e) {
                System.err.println("Invalid year format: " + year);
                params.add(0); // Default value
            }
        }

        if (genre != null && !genre.isEmpty()) {
            queryBuilder.append(" AND m.genre LIKE ?");
            params.add("%" + genre + "%");
        }

        if (language != null && !language.isEmpty()) {
            queryBuilder.append(" AND l.language = ?");
            params.add(language);
        }

        queryBuilder.append(" ORDER BY m.created_at DESC");

        try (PreparedStatement stmt = connection.prepareStatement(queryBuilder.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String name = rs.getString("title");
                    String seriesYear = String.valueOf(rs.getInt("year"));
                    String seriesGenre = rs.getString("genre");
                    String rating = String.valueOf(rs.getDouble("imdb_rating"));

                    results.add(new DirectDownloadUI.Series(id, name, seriesYear, rating, seriesGenre));
                }
            }

            System.out.println("Results found: " + results.size());
        } catch (SQLException e) {
            System.err.println("Error searching series with filters: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    public <T> ObservableList<T> getLatestSeries(int limit, Class<T> seriesClass) {
        ObservableList<T> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT id, title, year, genre, imdb_rating FROM media_downloads " +
                "WHERE type = 'serie' " +
                "ORDER BY created_at DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, limit);

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    String name = rs.getString("title");
                    String year = String.valueOf(rs.getInt("year"));
                    String genre = rs.getString("genre");
                    String rating = String.valueOf(rs.getDouble("imdb_rating"));

                    T series;
                    if (seriesClass == DirectDownloadUI.Series.class) {
                        series = (T) new DirectDownloadUI.Series(id, name, year, rating, genre);
                    } else if (seriesClass == TorrentDownloadUI.Series.class) {
                        series = (T) new TorrentDownloadUI.Series(id, name, year, rating, genre, "");
                    } else {
                        throw new IllegalArgumentException("Unsupported series class: " + seriesClass.getName());
                    }
                    results.add(series);
                }
            }

            System.out.println("Results found: " + results.size());
        } catch (SQLException e) {
            System.err.println("Error getting latest series: " + e.getMessage());
            e.printStackTrace();
        }

        return results;
    }

    public ObservableList<String> getUniqueDirectors() {
        ObservableList<String> directors = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return directors;
        }

        String query = "SELECT DISTINCT director FROM media_downloads WHERE director IS NOT NULL ORDER BY director";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                directors.add(rs.getString("director"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting unique directors: " + e.getMessage());
            e.printStackTrace();
        }

        return directors;
    }

    public ObservableList<TorrentDownloadUI.TorrentFile> getTorrentFiles(int episodeId) {
        ObservableList<TorrentDownloadUI.TorrentFile> torrentFiles = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return torrentFiles;
        }

        String query = "SELECT tf.id, tf.torrent_id, tf.episode_id, tf.quality_id, tf.torrent_link, q.quality " +
                "FROM torrent_files tf " +
                "JOIN qualities q ON tf.quality_id = q.id " +
                "WHERE tf.episode_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, episodeId);

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int torrentId = rs.getInt("torrent_id");
                    Integer epId = rs.getInt("episode_id");
                    int qualityId = rs.getInt("quality_id");
                    String torrentLink = rs.getString("torrent_link");
                    String quality = rs.getString("quality");

                    torrentFiles.add(new TorrentDownloadUI.TorrentFile(id, torrentId, epId, qualityId, torrentLink, quality));
                }
            }

            System.out.println("Torrent files found: " + torrentFiles.size());
        } catch (SQLException e) {
            System.err.println("Error getting torrent files: " + e.getMessage());
            e.printStackTrace();
        }

        return torrentFiles;
    }

    // Methods for seasons and episodes in direct download database
    public List<Season> getSeasons(int seriesId) {
        List<Season> seasons = new ArrayList<>();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return seasons;
        }

        String query = "SELECT id, series_id, season_number FROM series_seasons " +
                "WHERE series_id = ? " +
                "ORDER BY season_number";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, seriesId);

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int movie_id = rs.getInt("series_id");
                    int seasonNumber = rs.getInt("season_number");

                    seasons.add(new Season(id, movie_id, seasonNumber));
                }
            }

            System.out.println("Seasons found: " + seasons.size());
        } catch (SQLException e) {
            System.err.println("Error getting seasons: " + e.getMessage());
            e.printStackTrace();
        }

        return seasons;
    }

    public ObservableList<DirectDownloadUI.Episode> getEpisodesBySeason(int seasonId) {
        ObservableList<DirectDownloadUI.Episode> episodes = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return episodes;
        }

        String query = "SELECT id, season_id, episode, title FROM series_episodes " +
                "WHERE season_id = ? " +
                "ORDER BY episode";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, seasonId);

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int season_id = rs.getInt("season_id");
                    int episodeNumber = rs.getInt("episode");
                    String title = rs.getString("title");

                    episodes.add(new DirectDownloadUI.Episode(id, season_id, episodeNumber, title));
                }
            }

            System.out.println("Episodes found: " + episodes.size());
        } catch (SQLException e) {
            System.err.println("Error getting episodes: " + e.getMessage());
            e.printStackTrace();
        }

        return episodes;
    }

    public ObservableList<DirectDownloadUI.DirectFile> getDirectFiles(int episodeId) {
        ObservableList<DirectDownloadUI.DirectFile> directFiles = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return directFiles;
        }

        String query = "SELECT l.id, l.movie_id, l.episode_id, l.quality_id, l.link, l.language, s.name as server, q.quality " +
                "FROM links_files_download l " +
                "JOIN qualities q ON l.quality_id = q.quality_id " +
                "JOIN servers s ON l.server_id = s.id " +
                "WHERE l.episode_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, episodeId);

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int fileId = rs.getInt("movie_id");
                    Integer epId = rs.getInt("episode_id");
                    int qualityId = rs.getInt("quality_id");
                    String link = rs.getString("link");
                    String language = rs.getString("language");
                    String server = rs.getString("server");
                    String quality = rs.getString("quality");

                    directFiles.add(new DirectDownloadUI.DirectFile(id, fileId, epId, qualityId, link, language, server, quality));
                }
            }

            System.out.println("Direct files found: " + directFiles.size());
        } catch (SQLException e) {
            System.err.println("Error getting direct files: " + e.getMessage());
            e.printStackTrace();
        }

        return directFiles;
    }

    public ObservableList<DirectDownloadUI.DirectFile> getMovieDirectFiles(int movieId) {
        ObservableList<DirectDownloadUI.DirectFile> directFiles = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return directFiles;
        }

        String query = "SELECT l.id, l.movie_id, l.episode_id, l.quality_id, l.link, l.language, s.name as server, q.quality " +
                "FROM links_files_download l " +
                "JOIN qualities q ON l.quality_id = q.quality_id " +
                "JOIN servers s ON l.server_id = s.id " +
                "WHERE l.movie_id = ? AND l.episode_id IS NULL";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, movieId);

            //System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int fileId = rs.getInt("movie_id");
                    Integer epId = rs.getObject("episode_id") != null ? rs.getInt("episode_id") : null;
                    int qualityId = rs.getInt("quality_id");
                    String link = rs.getString("link");
                    String language = rs.getString("language");
                    String server = rs.getString("server");
                    String quality = rs.getString("quality");

                    directFiles.add(new DirectDownloadUI.DirectFile(id, fileId, epId, qualityId, link, language, server, quality));
                }
            }

            //System.out.println("Movie direct files found: " + directFiles.size());
        } catch (SQLException e) {
            System.err.println("Error getting movie direct files: " + e.getMessage());
            e.printStackTrace();
        }

        return directFiles;
    }

    // Methods for qualities
    public ObservableList<Quality> getQualities() {
        // Si ya se consultaron las calidades anteriormente, devolver el caché
        if (qualityCache != null) {
            return qualityCache;
        }
        ObservableList<Quality> qualities = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return qualities;
        }

        String query = "SELECT id, quality FROM qualities ORDER BY id";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                int id = rs.getInt("id");
                String quality = rs.getString("quality");

                qualities.add(new Quality(id, quality));
            }

            // System.out.println("Qualities found: " + qualities.size());
            // Guardar el resultado en caché para futuras consultas
            qualityCache = qualities;
        } catch (SQLException e) {
            System.err.println("Error getting qualities: " + e.getMessage());
            e.printStackTrace();
        }

        return qualities;
    }

    // Methods for servers
    public ObservableList<Server> getServers() {
        ObservableList<Server> servers = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return servers;
        }

        String query = "SELECT id, name FROM servers ORDER BY id";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            System.out.println("Executing query: " + query);

            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");

                servers.add(new Server(id, name));
            }

            System.out.println("Servers found: " + servers.size());

        } catch (SQLException e) {
            System.err.println("Error getting servers: " + e.getMessage());
            e.printStackTrace();
        }

        return servers;
    }

    // Methods for getting unique values for filters
    public ObservableList<String> getUniqueYears() {
        ObservableList<String> years = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return years;
        }

        String query = "SELECT DISTINCT year FROM torrent_downloads ORDER BY year DESC";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                years.add(String.valueOf(rs.getInt("year")));
            }
        } catch (SQLException e) {
            System.err.println("Error getting unique years: " + e.getMessage());
            e.printStackTrace();
        }

        return years;
    }

    public ObservableList<String> getUniqueGenres() {
        ObservableList<String> genres = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return genres;
        }

        String query = "SELECT DISTINCT genre FROM torrent_downloads WHERE genre IS NOT NULL ORDER BY genre";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                String genreList = rs.getString("genre");
                if (genreList != null && !genreList.isEmpty()) {
                    // If genres are stored as a comma-separated list
                    String[] genreArray = genreList.split(",");
                    for (String genre : genreArray) {
                        String trimmedGenre = genre.trim();
                        if (!genres.contains(trimmedGenre)) {
                            genres.add(trimmedGenre);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error getting unique genres: " + e.getMessage());
            e.printStackTrace();
        }

        return genres;
    }

    public ObservableList<String> getUniqueLanguages() {
        ObservableList<String> languages = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return languages;
        }

        String query = "SELECT DISTINCT language FROM links_files_download WHERE language IS NOT NULL ORDER BY language";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                languages.add(rs.getString("language"));
            }
        } catch (SQLException e) {
            System.err.println("Error getting unique languages: " + e.getMessage());
            e.printStackTrace();
        }

        return languages;
    }

    // Class to represent a season
    public static class Season {
        private final int id;
        private final int seriesId;
        private final int seasonNumber;

        public Season(int id, int seriesId, int seasonNumber) {
            this.id = id;
            this.seriesId = seriesId;
            this.seasonNumber = seasonNumber;
        }

        public int getId() {
            return id;
        }

        public int getSeriesId() {
            return seriesId;
        }

        public int getSeasonNumber() {
            return seasonNumber;
        }
    }

    // Class to represent a quality
    public static class Quality {
        private final int id;
        private final String quality;

        public Quality(int id, String quality) {
            this.id = id;
            this.quality = quality;
        }

        public int getId() {
            return id;
        }

        public String getQuality() {
            return quality;
        }

        @Override
        public String toString() {
            return quality;
        }
    }

    // Class to represent a server
    public static class Server {
        private final int id;
        private final String name;

        public Server(int id, String name) {
            this.id = id;
            this.name = name;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Methods for torrent database (keeping for compatibility)
    public ObservableList<TorrentDownloadUI.Movie> searchTorrentMovies(String searchTerm) {
        ObservableList<TorrentDownloadUI.Movie> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT td.id AS torrent_id, td.title, td.year, td.genre, td.director, " +
                "tf.id AS torrent_file_id, tf.quality_id, tf.torrent_link, q.quality " +
                "FROM torrent_downloads td " +
                "JOIN torrent_files tf ON td.id = tf.torrent_id " +
                "JOIN qualities q ON tf.quality_id = q.id " +
                "WHERE td.type = 'movie' AND td.title LIKE ? " +
                "ORDER BY td.added_at DESC";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, "%" + searchTerm + "%");
            ResultSet rs = stmt.executeQuery();

            Map<Integer, TorrentDownloadUI.Movie> moviesMap = new LinkedHashMap<>();

            while (rs.next()) {
                int torrentId = rs.getInt("torrent_id");
                String title = rs.getString("title");
                String year = String.valueOf(rs.getInt("year"));
                String genre = rs.getString("genre");
                String director = rs.getString("director");
                String quality = rs.getString("quality");
                String torrentLink = rs.getString("torrent_link");
                int qualityId = rs.getInt("quality_id");
                int torrentFileId = rs.getInt("torrent_file_id");

                TorrentDownloadUI.Movie movie = moviesMap.computeIfAbsent(torrentId,
                        id -> new TorrentDownloadUI.Movie(id, title, year, genre, director));
                movie.addTorrentFile(new TorrentDownloadUI.TorrentFile(
                        torrentFileId,
                        torrentId,
                        null,
                        qualityId,
                        torrentLink,
                        quality
                ));
            }

            moviesMap.values().forEach(TorrentDownloadUI.Movie::selectBestAvailableQuality);
            results.addAll(moviesMap.values());
            System.out.println("Movies found: " + results.size());
        } catch (SQLException e) {
            System.err.println("Error searching movies: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    // Métodos para Torrent
    public ObservableList<TorrentDownloadUI.Movie> searchTorrentMoviesWithFilters(
            String searchTerm, String year, String genre, String director, String quality) {

        ObservableList<TorrentDownloadUI.Movie> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        StringBuilder query = new StringBuilder("SELECT td.id AS torrent_id, td.title, td.year, td.genre, td.director, " +
                "tf.id AS torrent_file_id, tf.quality_id, tf.torrent_link, q.quality " +
                "FROM torrent_downloads td " +
                "JOIN torrent_files tf ON td.id = tf.torrent_id " +
                "JOIN qualities q ON tf.quality_id = q.id " +
                "WHERE td.type = 'movie'");

        List<Object> params = new ArrayList<>();

        if (searchTerm != null && !searchTerm.isEmpty()) {
            query.append(" AND td.title LIKE ?");
            params.add("%" + searchTerm + "%");
        }

        if (year != null && !year.isEmpty()) {
            query.append(" AND td.year = ?");
            params.add(year);
        }

        if (genre != null && !genre.isEmpty()) {
            query.append(" AND td.genre LIKE ?");
            params.add("%" + genre + "%");
        }

        if (director != null && !director.isEmpty()) {
            query.append(" AND td.director LIKE ?");
            params.add("%" + director + "%");
        }

        if (quality != null && !quality.isEmpty()) {
            query.append(" AND q.quality = ?");
            params.add(quality);
        }

        query.append(" ORDER BY td.added_at DESC");

        try (PreparedStatement stmt = connection.prepareStatement(query.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            Map<Integer, TorrentDownloadUI.Movie> moviesMap = new LinkedHashMap<>();

            while (rs.next()) {
                int torrentId = rs.getInt("torrent_id");
                String title = rs.getString("title");
                String yearResult = rs.getString("year");
                String genreResult = rs.getString("genre");
                String directorResult = rs.getString("director");
                String qualityResult = rs.getString("quality");
                String torrentLink = rs.getString("torrent_link");
                int qualityId = rs.getInt("quality_id");
                int torrentFileId = rs.getInt("torrent_file_id");

                TorrentDownloadUI.Movie movie = moviesMap.computeIfAbsent(torrentId,
                        id -> new TorrentDownloadUI.Movie(id, title, yearResult, genreResult, directorResult));
                movie.addTorrentFile(new TorrentDownloadUI.TorrentFile(
                        torrentFileId,
                        torrentId,
                        null,
                        qualityId,
                        torrentLink,
                        qualityResult
                ));
            }
            moviesMap.values().forEach(TorrentDownloadUI.Movie::selectBestAvailableQuality);
            results.addAll(moviesMap.values());

        } catch (SQLException e) {
            System.err.println("Error searching torrent movies: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    // Método para Torrent
    public <T> ObservableList<T> getLatestTorrentMovies(int limit, Class<T> movieClass) {
        ObservableList<T> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT td.id AS torrent_id, td.title, td.year, td.genre, td.director, " +
                "tf.id AS torrent_file_id, tf.quality_id, tf.torrent_link, q.quality " +
                "FROM torrent_downloads td " +
                "JOIN torrent_files tf ON td.id = tf.torrent_id " +
                "JOIN qualities q ON tf.quality_id = q.id " +
                "WHERE td.type = 'movie' " +
                "ORDER BY td.added_at DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            Map<Integer, TorrentDownloadUI.Movie> moviesMap = new LinkedHashMap<>();

            while (rs.next()) {
                int torrentId = rs.getInt("torrent_id");
                String title = rs.getString("title");
                String year = rs.getString("year");
                String genre = rs.getString("genre");
                String director = rs.getString("director");
                String quality = rs.getString("quality");
                String torrentLink = rs.getString("torrent_link");
                int qualityId = rs.getInt("quality_id");
                int torrentFileId = rs.getInt("torrent_file_id");

                TorrentDownloadUI.Movie movie = moviesMap.computeIfAbsent(torrentId,
                        id -> new TorrentDownloadUI.Movie(id, title, year, genre, director));
                movie.addTorrentFile(new TorrentDownloadUI.TorrentFile(
                        torrentFileId,
                        torrentId,
                        null,
                        qualityId,
                        torrentLink,
                        quality
                ));
            }

            for (TorrentDownloadUI.Movie movie : moviesMap.values()) {
                movie.selectBestAvailableQuality();
                results.add(movieClass.cast(movie));
            }
        } catch (SQLException e) {
            System.err.println("Error getting torrent movies: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    public ObservableList<TorrentDownloadUI.Series> searchTorrentSeries(String searchTerm) {
        ObservableList<TorrentDownloadUI.Series> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT id, title, year, genre, director FROM torrent_downloads " +
                "WHERE type = 'series' AND title LIKE ? " +
                "ORDER BY added_at DESC";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, "%" + searchTerm + "%");
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String year = rs.getString("year");
                String genre = rs.getString("genre");
                String director = rs.getString("director");

                results.add(new TorrentDownloadUI.Series(id, title, year, "", genre, director));
            }

        } catch (SQLException e) {
            System.err.println("Error searching torrent series: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    public ObservableList<TorrentDownloadUI.Series> searchTorrentSeriesWithFilters(String searchTerm, String yearFilter, String genre, String director) {
        ObservableList<TorrentDownloadUI.Series> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        StringBuilder query = new StringBuilder("SELECT id, title, year, genre, director FROM torrent_downloads " +
                "WHERE type = 'series'");

        List<Object> params = new ArrayList<>();

        if (searchTerm != null && !searchTerm.isEmpty()) {
            query.append(" AND title LIKE ?");
            params.add("%" + searchTerm + "%");
        }

        if (yearFilter != null && !yearFilter.isEmpty()) {
            query.append(" AND year = ?");
            params.add(yearFilter);
        }

        if (genre != null && !genre.isEmpty()) {
            query.append(" AND genre LIKE ?");
            params.add("%" + genre + "%");
        }

        if (director != null && !director.isEmpty()) {
            query.append(" AND director LIKE ?");
            params.add("%" + director + "%");
        }

        query.append(" ORDER BY added_at DESC");

        try (PreparedStatement stmt = connection.prepareStatement(query.toString())) {
            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String year = rs.getString("year");
                String genreResult = rs.getString("genre");
                String directorResult = rs.getString("director");

                results.add(new TorrentDownloadUI.Series(id, title, year, "", genreResult, directorResult));
            }

        } catch (SQLException e) {
            System.err.println("Error searching torrent series: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    public <T> ObservableList<T> getLatestTorrentSeries(int limit, Class<T> seriesClass) {
        ObservableList<T> results = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return results;
        }

        String query = "SELECT id, title, year, genre, director FROM torrent_downloads " +
                "WHERE type = 'series' ORDER BY added_at DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                String title = rs.getString("title");
                String year = rs.getString("year");
                String genre = rs.getString("genre");
                String director = rs.getString("director");

                T series = (T) new TorrentDownloadUI.Series(id, title, year, "", genre, director);
                results.add(series);
            }

        } catch (SQLException e) {
            System.err.println("Error getting torrent series: " + e.getMessage());
            e.printStackTrace();
        }
        return results;
    }

    public ObservableList<TorrentDownloadUI.Episode> getTorrentEpisodesBySeason(int seasonId) {
        ObservableList<TorrentDownloadUI.Episode> episodes = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return episodes;
        }

        String query = "SELECT id, season_id, episode_number, title FROM series_episodes WHERE season_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, seasonId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                int season_id = rs.getInt("season_id");
                int episodeNumber = rs.getInt("episode_number");
                String title = rs.getString("title");

                episodes.add(new TorrentDownloadUI.Episode(id, season_id, episodeNumber, title));
            }

        } catch (SQLException e) {
            System.err.println("Error getting torrent episodes: " + e.getMessage());
            e.printStackTrace();
        }
        return episodes;
    }

    /**
     * Obtiene los episodios para una temporada específica con la consulta optimizada
     * @param seriesName Nombre de la serie
     * @param seasonNumber Número de temporada
     * @param qualityId ID de la calidad (opcional)
     * @return Lista de episodios con sus archivos torrent
     */
    public ObservableList<TorrentDownloadUI.Episode> getEpisodesForSeason(String seriesName, int seasonNumber, Integer qualityId) {
        ObservableList<TorrentDownloadUI.Episode> episodes = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return episodes;
        }

        try {
            // Construir la consulta SQL
            StringBuilder queryBuilder = new StringBuilder(
                    "SELECT se.id AS episode_id, se.episode_number, se.title, tf.torrent_link, q.quality AS quality_name, tf.quality_id " +
                            "FROM series_episodes se " +
                            "JOIN series_seasons ss ON se.season_id = ss.id " +
                            "JOIN torrent_downloads td ON ss.series_id = td.id " +
                            "JOIN torrent_files tf ON se.id = tf.episode_id " +
                            "JOIN qualities q ON tf.quality_id = q.id " +
                            "WHERE td.title = ? AND ss.season_number = ? "
            );

            // Añadir filtro de calidad si se proporciona
            if (qualityId != null) {
                queryBuilder.append("AND tf.quality_id = ? ");
            }

            // Agrupar por ID de episodio para evitar duplicados
            queryBuilder.append("GROUP BY se.id ");
            queryBuilder.append("ORDER BY se.episode_number");

            PreparedStatement stmt = connection.prepareStatement(queryBuilder.toString());
            stmt.setString(1, seriesName);
            stmt.setInt(2, seasonNumber);

            if (qualityId != null) {
                stmt.setInt(3, qualityId);
            }

            System.out.println("Executing query: " + stmt.toString());
            ResultSet rs = stmt.executeQuery();

            // Mapa para evitar episodios duplicados por número de episodio
            Map<Integer, TorrentDownloadUI.Episode> episodeMap = new HashMap<>();
            Map<Integer, List<TorrentDownloadUI.TorrentFile>> torrentFilesMap = new HashMap<>();

            while (rs.next()) {
                int id = rs.getInt("episode_id");
                int episodeNumber = rs.getInt("episode_number");
                String title = rs.getString("title");
                String torrentLink = rs.getString("torrent_link");
                String quality = rs.getString("quality_name");
                int torrentQualityId = rs.getInt("quality_id");

                // Si ya tenemos este número de episodio, no lo añadimos de nuevo
                if (!episodeMap.containsKey(episodeNumber)) {
                    // Crear un nuevo episodio solo si no existe un episodio con el mismo número
                    TorrentDownloadUI.Episode episode = new TorrentDownloadUI.Episode(id, 0, episodeNumber, title);
                    episodeMap.put(episodeNumber, episode);
                    // Usamos episodeNumber como clave
                    torrentFilesMap.put(episodeNumber, new ArrayList<>());
                }

                // Almacenar el torrent file para este episodio
                TorrentDownloadUI.TorrentFile torrentFile = new TorrentDownloadUI.TorrentFile(
                        0, 0, id, torrentQualityId, torrentLink, quality);
                torrentFilesMap.get(episodeNumber).add(torrentFile);
            }

            // Convertir el mapa de episodios a lista
            episodes.addAll(episodeMap.values());

            rs.close();
            stmt.close();

            System.out.println("Episodes found: " + episodes.size());

        } catch (SQLException e) {
            System.err.println("Error al obtener episodios para la temporada: " + e.getMessage());
            e.printStackTrace();
        }

        return episodes;
    }

    public List<Season> getSeasonsTorrent(int seriesId, Integer qualityId) {
        Map<Integer, Season> uniqueSeasons = new HashMap<>(); // Mapa para almacenar la última temporada de cada número

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return new ArrayList<>();
        }

        StringBuilder queryBuilder = new StringBuilder(
                "SELECT ss.id, ss.series_id, ss.season_number " +
                        "FROM series_seasons ss " +
                        "JOIN series_episodes se ON ss.id = se.season_id "
        );

        // Si se proporciona un ID de calidad, filtrar por episodios que tengan archivos torrent con esa calidad
        if (qualityId != null) {
            queryBuilder.append(
                    "JOIN torrent_files tf ON se.id = tf.episode_id " +
                            "WHERE ss.series_id = ? AND tf.quality_id = ? "
            );
        } else {
            queryBuilder.append("WHERE ss.series_id = ? ");
        }

        // Ordenar por número de temporada y luego por ID descendente para obtener la última añadida primero
        queryBuilder.append("ORDER BY ss.season_number, ss.id DESC");

        try (PreparedStatement stmt = connection.prepareStatement(queryBuilder.toString())) {
            stmt.setInt(1, seriesId);
            if (qualityId != null) {
                stmt.setInt(2, qualityId);
            }

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int movie_id = rs.getInt("series_id");
                    int seasonNumber = rs.getInt("season_number");

                    // Solo añadir esta temporada si no hemos visto este número de temporada antes
                    // o si tiene un ID mayor (más reciente)
                    Season season = new Season(id, movie_id, seasonNumber);

                    // Si no existe esta temporada en el mapa o si el ID es mayor, la añadimos/reemplazamos
                    if (!uniqueSeasons.containsKey(seasonNumber) || uniqueSeasons.get(seasonNumber).getId() < id) {
                        uniqueSeasons.put(seasonNumber, season);
                    }
                }
            }

            System.out.println("Unique seasons found: " + uniqueSeasons.size());
        } catch (SQLException e) {
            System.err.println("Error getting seasons: " + e.getMessage());
            e.printStackTrace();
        }

        // Convertir el mapa a una lista ordenada por número de temporada
        List<Season> seasons = new ArrayList<>(uniqueSeasons.values());
        seasons.sort((s1, s2) -> Integer.compare(s1.getSeasonNumber(), s2.getSeasonNumber()));

        return seasons;
    }

    // Methods for seasons and episodes in direct download database
    public List<Season> getSeasonsTorrent(int seriesId) {
        List<Season> seasons = new ArrayList<>();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return seasons;
        }

        String query = "SELECT id, series_id, season_number FROM series_seasons " +
                "WHERE series_id = ? " +
                "ORDER BY season_number";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, seriesId);

            System.out.println("Executing query: " + stmt.toString());

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    int movie_id = rs.getInt("series_id");
                    int seasonNumber = rs.getInt("season_number");

                    seasons.add(new Season(id, movie_id, seasonNumber));
                }
            }

            System.out.println("Seasons found: " + seasons.size());
        } catch (SQLException e) {
            System.err.println("Error getting seasons: " + e.getMessage());
            e.printStackTrace();
        }

        return seasons;
    }

    public ObservableList<Quality> getQualitiesForSeries(int seriesId) {
        ObservableList<Quality> qualities = FXCollections.observableArrayList();

        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return qualities;
        }

        String query = "SELECT DISTINCT q.id, q.quality " +
                "FROM qualities q " +
                "JOIN torrent_files tf ON q.id = tf.quality_id " +
                "JOIN series_episodes se ON tf.episode_id = se.id " +
                "JOIN series_seasons ss ON se.season_id = ss.id " +
                "WHERE ss.series_id = ? " +
                "ORDER BY q.id";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setInt(1, seriesId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int id = rs.getInt("id");
                String quality = rs.getString("quality");
                qualities.add(new Quality(id, quality));
            }

            System.out.println("Calidades disponibles para serie ID " + seriesId + ": " + qualities.size());
        } catch (SQLException e) {
            System.err.println("Error al obtener calidades para serie: " + e.getMessage());
            e.printStackTrace();
        }

        return qualities;
    }

    /**
     * Obtiene el número total de películas en la base de datos.
     * Este método es necesario para el DatabaseStatusPanel.
     * @return El número de películas
     */
    public int getMoviesCount() {
        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return 0;
        }

        int count = 0;
        String query = "SELECT COUNT(*) as count FROM media_downloads WHERE type = 'movie'";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            if (rs.next()) {
                count = rs.getInt("count");
            }

        } catch (SQLException e) {
            System.err.println("Error al contar películas: " + e.getMessage());
            e.printStackTrace();
        }

        return count;
    }

    /**
     * Obtiene el número total de series en la base de datos.
     * Este método es necesario para el DatabaseStatusPanel.
     * @return El número de series
     */
    public int getSeriesCount() {
        if (!isConnected() && !connect()) {
            System.err.println("No se pudo conectar a la base de datos");
            return 0;
        }

        int count = 0;
        String query = "SELECT COUNT(*) as count FROM media_downloads WHERE type = 'serie'";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            if (rs.next()) {
                count = rs.getInt("count");
            }

        } catch (SQLException e) {
            System.err.println("Error al contar series: " + e.getMessage());
            e.printStackTrace();
        }

        return count;
    }

    /**
     * Indica si la base de datos está siendo actualizada actualmente.
     * Este método es necesario para el DatabaseStatusPanel.
     * @return true si la base de datos está siendo actualizada, false en caso contrario
     */
    public boolean isUpdating() {
        return isUpdating;
    }

    /**
     * Establece el estado de actualización de la base de datos.
     * Este método puede ser llamado cuando se inicia o finaliza una actualización.
     * @param updating true si la base de datos está siendo actualizada, false en caso contrario
     */
    public void setUpdating(boolean updating) {
        this.isUpdating = updating;
        if (!updating) {
            // Si se termina la actualización, actualizamos la fecha
            this.lastUpdateDate = new Date();
        }
    }

    /**
     * Obtiene la fecha de la última actualización de la base de datos.
     * Este método es necesario para el DatabaseStatusPanel.
     * @return La fecha de la última actualización o null si nunca se ha actualizado
     */
    public Date getLastUpdateDate() {
        return lastUpdateDate;
    }

    // Método para cerrar la conexión cuando ya no se necesita
    public void closeConnection() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                System.out.println("Conexión a la base de datos cerrada correctamente");
            }
        } catch (SQLException e) {
            System.err.println("Error al cerrar la conexión: " + e.getMessage());
            e.printStackTrace();
        }
    }
}