package org.example.filmotecadelreves.UI;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.example.filmotecadelreves.downloaders.TorrentDownloader;
import org.example.filmotecadelreves.scrapers.ScraperProgressTracker;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.control.ColorPicker;

import java.awt.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.awt.Desktop;
import java.net.URI;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class AjustesUI {

    private Tab tab;
    private MainUI mainUI;
    private final ScraperProgressTracker scraperProgressTracker;

    // Rutas de descarga
    private TextField torrentMovieDestinationField;
    private TextField torrentSeriesDestinationField;
    private Button torrentMovieDestinationButton;
    private Button torrentSeriesDestinationButton;

    private TextField directMovieDestinationField;
    private TextField directSeriesDestinationField;
    private Button directMovieDestinationButton;
    private Button directSeriesDestinationButton;

    // Base de datos
    private TextField torrentDatabasePathField;
    private Button torrentDatabasePathButton;
    private TextField directDatabasePathField;
    private Button directDatabasePathButton;

    // Configuración de descargas de torrent
    private CheckBox autoStartTorrentDownloadsCheckbox;
    private CheckBox createSubfoldersCheckbox;
    private CheckBox extractArchivesCheckbox;
    private Slider maxConcurrentTorrentDownloadsSlider; // Nuevo slider para descargas simultáneas de torrent
    private Label maxConcurrentTorrentDownloadsValueLabel; // Etiqueta para mostrar el valor actual

    // Configuración de descargas directas
    private Slider maxConcurrentDirectDownloadsSlider;
    private Slider directDownloadSpeedLimitSlider;
    private CheckBox autoStartDirectDownloadsCheckbox;
    private CheckBox streamplayHeadlessCheckbox;
    private TextField apiKeyCaptchaField;

    // Configuración de interfaz
    private ComboBox<String> themeComboBox;
    private CheckBox showNotificationsCheckbox;
    private CheckBox minimizeToTrayCheckbox;

    // Selectores de color para personalización de la interfaz
    private ColorPicker tabsBorderColorPicker;
    private ColorPicker backgroundColorPicker;
    private ColorPicker fontColorPicker;
    private ColorPicker buttonColorPicker;

    // Panel de vista previa
    private VBox previewPanel;
    private Label previewTitle;
    private Button previewButton;
    private CheckBox previewCheckbox;
    private Label previewText;

    // Botones de guardar/restaurar
    private Button saveButton;
    private Button resetButton;

    // Archivo de configuración
    private static final String CONFIG_FILE = "config.json";
    // Nombre del archivo CSS generado por personalización
    // Se escribe en un archivo distinto para no sobrescribir el tema por defecto
    private static final String CUSTOM_CSS_FILE = "src/main/resources/custom-theme.css";
    // Archivo CSS para tema oscuro por defecto
    static final String DARK_THEME_FILE = "src/main/resources/Styles.css";
    // Archivo CSS para tema claro elegante
    private static final String LIGHT_THEME_FILE = "src/main/resources/light-theme.css";

    // Referencia al TorrentDownloader
    private TorrentDownloader torrentDownloader;

    public AjustesUI(Stage primaryStage, MainUI mainUI, ScraperProgressTracker scraperProgressTracker) {
        this.mainUI = mainUI;
        this.scraperProgressTracker = scraperProgressTracker;
        tab = new Tab("Ajustes");
        tab.setClosable(false);

// Crear TabPane para organizar las secciones
        TabPane settingsTabs = new TabPane();
        settingsTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

// Crear las pestañas
        Tab torrentSettingsTab = new Tab("Ajustes Torrent");
        Tab directDownloadSettingsTab = new Tab("Ajustes Direct Download");
        Tab databaseSettingsTab = new Tab("Ajustes Base de datos");
        Tab interfaceSettingsTab = new Tab("Ajustes Interfaz");

// Configurar contenido de las pestañas
        torrentSettingsTab.setContent(createTorrentSettingsContent(primaryStage));
        directDownloadSettingsTab.setContent(createDirectDownloadSettingsContent(primaryStage));
        databaseSettingsTab.setContent(createDatabaseSettingsContent(primaryStage));
        interfaceSettingsTab.setContent(createInterfaceSettingsContent());

// Añadir pestañas al TabPane
        settingsTabs.getTabs().addAll(
                torrentSettingsTab,
                directDownloadSettingsTab,
                databaseSettingsTab,
                interfaceSettingsTab
        );

// Crear botones de guardar/restaurar
        HBox buttonsBox = new HBox(10);
        buttonsBox.setPadding(new Insets(10));
        saveButton = new Button("Guardar Configuración");
        saveButton.setId("saveButton");
        saveButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
        saveButton.setOnAction(e -> saveSettings());

        resetButton = new Button("Restaurar Valores Predeterminados");
        resetButton.setId("resetButton");
        resetButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        resetButton.setOnAction(e -> resetSettings());

        buttonsBox.getChildren().addAll(saveButton, resetButton);

// Layout principal
        VBox mainLayout = new VBox(10);
        mainLayout.setPadding(new Insets(10));
        VBox.setVgrow(settingsTabs, Priority.ALWAYS);
        mainLayout.getChildren().addAll(settingsTabs, buttonsBox);

        tab.setContent(mainLayout);

// Cargar configuración guardada
        loadSettings();
    }

    private VBox createTorrentSettingsContent(Stage primaryStage) {
        VBox content = new VBox(15);
        content.setPadding(new Insets(10));

// Sección de rutas de descarga
        TitledPane pathsPane = createTorrentPathsSection(primaryStage);

// Sección de configuración de descargas de torrent
        TitledPane torrentConfigPane = createTorrentConfigSection();

        content.getChildren().addAll(pathsPane, torrentConfigPane);

        return content;
    }

    private VBox createDirectDownloadSettingsContent(Stage primaryStage) {
        VBox content = new VBox(15);
        content.setPadding(new Insets(10));

// Sección de configuración de descargas directas
        TitledPane directPathsPane = createDirectDownloadPathsSection(primaryStage);
        TitledPane directConfigPane = createDirectDownloadConfigSection();

// Sección de configuración de 2Captcha
        TitledPane captchaConfigPane = createCaptchaConfigSection();

        content.getChildren().addAll(directPathsPane, directConfigPane, captchaConfigPane);

        return content;
    }

    private VBox createDatabaseSettingsContent(Stage primaryStage) {
        VBox content = new VBox(15);
        content.setPadding(new Insets(10));

// Sección de base de datos
        TitledPane databasePane = createDatabaseSection(primaryStage);

        content.getChildren().add(databasePane);

        return content;
    }

    private VBox createInterfaceSettingsContent() {
        VBox content = new VBox(15);
        content.setPadding(new Insets(10));

// Sección de interfaz básica
        TitledPane interfacePane = createInterfaceSection();

// Sección de personalización de colores
        TitledPane colorCustomizationPane = createColorCustomizationSection();

        content.getChildren().addAll(interfacePane, colorCustomizationPane);

        return content;
    }

    private TitledPane createTorrentPathsSection(Stage primaryStage) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        Label movieDestinationLabel = new Label("Ubicación predeterminada para películas:");
        torrentMovieDestinationField = new TextField();
        torrentMovieDestinationField.setPromptText("Ruta de descarga predeterminada para películas (Torrent)");
        torrentMovieDestinationButton = new Button("Seleccionar carpeta");
        torrentMovieDestinationButton.setOnAction(e -> selectFolder(primaryStage, torrentMovieDestinationField));

        Label seriesDestinationLabel = new Label("Ubicación predeterminada para series:");
        torrentSeriesDestinationField = new TextField();
        torrentSeriesDestinationField.setPromptText("Ruta de descarga predeterminada para series (Torrent)");
        torrentSeriesDestinationButton = new Button("Seleccionar carpeta");
        torrentSeriesDestinationButton.setOnAction(e -> selectFolder(primaryStage, torrentSeriesDestinationField));

        grid.add(movieDestinationLabel, 0, 0);
        grid.add(torrentMovieDestinationField, 0, 1);
        grid.add(torrentMovieDestinationButton, 1, 1);
        grid.add(seriesDestinationLabel, 0, 2);
        grid.add(torrentSeriesDestinationField, 0, 3);
        grid.add(torrentSeriesDestinationButton, 1, 3);

        TitledPane pathsPane = new TitledPane("Rutas de Descarga Torrent", grid);
        pathsPane.setExpanded(true);

        return pathsPane;
    }

    private TitledPane createDirectDownloadPathsSection(Stage primaryStage) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

        Label movieDestinationLabel = new Label("Ubicación predeterminada para películas:");
        directMovieDestinationField = new TextField();
        directMovieDestinationField.setPromptText("Ruta de descarga predeterminada para películas (Direct Download)");
        directMovieDestinationButton = new Button("Seleccionar carpeta");
        directMovieDestinationButton.setOnAction(e -> selectFolder(primaryStage, directMovieDestinationField));

        Label seriesDestinationLabel = new Label("Ubicación predeterminada para series:");
        directSeriesDestinationField = new TextField();
        directSeriesDestinationField.setPromptText("Ruta de descarga predeterminada para series (Direct Download)");
        directSeriesDestinationButton = new Button("Seleccionar carpeta");
        directSeriesDestinationButton.setOnAction(e -> selectFolder(primaryStage, directSeriesDestinationField));

        grid.add(movieDestinationLabel, 0, 0);
        grid.add(directMovieDestinationField, 0, 1);
        grid.add(directMovieDestinationButton, 1, 1);
        grid.add(seriesDestinationLabel, 0, 2);
        grid.add(directSeriesDestinationField, 0, 3);
        grid.add(directSeriesDestinationButton, 1, 3);

        TitledPane pathsPane = new TitledPane("Rutas de Descarga Directas", grid);
        pathsPane.setExpanded(true);

        return pathsPane;
    }

    private TitledPane createTorrentConfigSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

// Máximo de descargas simultáneas de torrent
        Label maxConcurrentTorrentDownloadsLabel = new Label("Máximo de descargas simultáneas (Torrent):");
        maxConcurrentTorrentDownloadsSlider = new Slider(1, 10, 1);
        maxConcurrentTorrentDownloadsSlider.setShowTickLabels(true);
        maxConcurrentTorrentDownloadsSlider.setShowTickMarks(true);
        maxConcurrentTorrentDownloadsSlider.setMajorTickUnit(1);
        maxConcurrentTorrentDownloadsSlider.setMinorTickCount(0);
        maxConcurrentTorrentDownloadsSlider.setSnapToTicks(true);
        maxConcurrentTorrentDownloadsValueLabel = new Label("1");
        maxConcurrentTorrentDownloadsSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            maxConcurrentTorrentDownloadsValueLabel.setText(String.valueOf(newVal.intValue()));
        });

// Tooltip para el slider de descargas simultáneas
        Tooltip concurrentDownloadsTooltip = new Tooltip(
                "Define cuántos torrents pueden descargarse simultáneamente. " +
                        "Un número mayor puede aumentar la velocidad total pero también el uso de recursos."
        );
        maxConcurrentTorrentDownloadsLabel.setTooltip(concurrentDownloadsTooltip);
        maxConcurrentTorrentDownloadsSlider.setTooltip(concurrentDownloadsTooltip);

// Opciones adicionales
        autoStartTorrentDownloadsCheckbox = new CheckBox("Iniciar descargas automáticamente");
        autoStartTorrentDownloadsCheckbox.setSelected(true);

// Tooltip para la opción de inicio automático
        Tooltip autoStartTooltip = new Tooltip(
                "Al activar esta opción, las descargas de torrent comenzarán automáticamente " +
                        "cuando se añadan. Si está desactivada, se pondrán en estado 'En espera'."
        );
        autoStartTorrentDownloadsCheckbox.setTooltip(autoStartTooltip);

        createSubfoldersCheckbox = new CheckBox("Crear subcarpetas para series (Temporadas)");
        createSubfoldersCheckbox.setSelected(true);

// Tooltip para la opción de subcarpetas
        Tooltip subfoldersTooltip = new Tooltip(
                "Al activar esta opción, se crearán subcarpetas para cada temporada " +
                        "de una serie (por ejemplo: Serie/Temporada 1, Serie/Temporada 2, etc.)."
        );
        createSubfoldersCheckbox.setTooltip(subfoldersTooltip);

// Opción para descomprimir archivos
        extractArchivesCheckbox = new CheckBox("Descomprimir archivos al finalizar la descarga (RAR, ZIP, 7Z)");
        extractArchivesCheckbox.setSelected(true);

// Tooltip para la opción de extracción
        Tooltip extractTooltip = new Tooltip(
                "Al activar esta opción, los archivos comprimidos (RAR, ZIP, 7Z, etc.) " +
                        "se extraerán automáticamente al finalizar la descarga."
        );
        extractArchivesCheckbox.setTooltip(extractTooltip);

// Información sobre la configuración
        Label infoLabel = new Label("Configura el número de descargas simultáneas según los recursos de tu sistema. " +
                "Un valor más alto puede mejorar la velocidad pero consumir más recursos.");
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #7f8c8d;");

// Añadir componentes al grid
        grid.add(maxConcurrentTorrentDownloadsLabel, 0, 0);
        grid.add(maxConcurrentTorrentDownloadsSlider, 0, 1);
        grid.add(maxConcurrentTorrentDownloadsValueLabel, 1, 1);
        grid.add(autoStartTorrentDownloadsCheckbox, 0, 2);
        grid.add(createSubfoldersCheckbox, 0, 3);
        grid.add(extractArchivesCheckbox, 0, 4);
        grid.add(infoLabel, 0, 5, 2, 1);

        TitledPane downloadsPane = new TitledPane("Configuración de Descargas Torrent", grid);
        downloadsPane.setExpanded(true);

        return downloadsPane;
    }

    private TitledPane createDirectDownloadConfigSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

// Máximo de descargas simultáneas
        Label maxConcurrentDirectDownloadsLabel = new Label("Máximo de descargas simultáneas (Direct Download):");
        maxConcurrentDirectDownloadsSlider = new Slider(1, 10, 5);
        maxConcurrentDirectDownloadsSlider.setShowTickLabels(true);
        maxConcurrentDirectDownloadsSlider.setShowTickMarks(true);
        maxConcurrentDirectDownloadsSlider.setMajorTickUnit(1);
        maxConcurrentDirectDownloadsSlider.setMinorTickCount(0);
        maxConcurrentDirectDownloadsSlider.setSnapToTicks(true);
        Label maxConcurrentDirectDownloadsValueLabel = new Label("5");
        maxConcurrentDirectDownloadsSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            maxConcurrentDirectDownloadsValueLabel.setText(String.valueOf(newVal.intValue()));
        });

// Límite de velocidad de descarga
        Label directDownloadSpeedLimitLabel = new Label("Límite de velocidad de descarga (KB/s, 0 = sin límite):");
        directDownloadSpeedLimitSlider = new Slider(0, 10000, 0);
        directDownloadSpeedLimitSlider.setShowTickLabels(true);
        directDownloadSpeedLimitSlider.setShowTickMarks(true);
        directDownloadSpeedLimitSlider.setMajorTickUnit(2000);
        directDownloadSpeedLimitSlider.setMinorTickCount(1);
        Label directDownloadSpeedLimitValueLabel = new Label("0");
        directDownloadSpeedLimitSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            directDownloadSpeedLimitValueLabel.setText(String.valueOf(newVal.intValue()));
        });

// Opciones adicionales
        autoStartDirectDownloadsCheckbox = new CheckBox("Iniciar descargas directas automáticamente");
        autoStartDirectDownloadsCheckbox.setSelected(true);

        streamplayHeadlessCheckbox = new CheckBox("Ejecutar Streamplay en segundo plano (modo headless)");
        streamplayHeadlessCheckbox.setSelected(true);
        Tooltip streamplayHeadlessTooltip = new Tooltip(
                "Desactiva esta opción para ver el navegador de Streamplay mientras se automatiza la descarga."
        );
        streamplayHeadlessCheckbox.setTooltip(streamplayHeadlessTooltip);

        grid.add(maxConcurrentDirectDownloadsLabel, 0, 0);
        grid.add(maxConcurrentDirectDownloadsSlider, 0, 1);
        grid.add(maxConcurrentDirectDownloadsValueLabel, 1, 1);
        grid.add(directDownloadSpeedLimitLabel, 0, 2);
        grid.add(directDownloadSpeedLimitSlider, 0, 3);
        grid.add(directDownloadSpeedLimitValueLabel, 1, 3);
        grid.add(autoStartDirectDownloadsCheckbox, 0, 4);
        grid.add(streamplayHeadlessCheckbox, 0, 5, 2, 1);

        TitledPane directDownloadsPane = new TitledPane("Configuración de Descargas Directas", grid);
        directDownloadsPane.setExpanded(true);

        return directDownloadsPane;
    }

    private TitledPane createCaptchaConfigSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

// API Key de 2Captcha
        Label apiKeyCaptchaLabel = new Label("API Key de 2Captcha:");
        apiKeyCaptchaField = new TextField();
        apiKeyCaptchaField.setPromptText("Introduce tu API Key de 2Captcha");

// Botón para verificar la API Key
        Button verifyApiKeyButton = new Button("Verificar API Key");
        verifyApiKeyButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        verifyApiKeyButton.setOnAction(e -> verifyCaptchaApiKey());

// Información sobre 2Captcha
        Label captchaInfoLabel = new Label("2Captcha es un servicio que ayuda a resolver CAPTCHAs automáticamente.");
        captchaInfoLabel.setWrapText(true);
        captchaInfoLabel.setStyle("-fx-font-style: italic; -fx-text-fill: #7f8c8d;");

// Enlace a 2Captcha
        Hyperlink captchaLink = new Hyperlink("Obtener una API Key en 2captcha.com");
        captchaLink.setOnAction(e -> {
            try {
                Desktop.getDesktop().browse(new URI("https://2captcha.com"));
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        });

        grid.add(apiKeyCaptchaLabel, 0, 0);
        grid.add(apiKeyCaptchaField, 0, 1);
        grid.add(verifyApiKeyButton, 1, 1);
        grid.add(captchaInfoLabel, 0, 2, 2, 1);
        grid.add(captchaLink, 0, 3, 2, 1);

        TitledPane captchaPane = new TitledPane("Configuración de 2Captcha", grid);
        captchaPane.setExpanded(true);

        return captchaPane;
    }

    private void verifyCaptchaApiKey() {
        String apiKey = apiKeyCaptchaField.getText().trim();
        if (apiKey.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Error", "Por favor, introduce una API Key de 2Captcha.");
            return;
        }

// Aquí implementaríamos la verificación real de la API Key
// Por ejemplo, haciendo una petición al servicio de 2Captcha
// Para esta demostración, simulamos una verificación exitosa
        showAlert(Alert.AlertType.INFORMATION, "Verificación Exitosa", "La API Key de 2Captcha parece ser válida. Se ha guardado correctamente.");
    }

    private TitledPane createDatabaseSection(Stage primaryStage) {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

// Base de datos deTorrent
        Label torrentDatabasePathLabel = new Label("Ruta de la base de datos de Torrent:");
        torrentDatabasePathField = new TextField();
        torrentDatabasePathField.setPromptText("DB/torrent_dw_db.db");
        torrentDatabasePathButton = new Button("Seleccionar archivo");
        torrentDatabasePathButton.setOnAction(e -> selectDatabaseFile(primaryStage, torrentDatabasePathField));

        Button testTorrentConnectionButton = new Button("Probar Conexión");
        testTorrentConnectionButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        testTorrentConnectionButton.setOnAction(e -> testDatabaseConnection(torrentDatabasePathField.getText(), "Torrent"));

        Button updateTorrentDatabaseButton = new Button("Actualizar Base de Datos");
        updateTorrentDatabaseButton.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white;");
        updateTorrentDatabaseButton.setOnAction(e -> updateDatabase(torrentDatabasePathField.getText(), "Torrent"));

// Base de datos de Direct Download
        Label directDatabasePathLabel = new Label("Ruta de la base de datos de Direct Download:");
        directDatabasePathField = new TextField();
        directDatabasePathField.setPromptText("DB/direct_dw_db.db");
        directDatabasePathButton = new Button("Seleccionar archivo");
        directDatabasePathButton.setOnAction(e -> selectDatabaseFile(primaryStage, directDatabasePathField));

        Button testDirectConnectionButton = new Button("Probar Conexión");
        testDirectConnectionButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        testDirectConnectionButton.setOnAction(e -> testDatabaseConnection(directDatabasePathField.getText(), "Direct Download"));

        Button updateDirectDatabaseButton = new Button("Actualizar Base de Datos");
        updateDirectDatabaseButton.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white;");
        updateDirectDatabaseButton.setOnAction(e -> updateDatabase(directDatabasePathField.getText(), "Direct Download"));

        grid.add(torrentDatabasePathLabel, 0, 0);
        grid.add(torrentDatabasePathField, 0, 1);
        grid.add(torrentDatabasePathButton, 1, 1);
        HBox torrentButtonsBox = new HBox(10);
        torrentButtonsBox.getChildren().addAll(testTorrentConnectionButton, updateTorrentDatabaseButton);
        grid.add(torrentButtonsBox, 0, 2);

        grid.add(directDatabasePathLabel, 0, 3);
        grid.add(directDatabasePathField, 0, 4);
        grid.add(directDatabasePathButton, 1, 4);
        HBox directButtonsBox = new HBox(10);
        directButtonsBox.getChildren().addAll(testDirectConnectionButton, updateDirectDatabaseButton);
        grid.add(directButtonsBox, 0, 5);

        TitledPane databasePane = new TitledPane("Base de Datos", grid);
        databasePane.setExpanded(true);

        return databasePane;
    }

    private TitledPane createInterfaceSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

// Tema
        Label themeLabel = new Label("Tema de la interfaz:");
        themeComboBox = new ComboBox<>();
        // Añadir temas predefinidos y opción de personalización
        themeComboBox.getItems().addAll(
                "Oscuro Elegante",
                "Claro Elegante",
                "Personalizado",
                "Sistema"
        );
        // Tema por defecto
        themeComboBox.setValue("Oscuro Elegante");
        // Aplicar el tema inicial según la selección actual
        themeComboBox.setOnAction(e -> applySelectedTheme(themeComboBox.getValue()));

// Notificaciones
        showNotificationsCheckbox = new CheckBox("Mostrar notificaciones");
        showNotificationsCheckbox.setSelected(true);

// Minimizar a bandeja
        minimizeToTrayCheckbox = new CheckBox("Minimizar a bandeja del sistema");
        minimizeToTrayCheckbox.setSelected(false);

        grid.add(themeLabel, 0, 0);
        grid.add(themeComboBox, 0, 1);
        grid.add(showNotificationsCheckbox, 0, 2);
        grid.add(minimizeToTrayCheckbox, 0, 3);

        TitledPane interfacePane = new TitledPane("Configuración de Interfaz", grid);
        interfacePane.setExpanded(true);

        return interfacePane;
    }

    /**
     * Aplica el tema seleccionado cargando el CSS correspondiente o
     * generando uno personalizado. Esta función se ejecuta cuando el
     * usuario cambia la selección en el combo de temas.
     *
     * @param theme Nombre del tema seleccionado
     */
    private void applySelectedTheme(String theme) {
        if (mainUI == null) {
            return;
        }
        switch (theme) {
            case "Oscuro Elegante":
                // Cargar el tema oscuro por defecto
                mainUI.loadStylesheet(DARK_THEME_FILE);
                break;
            case "Claro Elegante":
                // Cargar el tema claro elegante
                mainUI.loadStylesheet(LIGHT_THEME_FILE);
                break;
            case "Personalizado":
                // Cargar el tema personalizado previamente generado. No regenerar aquí para evitar recursión.
                mainUI.loadStylesheet(CUSTOM_CSS_FILE);
                break;
            case "Sistema":
            default:
                // Limpiar estilos para usar los valores por defecto del sistema
                mainUI.loadStylesheet("");
                break;
        }
    }

    private TitledPane createColorCustomizationSection() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));

// Color de pestañas y bordes
        Label tabsBorderColorLabel = new Label("Color de pestañas y bordes:");
        tabsBorderColorPicker = new ColorPicker(Color.web("#3498db"));
        tabsBorderColorPicker.setOnAction(e -> updatePreview());

// Color de fondo
        Label backgroundColorLabel = new Label("Color de fondo:");
        backgroundColorPicker = new ColorPicker(Color.web("#f5f5f5"));
        backgroundColorPicker.setOnAction(e -> updatePreview());

// Color de texto
        Label fontColorLabel = new Label("Color de texto:");
        fontColorPicker = new ColorPicker(Color.web("#333333"));
        fontColorPicker.setOnAction(e -> updatePreview());

// Color de botones
        Label buttonColorLabel = new Label("Color de botones:");
        buttonColorPicker = new ColorPicker(Color.web("#2ecc71"));
        buttonColorPicker.setOnAction(e -> updatePreview());

// Vista previa
        Label previewLabel = new Label("Vista previa:");
        Button applyColorsButton = new Button("Aplicar colores");
        applyColorsButton.setOnAction(e -> generateAndApplyCSS());

// Botón para restaurar colores predeterminados
        Button resetColorsButton = new Button("Restaurar colores predeterminados");
        resetColorsButton.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white;");
        resetColorsButton.setOnAction(e -> resetColorSettings());

        grid.add(tabsBorderColorLabel, 0, 0);
        grid.add(tabsBorderColorPicker, 1, 0);
        grid.add(backgroundColorLabel, 0, 1);
        grid.add(backgroundColorPicker, 1, 1);
        grid.add(fontColorLabel, 0, 2);
        grid.add(fontColorPicker, 1, 2);
        grid.add(buttonColorLabel, 0, 3);
        grid.add(buttonColorPicker, 1, 3);
        grid.add(previewLabel, 0, 4);
        grid.add(applyColorsButton, 1, 4);
        grid.add(resetColorsButton, 0, 5, 2, 1);

// Panel de vista previa
        previewPanel = createPreviewPanel();
        grid.add(previewPanel, 2, 0, 1, 6);

        TitledPane colorCustomizationPane = new TitledPane("Personalización de Colores", grid);
        colorCustomizationPane.setExpanded(true);

        return colorCustomizationPane;
    }

    private VBox createPreviewPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));
        panel.setStyle("-fx-background-color: #f5f5f5; -fx-border-color: #3498db; -fx-border-width: 1px;");
        panel.setPrefWidth(200);
        panel.setPrefHeight(200);

        previewTitle = new Label("Vista Previa");
        previewTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: #333333;");

        previewButton = new Button("Botón de Ejemplo");
        previewButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");

        previewCheckbox = new CheckBox("Opción de Ejemplo");
        previewCheckbox.setStyle("-fx-text-fill: #333333;");

        Separator previewSeparator = new Separator();

        previewText = new Label("Este es un texto de ejemplo para mostrar cómo se verán los colores en la aplicación.");
        previewText.setWrapText(true);
        previewText.setStyle("-fx-text-fill: #333333;");

        panel.getChildren().addAll(previewTitle, previewButton, previewCheckbox, previewSeparator, previewText);

        return panel;
    }

    private void updatePreview() {
// Actualizar el panel de vista previa con los colores seleccionados
        String tabsBorderColor = toHexString(tabsBorderColorPicker.getValue());
        String backgroundColor = toHexString(backgroundColorPicker.getValue());
        String fontColor = toHexString(fontColorPicker.getValue());
        String buttonColor = toHexString(buttonColorPicker.getValue());

        previewPanel.setStyle("-fx-background-color: " + backgroundColor + "; -fx-border-color: " + tabsBorderColor + "; -fx-border-width: 1px;");
        previewTitle.setStyle("-fx-font-weight: bold; -fx-text-fill: " + fontColor + ";");
        previewButton.setStyle("-fx-background-color: " + buttonColor + "; -fx-text-fill: white;");
        previewCheckbox.setStyle("-fx-text-fill: " + fontColor + ";");
        previewText.setStyle("-fx-text-fill: " + fontColor + ";");
    }

    private void generateAndApplyCSS() {
        // Generar el archivo CSS con los colores seleccionados
        String tabsBorderColor = toHexString(tabsBorderColorPicker.getValue());
        String backgroundColor = toHexString(backgroundColorPicker.getValue());
        String fontColor = toHexString(fontColorPicker.getValue());
        String buttonColor = toHexString(buttonColorPicker.getValue());

        try {
            // Crear el directorio si no existe
            Path cssDir = Paths.get("src/main/resources");
            if (!Files.exists(cssDir)) {
                Files.createDirectories(cssDir);
            }

            // Construir un CSS más completo para aplicar los colores en toda la UI
            String cssContent = "/* Estilos generados por MovieDownloader */\n" +
                    ".root {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "  -fx-text-fill: " + fontColor + ";\n" +
                    "  -fx-font-family: \"System\";\n" +
                    "}\n\n" +
                    "/* Texto en etiquetas, campos de texto y celdas */\n" +
                    ".label, .text, .text-area, .text-field, .combo-box-base, .table-view, .list-cell, .check-box .text, .radio-button .text {\n" +
                    "  -fx-text-fill: " + fontColor + ";\n" +
                    "}\n\n" +
                    "/* Botones */\n" +
                    ".button {\n" +
                    "  -fx-background-color: " + buttonColor + ";\n" +
                    "  -fx-text-fill: white;\n" +
                    "  -fx-background-radius: 4;\n" +
                    "  -fx-font-weight: bold;\n" +
                    "  -fx-padding: 6 12;\n" +
                    "}\n\n" +
                    ".button:hover {\n" +
                    "  -fx-background-color: derive(" + buttonColor + ", 20%);\n" +
                    "}\n\n" +
                    "/* Pestañas */\n" +
                    ".tab-pane .tab-header-area .tab-header-background {\n" +
                    "  -fx-background-color: " + tabsBorderColor + ";\n" +
                    "}\n\n" +
                    ".tab-pane .tab {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "  -fx-border-color: " + tabsBorderColor + ";\n" +
                    "  -fx-padding: 4 8;\n" +
                    "}\n\n" +
                    ".tab-pane .tab:selected {\n" +
                    "  -fx-background-color: " + tabsBorderColor + ";\n" +
                    "  -fx-border-color: " + tabsBorderColor + ";\n" +
                    "  -fx-text-fill: white;\n" +
                    "}\n\n" +
                    ".tab-pane .tab .tab-label {\n" +
                    "  -fx-text-fill: " + fontColor + ";\n" +
                    "}\n\n" +
                    "/* Campos de texto, cajas de combinación y similares */\n" +
                    ".text-field, .combo-box, .combo-box-base, .choice-box, .spinner {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "  -fx-border-color: " + tabsBorderColor + ";\n" +
                    "  -fx-border-radius: 3;\n" +
                    "  -fx-background-radius: 3;\n" +
                    "  -fx-text-fill: " + fontColor + ";\n" +
                    "}\n\n" +
                    ".text-field:focused, .combo-box-base:focused, .choice-box:focused, .spinner:focused {\n" +
                    "  -fx-border-color: " + buttonColor + ";\n" +
                    "}\n\n" +
                    "/* Checkbox y radio */\n" +
                    ".check-box .box, .radio-button .radio {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "  -fx-border-color: " + tabsBorderColor + ";\n" +
                    "  -fx-border-radius: 3;\n" +
                    "}\n\n" +
                    ".check-box:selected .mark, .radio-button:selected .radio {\n" +
                    "  -fx-background-color: " + buttonColor + ";\n" +
                    "}\n\n" +
                    "/* Panelemos desplegables */\n" +
                    ".titled-pane > .title {\n" +
                    "  -fx-background-color: " + tabsBorderColor + ";\n" +
                    "  -fx-text-fill: white;\n" +
                    "}\n\n" +
                    ".titled-pane > .content {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "  -fx-border-color: " + tabsBorderColor + ";\n" +
                    "}\n\n" +
                    "/* TableView */\n" +
                    ".table-view {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "  -fx-table-cell-border-color: " + tabsBorderColor + ";\n" +
                    "  -fx-table-header-border-color: " + tabsBorderColor + ";\n" +
                    "  -fx-control-inner-background: " + backgroundColor + ";\n" +
                    "}\n\n" +
                    ".table-view .column-header-background {\n" +
                    "  -fx-background-color: " + tabsBorderColor + ";\n" +
                    "}\n\n" +
                    ".table-view .column-header .label {\n" +
                    "  -fx-text-fill: " + fontColor + ";\n" +
                    "  -fx-font-weight: bold;\n" +
                    "}\n\n" +
                    ".table-row-cell:odd {\n" +
                    "  -fx-background-color: derive(" + backgroundColor + ", -5%);\n" +
                    "}\n\n" +
                    ".table-row-cell:even {\n" +
                    "  -fx-background-color: " + backgroundColor + ";\n" +
                    "}\n\n" +
                    ".table-row-cell:selected {\n" +
                    "  -fx-background-color: " + tabsBorderColor + ";\n" +
                    "  -fx-text-fill: white;\n" +
                    "}\n\n" +
                    "/* Hipervínculos */\n" +
                    ".hyperlink {\n" +
                    "  -fx-text-fill: " + tabsBorderColor + ";\n" +
                    "}\n\n" +
                    ".hyperlink:hover {\n" +
                    "  -fx-text-fill: derive(" + tabsBorderColor + ", 20%);\n" +
                    "}\n";

            // Escribir el archivo CSS personalizado
            Files.write(Paths.get(CUSTOM_CSS_FILE), cssContent.getBytes());
            System.out.println("Archivo CSS generado: " + CUSTOM_CSS_FILE);

            // Aplicar el CSS personalizado
            if (mainUI != null) {
                mainUI.loadStylesheet(CUSTOM_CSS_FILE);
                showAlert(Alert.AlertType.INFORMATION, "Estilos Aplicados", "Los estilos personalizados han sido aplicados correctamente.");
            }
        } catch (Exception e) {
            Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error CSS", "No se pudo generar el archivo de estilos: " + e.getMessage()));
        }
    }

    private void resetColorSettings() {
// Restaurar colores predeterminados
        tabsBorderColorPicker.setValue(Color.web("#3498db"));
        backgroundColorPicker.setValue(Color.web("#f5f5f5"));
        fontColorPicker.setValue(Color.web("#333333"));
        buttonColorPicker.setValue(Color.web("#2ecc71"));

// Actualizar la vista previa
        updatePreview();

        showAlert(Alert.AlertType.INFORMATION, "Colores Restaurados", "Los colores han sido restaurados a los valores predeterminados.");
    }

    private String toHexString(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private void selectFolder(Stage primaryStage, TextField textField) {
        DirectoryChooser directoryChooser = new DirectoryChooser();

// Si ya hay una ruta, iniciar el selector en esa ubicación
        if (!textField.getText().isEmpty()) {
            File initialDir = new File(textField.getText());
            if (initialDir.exists() && initialDir.isDirectory()) {
                directoryChooser.setInitialDirectory(initialDir);
            }
        }

        File selectedDirectory = directoryChooser.showDialog(primaryStage);
        if (selectedDirectory != null) {
            textField.setText(selectedDirectory.getAbsolutePath());
            System.out.println("Carpeta seleccionada: " + selectedDirectory.getAbsolutePath());
        }
    }

    private void selectDatabaseFile(Stage primaryStage, TextField textField) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("SQLite Database", "*.db")
        );

// Si ya hay una ruta, iniciar el selector en esa ubicación
        if (!textField.getText().isEmpty()) {
            File initialFile = new File(textField.getText());
            if (initialFile.exists()) {
                fileChooser.setInitialDirectory(initialFile.getParentFile());
            }
        }

        File selectedFile = fileChooser.showOpenDialog(primaryStage);
        if (selectedFile != null) {
            if (!selectedFile.exists() || !selectedFile.isFile()) {
                showAlert(Alert.AlertType.ERROR, "Archivo inválido",
                        "Debes seleccionar una base de datos existente. No se crearán archivos nuevos desde la aplicación.");
                return;
            }
            textField.setText(selectedFile.getAbsolutePath());
            System.out.println("Archivo de base de datos seleccionado: " + selectedFile.getAbsolutePath());
        }
    }

    private void testDatabaseConnection(String dbPath, String dbType) {
        if (dbPath.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Error", "Por favor, especifica la ruta de la base de datos de " + dbType + ".");
            return;
        }

// Aquí implementaríamos la lógica para probar la conexión a la base de datos
// Por ejemplo:
// boolean success = connectDataBase.testConnection(dbPath);
        System.out.println("Probando conexión a la base de datos de " + dbType + ": " + dbPath);

// Para la demostración, simulamos una conexión exitosa
        boolean success = true;
        if (success) {
            showAlert(Alert.AlertType.INFORMATION, "Conexión Exitosa", "La conexión a la base de datos de " + dbType + " se ha establecido correctamente.");
        } else {
            showAlert(Alert.AlertType.ERROR, "Error de Conexión", "No se pudo conectar a la base de datos de " + dbType + ". Verifica la ruta y que el archivo exista.");
        }
    }

    private void updateDatabase(String dbPath, String dbType) {
        if (dbPath.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Error", "Por favor, especifica la ruta de la base de datos de " + dbType + ".");
            return;
        }

// Aquí implementaríamos la lógica para actualizar la base de datos
// Por ejemplo:
// boolean success = connectDataBase.updateDatabase(dbPath);
        System.out.println("Actualizando base de datos de " + dbType + ": " + dbPath);

// Para la demostración, simulamos una actualización exitosa
        boolean success = true;
        if (success) {
            showAlert(Alert.AlertType.INFORMATION, "Actualización Exitosa", "La base de datos de " + dbType + " se ha actualizado correctamente.");
        } else {
            showAlert(Alert.AlertType.ERROR, "Error de Actualización", "No se pudo actualizar la base de datos de " + dbType + ".");
        }
    }

    private void saveSettings() {
// Guardar configuración en el archivo JSON
        JSONObject config = new JSONObject();

// Rutas
        config.put("torrentMovieDestination", torrentMovieDestinationField.getText());
        config.put("torrentSeriesDestination", torrentSeriesDestinationField.getText());
        config.put("directMovieDestination", directMovieDestinationField.getText());
        config.put("directSeriesDestination", directSeriesDestinationField.getText());
// Mantener claves heredadas para compatibilidad
        config.put("movieDestination", directMovieDestinationField.getText());
        config.put("seriesDestination", directSeriesDestinationField.getText());

// Bases de datos
        config.put("torrentDatabasePath", torrentDatabasePathField.getText());
        config.put("directDatabasePath", directDatabasePathField.getText());

// Configuración de descargas de torrent
        config.put("autoStartTorrentDownloads", autoStartTorrentDownloadsCheckbox.isSelected());
        config.put("createSubfolders", createSubfoldersCheckbox.isSelected());
        config.put("extractArchives", extractArchivesCheckbox.isSelected());
        config.put("maxConcurrentTorrentDownloads", (int) maxConcurrentTorrentDownloadsSlider.getValue());

// Configuración de descargas directas
        config.put("maxConcurrentDirectDownloads", (int) maxConcurrentDirectDownloadsSlider.getValue());
        config.put("directDownloadSpeedLimit", (int) directDownloadSpeedLimitSlider.getValue());
        config.put("autoStartDirectDownloads", autoStartDirectDownloadsCheckbox.isSelected());
        config.put("streamplayHeadless", streamplayHeadlessCheckbox.isSelected());
        config.put("apiKeyCaptcha", apiKeyCaptchaField.getText());

// Configuración de interfaz
        config.put("theme", themeComboBox.getValue());
        config.put("showNotifications", showNotificationsCheckbox.isSelected());
        config.put("minimizeToTray", minimizeToTrayCheckbox.isSelected());

// Configuración de colores
        config.put("tabsBorderColor", toHexString(tabsBorderColorPicker.getValue()));
        config.put("backgroundColor", toHexString(backgroundColorPicker.getValue()));
        config.put("fontColor", toHexString(fontColorPicker.getValue()));
        config.put("buttonColor", toHexString(buttonColorPicker.getValue()));

        if (scraperProgressTracker != null) {
            config.put("directScraperMoviesLastPage", scraperProgressTracker.getDirectMoviesLastPage());
            config.put("directScraperSeriesLastPage", scraperProgressTracker.getDirectSeriesLastPage());
            config.put("torrentScraperMoviesLastPage", scraperProgressTracker.getTorrentMoviesLastPage());
            config.put("torrentScraperSeriesLastPage", scraperProgressTracker.getTorrentSeriesLastPage());
        }

        try {
// Asegurar que el directorio existe
            File configFile = new File(CONFIG_FILE);
            if (configFile.getParentFile() != null && !configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }

// Escribir el archivo JSON
            try (FileWriter file = new FileWriter(CONFIG_FILE)) {
                file.write(config.toJSONString());
                file.flush();
                System.out.println("Configuración guardada en: " + CONFIG_FILE);
            }

            if (mainUI != null) {
                mainUI.updateConfig(config);
                mainUI.applyStreamplayHeadlessPreference(streamplayHeadlessCheckbox.isSelected());
            }

            // Generar y aplicar el archivo CSS solo si el tema seleccionado es "Personalizado".
            String selectedTheme = themeComboBox.getValue();
            if ("Personalizado".equals(selectedTheme)) {
                generateAndApplyCSS();
            } else {
                // Aplicar el tema seleccionado de manera inmediata
                applySelectedTheme(selectedTheme);
            }

            // Actualizar la configuración del TorrentDownloader si está disponible
            if (torrentDownloader != null) {
                // Actualizar la configuración del TorrentDownloader con los nuevos valores
                torrentDownloader.updateConfig(
                        (int) maxConcurrentTorrentDownloadsSlider.getValue(), // Usar el valor del slider
                        extractArchivesCheckbox.isSelected(),
                        0, // Sin límite de velocidad de descarga
                        0, // Sin límite de velocidad de subida
                        autoStartTorrentDownloadsCheckbox.isSelected()
                );
                System.out.println("Configuración del TorrentDownloader actualizada");
            } else {
                System.out.println("TorrentDownloader no disponible, la configuración se aplicará en el próximo reinicio");
            }

            // Recargar conexiones de bases de datos con las nuevas rutas
            if (mainUI != null) {
                mainUI.reloadDatabases(torrentDatabasePathField.getText(), directDatabasePathField.getText());
            }

            showAlert(Alert.AlertType.INFORMATION, "Configuración Guardada", "La configuración se ha guardado correctamente.");
        } catch (IOException e) {
            System.err.println("Error al guardar la configuración: " + e.getMessage());
            e.printStackTrace();
            showAlert(Alert.AlertType.ERROR, "Error", "No se pudo guardar la configuración: " + e.getMessage());
        }
    }

    void loadSettings() {
        try {
            File configFile = new File(CONFIG_FILE);
            if (!configFile.exists()) {
                System.out.println("Archivo de configuración no encontrado. Usando valores predeterminados.");
// Establecer valores predeterminados para las bases de datos
                torrentDatabasePathField.setText("DB/torrent_dw_db.db");
                directDatabasePathField.setText("DB/direct_dw_db.db");
                return;
            }

            JSONParser parser = new JSONParser();
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                JSONObject config = (JSONObject) parser.parse(reader);

// Rutas
                String legacyMovieDestination = config.containsKey("movieDestination")
                        ? (String) config.get("movieDestination")
                        : "";
                String legacySeriesDestination = config.containsKey("seriesDestination")
                        ? (String) config.get("seriesDestination")
                        : "";

                if (config.containsKey("torrentMovieDestination")) {
                    torrentMovieDestinationField.setText((String) config.get("torrentMovieDestination"));
                } else if (!legacyMovieDestination.isEmpty()) {
                    torrentMovieDestinationField.setText(legacyMovieDestination);
                }

                if (config.containsKey("torrentSeriesDestination")) {
                    torrentSeriesDestinationField.setText((String) config.get("torrentSeriesDestination"));
                } else if (!legacySeriesDestination.isEmpty()) {
                    torrentSeriesDestinationField.setText(legacySeriesDestination);
                }

                if (config.containsKey("directMovieDestination")) {
                    directMovieDestinationField.setText((String) config.get("directMovieDestination"));
                } else if (!legacyMovieDestination.isEmpty()) {
                    directMovieDestinationField.setText(legacyMovieDestination);
                }

                if (config.containsKey("directSeriesDestination")) {
                    directSeriesDestinationField.setText((String) config.get("directSeriesDestination"));
                } else if (!legacySeriesDestination.isEmpty()) {
                    directSeriesDestinationField.setText(legacySeriesDestination);
                }

// Bases de datos
                if (config.containsKey("torrentDatabasePath")) {
                    torrentDatabasePathField.setText((String) config.get("torrentDatabasePath"));
                } else {
                    torrentDatabasePathField.setText("DB/torrent_dw_db.db");
                }
                if (config.containsKey("directDatabasePath")) {
                    directDatabasePathField.setText((String) config.get("directDatabasePath"));
                } else {
                    directDatabasePathField.setText("DB/direct_dw_db.db");
                }

// Configuración de descargas de torrent
                if (config.containsKey("autoStartTorrentDownloads")) {
                    autoStartTorrentDownloadsCheckbox.setSelected((Boolean) config.get("autoStartTorrentDownloads"));
                }
                if (config.containsKey("createSubfolders")) {
                    createSubfoldersCheckbox.setSelected((Boolean) config.get("createSubfolders"));
                }
                if (config.containsKey("extractArchives")) {
                    extractArchivesCheckbox.setSelected((Boolean) config.get("extractArchives"));
                }
                if (config.containsKey("maxConcurrentTorrentDownloads")) {
                    maxConcurrentTorrentDownloadsSlider.setValue(((Number) config.get("maxConcurrentTorrentDownloads")).intValue());
                }

// Configuración de descargas directas
                if (config.containsKey("maxConcurrentDirectDownloads")) {
                    maxConcurrentDirectDownloadsSlider.setValue(((Number) config.get("maxConcurrentDirectDownloads")).intValue());
                }
                if (config.containsKey("directDownloadSpeedLimit")) {
                    directDownloadSpeedLimitSlider.setValue(((Number) config.get("directDownloadSpeedLimit")).intValue());
                }
                if (config.containsKey("autoStartDirectDownloads")) {
                    autoStartDirectDownloadsCheckbox.setSelected((Boolean) config.get("autoStartDirectDownloads"));
                }
                if (config.containsKey("streamplayHeadless")) {
                    streamplayHeadlessCheckbox.setSelected((Boolean) config.get("streamplayHeadless"));
                } else {
                    streamplayHeadlessCheckbox.setSelected(true);
                }
                if (config.containsKey("apiKeyCaptcha")) {
                    apiKeyCaptchaField.setText((String) config.get("apiKeyCaptcha"));
                }

                if (mainUI != null) {
                    mainUI.applyStreamplayHeadlessPreference(streamplayHeadlessCheckbox.isSelected());
                }

// Configuración de interfaz
                if (config.containsKey("theme")) {
                    String theme = (String) config.get("theme");
                    // Mapear valores antiguos a los nuevos nombres de tema
                    if ("Claro".equalsIgnoreCase(theme)) {
                        theme = "Claro Elegante";
                    } else if ("Oscuro".equalsIgnoreCase(theme)) {
                        theme = "Oscuro Elegante";
                    }
                    // Establecer el tema y aplicarlo
                    themeComboBox.setValue(theme);
                    applySelectedTheme(theme);
                }
                if (config.containsKey("showNotifications")) {
                    showNotificationsCheckbox.setSelected((Boolean) config.get("showNotifications"));
                }
                if (config.containsKey("minimizeToTray")) {
                    minimizeToTrayCheckbox.setSelected((Boolean) config.get("minimizeToTray"));
                }

// Configuración de colores
                if (config.containsKey("tabsBorderColor")) {
                    tabsBorderColorPicker.setValue(Color.web((String) config.get("tabsBorderColor")));
                }
                if (config.containsKey("backgroundColor")) {
                    backgroundColorPicker.setValue(Color.web((String) config.get("backgroundColor")));
                }
                if (config.containsKey("fontColor")) {
                    fontColorPicker.setValue(Color.web((String)config.get("fontColor")));
                }
                if (config.containsKey("buttonColor")) {
                    buttonColorPicker.setValue(Color.web((String) config.get("buttonColor")));
                }

                if (scraperProgressTracker != null) {
                    scraperProgressTracker.setDirectMoviesLastPage(parseConfigInt(config.get("directScraperMoviesLastPage"), -1));
                    scraperProgressTracker.setDirectSeriesLastPage(parseConfigInt(config.get("directScraperSeriesLastPage"), -1));
                    scraperProgressTracker.setTorrentMoviesLastPage(parseConfigInt(config.get("torrentScraperMoviesLastPage"), -1));
                    scraperProgressTracker.setTorrentSeriesLastPage(parseConfigInt(config.get("torrentScraperSeriesLastPage"), -1));
                }

                // Actualizar la vista previa
                updatePreview();

                // Aplicar el tema seleccionado tras cargar la configuración.  
                // Esto garantiza que se apliquen los estilos correctos al abrir la aplicación.
                applySelectedTheme(themeComboBox.getValue());

                System.out.println("Configuración cargada desde: " + CONFIG_FILE);

// Si el TorrentDownloader ya está disponible, actualizar su configuración
                if (torrentDownloader != null) {
                    torrentDownloader.updateConfig(
                            (int) maxConcurrentTorrentDownloadsSlider.getValue(), // Usar el valor del slider
                            extractArchivesCheckbox.isSelected(),
                            0, // Sin límite de velocidad de descarga
                            0, // Sin límite de velocidad de subida
                            autoStartTorrentDownloadsCheckbox.isSelected()
                    );
                    System.out.println("Configuración del TorrentDownloader actualizada al cargar ajustes");
                }
                // Si el gestor de torrents existe, actualiza su configuración con los valores cargados.
                if (torrentDownloader != null) {
                    torrentDownloader.updateConfig(
                        (int) maxConcurrentTorrentDownloadsSlider.getValue(),
                        extractArchivesCheckbox.isSelected(),
                        0, // Sin límite de velocidad de descarga
                        0, // Sin límite de velocidad de subida
                        autoStartTorrentDownloadsCheckbox.isSelected()
                    );
                    System.out.println("Configuración del TorrentDownloader actualizada al cargar ajustes");
                }
            }
        } catch (IOException | ParseException e) {
            System.err.println("Error al cargar la configuración: " + e.getMessage());
            e.printStackTrace();
            showAlert(Alert.AlertType.ERROR, "Error", "No se pudo cargar la configuración: " + e.getMessage());
        }
    }

    private int parseConfigInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                // Fall through to return default value
            }
        }
        return defaultValue;
    }

    private void resetSettings() {
// Restaurar valores predeterminados
// Rutas
        torrentMovieDestinationField.setText("");
        torrentSeriesDestinationField.setText("");
        directMovieDestinationField.setText("");
        directSeriesDestinationField.setText("");

// Bases de datos
        torrentDatabasePathField.setText("DB/torrent_dw_db.db");
        directDatabasePathField.setText("DB/direct_dw_db.db");

// Configuración de descargas de torrent
        autoStartTorrentDownloadsCheckbox.setSelected(true);
        createSubfoldersCheckbox.setSelected(true);
        extractArchivesCheckbox.setSelected(true);
        maxConcurrentTorrentDownloadsSlider.setValue(1); // Valor predeterminado: 1

// Configuración de descargas directas
        maxConcurrentDirectDownloadsSlider.setValue(5);
        directDownloadSpeedLimitSlider.setValue(0);
        autoStartDirectDownloadsCheckbox.setSelected(true);
        streamplayHeadlessCheckbox.setSelected(true);
        apiKeyCaptchaField.setText("");

// Configuración de interfaz
        themeComboBox.setValue("Oscuro Elegante");
        applySelectedTheme("Oscuro Elegante");
        showNotificationsCheckbox.setSelected(true);
        minimizeToTrayCheckbox.setSelected(false);

        if (scraperProgressTracker != null) {
            scraperProgressTracker.reset();
        }

        if (mainUI != null) {
            mainUI.applyStreamplayHeadlessPreference(true);
        }

// Restaurar colores predeterminados
        resetColorSettings();

// Si el TorrentDownloader ya está disponible, actualizar su configuración
        if (torrentDownloader != null) {
            torrentDownloader.updateConfig(
                    1, // Valor predeterminado: 1 descarga simultánea
                    true, // extractArchives
                    0, // downloadSpeedLimit
                    0, // uploadSpeedLimit
                    true // autoStartDownloads
            );
            System.out.println("Configuración del TorrentDownloader restaurada a valores predeterminados");
        }

        System.out.println("Configuración restaurada a valores predeterminados");
        showAlert(Alert.AlertType.INFORMATION, "Configuración Restaurada", "La configuración se ha restaurado a los valores predeterminados.");
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // Getters para rutas de descarga
    public String getTorrentMovieDestination() {
        return torrentMovieDestinationField.getText();
    }

    public String getTorrentSeriesDestination() {
        return torrentSeriesDestinationField.getText();
    }

    public String getDirectMovieDestination() {
        return directMovieDestinationField.getText();
    }

    public String getDirectSeriesDestination() {
        return directSeriesDestinationField.getText();
    }

    public String getMovieDestination() {
        return getDirectMovieDestination();
    }

    public String getSeriesDestination() {
        return getDirectSeriesDestination();
    }

    // Getters para bases de datos
    public String getTorrentDatabasePath() {
        return torrentDatabasePathField.getText();
    }

    public String getDirectDatabasePath() {
        return directDatabasePathField.getText();
    }

    // Getters para configuración de descargas de torrent
    public int getMaxConcurrentDownloads() {
        return (int) maxConcurrentTorrentDownloadsSlider.getValue(); // Usar el valor del slider
    }

    public int getTorrentDownloadSpeedLimit() {
        return 0; // Sin límite de velocidad
    }

    public int getTorrentUploadSpeedLimit() {
        return 0; // Sin límite de velocidad
    }

    public boolean isAutoStartTorrentDownloads() {
        return autoStartTorrentDownloadsCheckbox.isSelected();
    }

    public boolean isCreateSubfolders() {
        return createSubfoldersCheckbox.isSelected();
    }

    public boolean isExtractArchives() {
        return extractArchivesCheckbox.isSelected();
    }

    // Getters para configuración de descargas directas
    public int getMaxConcurrentDirectDownloads() {
        return (int) maxConcurrentDirectDownloadsSlider.getValue();
    }

    public int getDirectDownloadSpeedLimit() {
        return (int) directDownloadSpeedLimitSlider.getValue();
    }

    public boolean isAutoStartDirectDownloads() {
        return autoStartDirectDownloadsCheckbox.isSelected();
    }

    public boolean isStreamplayHeadless() {
        return streamplayHeadlessCheckbox == null || streamplayHeadlessCheckbox.isSelected();
    }

    public String getApiKeyCaptcha() {
        return apiKeyCaptchaField.getText();
    }

    // Getters para configuración de interfaz
    public String getTheme() {
        return themeComboBox.getValue();
    }

    public void applyCurrentTheme() {
        if (themeComboBox != null) {
            applySelectedTheme(themeComboBox.getValue());
        }
    }

    public boolean isShowNotifications() {
        return showNotificationsCheckbox.isSelected();
    }

    public boolean isMinimizeToTray() {
        return minimizeToTrayCheckbox.isSelected();
    }

    // Getters para configuración de colores
    public Color getTabsBorderColor() {
        return tabsBorderColorPicker.getValue();
    }

    public Color getBackgroundColor() {
        return backgroundColorPicker.getValue();
    }

    public Color getFontColor() {
        return fontColorPicker.getValue();
    }

    public Color getButtonColor() {
        return buttonColorPicker.getValue();
    }

    public Tab getTab() {
        return tab;
    }

    // Setter para el TorrentDownloader
    public void setTorrentDownloader(TorrentDownloader torrentDownloader) {
        // Asigna la referencia sin reinicializar el gestor torrent.
        this.torrentDownloader = torrentDownloader;
        // Si ya existe, aplica la configuración actual ajustando sus valores.
        if (this.torrentDownloader != null) {
            this.torrentDownloader.updateConfig(
                (int) maxConcurrentTorrentDownloadsSlider.getValue(),
                extractArchivesCheckbox.isSelected(),
                0, // Sin límite de velocidad de descarga
                0, // Sin límite de velocidad de subida
                autoStartTorrentDownloadsCheckbox.isSelected()
            );
        }
    }

    /**
     * Aplica la configuración actual al TorrentDownloader. Si no existe aún,
     * se crea uno nuevo y se registra en la MainUI. Si ya existe, se
     * actualiza su configuración sin cerrar la sesión para evitar retrasos
     * innecesarios en la inicialización.
     */
    private void applyCurrentSettingsToDownloader() {
        if (this.torrentDownloader == null) {
            // No hay instancia previa, crea una nueva con los valores actuales
            TorrentDownloader newDownloader = createTorrentDownloader();
            // Registra el nuevo gestor en la interfaz principal
            mainUI.setTorrentDownloader(newDownloader);
        } else {
            // Si ya existe, actualiza la configuración en lugar de reinicializar
            this.torrentDownloader.updateConfig(
                (int) maxConcurrentTorrentDownloadsSlider.getValue(),
                extractArchivesCheckbox.isSelected(),
                0, // Sin límite de velocidad de descarga
                0, // Sin límite de velocidad de subida
                autoStartTorrentDownloadsCheckbox.isSelected()
            );
        }
    }

    // Método para crear un nuevo TorrentDownloader con la configuración actual
    public TorrentDownloader createTorrentDownloader() {
        TorrentDownloader downloader = new TorrentDownloader(
                (int) maxConcurrentTorrentDownloadsSlider.getValue(), // Usar el valor del slider
                isExtractArchives(),
                0, // Sin límite de velocidad de descarga
                0  // Sin límite de velocidad de subida
        );
        downloader.setAutoStartDownloads(isAutoStartTorrentDownloads());
        return downloader;
    }
}