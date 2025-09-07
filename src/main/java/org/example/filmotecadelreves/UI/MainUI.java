package org.example.filmotecadelreves.UI;

import org.example.filmotecadelreves.downloaders.TorrentDownloader;
import org.example.filmotecadelreves.moviesad.ConnectDataBase;
import org.example.filmotecadelreves.moviesad.DatabaseStatusPanel;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.Base64;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import static org.example.filmotecadelreves.UI.AjustesUI.DARK_THEME_FILE;
//ver1.3

public class MainUI extends Application {

    private ConnectDataBase torrentDB;
    private ConnectDataBase directDB;
    private TorrentDownloader torrentDownloader;
    private Scene scene;
    private DatabaseStatusPanel statusPanel;
    private DescargasUI descargasUI;
    private TorrentDownloadUI torrentDownloadUI;
    private DirectDownloadUI directDownloadUI;
    private JSONObject configJson;
    private static final String CONFIG_FILE_PATH = "config.json";

    @Override
    public void start(Stage primaryStage) {
        // Cargar configuración
        loadConfig();

        // Crear directorios necesarios
        createRequiredDirectories();

        primaryStage.setTitle("MovieDownloader");

        // Usar BorderPane para poder colocar el panel de estado en la parte inferior
        BorderPane mainLayout = new BorderPane();
        VBox contentLayout = new VBox(10);
        contentLayout.setPadding(new Insets(10));

        TabPane mainTabs = new TabPane();
        mainTabs.setPrefHeight(800);
        mainTabs.getStyleClass().add("main-tabs");

        // 1. Crear AjustesUI y cargar configuración
        AjustesUI ajustesUI = new AjustesUI(primaryStage, this);

        // 2. Inicializar TorrentDownloader de forma diferida. En lugar de crear
        // automáticamente una instancia al inicio, se cargará cuando el usuario
        // seleccione la pestaña de descargas torrent por primera vez. Esto
        // reduce notablemente el tiempo de arranque al evitar cargar jlibtorrent.
        this.torrentDownloader = null;

        // 3. Establecer el TorrentDownloader (nulo inicialmente) en AjustesUI y
        // otros componentes para que puedan actualizarse cuando se cargue.
        ajustesUI.setTorrentDownloader(this.torrentDownloader);

        // 4. Inicializar componentes con referencias correctas
        descargasUI = new DescargasUI();
        descargasUI.setTorrentDownloader(this.torrentDownloader);

        // Aplicar configuración de columnas visibles
        applyColumnVisibilityConfig();

        torrentDownloadUI = new TorrentDownloadUI(ajustesUI, descargasUI, primaryStage);
        torrentDownloadUI.updateDownloader(this.torrentDownloader);

        directDownloadUI = new DirectDownloadUI(ajustesUI, descargasUI, primaryStage);

        // Inicializar bases de datos después de cargar la configuración
        initializeDatabases(ajustesUI);

        mainTabs.getTabs().add(torrentDownloadUI.getTab());
        mainTabs.getTabs().add(directDownloadUI.getTab());
        mainTabs.getTabs().add(descargasUI.getTab());
        mainTabs.getTabs().add(ajustesUI.getTab());

        // Cargar el TorrentDownloader de forma diferida cuando el usuario seleccione
        // la pestaña de descarga torrent por primera vez.
        mainTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == torrentDownloadUI.getTab() && this.torrentDownloader == null) {
                // Crear nueva instancia con la configuración actual
                this.torrentDownloader = ajustesUI.createTorrentDownloader();
                // Actualizar todas las referencias
                ajustesUI.setTorrentDownloader(this.torrentDownloader);
                descargasUI.setTorrentDownloader(this.torrentDownloader);
                torrentDownloadUI.updateDownloader(this.torrentDownloader);
                System.out.println("TorrentDownloader creado y configurado al seleccionar la pestaña torrent");
            }
        });

        contentLayout.getChildren().add(mainTabs);

        // Crear el panel de estado de la base de datos con ambas bases de datos
        statusPanel = new DatabaseStatusPanel(directDB, torrentDB);

        // Configurar el layout principal
        mainLayout.setCenter(contentLayout);
        mainLayout.setBottom(statusPanel);

        scene = new Scene(mainLayout, 1200, 800);

        // Cargar el archivo CSS si existe
        loadStylesheet(DARK_THEME_FILE);

        primaryStage.setScene(scene);
        primaryStage.show();

        // Configurar el cierre adecuado de la aplicación
        primaryStage.setOnCloseRequest(event -> {
            event.consume(); // Prevenir cierre automático
            shutdownApplication();
        });
    }

    /**
     * Carga la configuración desde el archivo config.json
     */
    private void loadConfig() {
        configJson = new JSONObject();

        try {
            File configFile = new File(CONFIG_FILE_PATH);
            if (configFile.exists()) {
                JSONParser parser = new JSONParser();
                configJson = (JSONObject) parser.parse(new FileReader(configFile));
                System.out.println("Configuración cargada desde: " + configFile.getAbsolutePath());
            } else {
                System.out.println("Archivo de configuración no encontrado. Se creará uno nuevo.");
                saveConfig(); // Crear un archivo de configuración vacío
            }
        } catch (IOException | ParseException e) {
            System.err.println("Error al cargar la configuración: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Guarda la configuración en el archivo config.json
     */
    private void saveConfig() {
        try {
            // Asegurarse de que configJson no sea null
            if (configJson == null) {
                configJson = new JSONObject();
            }

            // Guardar la configuración de columnas visibles
            if (descargasUI != null) {
                // Guardar la configuración de columnas de torrents
                JSONObject torrentColumnsConfig = new JSONObject();
                Map<String, Boolean> torrentColumnsVisibility = descargasUI.getTorrentColumnsVisibility();
                for (Map.Entry<String, Boolean> entry : torrentColumnsVisibility.entrySet()) {
                    torrentColumnsConfig.put(entry.getKey(), entry.getValue());
                }
                configJson.put("torrentColumnsVisibility", torrentColumnsConfig);

                // Guardar la configuración de columnas de descargas directas
                JSONObject directColumnsConfig = new JSONObject();
                Map<String, Boolean> directColumnsVisibility = descargasUI.getDirectColumnsVisibility();
                for (Map.Entry<String, Boolean> entry : directColumnsVisibility.entrySet()) {
                    directColumnsConfig.put(entry.getKey(), entry.getValue());
                }
                configJson.put("directColumnsVisibility", directColumnsConfig);
            }

            // Guardar el estado de la sesión de torrent si está disponible
            if (torrentDownloader != null) {
                byte[] sessionState = torrentDownloader.saveSessionState();
                if (sessionState != null) {
                    // Convertir el array de bytes a una cadena Base64 para almacenarlo en JSON
                    String base64SessionState = Base64.getEncoder().encodeToString(sessionState);
                    configJson.put("torrentSessionState", base64SessionState);
                }
            }

            // Escribir el archivo de configuración
            try (FileWriter file = new FileWriter(CONFIG_FILE_PATH)) {
                file.write(configJson.toJSONString());
                file.flush();
                System.out.println("Configuración guardada en: " + CONFIG_FILE_PATH);
            }
        } catch (IOException e) {
            System.err.println("Error al guardar la configuración: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Aplica la configuración de columnas visibles a las tablas
     */
    private void applyColumnVisibilityConfig() {
        if (descargasUI != null && configJson != null) {
            // Aplicar configuración de columnas de torrents
            JSONObject torrentColumnsConfig = (JSONObject) configJson.get("torrentColumnsVisibility");
            if (torrentColumnsConfig != null) {
                descargasUI.setTorrentColumnsVisibility(torrentColumnsConfig);
            }

            // Aplicar configuración de columnas de descargas directas
            JSONObject directColumnsConfig = (JSONObject) configJson.get("directColumnsVisibility");
            if (directColumnsConfig != null) {
                descargasUI.setDirectColumnsVisibility(directColumnsConfig);
            }

            // Restaurar el estado de la sesión de torrent si está disponible
            if (torrentDownloader != null) {
                String base64SessionState = (String) configJson.get("torrentSessionState");
                if (base64SessionState != null && !base64SessionState.isEmpty()) {
                    try {
                        byte[] sessionState = Base64.getDecoder().decode(base64SessionState);
                        torrentDownloader.restoreSessionState(sessionState);
                    } catch (Exception e) {
                        System.err.println("Error al restaurar el estado de la sesión de torrent: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void shutdownApplication() {
        System.out.println("Iniciando proceso de cierre...");

        // 1. Mostrar diálogo de confirmación (opcional)
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
        confirmation.setTitle("Confirmar cierre");
        confirmation.setHeaderText("¿Estás seguro de que quieres salir?");
        confirmation.setContentText("Las descargas en progreso se detendrán.");

        // Configurar para evitar cierre forzado si el usuario cancela
        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isPresent() && result.get() != ButtonType.OK) {
            System.out.println("Cierre cancelado por el usuario");
            return;
        }

        // Guardar la configuración antes de cerrar
        saveConfig();

        // 2. Detener todas las descargas y liberar recursos
        if (torrentDownloader != null) {
            System.out.println("Deteniendo TorrentDownloader...");
            try {
                torrentDownloader.shutdown();
                System.out.println("TorrentDownloader detenido correctamente");
            } catch (Exception e) {
                System.err.println("Error al detener TorrentDownloader: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 3. Cerrar conexiones de bases de datos
        System.out.println("Cerrando conexiones a bases de datos...");
        try {
            if (directDB != null) {
                directDB.closeConnection();
                System.out.println("Conexión a Direct DB cerrada");
            }
            if (torrentDB != null) {
                torrentDB.closeConnection();
                System.out.println("Conexión a Torrent DB cerrada");
            }
        } catch (Exception e) {
            System.err.println("Error al cerrar bases de datos: " + e.getMessage());
            e.printStackTrace();
        }

        // 4. Limpiar archivos temporales
        System.out.println("Limpiando archivos temporales...");
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "torrent_downloader");
            if (Files.exists(tempDir)) {
                Files.walk(tempDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                System.out.println("Directorio temporal eliminado: " + tempDir);
            }
        } catch (IOException e) {
            System.err.println("Error al limpiar archivos temporales: " + e.getMessage());
            e.printStackTrace();
        }

        // 5. Salir de la aplicación
        System.out.println("Solicitando cierre de la aplicación...");
        Platform.exit();

        // 6. Forzar cierre si es necesario después de 5 segundos
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                System.err.println("Forzando cierre del sistema...");
                System.exit(0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Carga el archivo CSS de estilos
     */
    /**
     * Carga la hoja de estilos indicada. Si la ruta es nula o vacía,
     * se limpian los estilos para utilizar los predeterminados de JavaFX.
     *
     * @param cssPath Ruta al archivo CSS que se desea cargar.
     */
    public void loadStylesheet(String cssPath) {
        try {
            // Limpiar cualquier stylesheet previo
            if (scene != null) {
                scene.getStylesheets().clear();
            }
            // Si la ruta es nula o vacía, no aplicar ningún CSS personalizado
            if (cssPath == null || cssPath.trim().isEmpty()) {
                System.out.println("Hoja de estilos limpia; se usarán los estilos predeterminados de JavaFX.");
                return;
            }
            File cssFile = new File(cssPath);
            if (cssFile.exists()) {
                String cssUrl = cssFile.toURI().toURL().toExternalForm();
                scene.getStylesheets().add(cssUrl);
                System.out.println("Estilos cargados desde: " + cssUrl);
            } else {
                System.out.println("Archivo de estilos no encontrado en: " + cssFile.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Error al cargar la hoja de estilos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createRequiredDirectories() {
        try {
            // Crear directorio DB si no existe
            Path dbPath = Paths.get("DB");
            if (!Files.exists(dbPath)) {
                Files.createDirectories(dbPath);
                System.out.println("Directorio DB creado: " + dbPath.toAbsolutePath());
            }

            // Crear directorio temporal para descargas si no existe
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "torrent_downloader");
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                System.out.println("Directorio temporal creado: " + tempDir.toAbsolutePath());
            }

            // Crear directorio resources si no existe
            Path resourcesDir = Paths.get("src/main/resources");
            if (!Files.exists(resourcesDir)) {
                Files.createDirectories(resourcesDir);
                System.out.println("Directorio resources creado: " + resourcesDir.toAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("Error al crear directorios: " + e.getMessage());
            e.printStackTrace();
            showAlert(Alert.AlertType.ERROR, "Error", "No se pudieron crear los directorios necesarios: " + e.getMessage());
        }
    }

    private void initializeDatabases(AjustesUI ajustesUI) {
        try {
            System.out.println("Inicializando bases de datos...");
            String torrentPath = ajustesUI.getTorrentDatabasePath();
            String directPath = ajustesUI.getDirectDatabasePath();

            torrentDB = new ConnectDataBase(torrentPath);
            directDB = new ConnectDataBase(directPath);

            if (!torrentDB.connect()) {
                showDatabaseError("torrent_dw_db");
            }

            if (!directDB.connect()) {
                showDatabaseError("direct_dw_db");
            }
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error crítico", "No se pudo inicializar las bases de datos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Recreate database connections using the provided paths. Called when the
     * user updates database locations in AjustesUI.
     *
     * @param torrentPath path to torrent database
     * @param directPath  path to direct-download database
     */
    public void reloadDatabases(String torrentPath, String directPath) {
        try {
            System.out.println("Recargando bases de datos...");
            if (torrentDB != null) {
                torrentDB.closeConnection();
            }
            if (directDB != null) {
                directDB.closeConnection();
            }
            torrentDB = new ConnectDataBase(torrentPath);
            directDB = new ConnectDataBase(directPath);
            if (!torrentDB.connect()) {
                showDatabaseError("torrent_dw_db");
            }
            if (!directDB.connect()) {
                showDatabaseError("direct_dw_db");
            }
            if (torrentDownloadUI != null) {
                torrentDownloadUI.updateDatabase(torrentPath);
            }
            if (directDownloadUI != null) {
                directDownloadUI.updateDatabase(directPath);
            }
            if (statusPanel != null) {
                statusPanel.updateConnections(directDB, torrentDB);
            }
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error crítico", "No se pudo recargar las bases de datos: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void showDatabaseError(String dbName) {
        showAlert(Alert.AlertType.ERROR, "Error de conexión",
                "No se pudo conectar a la base de datos: " + dbName + "\\n" +
                        "Verifica que el archivo existe en: " + Paths.get("DB/" + dbName + ".db").toAbsolutePath());
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void setTorrentDownloader(TorrentDownloader torrentDownloader) {
        // Detener y limpiar el downloader anterior si existe
        if (this.torrentDownloader != null) {
            this.torrentDownloader.shutdown();
        }

        // Asignar el nuevo downloader
        this.torrentDownloader = torrentDownloader;

        // Actualizar todas las dependencias que usan TorrentDownloader
        updateDependencies();
    }

    private void updateDependencies() {
        // Actualizar las referencias en todos los componentes que usan TorrentDownloader
        if (descargasUI != null) {
            descargasUI.setTorrentDownloader(torrentDownloader);
        }

        if (torrentDownloadUI != null) {
            torrentDownloadUI.updateDownloader(torrentDownloader);
        }

        System.out.println("Dependencias actualizadas con el nuevo TorrentDownloader");
    }

    public static void main(String[] args) {
        launch(args);
    }
}