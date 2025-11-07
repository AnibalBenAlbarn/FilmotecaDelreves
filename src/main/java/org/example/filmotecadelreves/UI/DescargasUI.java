package org.example.filmotecadelreves.UI;
//ver1.3

import org.example.filmotecadelreves.downloaders.TorrentHealthReport;
import org.example.filmotecadelreves.downloaders.TorrentLogEntry;
import org.example.filmotecadelreves.downloaders.TorrentStats;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Callback;
import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.downloaders.TorrentDownloader;
import org.example.filmotecadelreves.moviesad.DownloadLimitManager;
import org.example.filmotecadelreves.moviesad.DownloadManager;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.example.filmotecadelreves.moviesad.TorrentState;
import org.example.filmotecadelreves.moviesad.DownloadPersistenceManager;
import org.example.filmotecadelreves.moviesad.DownloadPersistenceManager.DirectDownloadRecord;
import org.example.filmotecadelreves.moviesad.DownloadPersistenceManager.TorrentDownloadRecord;
import org.json.simple.JSONObject;

import java.awt.Desktop;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.concurrent.CompletableFuture;

/**
 * DescargasUI - Interfaz de usuario para gestionar descargas de torrents y directas
 */
public class DescargasUI implements TorrentDownloader.TorrentNotificationListener {

    //==========================================================================
    // SECCIONES DE LA INTERFAZ
    //==========================================================================

    /** Sección: Componentes principales de la UI */
    private Tab tab;
    private TabPane downloadsTabs;
    private Tab tabTorrents;
    private Tab tabDirectas;

    /** Sección: Tablas de descargas */
    private TableView<TorrentState> torrentsTable;
    private TableView<DirectDownload> directTable;

    /** Sección: Datos y filtros */
    private ObservableList<TorrentState> torrentDownloads;
    private ObservableList<DirectDownload> directDownloads;
    private FilteredList<TorrentState> filteredTorrentDownloads;
    private FilteredList<DirectDownload> filteredDirectDownloads;

    /** Sección: Gestión de descargas */
    private TorrentDownloader torrentDownloader;

    /** Sección: Contador de descargas para PowVideo y StreamPlay */
    private Label downloadCounterLabel;
    private Label downloadTimerLabel;
    private Timer countdownTimer;
    private final ReadOnlyBooleanWrapper powvideoStreamplayLimitActive = new ReadOnlyBooleanWrapper(false);

    /** Sección: Componentes para búsqueda de torrents */
    private TextField searchField;
    private ComboBox<String> searchEngineComboBox;
    private Button searchButton;

    /** Sección: Configuración de columnas */
    private Map<String, Boolean> torrentColumnsVisibility = new HashMap<>();
    private Map<String, Boolean> directColumnsVisibility = new HashMap<>();

    /** Persistencia */
    private final DownloadPersistenceManager persistenceManager = DownloadPersistenceManager.getInstance();
    private final Set<String> pendingTorrentAutoResume = new HashSet<>();

    //==========================================================================
    // CONSTRUCTOR
    //==========================================================================

    /**
     * Constructor de DescargasUI
     */
    public DescargasUI() {
        // Inicializar componentes principales
        initializeMainComponents();

        // Configurar las tablas
        setupTorrentsTable();
        setupDirectTable();

        // Crear panel de búsqueda de torrents
        VBox searchPanel = createTorrentSearchPanel();

        // Configurar contador de descargas
        HBox counterBox = createDownloadCounter();

        // Configurar filtros para torrents
        HBox torrentFiltersBox = createTorrentFilters();

        // Configurar filtros para descargas directas
        HBox directFiltersBox = createDirectFilters();

        // Organizar layout para la pestaña de torrents
        VBox torrentsLayout = new VBox(10);
        torrentsLayout.setPadding(new Insets(10));
        torrentsLayout.getChildren().addAll(searchPanel, torrentFiltersBox, torrentsTable);
        VBox.setVgrow(torrentsTable, Priority.ALWAYS);
        tabTorrents.setContent(torrentsLayout);

        // Organizar layout para la pestaña de descargas directas
        VBox directLayout = new VBox(10);
        directLayout.setPadding(new Insets(10));
        directLayout.getChildren().addAll(counterBox, directFiltersBox, directTable);
        VBox.setVgrow(directTable, Priority.ALWAYS);
        tabDirectas.setContent(directLayout);

        // Layout principal
        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));
        layout.getChildren().add(downloadsTabs);
        VBox.setVgrow(downloadsTabs, Priority.ALWAYS);
        tab.setContent(layout);

        // Inicializar mapas de visibilidad de columnas
        initializeColumnVisibilityMaps();

        // Restaurar descargas persistidas de sesiones previas
        restorePersistedDownloads();
    }

    /**
     * Inicializa los componentes principales de la UI
     */
    private void initializeMainComponents() {
        tab = new Tab("Descargas");
        tab.setClosable(false);

        // Inicializar listas observables
        torrentDownloads = FXCollections.observableArrayList();
        directDownloads = FXCollections.observableArrayList();

        // Crear listas filtradas
        filteredTorrentDownloads = new FilteredList<>(torrentDownloads, p -> true);
        filteredDirectDownloads = new FilteredList<>(directDownloads, p -> true);

        // Crear pestañas
        downloadsTabs = new TabPane();
        tabTorrents = new Tab("Torrents");
        tabTorrents.setClosable(false);
        tabDirectas = new Tab("Descargas Directas");
        tabDirectas.setClosable(false);
        downloadsTabs.getTabs().addAll(tabTorrents, tabDirectas);
    }

    /**
     * Crea el contador de descargas para PowVideo y StreamPlay
     *
     * @return HBox con el contador de descargas
     */
    private HBox createDownloadCounter() {
        HBox counterBox = new HBox(10);
        counterBox.setAlignment(Pos.CENTER_LEFT);
        downloadCounterLabel = new Label("Descargas PowVideo/StreamPlay: 0/10");
        downloadCounterLabel.setStyle("-fx-font-weight: bold;");

        downloadTimerLabel = new Label();
        downloadTimerLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #e74c3c;");
        downloadTimerLabel.setVisible(false);
        downloadTimerLabel.setManaged(false);

        // Iniciar el timer para actualizar el contador y la cuenta atrás
        startCountdownTimer();

        counterBox.getChildren().addAll(downloadCounterLabel, downloadTimerLabel);
        return counterBox;
    }

    /**
     * Expone el estado actual del límite PowVideo/StreamPlay para otras interfaces.
     *
     * @return Propiedad de solo lectura que indica si el límite está activo.
     */
    public ReadOnlyBooleanProperty powvideoStreamplayLimitActiveProperty() {
        return powvideoStreamplayLimitActive.getReadOnlyProperty();
    }

    /**
     * Crea los filtros para la tabla de torrents
     *
     * @return HBox con los filtros
     */
    private HBox createTorrentFilters() {
        HBox torrentFiltersBox = new HBox(10);

        ComboBox<String> torrentStatusFilter = new ComboBox<>();
        torrentStatusFilter.getItems().addAll("Todos", "Descargando", "Pausado", "Completado", "Error", "En espera");
        torrentStatusFilter.setValue("Todos");
        torrentStatusFilter.setPromptText("Filtrar por estado");

        Button clearCompletedTorrentsButton = new Button("Limpiar Completados");
        clearCompletedTorrentsButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        clearCompletedTorrentsButton.setOnAction(e -> clearCompletedTorrents());

        // Botón para configurar columnas visibles de torrents
        Button configTorrentColumnsButton = new Button("Configurar Columnas");
        configTorrentColumnsButton.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white;");
        configTorrentColumnsButton.setOnAction(e -> configureTableColumns(torrentsTable, torrentColumnsVisibility, "Configurar Columnas de Torrents"));

        torrentFiltersBox.getChildren().addAll(new Label("Filtrar:"), torrentStatusFilter, clearCompletedTorrentsButton, configTorrentColumnsButton);

        // Configurar filtro
        torrentStatusFilter.valueProperty().addListener((obs, oldVal, newVal) -> {
            filterTorrents(newVal);
        });

        return torrentFiltersBox;
    }

    /**
     * Crea los filtros para la tabla de descargas directas
     *
     * @return HBox con los filtros
     */
    private HBox createDirectFilters() {
        HBox directFiltersBox = new HBox(10);

        ComboBox<String> directStatusFilter = new ComboBox<>();
        directStatusFilter.getItems().addAll("All", "Downloading", "Paused", "Completed", "Error", "Waiting", "Processing");
        directStatusFilter.setValue("All");
        directStatusFilter.setPromptText("Filter by status");

        Button clearCompletedDirectButton = new Button("Clear Completed");
        clearCompletedDirectButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        clearCompletedDirectButton.setOnAction(e -> clearCompletedDirect());

        // Botón para configurar columnas visibles de descargas directas
        Button configDirectColumnsButton = new Button("Configurar Columnas");
        configDirectColumnsButton.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white;");
        configDirectColumnsButton.setOnAction(e -> configureTableColumns(directTable, directColumnsVisibility, "Configurar Columnas de Descargas Directas"));

        directFiltersBox.getChildren().addAll(new Label("Filter:"), directStatusFilter, clearCompletedDirectButton, configDirectColumnsButton);

        // Configurar filtro
        directStatusFilter.valueProperty().addListener((obs, oldVal, newVal) -> {
            filterDirectDownloads(newVal);
        });

        return directFiltersBox;
    }

    /**
     * Inicializa los mapas de visibilidad de columnas con los valores predeterminados
     */
    private void initializeColumnVisibilityMaps() {
        // Para la tabla de torrents
        if (torrentsTable != null) {
            for (TableColumn<TorrentState, ?> column : torrentsTable.getColumns()) {
                torrentColumnsVisibility.put(column.getText(), true);
            }
        }

        // Para la tabla de descargas directas
        if (directTable != null) {
            for (TableColumn<DirectDownload, ?> column : directTable.getColumns()) {
                directColumnsVisibility.put(column.getText(), true);
            }
        }
    }

    private void restorePersistedDownloads() {
        List<DirectDownloadRecord> directRecords = persistenceManager.loadDirectDownloads();
        for (DirectDownloadRecord record : directRecords) {
            DirectDownloader downloader = DownloadManager.getDownloaderForServer(record.getServer());
            DirectDownload download = new DirectDownload(
                    record.getId(),
                    record.getName(),
                    record.getProgress(),
                    record.getStatus(),
                    record.getServer(),
                    record.getUrl(),
                    record.getDestinationPath(),
                    downloader,
                    record.isManuallyPaused()
            );
            download.applySnapshot(record);
            directDownloads.add(download);

            if (downloader == null && record.getServer() != null && !record.getServer().isEmpty()) {
                Logger.getLogger(DescargasUI.class.getName()).log(Level.WARNING,
                        "No se encontró downloader para el servidor " + record.getServer() +
                                " al restaurar la descarga " + record.getName());
            }

            if (shouldAutoRestartDirect(download)) {
                autoRestartDirectDownload(download);
            }
        }

        List<TorrentDownloadRecord> torrentRecords = persistenceManager.loadTorrentDownloads();
        for (TorrentDownloadRecord record : torrentRecords) {
            TorrentState state = new TorrentState(
                    record.getId(),
                    record.getSource(),
                    record.getDestinationPath(),
                    0,
                    0,
                    0
            );
            state.applySnapshot(record);
            torrentDownloads.add(state);

            if (shouldAutoRestartTorrent(state)) {
                pendingTorrentAutoResume.add(state.getInstanceId());
            }
        }

        resumePendingTorrentRestores();
    }

    private boolean shouldAutoRestartDirect(DirectDownload download) {
        if (download == null || download.isUserPaused()) {
            return false;
        }
        if (download.getDownloader() == null) {
            return false;
        }
        String destination = download.getDestinationPath();
        if (destination == null || destination.isBlank()) {
            return false;
        }
        String status = download.getStatus();
        if (status == null) {
            return true;
        }
        String normalized = status.trim().toLowerCase();
        return !(normalized.contains("completed") || normalized.contains("cancelled") || normalized.contains("cancelado"));
    }

    private void autoRestartDirectDownload(DirectDownload download) {
        DirectDownloader downloader = download.getDownloader();
        if (downloader == null) {
            return;
        }
        download.setUserPaused(false);
        Platform.runLater(() -> {
            download.setStatus("Waiting");
            downloader.download(download.getUrl(), download.getDestinationPath(), download);
        });
    }

    private boolean shouldAutoRestartTorrent(TorrentState state) {
        if (state == null || state.isUserPaused()) {
            return false;
        }
        String source = state.getTorrentSource();
        if (source == null || source.isBlank()) {
            return false;
        }
        String status = state.getStatus();
        if (status == null) {
            return true;
        }
        String normalized = status.trim().toLowerCase();
        return !(normalized.startsWith("completado") || normalized.contains("eliminado") || normalized.contains("cancelado"));
    }

    private void resumePendingTorrentRestores() {
        if (torrentDownloader == null || pendingTorrentAutoResume.isEmpty()) {
            return;
        }

        Iterator<String> iterator = pendingTorrentAutoResume.iterator();
        while (iterator.hasNext()) {
            String torrentId = iterator.next();
            TorrentState state = findTorrentStateById(torrentId);
            if (state != null) {
                state.setStatus("En espera");
                state.setUserPaused(false);
                torrentDownloader.addTorrent(state);
            }
            iterator.remove();
        }
    }

    private TorrentState findTorrentStateById(String instanceId) {
        if (instanceId == null) {
            return null;
        }
        for (TorrentState state : torrentDownloads) {
            if (instanceId.equals(state.getInstanceId())) {
                return state;
            }
        }
        return null;
    }

    /**
     * Configura las columnas visibles de una tabla
     * @param table La tabla a configurar
     * @param columnsVisibility El mapa de visibilidad de columnas
     * @param title El título del diálogo
     */
    private <T> void configureTableColumns(TableView<T> table, Map<String, Boolean> columnsVisibility, String title) {
        // Crear un diálogo para configurar las columnas
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText("Selecciona las columnas que deseas mostrar en la tabla");

        // Crear el contenido del diálogo
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));

        // Crear un checkbox para cada columna
        for (TableColumn<T, ?> column : table.getColumns()) {
            String columnName = column.getText();
            CheckBox checkBox = new CheckBox(columnName);

            // Establecer el estado inicial según el mapa de visibilidad
            boolean isVisible = columnsVisibility.getOrDefault(columnName, true);
            checkBox.setSelected(isVisible);
            column.setVisible(isVisible);

            // Actualizar la visibilidad de la columna y el mapa cuando cambia el checkbox
            checkBox.setOnAction(e -> {
                boolean selected = checkBox.isSelected();
                column.setVisible(selected);
                columnsVisibility.put(columnName, selected);
            });

            content.getChildren().add(checkBox);
        }

        // Botones para aceptar y cancelar
        ButtonType okButtonType = new ButtonType("Aceptar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);

        // Establecer el contenido del diálogo
        dialog.getDialogPane().setContent(content);

        // Mostrar el diálogo
        dialog.showAndWait();
    }

    /**
     * Obtiene el mapa de visibilidad de columnas de torrents
     * @return El mapa de visibilidad de columnas de torrents
     */
    public Map<String, Boolean> getTorrentColumnsVisibility() {
        return torrentColumnsVisibility;
    }

    /**
     * Obtiene el mapa de visibilidad de columnas de descargas directas
     * @return El mapa de visibilidad de columnas de descargas directas
     */
    public Map<String, Boolean> getDirectColumnsVisibility() {
        return directColumnsVisibility;
    }

    /**
     * Establece la visibilidad de las columnas de torrents desde un JSONObject
     * @param jsonObject El JSONObject con la configuración de visibilidad
     */
    public void setTorrentColumnsVisibility(JSONObject jsonObject) {
        if (jsonObject == null || torrentsTable == null) return;

        for (TableColumn<TorrentState, ?> column : torrentsTable.getColumns()) {
            String columnName = column.getText();
            if (jsonObject.containsKey(columnName)) {
                boolean isVisible = (Boolean) jsonObject.get(columnName);
                column.setVisible(isVisible);
                torrentColumnsVisibility.put(columnName, isVisible);
            }
        }
    }

    /**
     * Establece la visibilidad de las columnas de descargas directas desde un JSONObject
     * @param jsonObject El JSONObject con la configuración de visibilidad
     */
    public void setDirectColumnsVisibility(JSONObject jsonObject) {
        if (jsonObject == null || directTable == null) return;

        for (TableColumn<DirectDownload, ?> column : directTable.getColumns()) {
            String columnName = column.getText();
            if (jsonObject.containsKey(columnName)) {
                boolean isVisible = (Boolean) jsonObject.get(columnName);
                column.setVisible(isVisible);
                directColumnsVisibility.put(columnName, isVisible);
            }
        }
    }

    //==========================================================================
    // CONFIGURACIÓN DE TABLAS
    //==========================================================================

    /**
     * Configura la tabla de torrents
     */
    private void setupTorrentsTable() {
        torrentsTable = new TableView<>();
        torrentsTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        // Configurar para que las columnas se ajusten automáticamente al contenido
        torrentsTable.setTableMenuButtonVisible(true);

        // Columna de nombre
        TableColumn<TorrentState, String> torrentNameCol = new TableColumn<>("Nombre");
        torrentNameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        torrentNameCol.setPrefWidth(250);

        // Columna de nombre de archivo
        TableColumn<TorrentState, String> fileNameCol = new TableColumn<>("Archivo");
        fileNameCol.setCellValueFactory(cellData -> {
            TorrentState state = cellData.getValue();
            String fileName = state.getFileName();
            return new SimpleStringProperty(fileName != null ? fileName : "Desconocido");
        });
        fileNameCol.setPrefWidth(200);

        // Columna de progreso
        TableColumn<TorrentState, Number> torrentProgressCol = new TableColumn<>("Progreso (%)");
        torrentProgressCol.setCellValueFactory(cellData -> cellData.getValue().progressProperty());
        torrentProgressCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            private final ProgressBar progressBar = new ProgressBar();
            private final Label percentLabel = new Label();
            private final HBox hbox = new HBox(5, progressBar, percentLabel);

            {
                progressBar.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(progressBar, Priority.ALWAYS);
                DecimalFormat df = new DecimalFormat("#.##");
                hbox.setPadding(new Insets(2));
                hbox.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    double progress = item.doubleValue() / 100.0;
                    progressBar.setProgress(progress);
                    percentLabel.setText(String.format("%.1f%%", item.doubleValue()));

                    // Cambiar el color de la barra de progreso según el estado
                    TorrentState state = getTableView().getItems().get(getIndex());
                    String status = state.getStatus();

                    if ("Completado".equals(status)) {
                        progressBar.setStyle("-fx-accent: #2ecc71;"); // Verde para completado
                    } else if ("Pausado".equals(status)) {
                        progressBar.setStyle("-fx-accent: #f39c12;"); // Naranja para pausado
                    } else if ("Error".equals(status)) {
                        progressBar.setStyle("-fx-accent: #e74c3c;"); // Rojo para error
                    } else {
                        progressBar.setStyle("-fx-accent: #3498db;"); // Azul para descargando
                    }

                    setGraphic(hbox);
                }
            }
        });
        torrentProgressCol.setPrefWidth(150);

        // Columna de estado
        TableColumn<TorrentState, String> torrentStatusCol = new TableColumn<>("Estado");
        torrentStatusCol.setCellValueFactory(cellData -> cellData.getValue().statusProperty());
        torrentStatusCol.setCellFactory(column -> new TableCell<TorrentState, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);

                    // Aplicar estilo según el estado
                    switch (item) {
                        case "Completado":
                            setStyle("-fx-text-fill: #2ecc71; -fx-font-weight: bold;");
                            break;
                        case "Descargando":
                            setStyle("-fx-text-fill: #3498db; -fx-font-weight: bold;");
                            break;
                        case "Pausado":
                            setStyle("-fx-text-fill: #f39c12; -fx-font-weight: bold;");
                            break;
                        case "Error":
                            setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                            break;
                        case "En espera":
                            setStyle("-fx-text-fill: #95a5a6; -fx-font-weight: bold;");
                            break;
                        default:
                            setStyle("");
                            break;
                    }
                }
            }
        });
        torrentStatusCol.setPrefWidth(120);

        // Columna de velocidad de descarga
        TableColumn<TorrentState, Number> downloadSpeedCol = new TableColumn<>("Vel. Bajada");
        downloadSpeedCol.setCellValueFactory(cellData -> cellData.getValue().downloadSpeedProperty());
        downloadSpeedCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("%.2f KB/s", item.doubleValue()));
                }
            }
        });
        downloadSpeedCol.setPrefWidth(100);

        // Columna de velocidad de subida
        TableColumn<TorrentState, Number> uploadSpeedCol = new TableColumn<>("Vel. Subida");
        uploadSpeedCol.setCellValueFactory(cellData -> cellData.getValue().uploadSpeedProperty());
        uploadSpeedCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("%.2f KB/s", item.doubleValue()));
                }
            }
        });
        uploadSpeedCol.setPrefWidth(100);

        // Columna de tamaño de archivo
        TableColumn<TorrentState, Number> fileSizeCol = new TableColumn<>("Tamaño");
        fileSizeCol.setCellValueFactory(cellData -> cellData.getValue().fileSizeProperty());
        fileSizeCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatSize(item.longValue()));
                }
            }
        });
        fileSizeCol.setPrefWidth(100);

        // Columna de pares
        TableColumn<TorrentState, Number> peersCol = new TableColumn<>("Pares");
        peersCol.setCellValueFactory(cellData -> cellData.getValue().peersProperty());
        peersCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.toString());
                }
            }
        });
        peersCol.setPrefWidth(70);

        // Columna de seeds
        TableColumn<TorrentState, Number> seedsCol = new TableColumn<>("Seeds");
        seedsCol.setCellValueFactory(cellData -> cellData.getValue().seedsProperty());
        seedsCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.toString());
                }
            }
        });
        seedsCol.setPrefWidth(70);

        // Columna de tiempo restante
        TableColumn<TorrentState, Number> remainingTimeCol = new TableColumn<>("Tiempo restante");
        remainingTimeCol.setCellValueFactory(cellData -> cellData.getValue().remainingTimeProperty());
        remainingTimeCol.setCellFactory(column -> new TableCell<TorrentState, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    long seconds = item.longValue();
                    if (seconds < 0) {
                        setText("Calculando...");
                    } else {
                        setText(formatTime(seconds));
                    }
                }
            }
        });
        remainingTimeCol.setPrefWidth(120);

        // Columna de destino
        TableColumn<TorrentState, String> torrentDestinationCol = new TableColumn<>("Destino");
        torrentDestinationCol.setCellValueFactory(cellData -> cellData.getValue().destinationPathProperty());
        torrentDestinationCol.setPrefWidth(200);

        // Columna de acciones
        TableColumn<TorrentState, Void> torrentActionsCol = new TableColumn<>("Acciones");
        torrentActionsCol.setCellFactory(createTorrentActionsCellFactory());
        torrentActionsCol.setPrefWidth(300); // Dar más espacio para los botones

        // Añadir todas las columnas a la tabla
        torrentsTable.getColumns().addAll(
                torrentNameCol,
                fileNameCol,
                torrentProgressCol,
                downloadSpeedCol,
                uploadSpeedCol,
                fileSizeCol,
                peersCol,
                seedsCol,
                remainingTimeCol,
                torrentStatusCol,
                torrentDestinationCol,
                torrentActionsCol
        );

        torrentsTable.setItems(filteredTorrentDownloads);

        TableUtils.enableCopyPasteSupport(torrentsTable, text -> {
            if (searchField != null) {
                searchField.setText(text);
                searchField.positionCaret(text.length());
            }
        });

        torrentsTable.setRowFactory(table -> {
            TableRow<TorrentState> row = new TableRow<>();
            TableUtils.installRowSelectionOnRightClick(torrentsTable, row);

            ContextMenu contextMenu = TableUtils.createCopyPasteContextMenu(torrentsTable, text -> {
                if (searchField != null) {
                    searchField.setText(text);
                    searchField.positionCaret(text.length());
                }
            });

            MenuItem pauseItem = new MenuItem("Pausar");
            pauseItem.setOnAction(event -> {
                TorrentState torrent = row.getItem();
                if (torrent != null) {
                    pauseTorrent(torrent);
                }
            });

            MenuItem resumeItem = new MenuItem("Reanudar");
            resumeItem.setOnAction(event -> {
                TorrentState torrent = row.getItem();
                if (torrent != null) {
                    resumeTorrent(torrent);
                }
            });

            MenuItem removeItem = new MenuItem("Eliminar");
            removeItem.setOnAction(event -> {
                TorrentState torrent = row.getItem();
                if (torrent != null) {
                    removeTorrent(torrent);
                }
            });

            contextMenu.getItems().addAll(new SeparatorMenuItem(), pauseItem, resumeItem, removeItem);

            row.itemProperty().addListener((obs, oldItem, newItem) ->
                    updateTorrentContextMenuItems(pauseItem, resumeItem, removeItem, newItem));

            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu));

            return row;
        });
    }

    /**
     * Configura la tabla de descargas directas
     */
    private void setupDirectTable() {
        directTable = new TableView<>();
        directTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Configurar para que las columnas se ajusten automáticamente al contenido
        directTable.setTableMenuButtonVisible(true);

        // Columna de nombre
        TableColumn<DirectDownload, String> directNameCol = new TableColumn<>("Nombre");
        directNameCol.setCellValueFactory(cellData -> cellData.getValue().nameProperty());
        directNameCol.setPrefWidth(250);

        // Columna de progreso
        TableColumn<DirectDownload, Number> directProgressCol = new TableColumn<>("Progreso (%)");
        directProgressCol.setCellValueFactory(cellData -> cellData.getValue().progressProperty());
        directProgressCol.setCellFactory(column -> new TableCell<DirectDownload, Number>() {
            private final ProgressBar progressBar = new ProgressBar();
            private final Label percentLabel = new Label();
            private final HBox hbox = new HBox(5, progressBar, percentLabel);

            {
                progressBar.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(progressBar, Priority.ALWAYS);
                hbox.setPadding(new Insets(2));
                hbox.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    double progress = item.doubleValue() / 100.0;
                    progressBar.setProgress(progress);
                    percentLabel.setText(String.format("%.1f%%", item.doubleValue()));

                    // Cambiar el color de la barra de progreso según el estado
                    DirectDownload download = getTableView().getItems().get(getIndex());
                    String status = download.getStatus();

                    if ("Completed".equals(status)) {
                        progressBar.setStyle("-fx-accent: #2ecc71;"); // Verde para completado
                    } else if ("Paused".equals(status)) {
                        progressBar.setStyle("-fx-accent: #f39c12;"); // Naranja para pausado
                    } else if ("Error".equals(status)) {
                        progressBar.setStyle("-fx-accent: #e74c3c;"); // Rojo para error
                    } else {
                        progressBar.setStyle("-fx-accent: #3498db;"); // Azul para descargando
                    }

                    setGraphic(hbox);
                }
            }
        });
        directProgressCol.setPrefWidth(150);

        // Columna de estado
        TableColumn<DirectDownload, String> directStatusCol = new TableColumn<>("Estado");
        directStatusCol.setCellValueFactory(cellData -> cellData.getValue().statusProperty());
        directStatusCol.setCellFactory(column -> new TableCell<DirectDownload, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(item);

                    // Aplicar estilo según el estado
                    switch (item) {
                        case "Completed":
                            setStyle("-fx-text-fill: #2ecc71; -fx-font-weight: bold;");
                            break;
                        case "Downloading":
                            setStyle("-fx-text-fill: #3498db; -fx-font-weight: bold;");
                            break;
                        case "Paused":
                            setStyle("-fx-text-fill: #f39c12; -fx-font-weight: bold;");
                            break;
                        case "Error":
                            setStyle("-fx-text-fill: #e74c3c; -fx-font-weight: bold;");
                            break;
                        case "Waiting":
                            setStyle("-fx-text-fill: #95a5a6; -fx-font-weight: bold;");
                            break;
                        case "Processing":
                            setStyle("-fx-text-fill: #9b59b6; -fx-font-weight: bold;");
                            break;
                        default:
                            setStyle("");
                            break;
                    }
                }
            }
        });
        directStatusCol.setPrefWidth(120);

        // Columna de servidor
        TableColumn<DirectDownload, String> directServerCol = new TableColumn<>("Servidor");
        directServerCol.setCellValueFactory(cellData -> cellData.getValue().serverProperty());
        directServerCol.setPrefWidth(120);

        // Columna de tamaño de archivo
        TableColumn<DirectDownload, Number> fileSizeCol2 = new TableColumn<>("Tamaño");
        fileSizeCol2.setCellValueFactory(cellData -> cellData.getValue().fileSizeProperty());
        fileSizeCol2.setCellFactory(column -> new TableCell<DirectDownload, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatSize(item.longValue()));
                }
            }
        });
        fileSizeCol2.setPrefWidth(100);

        // Columna de velocidad de descarga
        TableColumn<DirectDownload, Number> downloadSpeedCol2 = new TableColumn<>("Velocidad");
        downloadSpeedCol2.setCellValueFactory(cellData -> cellData.getValue().downloadSpeedProperty());
        downloadSpeedCol2.setCellFactory(column -> new TableCell<DirectDownload, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(String.format("%.2f MB/s", item.doubleValue()));
                }
            }
        });
        downloadSpeedCol2.setPrefWidth(100);

        // Columna de tiempo restante
        TableColumn<DirectDownload, Number> remainingTimeCol2 = new TableColumn<>("Tiempo restante");
        remainingTimeCol2.setCellValueFactory(cellData -> cellData.getValue().remainingTimeProperty());
        remainingTimeCol2.setCellFactory(column -> new TableCell<DirectDownload, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    long seconds = item.longValue();
                    setText(formatTime(seconds));
                }
            }
        });
        remainingTimeCol2.setPrefWidth(120);

        // Columna de bytes descargados
        TableColumn<DirectDownload, Number> downloadedBytesCol = new TableColumn<>("Descargado");
        downloadedBytesCol.setCellValueFactory(cellData -> cellData.getValue().downloadedBytesProperty());
        downloadedBytesCol.setCellFactory(column -> new TableCell<DirectDownload, Number>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(formatSize(item.longValue()));
                }
            }
        });
        downloadedBytesCol.setPrefWidth(100);

        // Columna de acciones
        TableColumn<DirectDownload, Void> directActionsCol = new TableColumn<>("Acciones");
        directActionsCol.setCellFactory(createDirectActionsCellFactory());
        directActionsCol.setPrefWidth(300);

        // Añadir todas las columnas a la tabla
        directTable.getColumns().addAll(
                directNameCol,
                directProgressCol,
                directStatusCol,
                directServerCol,
                fileSizeCol2,
                downloadSpeedCol2,
                remainingTimeCol2,
                downloadedBytesCol,
                directActionsCol
        );

        directTable.setItems(filteredDirectDownloads);

        TableUtils.enableCopyPasteSupport(directTable, null);

        directTable.setRowFactory(table -> {
            TableRow<DirectDownload> row = new TableRow<>();
            TableUtils.installRowSelectionOnRightClick(directTable, row);

            ContextMenu contextMenu = TableUtils.createCopyPasteContextMenu(directTable, null);

            MenuItem pauseItem = new MenuItem("Pausar");
            pauseItem.setOnAction(event -> {
                DirectDownload download = row.getItem();
                if (download != null) {
                    pauseDirectDownload(download);
                }
            });

            MenuItem resumeItem = new MenuItem("Reanudar");
            resumeItem.setOnAction(event -> {
                DirectDownload download = row.getItem();
                if (download != null) {
                    resumeDirectDownload(download);
                }
            });

            MenuItem removeItem = new MenuItem("Eliminar");
            removeItem.setOnAction(event -> {
                DirectDownload download = row.getItem();
                if (download != null) {
                    removeDirectDownload(download);
                }
            });

            contextMenu.getItems().addAll(new SeparatorMenuItem(), pauseItem, resumeItem, removeItem);

            row.itemProperty().addListener((obs, oldItem, newItem) ->
                    updateDirectContextMenuItems(pauseItem, resumeItem, removeItem, newItem));

            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu));

            return row;
        });

        // Ajustar automáticamente el ancho de las columnas al contenido
        directTable.widthProperty().addListener((source, oldWidth, newWidth) -> {
            double totalWidth = newWidth.doubleValue();
            double totalPrefWidth = 0;

            for (TableColumn<DirectDownload, ?> column : directTable.getColumns()) {
                totalPrefWidth += column.getPrefWidth();
            }

            for (TableColumn<DirectDownload, ?> column : directTable.getColumns()) {
                column.setPrefWidth(column.getPrefWidth() / totalPrefWidth * totalWidth);
            }
        });
    }

    private void updateTorrentContextMenuItems(MenuItem pauseItem, MenuItem resumeItem, MenuItem removeItem, TorrentState torrent) {
        if (torrent == null) {
            pauseItem.setDisable(true);
            resumeItem.setDisable(true);
            removeItem.setDisable(true);
            return;
        }

        String status = torrent.getStatus();
        boolean isPaused = "Pausado".equalsIgnoreCase(status);
        boolean isCompleted = "Completado".equalsIgnoreCase(status);

        pauseItem.setDisable(isPaused || isCompleted);
        resumeItem.setDisable(!isPaused);
        removeItem.setDisable(false);
    }

    private void updateDirectContextMenuItems(MenuItem pauseItem, MenuItem resumeItem, MenuItem removeItem, DirectDownload download) {
        if (download == null) {
            pauseItem.setDisable(true);
            resumeItem.setDisable(true);
            removeItem.setDisable(true);
            return;
        }

        String status = download.getStatus();
        boolean isPaused = "Paused".equalsIgnoreCase(status);
        boolean isCompleted = "Completed".equalsIgnoreCase(status);

        pauseItem.setDisable(isPaused || isCompleted);
        resumeItem.setDisable(!isPaused);
        removeItem.setDisable(false);
    }

    /**
     * Crea el panel de búsqueda de torrents
     *
     * @return VBox con los componentes de búsqueda
     */
    private VBox createTorrentSearchPanel() {
        VBox searchPanel = new VBox(10);
        searchPanel.setPadding(new Insets(0, 0, 10, 0));
        searchPanel.setStyle("-fx-background-color: #f8f9fa; -fx-border-color: #dee2e6; -fx-border-radius: 5px; -fx-padding: 10px;");

        Label titleLabel = new Label("Añadir Torrent");
        titleLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        HBox searchBox = new HBox(10);
        searchBox.setAlignment(Pos.CENTER_LEFT);

        searchField = new TextField();
        searchField.setPromptText("Buscar torrents o pegar enlace magnet/URL");
        searchField.setPrefWidth(400);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        searchEngineComboBox = new ComboBox<>();
        searchEngineComboBox.getItems().addAll("The Pirate Bay", "1337x", "RARBG", "YTS", "EZTV");
        searchEngineComboBox.setValue("The Pirate Bay");

        searchButton = new Button("Buscar");
        searchButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        searchButton.setOnAction(e -> searchTorrents());
        searchField.setOnAction(e -> searchButton.fire());

        Button addTorrentButton = new Button("Añadir archivo .torrent");
        addTorrentButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
        addTorrentButton.setOnAction(e -> addTorrentFile());

        Button addMagnetButton = new Button("Añadir enlace magnet");
        addMagnetButton.setStyle("-fx-background-color: #9b59b6; -fx-text-fill: white;");
        addMagnetButton.setOnAction(e -> addMagnetLink());

        searchBox.getChildren().addAll(searchField, searchEngineComboBox, searchButton, addTorrentButton, addMagnetButton);

        searchPanel.getChildren().addAll(titleLabel, searchBox);

        return searchPanel;
    }

    /**
     * Busca torrents en el motor de búsqueda seleccionado
     */
    private void searchTorrents() {
        String query = searchField.getText().trim();
        String engine = searchEngineComboBox.getValue();

        if (query.isEmpty()) {
            showErrorAlert("Búsqueda vacía", "Por favor, introduce un término de búsqueda.");
            return;
        }

        // Si es un enlace magnet, procesarlo directamente
        if (query.startsWith("magnet:")) {
            addMagnetLink(query);
            return;
        }

        // Si es una URL a un archivo .torrent, procesarlo directamente
        if (query.startsWith("http") && (query.endsWith(".torrent") || query.contains(".torrent?"))) {
            addTorrentUrl(query);
            return;
        }

        // Mostrar ventana de resultados de búsqueda
        showTorrentSearchResults(query, engine);
    }

    /**
     * Muestra los resultados de búsqueda de torrents
     *
     * @param query Término de búsqueda
     * @param engine Motor de búsqueda seleccionado
     */
    private void showTorrentSearchResults(String query, String engine) {
        // Esta es una implementación simulada. En una implementación real,
        // se conectaría a una API de búsqueda de torrents.

        Stage searchResultsStage = new Stage();
        searchResultsStage.initModality(Modality.APPLICATION_MODAL);
        searchResultsStage.setTitle("Resultados de búsqueda: " + query);
        searchResultsStage.setMinWidth(800);
        searchResultsStage.setMinHeight(600);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        Label infoLabel = new Label("Búsqueda de \"" + query + "\" en " + engine);
        infoLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        Label notImplementedLabel = new Label("La búsqueda de torrents no está implementada en esta versión.\\n" +
                "Esta es una interfaz de demostración.");
        notImplementedLabel.setStyle("-fx-text-fill: #e74c3c;");

        // Tabla simulada de resultados
        TableView<TorrentSearchResult> resultsTable = new TableView<>();
        resultsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<TorrentSearchResult, String> nameCol = new TableColumn<>("Nombre");
        nameCol.setCellValueFactory(cellData -> cellData.getValue().nameProperty());
        nameCol.setPrefWidth(400);

        TableColumn<TorrentSearchResult, String> sizeCol = new TableColumn<>("Tamaño");
        sizeCol.setCellValueFactory(cellData -> cellData.getValue().sizeProperty());
        sizeCol.setPrefWidth(100);

        TableColumn<TorrentSearchResult, String> seedsCol = new TableColumn<>("Seeds");
        seedsCol.setCellValueFactory(cellData -> cellData.getValue().seedsProperty());
        seedsCol.setPrefWidth(80);

        TableColumn<TorrentSearchResult, String> peersCol = new TableColumn<>("Peers");
        peersCol.setCellValueFactory(cellData -> cellData.getValue().peersProperty());
        peersCol.setPrefWidth(80);

        TableColumn<TorrentSearchResult, Void> actionsCol = new TableColumn<>("Acciones");
        actionsCol.setCellFactory(param -> new TableCell<TorrentSearchResult, Void>() {
            private final Button downloadButton = new Button("Descargar");

            {
                downloadButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                downloadButton.setOnAction(event -> {
                    TorrentSearchResult result = getTableView().getItems().get(getIndex());

                    // Simular descarga
                    searchResultsStage.close();
                    showDownloadDialog(result);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);

                if (empty) {
                    setGraphic(null);
                } else {
                    setGraphic(downloadButton);
                }
            }
        });
        actionsCol.setPrefWidth(100);

        resultsTable.getColumns().addAll(nameCol, sizeCol, seedsCol, peersCol, actionsCol);

        TableUtils.enableCopyPasteSupport(resultsTable, text -> {
            if (searchField != null) {
                searchField.setText(text);
                searchField.positionCaret(text.length());
            }
        });

        resultsTable.setRowFactory(table -> {
            TableRow<TorrentSearchResult> row = new TableRow<>();
            TableUtils.installRowSelectionOnRightClick(resultsTable, row);

            ContextMenu contextMenu = TableUtils.createCopyPasteContextMenu(resultsTable, text -> {
                if (searchField != null) {
                    searchField.setText(text);
                    searchField.positionCaret(text.length());
                }
            });

            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu));

            return row;
        });

        // Datos simulados
        ObservableList<TorrentSearchResult> results = FXCollections.observableArrayList(
                new TorrentSearchResult(query + " - Resultado 1", "1.2 GB", "120", "15"),
                new TorrentSearchResult(query + " - Resultado 2", "2.5 GB", "85", "10"),
                new TorrentSearchResult(query + " - Resultado 3", "800 MB", "45", "5")
        );

        resultsTable.setItems(results);

        Button closeButton = new Button("Cerrar");
        closeButton.setOnAction(e -> searchResultsStage.close());

        layout.getChildren().addAll(infoLabel, notImplementedLabel, resultsTable, closeButton);
        VBox.setVgrow(resultsTable, Priority.ALWAYS);

        Scene scene = new Scene(layout);
        searchResultsStage.setScene(scene);
        searchResultsStage.show();
    }

    /**
     * Muestra el diálogo de descarga para un resultado de búsqueda
     *
     * @param result El resultado de búsqueda seleccionado
     */
    private void showDownloadDialog(TorrentSearchResult result) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Seleccionar carpeta de destino");
        File selectedDirectory = directoryChooser.showDialog(null);

        if (selectedDirectory != null) {
            // Crear un nuevo TorrentState con los parámetros requeridos
            TorrentState torrentState = new TorrentState(
                    "magnet:?xt=urn:btih:SIMULADO&dn=" + result.getName(),
                    selectedDirectory.getAbsolutePath(),
                    0, // bytesDownloaded
                    0, // piecesComplete
                    0  // piecesTotal
            );

            // Añadir a la lista de descargas
            addTorrentDownload(torrentState);

            // Mostrar notificación
            showInfoAlert("Descarga añadida",
                    "La descarga de \"" + result.getName() + "\" ha sido añadida a la cola.");
        }
    }

    /**
     * Añade un archivo .torrent
     */
    private void addTorrentFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Seleccionar archivo .torrent");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Archivos Torrent", "*.torrent"));
        File selectedFile = fileChooser.showOpenDialog(null);

        if (selectedFile != null) {
            DirectoryChooser directoryChooser = new DirectoryChooser();
            directoryChooser.setTitle("Seleccionar carpeta de destino");
            File selectedDirectory = directoryChooser.showDialog(null);

            if (selectedDirectory != null && torrentDownloader != null) {
                // Crear un nuevo TorrentState con los parámetros requeridos
                TorrentState torrentState = new TorrentState(
                        selectedFile.getAbsolutePath(),
                        selectedDirectory.getAbsolutePath(),
                        0, // bytesDownloaded
                        0, // piecesComplete
                        0  // piecesTotal
                );

                torrentState.setName(selectedFile.getName().replace(".torrent", ""));

                torrentDownloader.downloadTorrent(selectedFile.getAbsolutePath(), torrentState);
                addTorrentDownload(torrentState);
            }
        }
    }

    /**
     * Añade un enlace magnet
     */
    private void addMagnetLink() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Añadir enlace magnet");
        dialog.setHeaderText("Introduce el enlace magnet");
        dialog.setContentText("Enlace magnet:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(this::addMagnetLink);
    }

    /**
     * Añade un enlace magnet específico
     *
     * @param magnetLink El enlace magnet
     */
    private void addMagnetLink(String magnetLink) {
        if (!magnetLink.startsWith("magnet:")) {
            showErrorAlert("Enlace inválido", "El enlace introducido no es un enlace magnet válido.");
            return;
        }

        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Seleccionar carpeta de destino");
        File selectedDirectory = directoryChooser.showDialog(null);

        if (selectedDirectory != null && torrentDownloader != null) {
            // Crear un nuevo TorrentState con los parámetros requeridos
            TorrentState torrentState = new TorrentState(
                    magnetLink,
                    selectedDirectory.getAbsolutePath(),
                    0, // bytesDownloaded
                    0, // piecesComplete
                    0  // piecesTotal
            );

            torrentState.setName("Magnet Link");  // Se actualizará cuando se obtenga la información

            torrentDownloader.addTorrent(torrentState);
            addTorrentDownload(torrentState);
        }
    }

    /**
     * Añade una URL a un archivo .torrent
     *
     * @param url La URL del archivo .torrent
     */
    private void addTorrentUrl(String url) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Seleccionar carpeta de destino");
        File selectedDirectory = directoryChooser.showDialog(null);

        if (selectedDirectory != null && torrentDownloader != null) {
            // Crear un nuevo TorrentState con los parámetros requeridos
            TorrentState torrentState = new TorrentState(
                    url,
                    selectedDirectory.getAbsolutePath(),
                    0, // bytesDownloaded
                    0, // piecesComplete
                    0  // piecesTotal
            );

            torrentState.setName("URL Torrent");  // Se actualizará cuando se descargue

            torrentDownloader.addTorrent(torrentState);
            addTorrentDownload(torrentState);
        }
    }

    //==========================================================================
    // MÉTODOS DE UTILIDAD
    //==========================================================================

    /**
     * Formatea el tiempo en segundos a formato hh:mm:ss
     *
     * @param seconds Tiempo en segundos
     * @return Tiempo formateado
     */
    private String formatTime(long seconds) {
        if (seconds <= 0) return "0:00";

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%d:%02d", minutes, secs);
        }
    }

    /**
     * Inicia el timer para actualizar el contador y la cuenta atrás
     */
    private void startCountdownTimer() {
        if (countdownTimer != null) {
            countdownTimer.cancel();
        }

        countdownTimer = new Timer(true);
        countdownTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> {
                    updateDownloadCounter();
                });
            }
        }, 0, 1000); // Actualizar cada segundo
    }

    /**
     * Actualiza el contador de descargas y la cuenta atrás
     */
    private void updateDownloadCounter() {
        int count = DownloadLimitManager.getPowvideoStreamplayCount();
        int limit = DownloadLimitManager.getPowvideoStreamplayLimit();

        boolean limitReached = count >= limit;
        powvideoStreamplayLimitActive.set(limitReached);

        downloadCounterLabel.setText(String.format("Descargas PowVideo/StreamPlay: %d/%d", count, limit));

        if (limitReached) {
            String remainingTime = DownloadLimitManager.getFormattedRemainingTime();
            downloadCounterLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #e74c3c;");
            downloadTimerLabel.setText("Reinicio en: " + remainingTime);
            downloadTimerLabel.setVisible(true);
            downloadTimerLabel.setManaged(true);
        } else {
            downloadCounterLabel.setStyle("-fx-font-weight: bold;");
            downloadTimerLabel.setVisible(false);
            downloadTimerLabel.setManaged(false);
        }
    }

    /**
     * Fuerza una actualización inmediata del contador de descargas desde cualquier hilo.
     */
    public void refreshDownloadCounter() {
        if (Platform.isFxApplicationThread()) {
            updateDownloadCounter();
        } else {
            Platform.runLater(this::updateDownloadCounter);
        }
    }

    /**
     * Formatea el tamaño en bytes
     *
     * @param bytes Tamaño en bytes
     * @return Tamaño formateado
     */
    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";

        String[] units = new String[]{"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));

        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    //==========================================================================
    // MÉTODOS PARA GESTIONAR TORRENTS
    //==========================================================================

    /**
     * Establece el TorrentDownloader
     *
     * @param torrentDownloader El TorrentDownloader a usar
     */
    public void setTorrentDownloader(TorrentDownloader torrentDownloader) {
        this.torrentDownloader = torrentDownloader;

        // Registrar esta clase como listener para notificaciones
        if (torrentDownloader != null) {
            torrentDownloader.addNotificationListener(this);
        }

        resumePendingTorrentRestores();
    }

    /**
     * Crea la fábrica de celdas para las acciones de torrents
     *
     * @return La fábrica de celdas
     */
    private Callback<TableColumn<TorrentState, Void>, TableCell<TorrentState, Void>> createTorrentActionsCellFactory() {
        return new Callback<TableColumn<TorrentState, Void>, TableCell<TorrentState, Void>>() {
            @Override
            public TableCell<TorrentState, Void> call(final TableColumn<TorrentState, Void> param) {
                return new TableCell<TorrentState, Void>() {
                    private final Button pauseButton = createActionButton("⏸", "Pausar descarga", "Pausar");
                    private final Button resumeButton = createActionButton("▶", "Reanudar descarga", "Reanudar");
                    private final Button removeButton = createActionButton("✖", "Eliminar descarga", "Eliminar");
                    private final Button retryButton = createActionButton("↻", "Reintentar descarga", "Reintentar");
                    private final Button openLocationButton = createActionButton("📂", "Abrir carpeta de destino", "Abrir ubicación");
                    private final Button logButton = createActionButton("ℹ", "Ver registro", "Ver registro");
                    private final Button healthCheckButton = createActionButton("⚕", "Diagnóstico de torrent", "Diagnóstico");
                    private final MenuButton advancedMenu = new MenuButton("⚙");
                    private final MenuItem changePriorityItem = new MenuItem("Cambiar prioridad...");
                    private final CheckMenuItem sequentialItem = new CheckMenuItem("Descarga secuencial");
                    private final MenuItem limitsItem = new MenuItem("Límites de velocidad...");
                    private final MenuItem clearLimitsItem = new MenuItem("Quitar límites");
                    private final Tooltip advancedTooltip = new Tooltip();
                    private final HBox pane = new HBox(5);

                    {
                        configureActionControl(advancedMenu, null, "Opciones avanzadas");
                        advancedMenu.setTooltip(advancedTooltip);

                        advancedMenu.getItems().addAll(changePriorityItem, sequentialItem, limitsItem, clearLimitsItem);

                        pauseButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            pauseTorrent(torrentState);
                        });

                        resumeButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            resumeTorrent(torrentState);
                        });

                        removeButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            removeTorrent(torrentState);
                        });

                        retryButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            retryTorrent(torrentState);
                        });

                        openLocationButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            openFileLocation(torrentState);
                        });

                        logButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            showTorrentLogDialog(torrentState);
                        });

                        healthCheckButton.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            runTorrentHealthCheck(torrentState);
                        });

                        changePriorityItem.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            changeTorrentPriority(torrentState);
                        });

                        sequentialItem.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            boolean enableSequential = sequentialItem.isSelected();
                            toggleSequentialDownload(torrentState, enableSequential, sequentialItem);
                        });

                        limitsItem.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            showRateLimitDialog(torrentState);
                        });

                        clearLimitsItem.setOnAction(event -> {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            clearTorrentRateLimits(torrentState);
                        });

                        pane.setAlignment(Pos.CENTER);
                        pane.setPadding(new Insets(2));
                        pane.setSpacing(4);
                        pane.getChildren().addAll(pauseButton, resumeButton, removeButton, retryButton,
                                openLocationButton, logButton, healthCheckButton, advancedMenu);
                    }

                    @Override
                    public void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);

                        if (empty) {
                            setGraphic(null);
                        } else {
                            TorrentState torrentState = getTableView().getItems().get(getIndex());
                            String status = torrentState.getStatus();

                            // Configurar visibilidad de botones según el estado
                            pauseButton.setVisible(!"Pausado".equals(status) && !"Completado".equals(status) && !"Error".equals(status));
                            resumeButton.setVisible("Pausado".equals(status));
                            retryButton.setVisible("Error".equals(status));

                            // El botón de abrir ubicación siempre visible pero habilitado solo si hay un destino válido
                            boolean hasValidDestination = torrentState.getDestinationPath() != null &&
                                    !torrentState.getDestinationPath().isEmpty();
                            openLocationButton.setDisable(!hasValidDestination);

                            // El botón de eliminar siempre visible
                            removeButton.setVisible(true);

                            // Reorganizar los botones según el estado
                            pane.getChildren().clear();

                            if (pauseButton.isVisible()) {
                                pane.getChildren().add(pauseButton);
                            }

                            if (resumeButton.isVisible()) {
                                pane.getChildren().add(resumeButton);
                            }

                            if (retryButton.isVisible()) {
                                pane.getChildren().add(retryButton);
                            }

                            // Estos botones siempre se muestran
                            pane.getChildren().add(removeButton);
                            pane.getChildren().add(openLocationButton);
                            pane.getChildren().add(logButton);
                            pane.getChildren().add(healthCheckButton);
                            pane.getChildren().add(advancedMenu);

                            sequentialItem.setSelected(torrentState.isSequentialDownload());
                            clearLimitsItem.setDisable(torrentState.getDownloadLimitKiB() <= 0
                                    && torrentState.getUploadLimitKiB() <= 0);
                            advancedTooltip.setText(buildAdvancedOptionsTooltip(torrentState));

                            setGraphic(pane);
                        }
                    }
                };
            }
        };
    }

    /**
     * Crea la fábrica de celdas para las acciones de descargas directas
     *
     * @return La fábrica de celdas
     */
    private Callback<TableColumn<DirectDownload, Void>, TableCell<DirectDownload, Void>> createDirectActionsCellFactory() {
        return new Callback<TableColumn<DirectDownload, Void>, TableCell<DirectDownload, Void>>() {
            @Override
            public TableCell<DirectDownload, Void> call(final TableColumn<DirectDownload, Void> param) {
                return new TableCell<DirectDownload, Void>() {
                    private final Button pauseButton = createActionButton("⏸", "Pause download", "Pause");
                    private final Button resumeButton = createActionButton("▶", "Resume download", "Resume");
                    private final Button removeButton = createActionButton("✖", "Remove download", "Remove");
                    private final Button retryButton = createActionButton("↻", "Retry download", "Retry");
                    private final Button openLocationButton = createActionButton("📂", "Open download folder", "Open location");
                    private final HBox pane = new HBox(5);

                    {
                        pauseButton.setOnAction(event -> {
                            DirectDownload download = getTableView().getItems().get(getIndex());
                            pauseDirectDownload(download);
                        });

                        resumeButton.setOnAction(event -> {
                            DirectDownload download = getTableView().getItems().get(getIndex());
                            resumeDirectDownload(download);
                        });

                        removeButton.setOnAction(event -> {
                            DirectDownload download = getTableView().getItems().get(getIndex());
                            removeDirectDownload(download);
                        });

                        retryButton.setOnAction(event -> {
                            DirectDownload download = getTableView().getItems().get(getIndex());
                            retryDirectDownload(download);
                        });

                        openLocationButton.setOnAction(event -> {
                            DirectDownload download = getTableView().getItems().get(getIndex());
                            openDirectDownloadLocation(download);
                        });

                        pane.setAlignment(Pos.CENTER);
                        pane.setPadding(new Insets(2));
                        pane.setSpacing(4);
                        pane.getChildren().addAll(pauseButton, resumeButton, removeButton, retryButton, openLocationButton);
                    }

                    @Override
                    protected void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);

                        if (empty) {
                            setGraphic(null);
                        } else {
                            DirectDownload download = getTableView().getItems().get(getIndex());
                            String status = download.getStatus();
                            String server = download.getServer().toLowerCase();
                            boolean isPowvideoOrStreamplay = server.contains("powvideo") || server.contains("streamplay");

                            // Configurar visibilidad de botones según el estado
                            pauseButton.setVisible(!"Paused".equals(status) && !"Completed".equals(status) && !"Error".equals(status));
                            resumeButton.setVisible("Paused".equals(status));
                            retryButton.setVisible("Error".equals(status));

                            // El botón de abrir ubicación siempre visible pero habilitado solo si hay un destino válido
                            boolean hasValidDestination = download.getDestinationPath() != null &&
                                    !download.getDestinationPath().isEmpty();
                            openLocationButton.setDisable(!hasValidDestination);

                            // El botón de eliminar siempre visible
                            removeButton.setVisible(true);

                            // Deshabilitar el botón de reintentar si se ha alcanzado el límite de descargas
                            // y es un servidor PowVideo o StreamPlay
                            if (isPowvideoOrStreamplay && DownloadLimitManager.isPowvideoStreamplayLimitReached()) {
                                retryButton.setDisable(true);
                                Tooltip tooltip = retryButton.getTooltip();
                                if (tooltip == null) {
                                    tooltip = new Tooltip();
                                    retryButton.setTooltip(tooltip);
                                }
                                tooltip.setText("Límite de descargas alcanzado. Espere a que se reinicie el contador.");
                            } else {
                                retryButton.setDisable(false);
                                Tooltip tooltip = retryButton.getTooltip();
                                if (tooltip != null) {
                                    tooltip.setText("Retry download");
                                } else {
                                    retryButton.setTooltip(new Tooltip("Retry download"));
                                }
                            }

                            // Reorganizar los botones según el estado
                            pane.getChildren().clear();

                            if (pauseButton.isVisible()) {
                                pane.getChildren().add(pauseButton);
                            }

                            if (resumeButton.isVisible()) {
                                pane.getChildren().add(resumeButton);
                            }

                            if (retryButton.isVisible()) {
                                pane.getChildren().add(retryButton);
                            }

                            // Estos botones siempre se muestran
                            pane.getChildren().add(removeButton);
                            pane.getChildren().add(openLocationButton);

                            setGraphic(pane);
                        }
                    }
                };
            }
        };
    }

    private Button createActionButton(String symbol, String tooltipText, String accessibleText) {
        Button button = new Button(symbol);
        configureActionControl(button, tooltipText, accessibleText);
        return button;
    }

    private void configureActionControl(Control control, String tooltipText, String accessibleText) {
        control.getStyleClass().add("torrent-action-button");
        control.setMinWidth(32);
        control.setPrefWidth(32);
        control.setMaxWidth(32);
        control.setFocusTraversable(false);
        if (accessibleText != null && !accessibleText.isBlank()) {
            control.setAccessibleText(accessibleText);
        }
        if (tooltipText != null && !tooltipText.isBlank()) {
            control.setTooltip(new Tooltip(tooltipText));
        }
    }

    /**
     * Filtra los torrents según el estado
     *
     * @param statusFilter El estado por el que filtrar
     */
    private void filterTorrents(String statusFilter) {
        if ("Todos".equals(statusFilter)) {
            filteredTorrentDownloads.setPredicate(p -> true);
        } else {
            filteredTorrentDownloads.setPredicate(torrent -> statusFilter.equals(torrent.getStatus()));
        }
    }

    /**
     * Filtra las descargas directas según el estado
     *
     * @param statusFilter El estado por el que filtrar
     */
    private void filterDirectDownloads(String statusFilter) {
        if ("All".equals(statusFilter)) {
            filteredDirectDownloads.setPredicate(p -> true);
        } else {
            filteredDirectDownloads.setPredicate(download -> statusFilter.equals(download.getStatus()));
        }
    }

    /**
     * Limpia los torrents completados de la lista
     */
    private void clearCompletedTorrents() {
        Iterator<TorrentState> iterator = torrentDownloads.iterator();
        while (iterator.hasNext()) {
            TorrentState torrent = iterator.next();
            if (torrent.getStatus() != null && torrent.getStatus().startsWith("Completado")) {
                iterator.remove();
                persistenceManager.deleteTorrent(torrent.getInstanceId());
            }
        }
    }

    /**
     * Limpia las descargas directas completadas de la lista
     */
    private void clearCompletedDirect() {
        Iterator<DirectDownload> iterator = directDownloads.iterator();
        while (iterator.hasNext()) {
            DirectDownload download = iterator.next();
            String status = download.getStatus();
            if (status != null && status.equalsIgnoreCase("Completed")) {
                iterator.remove();
                persistenceManager.deleteDirectDownload(download.getId());
            }
        }
    }

    /**
     * Cancela todas las descargas directas que siguen en un estado activo. Esto se
     * utiliza durante el cierre de la aplicación para garantizar que ningún
     * hilo de descarga impida que el proceso finalice por completo.
     */
    public void cancelAllActiveDirectDownloads() {
        if (directDownloads == null || directDownloads.isEmpty()) {
            return;
        }

        System.out.println("Cancelando descargas directas activas antes de cerrar la aplicación...");
        for (DirectDownload download : new ArrayList<>(directDownloads)) {
            if (download == null) {
                continue;
            }

            if (!isDirectDownloadActive(download.getStatus())) {
                continue;
            }

            DirectDownloader downloader = download.getDownloader();
            if (downloader == null) {
                continue;
            }

            try {
                downloader.cancelDownload(download);
            } catch (Exception e) {
                System.err.println("No se pudo cancelar la descarga '" + download.getName() + "': " + e.getMessage());
            }
        }
    }

    /**
     * Pausa todas las descargas directas activas y garantiza que su estado
     * quede persistido antes de cerrar la aplicación. Las descargas que
     * estaban en curso se marcan como pausadas sin considerarlas como pausa
     * manual del usuario para que puedan reanudarse automáticamente al
     * reiniciar.
     */
    public void prepareDirectDownloadsForShutdown() {
        if (directDownloads == null || directDownloads.isEmpty()) {
            return;
        }

        System.out.println("Guardando estado de descargas directas antes de cerrar la aplicación...");
        for (DirectDownload download : directDownloads) {
            if (download == null) {
                continue;
            }

            if (isDirectDownloadActive(download.getStatus())) {
                DirectDownloader downloader = download.getDownloader();
                if (downloader != null) {
                    try {
                        downloader.pauseDownload(download);
                    } catch (Exception e) {
                        System.err.println("No se pudo pausar la descarga '" + download.getName() + "': " + e.getMessage());
                    }
                }
            }

            download.forcePersistSnapshot();
        }
    }

    private boolean isDirectDownloadActive(String status) {
        if (status == null) {
            return true;
        }

        String normalized = status.trim().toLowerCase(Locale.ROOT);
        return !(normalized.startsWith("completed")
                || normalized.startsWith("completado")
                || normalized.startsWith("cancelled")
                || normalized.startsWith("cancelado")
                || normalized.startsWith("paused")
                || normalized.startsWith("pausado")
                || normalized.startsWith("waiting")
                || normalized.startsWith("en espera")
                || normalized.startsWith("error")
                || normalized.startsWith("erro")
                || normalized.startsWith("failed")
                || normalized.startsWith("fallo"));
    }

    /**
     * Pausa un torrent
     *
     * @param torrentState El estado del torrent a pausar
     */
    private void pauseTorrent(TorrentState torrentState) {
        if (torrentDownloader != null) {
            torrentState.setUserPaused(true);
            torrentDownloader.pauseDownload(torrentState);
            // No establecemos el estado aquí, dejamos que el TorrentDownloader lo haga
            // y actualice el estado a través de eventos
        } else {
            System.err.println("Error: TorrentDownloader no inicializado");
            torrentState.setStatus("Pausado");
            torrentState.setUserPaused(true);
        }

        torrentsTable.refresh();
    }

    /**
     * Reanuda un torrent pausado
     *
     * @param torrentState El estado del torrent a reanudar
     */
    private void resumeTorrent(TorrentState torrentState) {
        if (torrentDownloader != null) {
            torrentState.setUserPaused(false);
            torrentDownloader.resumeDownload(torrentState);
            // No establecemos el estado aquí, dejamos que el TorrentDownloader lo haga
            // y actualice el estado a través de eventos
        } else {
            System.err.println("Error: TorrentDownloader no inicializado");
            torrentState.setStatus("Descargando");
            torrentState.setUserPaused(false);
        }

        torrentsTable.refresh();
    }

    /**
     * Reintenta la descarga de un torrent con error
     *
     * @param torrentState El estado del torrent a reintentar
     */
    private void retryTorrent(TorrentState torrentState) {
        if (torrentDownloader != null) {
            torrentState.setStatus("En espera");
            torrentState.setProgress(0);
            torrentState.setUserPaused(false);
            torrentDownloader.resumeDownload(torrentState);
        } else {
            System.err.println("Error: TorrentDownloader no inicializado");
        }

        torrentsTable.refresh();
    }

    /**
     * Elimina un torrent
     *
     * @param torrentState El estado del torrent a eliminar
     */
    private void removeTorrent(TorrentState torrentState) {
        // Crear diálogo de confirmación con checkbox para eliminar archivos
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Confirmar eliminación");
        alert.setHeaderText("¿Estás seguro de que quieres eliminar esta descarga?");
        alert.setContentText("Torrent: " + torrentState.getName());

        // Añadir checkbox para eliminar archivos
        CheckBox deleteFilesCheckBox = new CheckBox("Eliminar también los archivos descargados");
        deleteFilesCheckBox.setSelected(false);

        // Añadir checkbox al diálogo
        VBox content = new VBox(10);
        content.getChildren().add(deleteFilesCheckBox);
        alert.getDialogPane().setContent(content);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            boolean deleteFiles = deleteFilesCheckBox.isSelected();

            if (torrentDownloader != null) {
                torrentDownloader.removeDownload(torrentState, deleteFiles);
            }

            torrentDownloads.remove(torrentState);
            persistenceManager.deleteTorrent(torrentState.getInstanceId());
        }
    }

    /**
     * Abre la ubicación de un torrent en el explorador de archivos
     *
     * @param torrentState El estado del torrent
     */
    private void openFileLocation(TorrentState torrentState) {
        try {
            String path = torrentState.getDestinationPath();
            if (path != null && !path.isEmpty()) {
                File directory = new File(path);
                if (directory.exists()) {
                    Desktop desktop = Desktop.getDesktop();
                    desktop.open(directory);
                } else {
                    showErrorAlert("Ubicación no encontrada",
                            "La carpeta de destino no existe: " + path);
                }
            } else {
                showErrorAlert("Ubicación no disponible",
                        "No hay una ubicación de destino definida para este torrent.");
            }
        } catch (Exception e) {
            System.err.println("Error al abrir la ubicación del archivo: " + e.getMessage());
            e.printStackTrace();
            showErrorAlert("Error", "No se pudo abrir la ubicación del archivo: " + e.getMessage());
        }
    }

    /**
     * Abre la ubicación de una descarga directa en el explorador de archivos
     *
     * @param download La descarga directa
     */
    private void openDirectDownloadLocation(DirectDownload download) {
        try {
            String path = download.getDestinationPath();
            if (path != null && !path.isEmpty()) {
                File file = new File(path);
                File directory = file.getParentFile();
                if (directory != null && directory.exists()) {
                    Desktop desktop = Desktop.getDesktop();
                    desktop.open(directory);
                } else {
                    showErrorAlert("Ubicación no encontrada",
                            "La carpeta de destino no existe: " + (directory != null ? directory.getAbsolutePath() : "null"));
                }
            } else {
                showErrorAlert("Ubicación no disponible",
                        "No hay una ubicación de destino definida para esta descarga.");
            }
        } catch (Exception e) {
            System.err.println("Error al abrir la ubicación del archivo: " + e.getMessage());
            e.printStackTrace();
            showErrorAlert("Error", "No se pudo abrir la ubicación del archivo: " + e.getMessage());
        }
    }

    private void changeTorrentPriority(TorrentState torrentState) {
        if (torrentState == null) {
            return;
        }
        if (torrentDownloader == null) {
            showErrorAlert("Cliente torrent no disponible",
                    "Inicializa el cliente de torrents antes de modificar la prioridad.");
            return;
        }

        List<Integer> priorities = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            priorities.add(i);
        }

        ChoiceDialog<Integer> dialog = new ChoiceDialog<>(torrentState.getPriority(), priorities);
        dialog.setTitle("Cambiar prioridad");
        dialog.setHeaderText("Selecciona la prioridad de descarga (1-10)");
        dialog.setContentText("Prioridad:");

        dialog.showAndWait().ifPresent(priority -> {
            torrentState.setPriority(priority);
            torrentDownloader.reprioritize(torrentState, priority);
            torrentsTable.refresh();
        });
    }

    private void toggleSequentialDownload(TorrentState torrentState, boolean enableSequential, CheckMenuItem menuItem) {
        if (torrentState == null) {
            return;
        }
        if (torrentDownloader == null) {
            showErrorAlert("Cliente torrent no disponible",
                    "Inicializa el cliente de torrents antes de cambiar la descarga secuencial.");
            menuItem.setSelected(torrentState.isSequentialDownload());
            return;
        }

        torrentDownloader.setSequentialDownload(torrentState, enableSequential);
        torrentState.setSequentialDownload(enableSequential);
        torrentsTable.refresh();
    }

    private void showRateLimitDialog(TorrentState torrentState) {
        if (torrentState == null) {
            return;
        }
        if (torrentDownloader == null) {
            showErrorAlert("Cliente torrent no disponible",
                    "Inicializa el cliente de torrents antes de modificar los límites de velocidad.");
            return;
        }

        Dialog<int[]> dialog = new Dialog<>();
        dialog.setTitle("Límites de velocidad");
        dialog.setHeaderText("Establece los límites en KiB/s (deja en blanco para ilimitado)");

        ButtonType applyButtonType = new ButtonType("Aplicar", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(applyButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField downloadField = new TextField();
        downloadField.setPromptText("Ilimitado");
        if (torrentState.getDownloadLimitKiB() > 0) {
            downloadField.setText(String.valueOf(torrentState.getDownloadLimitKiB()));
        }

        TextField uploadField = new TextField();
        uploadField.setPromptText("Ilimitado");
        if (torrentState.getUploadLimitKiB() > 0) {
            uploadField.setText(String.valueOf(torrentState.getUploadLimitKiB()));
        }

        restrictToNumericInput(downloadField);
        restrictToNumericInput(uploadField);

        grid.add(new Label("Descarga (KiB/s):"), 0, 0);
        grid.add(downloadField, 1, 0);
        grid.add(new Label("Subida (KiB/s):"), 0, 1);
        grid.add(uploadField, 1, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == applyButtonType) {
                int downloadLimit = parseLimitValue(downloadField.getText());
                int uploadLimit = parseLimitValue(uploadField.getText());
                return new int[]{downloadLimit, uploadLimit};
            }
            return null;
        });

        dialog.showAndWait().ifPresent(result -> {
            int downloadLimit = result[0];
            int uploadLimit = result[1];
            torrentDownloader.setTorrentRateLimits(torrentState, downloadLimit, uploadLimit);
            torrentState.setDownloadLimitKiB(downloadLimit);
            torrentState.setUploadLimitKiB(uploadLimit);
            torrentsTable.refresh();
        });
    }

    private void clearTorrentRateLimits(TorrentState torrentState) {
        if (torrentState == null) {
            return;
        }
        if (torrentDownloader == null) {
            showErrorAlert("Cliente torrent no disponible",
                    "Inicializa el cliente de torrents antes de eliminar los límites de velocidad.");
            return;
        }

        torrentDownloader.setTorrentRateLimits(torrentState, -1, -1);
        torrentState.clearRateLimits();
        torrentsTable.refresh();
    }

    private int parseLimitValue(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed <= 0 ? -1 : parsed;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void restrictToNumericInput(TextField textField) {
        textField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.matches("\\d*")) {
                textField.setText(oldValue);
            }
        });
    }

    private String buildAdvancedOptionsTooltip(TorrentState torrentState) {
        return "Prioridad: " + torrentState.getPriority()
                + "\nDescarga secuencial: " + (torrentState.isSequentialDownload() ? "Activada" : "Desactivada")
                + "\nLímite descarga: " + formatRateLimit(torrentState.getDownloadLimitKiB())
                + "\nLímite subida: " + formatRateLimit(torrentState.getUploadLimitKiB());
    }

    private String formatRateLimit(int limitKiB) {
        return limitKiB > 0 ? limitKiB + " KiB/s" : "Sin límite";
    }

    private void showTorrentLogDialog(TorrentState torrentState) {
        if (torrentDownloader == null) {
            showErrorAlert("Cliente torrent no disponible",
                    "Inicializa el cliente de torrents desde los ajustes antes de revisar el registro.");
            return;
        }
        List<TorrentLogEntry> entries = torrentDownloader.getTorrentLog(torrentState);
        if (entries.isEmpty()) {
            showInfoAlert("Registro vacío",
                    "Todavía no hay eventos registrados para esta descarga.");
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Registro de " + torrentState.getName());
        dialog.setHeaderText("Eventos de la descarga");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);

        TableView<TorrentLogEntry> tableView = new TableView<>();
        tableView.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        tableView.setItems(FXCollections.observableArrayList(entries));

        TableUtils.enableCopyPasteSupport(tableView, null);

        tableView.setRowFactory(tv -> {
            TableRow<TorrentLogEntry> row = new TableRow<>();
            TableUtils.installRowSelectionOnRightClick(tableView, row);

            ContextMenu contextMenu = TableUtils.createCopyPasteContextMenu(tableView, null);
            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu));

            return row;
        });

        TableColumn<TorrentLogEntry, String> timeColumn = new TableColumn<>("Hora");
        timeColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getFormattedTimestamp()));

        TableColumn<TorrentLogEntry, String> stepColumn = new TableColumn<>("Paso");
        stepColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getStep().getDisplayName()));

        TableColumn<TorrentLogEntry, String> levelColumn = new TableColumn<>("Nivel");
        levelColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getLevel().getLocalizedName()));

        TableColumn<TorrentLogEntry, String> messageColumn = new TableColumn<>("Mensaje");
        messageColumn.setCellValueFactory(data -> new SimpleStringProperty(data.getValue().getMessage()));

        tableView.getColumns().setAll(timeColumn, stepColumn, levelColumn, messageColumn);
        tableView.setPrefSize(720, 360);

        dialog.getDialogPane().setContent(tableView);
        dialog.showAndWait();
    }

    private void runTorrentHealthCheck(TorrentState torrentState) {
        if (torrentDownloader == null) {
            showErrorAlert("Diagnóstico no disponible",
                    "Inicializa el cliente de torrents desde los ajustes antes de ejecutar el diagnóstico.");
            return;
        }
        TorrentHealthReport report = torrentDownloader.runHealthCheck(torrentState);
        if (report == null) {
            showErrorAlert("Diagnóstico no disponible",
                    "No se pudo obtener información de esta descarga. Asegúrate de que el torrent esté activo.");
            return;
        }

        StringBuilder builder = new StringBuilder();
        for (TorrentHealthReport.Check check : report.getChecks()) {
            builder.append(check.isPassed() ? "✅ " : "⚠️ ")
                    .append(check.getName()).append(": ")
                    .append(check.getDetails()).append('\n');
        }
        if (report.getInfoHash() != null) {
            builder.append('\n').append("Infohash: ").append(report.getInfoHash());
        }

        Alert.AlertType type = report.isHealthy() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING;
        Alert alert = new Alert(type);
        alert.setTitle("Diagnóstico de " + torrentState.getName());
        alert.setHeaderText(report.isHealthy()
                ? "El torrent funciona correctamente"
                : "Se detectaron incidencias en la descarga");
        alert.setContentText(builder.toString());
        alert.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        alert.showAndWait();
    }

    /**
     * Muestra un diálogo de error
     *
     * @param title Título del diálogo
     * @param message Mensaje de error
     */
    private void showErrorAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Muestra un diálogo de información
     *
     * @param title Título del diálogo
     * @param message Mensaje informativo
     */
    private void showInfoAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Pausa una descarga directa
     *
     * @param download La descarga directa a pausar
     */
    private void pauseDirectDownload(DirectDownload download) {
        // Implement logic to pause a direct download
        if (download.getDownloader() != null) {
            download.getDownloader().pauseDownload(download);
        } else {
            download.setStatus("Paused");
        }

        download.setUserPaused(true);

        directTable.refresh();
    }

    /**
     * Reanuda una descarga directa pausada
     *
     * @param download La descarga directa a reanudar
     */
    private void resumeDirectDownload(DirectDownload download) {
        // Implement logic to resume a direct download
        if (download.getDownloader() != null) {
            download.getDownloader().resumeDownload(download);
        } else {
            download.setStatus("Downloading");
        }

        download.setUserPaused(false);

        directTable.refresh();
    }

    /**
     * Reintenta una descarga directa con error
     *
     * @param download La descarga directa a reintentar
     */
    private void retryDirectDownload(DirectDownload download) {
        String server = download.getServer().toLowerCase();
        boolean isPowvideoOrStreamplay = server.contains("powvideo") || server.contains("streamplay");

        // Verificar si se ha alcanzado el límite para PowVideo/StreamPlay
        if (isPowvideoOrStreamplay && DownloadLimitManager.isPowvideoStreamplayLimitReached()) {
            // Mostrar mensaje de error
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Límite de descargas alcanzado");
            alert.setHeaderText(null);
            alert.setContentText("Se ha alcanzado el límite diario de descargas para PowVideo/StreamPlay. " +
                    "Debe esperar " + DownloadLimitManager.getFormattedRemainingTime() + " para intentarlo de nuevo.");
            alert.showAndWait();
            return;
        }

        // Reintentar la descarga
        if (download.getDownloader() != null) {
            if (isPowvideoOrStreamplay) {
                DownloadLimitManager.incrementPowvideoStreamplayCount();
                refreshDownloadCounter();
            }
            download.setProgress(0);
            download.setStatus("Waiting");
            download.getDownloader().download(download.getUrl(), download.getDestinationPath(), download);
        } else {
            download.setStatus("Error");
            System.err.println("Error: Downloader no inicializado para " + download.getName());
        }

        download.setUserPaused(false);

        directTable.refresh();
    }

    /**
     * Elimina una descarga directa
     *
     * @param download La descarga directa a eliminar
     */
    private void removeDirectDownload(DirectDownload download) {
        // Implement logic to remove a direct download
        if (download.getDownloader() != null) {
            download.getDownloader().cancelDownload(download);
        }

        directDownloads.remove(download);
        persistenceManager.deleteDirectDownload(download.getId());
    }

    /**
     * Añade un torrent a la lista de descargas
     *
     * @param torrentState El estado del torrent a añadir
     */
    public void addTorrentDownload(TorrentState torrentState) {
        System.out.println("Añadiendo descarga de torrent: " + torrentState.getTorrentSource());
        torrentDownloads.add(torrentState);
        persistenceManager.upsertTorrent(torrentState);
        torrentState.markPersistenceSynced();
    }

    /**
     * Añade una descarga directa a la lista de descargas
     *
     * @param download La descarga directa a añadir
     */
    public void addDirectDownload(DirectDownload download) {
        System.out.println("Añadiendo descarga directa: " + download.getName());
        directDownloads.add(download);
        persistenceManager.upsertDirectDownload(download);
        download.markPersistenceSynced();
    }

    /**
     * Obtiene la pestaña de descargas
     *
     * @return La pestaña de descargas
     */
    public Tab getTab() {
        return tab;
    }

    //==========================================================================
    // IMPLEMENTACIÓN DE TORRENTNOTIFICATIONLISTENER
    //==========================================================================

    @Override
    public void onTorrentComplete(TorrentState torrentState) {
        Platform.runLater(() -> {
            // Mostrar notificación de descarga completada
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Descarga Completada");
            alert.setHeaderText(null);
            alert.setContentText("La descarga de \"" + torrentState.getName() + "\" ha finalizado.");
            alert.show();

            // Actualizar la tabla
            torrentsTable.refresh();
        });
    }

    @Override
    public void onTorrentError(TorrentState torrentState, String errorMessage) {
        Platform.runLater(() -> {
            // Mostrar notificación de error
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error en la Descarga");
            alert.setHeaderText("Error en la descarga de \"" + torrentState.getName() + "\"");
            alert.setContentText(errorMessage);
            alert.show();

            // Actualizar la tabla
            torrentsTable.refresh();
        });
    }

    @Override
    public void onDiskSpaceLow(long availableSpace, long requiredSpace) {
        Platform.runLater(() -> {
            // Mostrar notificación de espacio en disco bajo
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Espacio en Disco Bajo");
            alert.setHeaderText("Espacio en disco insuficiente");
            alert.setContentText("El espacio disponible (" + formatSize(availableSpace) +
                    ") es menor que el mínimo requerido (" + formatSize(requiredSpace) +
                    ").\\nAlgunas descargas han sido pausadas.");
            alert.show();

            // Actualizar la tabla
            torrentsTable.refresh();
        });
    }

    @Override
    public void onDebugMessage(String message, Level level) {
        Logger.getLogger(DescargasUI.class.getName()).log(level, message);

    }

    @Override
    public void onTorrentStatusUpdate(TorrentState torrentState, TorrentStats stats) {
        Platform.runLater(() -> torrentsTable.refresh());
    }

    //==========================================================================
    // CLASE INTERNA PARA DESCARGAS DIRECTAS
    //==========================================================================

    /**
     * Clase para representar una descarga directa
     */
    public static class DirectDownload {
        private final String id;
        private final SimpleStringProperty name;
        private final SimpleDoubleProperty progress;
        private final SimpleStringProperty status;
        private final SimpleStringProperty server;
        private final SimpleStringProperty url;
        private final SimpleStringProperty destinationPath;
        private final SimpleLongProperty fileSize;
        private final SimpleDoubleProperty downloadSpeed;
        private final SimpleLongProperty remainingTime;
        private final SimpleLongProperty downloadedBytes;
        private final SimpleStringProperty actualFilePath;
        private final SimpleStringProperty etag;
        private final SimpleStringProperty lastModified;
        private final SimpleBooleanProperty resumeSupported;
        private DirectDownloader downloader;
        private boolean userPaused;

        private transient boolean persistenceEnabled = true;
        private transient double lastPersistedProgress = -1;
        private transient long lastPersistedBytes = -1;
        private transient String lastPersistedStatus = null;
        private transient long lastPersistedTimestamp = 0L;

        public DirectDownload(String name, double progress, String status, String server, String url) {
            this(null, name, progress, status, server, url, "", null, false);
        }

        public DirectDownload(String name, double progress, String status, String server, String url, String destinationPath, DirectDownloader downloader) {
            this(null, name, progress, status, server, url, destinationPath, downloader, false);
        }

        public DirectDownload(String id,
                              String name,
                              double progress,
                              String status,
                              String server,
                              String url,
                              String destinationPath,
                              DirectDownloader downloader,
                              boolean userPaused) {
            this.id = id != null ? id : UUID.randomUUID().toString();
            this.name = new SimpleStringProperty(name);
            this.progress = new SimpleDoubleProperty(progress);
            this.status = new SimpleStringProperty(status);
            this.server = new SimpleStringProperty(server);
            this.url = new SimpleStringProperty(url);
            this.destinationPath = new SimpleStringProperty(destinationPath != null ? destinationPath : "");
            this.fileSize = new SimpleLongProperty(0);
            this.downloadSpeed = new SimpleDoubleProperty(0);
            this.remainingTime = new SimpleLongProperty(0);
            this.downloadedBytes = new SimpleLongProperty(0);
            this.actualFilePath = new SimpleStringProperty();
            this.etag = new SimpleStringProperty();
            this.lastModified = new SimpleStringProperty();
            this.resumeSupported = new SimpleBooleanProperty(false);
            this.downloader = downloader;
            this.userPaused = userPaused;
        }

        public String getId() {
            return id;
        }

        public boolean isUserPaused() {
            return userPaused;
        }

        public void setUserPaused(boolean userPaused) {
            if (this.userPaused != userPaused) {
                this.userPaused = userPaused;
                persistSnapshot(true);
            }
        }

        public CompletableFuture<Boolean> askToRestartDownload(String reasonMessage) {
            CompletableFuture<Boolean> decision = new CompletableFuture<>();
            Platform.runLater(() -> {
                try {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle("Reanudar descarga");
                    alert.setHeaderText("No se pudo reanudar \"" + getName() + "\"");
                    StringBuilder content = new StringBuilder();
                    if (reasonMessage != null && !reasonMessage.isBlank()) {
                        content.append(reasonMessage.trim()).append("\n\n");
                    }
                    content.append("¿Deseas reiniciar la descarga desde el principio?");
                    alert.setContentText(content.toString());

                    ButtonType restartButton = new ButtonType("Reiniciar", ButtonBar.ButtonData.OK_DONE);
                    ButtonType keepButton = new ButtonType("Mantener pausada", ButtonBar.ButtonData.CANCEL_CLOSE);
                    alert.getButtonTypes().setAll(restartButton, keepButton);

                    Optional<ButtonType> result = alert.showAndWait();
                    boolean restart = result.isPresent() && result.get() == restartButton;
                    decision.complete(restart);
                } catch (Exception e) {
                    decision.completeExceptionally(e);
                }
            });
            return decision;
        }

        public void applySnapshot(DirectDownloadRecord record) {
            if (record == null) {
                return;
            }
            boolean previous = this.persistenceEnabled;
            this.persistenceEnabled = false;
            boolean normalizeCompleted = false;
            try {
                setProgress(record.getProgress());
                if (record.getStatus() != null) {
                    setStatus(record.getStatus());
                }
                setDestinationPath(record.getDestinationPath());
                setActualFilePath(record.getActualFilePath());
                setFileSize(record.getFileSize());
                setDownloadedBytes(record.getDownloadedBytes());
                double recordedSpeed = record.getDownloadSpeed();
                long recordedRemaining = record.getRemainingTime();
                setDownloadSpeed(recordedSpeed);
                setRemainingTime(recordedRemaining);
                setEtag(record.getEtag());
                setLastModified(record.getLastModified());
                setResumeSupported(record.isResumeSupported());
                if (isCompletedStatus(record.getStatus())) {
                    normalizeCompleted = (recordedSpeed != 0) || (recordedRemaining != 0);
                    setDownloadSpeed(0);
                    setRemainingTime(0);
                }
                this.userPaused = record.isManuallyPaused();
            } finally {
                this.persistenceEnabled = previous;
            }
            if (normalizeCompleted) {
                persistSnapshot(true);
            }
            markPersistenceSynced();
        }

        public void markPersistenceSynced() {
            this.lastPersistedProgress = progress.get();
            this.lastPersistedBytes = downloadedBytes.get();
            this.lastPersistedStatus = status.get();
            this.lastPersistedTimestamp = System.currentTimeMillis();
        }

        private void persistSnapshot(boolean force) {
            if (!persistenceEnabled) {
                return;
            }
            double currentProgress = progress.get();
            long currentBytes = downloadedBytes.get();
            String currentStatus = status.get();
            long now = System.currentTimeMillis();

            if (!force) {
                boolean progressChanged = Math.abs(currentProgress - lastPersistedProgress) >= 1.0;
                boolean bytesChanged = Math.abs(currentBytes - lastPersistedBytes) >= 512 * 1024L;
                boolean statusChanged = !Objects.equals(currentStatus, lastPersistedStatus);
                boolean timeElapsed = (now - lastPersistedTimestamp) >= 1000L;
                if (!progressChanged && !bytesChanged && !statusChanged && !timeElapsed) {
                    return;
                }
            }

            DownloadPersistenceManager.getInstance().upsertDirectDownload(
                    id,
                    getName(),
                    getUrl(),
                    getServer(),
                    getDestinationPath(),
                    getActualFilePath(),
                    currentStatus,
                    currentProgress,
                    getFileSize(),
                    currentBytes,
                    getDownloadSpeed(),
                    getRemainingTime(),
                    userPaused,
                    getEtag(),
                    getLastModified(),
                    isResumeSupported()
            );

            lastPersistedProgress = currentProgress;
            lastPersistedBytes = currentBytes;
            lastPersistedStatus = currentStatus;
            lastPersistedTimestamp = now;
        }

        public void forcePersistSnapshot() {
            persistSnapshot(true);
        }

        // Getters y setters existentes
        public String getName() {
            return name.get();
        }

        public void setName(String name) {
            this.name.set(name);
            persistSnapshot(false);
        }

        public int getProgress() {
            return (int) progress.get();
        }

        public void setProgress(double progress) {
            this.progress.set(progress);
            persistSnapshot(false);
        }

        public String getStatus() {
            return status.get();
        }

        public void setStatus(String status) {
            this.status.set(status);
            if (isCompletedStatus(status)) {
                setDownloadSpeed(0);
                setRemainingTime(0);
            }
            persistSnapshot(true);
        }

        public String getServer() {
            return server.get();
        }

        public void setServer(String server) {
            this.server.set(server);
            persistSnapshot(true);
        }

        public String getUrl() {
            return url.get();
        }

        public String getDestinationPath() {
            return destinationPath.get();
        }

        public void setDestinationPath(String destinationPath) {
            this.destinationPath.set(destinationPath != null ? destinationPath : "");
            persistSnapshot(true);
        }

        public DirectDownloader getDownloader() {
            return downloader;
        }

        public void setDownloader(DirectDownloader downloader) {
            this.downloader = downloader;
        }

        // Nuevos getters y setters para las propiedades adicionales
        public long getFileSize() {
            return fileSize.get();
        }

        public void setFileSize(long fileSize) {
            this.fileSize.set(fileSize);
            persistSnapshot(false);
        }

        public SimpleLongProperty fileSizeProperty() {
            return fileSize;
        }

        public double getDownloadSpeed() {
            return downloadSpeed.get();
        }

        public void setDownloadSpeed(double downloadSpeed) {
            this.downloadSpeed.set(downloadSpeed);
            persistSnapshot(false);
        }

        public SimpleDoubleProperty downloadSpeedProperty() {
            return downloadSpeed;
        }

        public long getRemainingTime() {
            return remainingTime.get();
        }

        public void setRemainingTime(long remainingTime) {
            this.remainingTime.set(remainingTime);
            persistSnapshot(false);
        }

        public SimpleLongProperty remainingTimeProperty() {
            return remainingTime;
        }

        public long getDownloadedBytes() {
            return downloadedBytes.get();
        }

        public void setDownloadedBytes(long downloadedBytes) {
            this.downloadedBytes.set(downloadedBytes);
            persistSnapshot(false);
        }

        public SimpleLongProperty downloadedBytesProperty() {
            return downloadedBytes;
        }

        public String getActualFilePath() {
            return actualFilePath.get();
        }

        public void setActualFilePath(String actualFilePath) {
            String normalized = (actualFilePath != null && !actualFilePath.isBlank()) ? actualFilePath : null;
            if (Objects.equals(this.actualFilePath.get(), normalized)) {
                return;
            }
            this.actualFilePath.set(normalized);
            persistSnapshot(true);
        }

        public SimpleStringProperty actualFilePathProperty() {
            return actualFilePath;
        }

        public String getEtag() {
            return etag.get();
        }

        public void setEtag(String etag) {
            if (Objects.equals(this.etag.get(), etag)) {
                return;
            }
            this.etag.set(etag);
            persistSnapshot(true);
        }

        public SimpleStringProperty etagProperty() {
            return etag;
        }

        public String getLastModified() {
            return lastModified.get();
        }

        public void setLastModified(String lastModified) {
            if (Objects.equals(this.lastModified.get(), lastModified)) {
                return;
            }
            this.lastModified.set(lastModified);
            persistSnapshot(true);
        }

        public SimpleStringProperty lastModifiedProperty() {
            return lastModified;
        }

        public boolean isResumeSupported() {
            return resumeSupported.get();
        }

        public void setResumeSupported(boolean resumeSupported) {
            if (this.resumeSupported.get() == resumeSupported) {
                return;
            }
            this.resumeSupported.set(resumeSupported);
            persistSnapshot(true);
        }

        public SimpleBooleanProperty resumeSupportedProperty() {
            return resumeSupported;
        }

        public Path resolveTargetFilePath() {
            String explicit = getActualFilePath();
            if (explicit != null && !explicit.isBlank()) {
                try {
                    return Paths.get(explicit);
                } catch (Exception ignored) {
                    // Ignorar rutas malformadas y reintentar con la ruta derivada
                }
            }
            String destination = getDestinationPath();
            if (destination == null || destination.isBlank()) {
                return null;
            }
            String fileName = getResolvedFileName();
            if (fileName == null || fileName.isBlank()) {
                return null;
            }
            return Paths.get(destination).resolve(fileName);
        }

        public String getResolvedFileName() {
            return sanitizeFileName(getName());
        }

        // Propiedades para binding
        public SimpleStringProperty nameProperty() {
            return name;
        }

        public SimpleDoubleProperty progressProperty() {
            return progress;
        }

        public SimpleStringProperty statusProperty() {
            return status;
        }

        public SimpleStringProperty serverProperty() {
            return server;
        }

        public SimpleStringProperty urlProperty() {
            return url;
        }

        private static boolean isCompletedStatus(String status) {
            if (status == null) {
                return false;
            }
            return "Completed".equalsIgnoreCase(status) || "Completado".equalsIgnoreCase(status);
        }

        public SimpleStringProperty destinationPathProperty() {
            return destinationPath;
        }

        private static String sanitizeFileName(String name) {
            if (name == null || name.isBlank()) {
                return "download.mp4";
            }
            String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            if (cleaned.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")) {
                cleaned = "_" + cleaned;
            }
            String normalized = new String(cleaned.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            return normalized.isEmpty() ? "download.mp4" : normalized;
        }
    }

    //==========================================================================
    // CLASE INTERNA PARA RESULTADOS DE BÚSQUEDA DE TORRENTS
    //==========================================================================

    /**
     * Clase para representar un resultado de búsqueda de torrents
     */
    private static class TorrentSearchResult {
        private final SimpleStringProperty name;
        private final SimpleStringProperty size;
        private final SimpleStringProperty seeds;
        private final SimpleStringProperty peers;

        public TorrentSearchResult(String name, String size, String seeds, String peers) {
            this.name = new SimpleStringProperty(name);
            this.size = new SimpleStringProperty(size);
            this.seeds = new SimpleStringProperty(seeds);
            this.peers = new SimpleStringProperty(peers);
        }

        public String getName() {
            return name.get();
        }

        public SimpleStringProperty nameProperty() {
            return name;
        }

        public String getSize() {
            return size.get();
        }

        public SimpleStringProperty sizeProperty() {
            return size;
        }

        public String getSeeds() {
            return seeds.get();
        }

        public SimpleStringProperty seedsProperty() {
            return seeds;
        }

        public String getPeers() {
            return peers.get();
        }

        public SimpleStringProperty peersProperty() {
            return peers;
        }
    }
}