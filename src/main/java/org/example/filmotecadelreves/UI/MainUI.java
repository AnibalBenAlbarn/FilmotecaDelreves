package org.example.filmotecadelreves.UI;

import org.example.filmotecadelreves.downloaders.TorrentDownloader;
import org.example.filmotecadelreves.moviesad.ConnectDataBase;
import org.example.filmotecadelreves.moviesad.DatabaseStatusPanel;
import org.example.filmotecadelreves.moviesad.DownloadManager;
import org.example.filmotecadelreves.scrapers.ScraperProgressTracker;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.MenuItem;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import javafx.util.Duration;
import javafx.stage.Window;
import org.example.filmotecadelreves.moviesad.DelayedLoadingDialog;
import org.example.filmotecadelreves.moviesad.DownloadPersistenceManager;

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
    private MiBibliotecaUI miBibliotecaUI;
    private JSONObject configJson;
    private static final String CONFIG_FILE_PATH = "config.json";
    private final ScraperProgressTracker scraperProgressTracker = new ScraperProgressTracker();
    private Stage primaryStage;
    private boolean initializingTorrentDownloader;
    private DelayedLoadingDialog startupLoadingDialog;
    private final AtomicInteger pendingStartupTasks = new AtomicInteger();
    private volatile boolean startupLoadingActive;
    private TrayIcon trayIcon;
    private boolean systemTraySupported;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        // Cargar configuración
        loadConfig();
        registerScraperProgressListeners();
        applyScraperProgressFromConfig();

        // Crear directorios necesarios
        createRequiredDirectories();

        primaryStage.setTitle("MovieDownloader");
        primaryStage.initStyle(StageStyle.DECORATED);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(1024);
        primaryStage.setMinHeight(720);

        // Usar BorderPane para poder colocar el panel de estado en la parte inferior
        BorderPane mainLayout = new BorderPane();
        VBox contentLayout = new VBox(10);
        contentLayout.setPadding(new Insets(10));

        TabPane mainTabs = new TabPane();
        mainTabs.setPrefHeight(800);
        mainTabs.getStyleClass().add("main-tabs");

        // 1. Crear AjustesUI y cargar configuración
        AjustesUI ajustesUI = new AjustesUI(primaryStage, this, scraperProgressTracker);

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

        beginStartupLoading(3, "Inicializando bases de datos...");

        torrentDownloadUI = new TorrentDownloadUI(ajustesUI, descargasUI, primaryStage, scraperProgressTracker, this);
        torrentDownloadUI.updateDownloader(this.torrentDownloader);

        directDownloadUI = new DirectDownloadUI(ajustesUI, descargasUI, primaryStage, scraperProgressTracker, this);
        miBibliotecaUI = new MiBibliotecaUI(primaryStage);

        // Inicializar bases de datos después de cargar la configuración
        initializeDatabases(ajustesUI);

        mainTabs.getTabs().add(torrentDownloadUI.getTab());
        mainTabs.getTabs().add(directDownloadUI.getTab());
        mainTabs.getTabs().add(descargasUI.getTab());
        mainTabs.getTabs().add(miBibliotecaUI.getTab());
        mainTabs.getTabs().add(ajustesUI.getTab());

        // Cargar el TorrentDownloader de forma diferida cuando el usuario seleccione
        // la pestaña de descarga torrent por primera vez.
        mainTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == torrentDownloadUI.getTab()) {
                ensureTorrentDownloaderInitialized(ajustesUI);
            }
        });

        contentLayout.getChildren().add(mainTabs);

        // Crear el panel de estado de la base de datos con ambas bases de datos
        statusPanel = new DatabaseStatusPanel(directDB, torrentDB);

        if (startupLoadingActive) {
            setDatabasesUpdatingState(true);
        }

        // Configurar el layout principal
        mainLayout.setCenter(contentLayout);
        mainLayout.setBottom(statusPanel);

        scene = new Scene(mainLayout, 1200, 800);

        // Aplicar el tema guardado una vez que la escena está disponible
        ajustesUI.applyCurrentTheme();

        primaryStage.setScene(scene);
        primaryStage.setMaximized(true);
        primaryStage.show();

        setupSystemTray(primaryStage);

        Platform.runLater(() -> {
            if (mainTabs.getSelectionModel().getSelectedItem() == torrentDownloadUI.getTab()) {
                ensureTorrentDownloaderInitialized(ajustesUI);
            }
        });

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
     * Reemplaza el contenido actual de la configuración en memoria con los
     * valores más recientes guardados desde la vista de ajustes. Esto evita
     * que los cambios escritos en disco (por ejemplo, los ajustes de interfaz)
     * se pierdan cuando MainUI vuelva a persistir config.json al cerrar.
     *
     * @param updatedConfig Configuración completa obtenida tras guardar ajustes
     */
    public void updateConfig(JSONObject updatedConfig) {
        if (updatedConfig == null) {
            return;
        }

        if (this.configJson == null) {
            this.configJson = new JSONObject();
        } else {
            this.configJson.clear();
        }

        this.configJson.putAll(updatedConfig);
    }

    public void applyStreamplayHeadlessPreference(boolean runHeadless) {
        DownloadManager.updateStreamplayHeadless(runHeadless);
        if (directDownloadUI != null) {
            directDownloadUI.applyStreamplayHeadlessPreference(runHeadless);
        }
    }

    public void applyPowvideoHeadlessPreference(boolean runHeadless) {
        DownloadManager.updatePowvideoHeadless(runHeadless);
        if (directDownloadUI != null) {
            directDownloadUI.applyPowvideoHeadlessPreference(runHeadless);
        }
    }

    public void applyNopechaTimeoutPreference(int timeoutSeconds) {
        DownloadManager.updateNopechaTimeoutSeconds(timeoutSeconds);
        if (directDownloadUI != null) {
            directDownloadUI.applyNopechaTimeoutPreference(timeoutSeconds);
        }
    }

    private void ensureTorrentDownloaderInitialized(AjustesUI ajustesUI) {
        if (this.torrentDownloader != null || initializingTorrentDownloader) {
            return;
        }
        initializingTorrentDownloader = true;

        int maxConcurrent = ajustesUI.getMaxConcurrentDownloads();
        boolean extractArchives = ajustesUI.isExtractArchives();
        int downloadLimit = ajustesUI.getTorrentDownloadSpeedLimit();
        int uploadLimit = ajustesUI.getTorrentUploadSpeedLimit();
        boolean autoStart = ajustesUI.isAutoStartTorrentDownloads();

        Task<TorrentDownloader> initTask = new Task<>() {
            @Override
            protected TorrentDownloader call() {
                TorrentDownloader downloader = new TorrentDownloader(
                        maxConcurrent,
                        extractArchives,
                        downloadLimit,
                        uploadLimit
                );
                downloader.setAutoStartDownloads(autoStart);
                return downloader;
            }
        };

        Window owner = primaryStage != null ? primaryStage : (scene != null ? scene.getWindow() : null);
        DelayedLoadingDialog loadingDialog = new DelayedLoadingDialog(owner,
                "Inicializando gestor de torrents...",
                Duration.ZERO);

        initTask.setOnSucceeded(event -> {
            loadingDialog.stop();
            initializingTorrentDownloader = false;
            TorrentDownloader newDownloader = initTask.getValue();
            if (newDownloader == null) {
                System.err.println("No se pudo crear una instancia de TorrentDownloader");
                return;
            }
            setTorrentDownloader(newDownloader);
            ajustesUI.setTorrentDownloader(newDownloader);
            applyColumnVisibilityConfig();
            System.out.println("TorrentDownloader creado y configurado");
        });

        initTask.setOnFailed(event -> {
            loadingDialog.stop();
            initializingTorrentDownloader = false;
            Throwable ex = initTask.getException();
            if (ex != null) {
                ex.printStackTrace();
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error",
                        "No se pudo inicializar el gestor de torrents: " + ex.getMessage()));
            } else {
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error",
                        "No se pudo inicializar el gestor de torrents."));
            }
        });

        Thread thread = new Thread(initTask, "torrent-downloader-init");
        thread.setDaemon(true);
        thread.start();
        loadingDialog.start();
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

            // Guardar el progreso actual de los scrapers
            configJson.put("directScraperMoviesLastPage", scraperProgressTracker.getDirectMoviesLastPage());
            configJson.put("directScraperSeriesLastPage", scraperProgressTracker.getDirectSeriesLastPage());
            configJson.put("torrentScraperMoviesLastPage", scraperProgressTracker.getTorrentMoviesLastPage());
            configJson.put("torrentScraperSeriesLastPage", scraperProgressTracker.getTorrentSeriesLastPage());

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

    private void registerScraperProgressListeners() {
        scraperProgressTracker.directMoviesLastPageProperty().addListener((obs, oldValue, newValue) ->
                configJson.put("directScraperMoviesLastPage", newValue.intValue()));
        scraperProgressTracker.directSeriesLastPageProperty().addListener((obs, oldValue, newValue) ->
                configJson.put("directScraperSeriesLastPage", newValue.intValue()));
        scraperProgressTracker.torrentMoviesLastPageProperty().addListener((obs, oldValue, newValue) ->
                configJson.put("torrentScraperMoviesLastPage", newValue.intValue()));
        scraperProgressTracker.torrentSeriesLastPageProperty().addListener((obs, oldValue, newValue) ->
                configJson.put("torrentScraperSeriesLastPage", newValue.intValue()));
    }

    private void applyScraperProgressFromConfig() {
        if (configJson == null) {
            scraperProgressTracker.reset();
            return;
        }

        scraperProgressTracker.setDirectMoviesLastPage(getIntConfigValue("directScraperMoviesLastPage", -1));
        scraperProgressTracker.setDirectSeriesLastPage(getIntConfigValue("directScraperSeriesLastPage", -1));
        scraperProgressTracker.setTorrentMoviesLastPage(getIntConfigValue("torrentScraperMoviesLastPage", -1));
        scraperProgressTracker.setTorrentSeriesLastPage(getIntConfigValue("torrentScraperSeriesLastPage", -1));
    }

    private int getIntConfigValue(String key, int defaultValue) {
        Object value = configJson.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
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

        Label message = new Label("Las descargas en progreso se detendrán si cierras la aplicación.");
        message.setWrapText(true);
        CheckBox keepRunningCheckBox = new CheckBox("Mantener la aplicación en segundo plano para continuar las descargas");
        VBox content = new VBox(10, message, keepRunningCheckBox);
        content.setPadding(new Insets(10, 0, 0, 0));
        confirmation.getDialogPane().setContent(content);

        // Configurar para evitar cierre forzado si el usuario cancela
        Optional<ButtonType> result = confirmation.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            System.out.println("Cierre cancelado por el usuario");
            return;
        }

        if (keepRunningCheckBox.isSelected()) {
            System.out.println("Se mantiene la aplicación en segundo plano para continuar las descargas.");
            minimizeToTray();
            return;
        }

        performFullShutdown();
    }

    private void performFullShutdown() {
        if (descargasUI != null) {
            try {
                descargasUI.prepareDirectDownloadsForShutdown();
            } catch (Exception e) {
                System.err.println("Error al pausar descargas directas activas: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Guardar la configuración antes de cerrar
        saveConfig();

        // 2. Detener todas las descargas y liberar recursos
        if (descargasUI != null) {
            try {
                descargasUI.cancelAllActiveDirectDownloads();
            } catch (Exception e) {
                System.err.println("Error al cancelar descargas directas activas: " + e.getMessage());
                e.printStackTrace();
            }
        }

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

        // 5. Cerrar la base de datos de sesiones de descarga
        DownloadPersistenceManager.getInstance().close();

        removeTrayIcon();

        // 6. Salir de la aplicación
        System.out.println("Solicitando cierre de la aplicación...");
        Platform.exit();

        // 7. Forzar cierre si es necesario después de 5 segundos
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

    private void setupSystemTray(Stage stage) {
        try {
            systemTraySupported = SystemTray.isSupported();
        } catch (Exception e) {
            systemTraySupported = false;
            System.out.println("No se pudo comprobar el soporte de bandeja del sistema: " + e.getMessage());
            return;
        }
        if (!systemTraySupported) {
            System.out.println("El sistema no soporta bandeja de sistema. Se usará minimización estándar.");
            return;
        }

        SystemTray tray = SystemTray.getSystemTray();
        PopupMenu popupMenu = new PopupMenu();

        MenuItem showItem = new MenuItem("Mostrar");
        ActionListener showListener = e -> Platform.runLater(() -> restoreFromTray(stage));
        showItem.addActionListener(showListener);
        popupMenu.add(showItem);

        MenuItem exitItem = new MenuItem("Salir");
        exitItem.addActionListener(e -> Platform.runLater(this::performFullShutdown));
        popupMenu.add(exitItem);

        Image trayImage = createTrayImage();
        trayIcon = new TrayIcon(trayImage, "MovieDownloader", popupMenu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(showListener);
        trayIcon.setToolTip("MovieDownloader");

        Platform.setImplicitExit(false);

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("No se pudo añadir el icono a la bandeja del sistema: " + e.getMessage());
            systemTraySupported = false;
            trayIcon = null;
        }
    }

    private Image createTrayImage() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(new Color(0x1F, 0x28, 0x3D));
            graphics.fillOval(2, 2, 28, 28);
            graphics.setColor(new Color(0xFF, 0xC1, 0x07));
            graphics.fillOval(6, 6, 20, 20);
            graphics.setColor(Color.WHITE);
            graphics.setFont(graphics.getFont().deriveFont(12f).deriveFont(java.awt.Font.BOLD));
            graphics.drawString("MD", 8, 20);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private void minimizeToTray() {
        if (!systemTraySupported || trayIcon == null) {
            if (primaryStage != null) {
                primaryStage.setIconified(true);
            }
            return;
        }

        Platform.runLater(() -> {
            primaryStage.hide();
            trayIcon.displayMessage("MovieDownloader", "La aplicación sigue ejecutándose en segundo plano.", TrayIcon.MessageType.INFO);
        });
    }

    private void restoreFromTray(Stage stage) {
        stage.show();
        stage.toFront();
        stage.setIconified(false);
        stage.requestFocus();
    }

    private void removeTrayIcon() {
        if (trayIcon != null && systemTraySupported) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
        }
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
            // Verificar que la escena esté inicializada
            if (scene == null) {
                System.err.println("Escena no inicializada; estilos no aplicados.");
                return;
            }

            // Limpiar cualquier stylesheet previo
            scene.getStylesheets().clear();
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
        Task<Void> initTask = new Task<>() {
            @Override
            protected Void call() {
                try {
                    System.out.println("Inicializando bases de datos...");
                    String torrentPath = ajustesUI.getTorrentDatabasePath();
                    String directPath = ajustesUI.getDirectDatabasePath();

                    ConnectDataBase newTorrentDB = new ConnectDataBase(torrentPath);
                    newTorrentDB.setUpdating(true);
                    ConnectDataBase newDirectDB = new ConnectDataBase(directPath);
                    newDirectDB.setUpdating(true);

                    if (!newTorrentDB.connect()) {
                        Platform.runLater(() -> showDatabaseError("torrent_dw_db"));
                    }

                    if (!newDirectDB.connect()) {
                        Platform.runLater(() -> showDatabaseError("direct_dw_db"));
                    }

                    torrentDB = newTorrentDB;
                    directDB = newDirectDB;
                } catch (Exception e) {
                    Platform.runLater(() -> {
                        showAlert(Alert.AlertType.ERROR, "Error crítico",
                                "No se pudo inicializar las bases de datos: " + e.getMessage());
                    });
                    throw e;
                }
                return null;
            }
        };

        initTask.setOnSucceeded(event -> {
            Platform.runLater(() -> {
                if (statusPanel != null) {
                    statusPanel.updateConnections(directDB, torrentDB);
                }
            });
            notifyStartupTaskCompleted();
        });

        initTask.setOnFailed(event -> {
            Throwable ex = initTask.getException();
            if (ex != null) {
                ex.printStackTrace();
            }
            notifyStartupTaskCompleted();
        });

        Thread thread = new Thread(initTask);
        thread.setDaemon(true);
        thread.start();
    }

    private void beginStartupLoading(int expectedTasks, String message) {
        startupLoadingActive = true;
        pendingStartupTasks.set(Math.max(0, expectedTasks));
        if (startupLoadingDialog != null) {
            startupLoadingDialog.stop();
        }
        startupLoadingDialog = new DelayedLoadingDialog(primaryStage, message);
        startupLoadingDialog.start();
    }

    public void notifyStartupTaskCompleted() {
        if (!startupLoadingActive) {
            return;
        }
        int remaining = pendingStartupTasks.updateAndGet(current -> current > 0 ? current - 1 : 0);
        if (remaining == 0) {
            stopStartupLoading();
        }
    }

    private void stopStartupLoading() {
        if (!startupLoadingActive) {
            return;
        }
        startupLoadingActive = false;
        pendingStartupTasks.set(0);
        Platform.runLater(() -> {
            if (startupLoadingDialog != null) {
                startupLoadingDialog.stop();
                startupLoadingDialog = null;
            }
            setDatabasesUpdatingState(false);
        });
    }

    private void setDatabasesUpdatingState(boolean updating) {
        if (directDB != null) {
            directDB.setUpdating(updating);
        }
        if (torrentDB != null) {
            torrentDB.setUpdating(updating);
        }
        if (statusPanel != null) {
            statusPanel.updateStatistics();
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
