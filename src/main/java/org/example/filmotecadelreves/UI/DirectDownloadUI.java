package org.example.filmotecadelreves.UI;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.downloaders.MixdropDownloader;
import org.example.filmotecadelreves.downloaders.SeleniumPowvideo;
import org.example.filmotecadelreves.downloaders.SeleniumStreamplay;
import org.example.filmotecadelreves.downloaders.StreamtapeDownloader;
import org.example.filmotecadelreves.downloaders.VideoStream;
import org.example.filmotecadelreves.moviesad.ConnectDataBase;
import org.example.filmotecadelreves.moviesad.DownloadBasketItem;
import org.example.filmotecadelreves.moviesad.DownloadManager;
import org.example.filmotecadelreves.moviesad.DownloadLimitManager;
import org.example.filmotecadelreves.moviesad.DelayedLoadingDialog;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.example.filmotecadelreves.util.UrlNormalizer;
import org.example.filmotecadelreves.scrapers.ScraperProgressTracker;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNull;

import java.awt.Desktop;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Interfaz de usuario para la descarga directa de contenido multimedia.
 * Permite buscar, seleccionar y descargar películas y series desde varios servidores.
 */
public class DirectDownloadUI {
    // ==================== ATRIBUTOS PRINCIPALES ====================
    private Tab tab;
    private AjustesUI ajustesUI;
    private DescargasUI descargasUI;
    private final MainUI mainUI;
    private static ConnectDataBase connectDataBase;
    private boolean notifyStartupOnInitialData = true;
    private final ScraperProgressTracker scraperProgressTracker;

    // ==================== COMPONENTES DE UI ====================
    // Componentes de búsqueda
    private ComboBox filterComboBox;
    private ComboBox filterOptionsComboBox;
    private VBox seriesLayout;
    private TableView seriesTable;
    private TableView moviesTable;
    private VBox busquedaSection;
    private TextField movieSearchField;
    private TextField seriesSearchField;

    // ==================== DOWNLOADERS ====================
    private StreamtapeDownloader streamtapeDownloader;
    private SeleniumPowvideo powvideoDownloader;
    private SeleniumStreamplay streamplayDownloader;
    private MixdropDownloader mixdropDownloader;
    private VideoStream videoStream;
    private Map<String, DirectDownloader> downloaders = new HashMap<>();
    private static final Consumer<Void> NO_OP_CONSUMER = value -> {};
    private static final String DEFAULT_LOADING_MESSAGE = "Cargando Datos...";

    // ==================== CESTA DE DESCARGAS ====================
    private final ObservableList<DownloadBasketItem> downloadBasket = FXCollections.observableArrayList();
    private TableView<DownloadBasketItem> basketTable;

    // ==================== DATOS DE NAVEGACIÓN ====================
    private Series currentSeries;
    private int currentSeason;
    private final Map<Integer, ObservableList<Episode>> episodesBySeason = new HashMap<>();
    private final Map<Integer, List<DirectFile>> filesByEpisode = new HashMap<>();

    private static class SeasonTabContext {
        private final ConnectDataBase.Season season;
        private final Tab tab;
        private final TableView<Episode> episodesTable;
        private final ObservableList<Episode> episodes;
        private final CheckBox selectAllCheckbox;
        private final Button addSelectedButton;
        private boolean loaded;

        private SeasonTabContext(ConnectDataBase.Season season,
                                 Tab tab,
                                 TableView<Episode> episodesTable,
                                 ObservableList<Episode> episodes,
                                 CheckBox selectAllCheckbox,
                                 Button addSelectedButton) {
            this.season = season;
            this.tab = tab;
            this.episodesTable = episodesTable;
            this.episodes = episodes;
            this.selectAllCheckbox = selectAllCheckbox;
            this.addSelectedButton = addSelectedButton;
            this.loaded = false;
        }
    }

    // ==================== CACHÉ PARA ENLACES DIRECTOS DE PELÍCULAS ====================
    /**
     * Cache local que almacena la lista de enlaces directos (DirectFile) por
     * identificación de película. Esto evita que se repitan las consultas a
     * la base de datos para obtener los enlaces de una misma película cada
     * vez que el usuario interactúa con los combos de idioma/servidor.
     */
    private final Map<Integer, ObservableList<DirectFile>> movieDirectFilesCache = new HashMap<>();

    // ==================== CACHÉS DE EPISODIOS ====================
    private final Map<Integer, List<String>> episodeLanguagesCache = new HashMap<>();
    private final Map<Integer, List<String>> episodeServersCache = new HashMap<>();
    private final Map<Integer, Map<String, List<String>>> episodeServersByLanguageCache = new HashMap<>();
    private final Map<Integer, Map<String, List<ConnectDataBase.Quality>>> episodeQualitiesCache = new HashMap<>();

    // ==================== CONFIGURACIÓN ====================
    // Lista de servidores compatibles para descarga directa
    private static final List<String> compatibleServers = Arrays.asList(
            "streamtape.com",
            "powvideo.org",
            "streamplay.to",
            "mixdrop.bz"
    );

    // ==================== CACHÉS PARA IDIOMAS Y SERVIDORES ====================
    /**
     * Cachea los idiomas disponibles para cada película consultada. Evita
     * repetir la consulta a la base de datos al navegar entre películas
     * durante una misma sesión.
     */
    private final Map<Integer, List<String>> movieLanguageCache = new HashMap<>();

    /**
     * Cachea los servidores disponibles por idioma para cada película.
     * La clave de primer nivel es el ID de la película y el segundo nivel es
     * el idioma seleccionado, devolviendo una lista de servidores (con
     * enumeraciones para duplicados) para ese idioma. Esto evita múltiples
     * consultas consecutivas a la base de datos cuando el usuario explora
     * diferentes idiomas y servidores de la misma película.
     */
    private final Map<Integer, Map<String, List<String>>> movieServerCache = new HashMap<>();

    /**
     * Mantiene una asociación directa entre el nombre mostrado del servidor y el
     * {@link DirectFile} correspondiente para cada combinación de película e
     * idioma. Gracias a este mapa podemos recuperar el enlace correcto incluso
     * cuando un mismo servidor ofrece varios enlaces (enumerados como
     * "Servidor 1", "Servidor 2", etc.).
     */
    private final Map<Integer, Map<String, Map<String, DirectFile>>> movieServerSelectionCache = new HashMap<>();

    // ==================== DOWNLOAD STATUS ====================
    private final LongProperty totalBytes = new SimpleLongProperty(0);
    private final LongProperty downloadedBytes = new SimpleLongProperty(0);
    private final LongProperty speed = new SimpleLongProperty(0);
    private final LongProperty startTime = new SimpleLongProperty(0);
    private final LongProperty remainingTime = new SimpleLongProperty(0);
    private final LongProperty lastUpdateTime = new SimpleLongProperty(0);
    private final LongProperty lastDownloadedBytes = new SimpleLongProperty(0);

    /**
     * Constructor principal de la interfaz de descarga directa
     */
    public DirectDownloadUI(AjustesUI ajustesUI, DescargasUI descargasUI, Stage primaryStage, ScraperProgressTracker scraperProgressTracker, MainUI mainUI) {
        this.ajustesUI = ajustesUI;
        this.descargasUI = descargasUI;
        this.scraperProgressTracker = scraperProgressTracker;
        this.mainUI = mainUI;
        // Use the database path specified in the user settings instead of a hardcoded name
        this.connectDataBase = new ConnectDataBase(ajustesUI.getDirectDatabasePath());

        // Inicializar downloaders
        initializeDownloaders();

        // Configurar la interfaz de usuario
        setupUI(primaryStage);

        // Cargar datos iniciales cuando la interfaz esté lista
        Platform.runLater(this::loadInitialData);
    }

    private Window getWindow() {
        if (tab != null && tab.getContent() != null && tab.getContent().getScene() != null) {
            return tab.getContent().getScene().getWindow();
        }
        return null;
    }

    private <T> void runWithLoading(Callable<T> operation, Consumer<T> onSuccess, String loadingMessage, String errorMessage) {
        runWithLoading(operation, onSuccess, loadingMessage, errorMessage, null);
    }

    private <T> void runWithLoading(Callable<T> operation, Consumer<T> onSuccess, String loadingMessage, String errorMessage, Runnable onComplete) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return operation.call();
            }
        };

        DelayedLoadingDialog loadingDialog = new DelayedLoadingDialog(getWindow(), loadingMessage);

        task.setOnSucceeded(event -> {
            loadingDialog.stop();
            if (onSuccess != null) {
                onSuccess.accept(task.getValue());
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });

        task.setOnFailed(event -> {
            loadingDialog.stop();
            Throwable ex = task.getException();
            if (ex != null) {
                ex.printStackTrace();
                String details = ex.getMessage();
                final String messageToShow = (details != null && !details.isBlank())
                        ? errorMessage + "\n" + details
                        : errorMessage;
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error", messageToShow));
            } else {
                Platform.runLater(() -> showAlert(Alert.AlertType.ERROR, "Error", errorMessage));
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        loadingDialog.start();
    }

    /**
     * Replace the active database connection with a new one. Called when the
     * user changes the database path in AjustesUI.
     *
     * @param newPath path to the new direct-download database
     */
    public void updateDatabase(String newPath) {
        if (connectDataBase != null) {
            connectDataBase.closeConnection();
        }
        connectDataBase = new ConnectDataBase(newPath);
        movieDirectFilesCache.clear();
        movieLanguageCache.clear();
        movieServerCache.clear();
        movieServerSelectionCache.clear();
        episodesBySeason.clear();
        filesByEpisode.clear();
        clearAllEpisodeCaches();
        loadInitialData();
    }

    public void applyStreamplayHeadlessPreference(boolean runHeadless) {
        if (streamplayDownloader != null) {
            streamplayDownloader.setRunHeadless(runHeadless);
        }
        if (videoStream != null) {
            videoStream.setHeadless(runHeadless);
        }
    }

    public void applyPowvideoHeadlessPreference(boolean runHeadless) {
        if (powvideoDownloader != null) {
            powvideoDownloader.setRunHeadless(runHeadless);
        }
    }

    // ==================== DESCARGAS Y SERVIDORES Y ESTATUS ====================

    // ==================== INICIALIZACIÓN Y CONFIGURACIÓN ====================

    /**
     * Inicializa los downloaders para cada servidor compatible
     */
    private void initializeDownloaders() {
        this.streamtapeDownloader = new StreamtapeDownloader();
        this.powvideoDownloader = new SeleniumPowvideo();
        this.streamplayDownloader = new SeleniumStreamplay();
        boolean runStreamplayHeadless = ajustesUI == null || ajustesUI.isStreamplayHeadless();
        boolean runPowvideoHeadless = ajustesUI == null || ajustesUI.isPowvideoHeadless();
        this.powvideoDownloader.setRunHeadless(runPowvideoHeadless);
        this.streamplayDownloader.setRunHeadless(runStreamplayHeadless);
        DownloadManager.updateStreamplayHeadless(runStreamplayHeadless);
        DownloadManager.updatePowvideoHeadless(runPowvideoHeadless);
        this.mixdropDownloader = new MixdropDownloader();
        this.videoStream = new VideoStream();
        this.videoStream.setHeadless(runStreamplayHeadless);

        // Mapear nombres de servidores a downloaders
        downloaders.put("streamtape.com", streamtapeDownloader);
        downloaders.put("powvideo.org", powvideoDownloader);
        downloaders.put("streamplay.to", streamplayDownloader);
        downloaders.put("mixdrop.bz", mixdropDownloader);
    }

    /**
     * Configura la interfaz de usuario principal
     */
    private void setupUI(Stage primaryStage) {
        tab = new Tab("Direct Download");
        tab.setClosable(false);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        // Crear cesta de descargas
        HBox basketSection = createDownloadBasket();
        layout.getChildren().add(basketSection);

        // Crear pestañas para películas y series
        TabPane subTabs = new TabPane();
        subTabs.getTabs().add(createMoviesTab(primaryStage));
        subTabs.getTabs().add(createSeriesTab(primaryStage));

        layout.getChildren().add(subTabs);
        tab.setContent(layout);
    }

    /**
     * Carga los datos iniciales para películas y series
     */
    private void loadInitialData() {
        final boolean notifyStartup = notifyStartupOnInitialData;
        notifyStartupOnInitialData = false;
        runWithLoading(() -> new InitialData(
                        connectDataBase.getLatestMovies(10),
                        connectDataBase.getLatestSeries(10, DirectDownloadUI.Series.class)
                ),
                data -> {
                    if (moviesTable != null) {
                        moviesTable.setItems(data.movies);
                    }
                    if (seriesTable != null) {
                        seriesTable.setItems(data.series);
                    }
                    System.out.println("Initial data loaded: " + data.movies.size() + " movies, " + data.series.size() + " series");
                },
                DEFAULT_LOADING_MESSAGE,
                "No se pudieron cargar los datos iniciales.",
                () -> {
                    if (notifyStartup && mainUI != null) {
                        mainUI.notifyStartupTaskCompleted();
                    }
                });
    }

    private static class InitialData {
        private final ObservableList<Movie> movies;
        private final ObservableList<Series> series;

        private InitialData(ObservableList<Movie> movies, ObservableList<Series> series) {
            this.movies = movies;
            this.series = series;
        }
    }

    // ==================== CESTA DE DESCARGAS ====================

    /**
     * Crea la sección de cesta de descargas
     */
    @NotNull
    private HBox createDownloadBasket() {
        HBox basketSection = new HBox(10);
        basketSection.setPadding(new Insets(10));
        basketSection.setStyle("-fx-border-color: #3498db; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #f8f9fa;");

        VBox basketInfo = new VBox(5);
        Label basketLabel = new Label("Download Basket");
        basketLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label basketCount = new Label("0 items");
        basketCount.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> downloadBasket.size() + " items",
                downloadBasket
        ));
        basketInfo.getChildren().addAll(basketLabel, basketCount);

        Button viewBasketButton = new Button("View Basket");
        viewBasketButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        viewBasketButton.setOnAction(e -> showDownloadBasket(basketSection.getScene().getWindow()));

        Button addToDownloadsButton = new Button("Add to Downloads");
        addToDownloadsButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
        addToDownloadsButton.setOnAction(e -> addBasketToDownloads());
        configureAddToDownloadsButton(addToDownloadsButton);

        HBox buttons = new HBox(10, viewBasketButton, addToDownloadsButton);

        basketSection.getChildren().addAll(basketInfo, new Pane(), buttons);
        HBox.setHgrow(basketSection.getChildren().get(1), Priority.ALWAYS);

        return basketSection;
    }

    private void configureAddToDownloadsButton(Button button) {
        Tooltip tooltip = button.getTooltip();
        if (tooltip == null) {
            tooltip = new Tooltip("Add basket items to downloads");
            button.setTooltip(tooltip);
        }

        BooleanBinding basketEmptyBinding = Bindings.isEmpty(downloadBasket);
        ReadOnlyBooleanProperty limitActiveProperty = descargasUI != null
                ? descargasUI.powvideoStreamplayLimitActiveProperty()
                : new SimpleBooleanProperty(false);

        BooleanBinding blockedByLimitBinding = Bindings.createBooleanBinding(
                () -> limitActiveProperty.get() && basketContainsPowvideoOrStreamplay(),
                downloadBasket,
                limitActiveProperty
        );

        button.disableProperty().bind(basketEmptyBinding.or(blockedByLimitBinding));

        Tooltip finalTooltip = tooltip;
        button.disableProperty().addListener((obs, wasDisabled, isDisabled) -> {
            if (isDisabled && !downloadBasket.isEmpty()
                    && limitActiveProperty.get()
                    && basketContainsPowvideoOrStreamplay()) {
                finalTooltip.setText("Límite alcanzado para PowVideo/StreamPlay. Espere "
                        + DownloadLimitManager.getFormattedRemainingTime() + ".");
            } else {
                finalTooltip.setText("Add basket items to downloads");
            }
        });
    }

    private boolean basketContainsPowvideoOrStreamplay() {
        return downloadBasket.stream()
                .map(DownloadBasketItem::getServer)
                .filter(Objects::nonNull)
                .map(server -> server.toLowerCase(Locale.ROOT))
                .anyMatch(server -> server.contains("powvideo") || server.contains("streamplay"));
    }

    /**
     * Muestra la ventana de la cesta de descargas
     */
    private void showDownloadBasket(javafx.stage.Window owner) {
        Stage basketStage = new Stage();
        basketStage.initOwner(owner);
        basketStage.setTitle("Download Basket");

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(20));

        Label titleLabel = new Label("Items in download basket");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        // Tabla para mostrar elementos de la cesta
        basketTable = new TableView<>();
        basketTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DownloadBasketItem, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(300);

        TableColumn<DownloadBasketItem, String> typeColBasket = new TableColumn<>("Type");
        typeColBasket.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColBasket.setCellFactory(column -> new TableCell<DownloadBasketItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.equals("movie") ? "Movie" : "Episode");
                }
            }
        });

        TableColumn<DownloadBasketItem, String> qualityColBasket = new TableColumn<>("Quality");
        qualityColBasket.setCellValueFactory(new PropertyValueFactory<>("quality"));

        TableColumn<DownloadBasketItem, String> serverColBasket = new TableColumn<>("Server");
        serverColBasket.setCellValueFactory(new PropertyValueFactory<>("server"));

        TableColumn<DownloadBasketItem, String> linkColBasket = new TableColumn<>("Link");
        linkColBasket.setCellValueFactory(new PropertyValueFactory<>("link"));
        linkColBasket.setPrefWidth(200);

        TableColumn<DownloadBasketItem, Void> actionsColBasket = new TableColumn<>("Actions");
        actionsColBasket.setCellFactory(param -> new TableCell<DownloadBasketItem, Void>() {
            private final Button removeButton = new Button("Remove");

            {
                removeButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                removeButton.setOnAction(event -> {
                    DownloadBasketItem item = getTableView().getItems().get(getIndex());
                    downloadBasket.remove(item);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : removeButton);
            }
        });

        basketTable.getColumns().addAll(nameCol, typeColBasket, qualityColBasket, serverColBasket, linkColBasket, actionsColBasket);
        basketTable.setItems(downloadBasket);

        HBox buttonsBox = new HBox(10);
        buttonsBox.setAlignment(Pos.CENTER_RIGHT);

        Button clearButton = new Button("Clear Basket");
        clearButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        clearButton.setOnAction(e -> downloadBasket.clear());

        Button addToDownloadsButton = new Button("Add to Downloads");
        addToDownloadsButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
        addToDownloadsButton.setOnAction(e -> {
            addBasketToDownloads();
            basketStage.close();
        });

        configureAddToDownloadsButton(addToDownloadsButton);

        buttonsBox.getChildren().addAll(clearButton, addToDownloadsButton);

        layout.getChildren().addAll(titleLabel, basketTable, buttonsBox);
        VBox.setVgrow(basketTable, Priority.ALWAYS);

        Scene scene = new Scene(layout, 800, 400);
        basketStage.setScene(scene);
        basketStage.show();
    }

    /**
     * Procesa los elementos de la cesta y los añade a la cola de descargas
     */
    private void addBasketToDownloads() {
        if (downloadBasket.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Empty Basket", "There are no items in the basket to download.");
            return;
        }

        if (ajustesUI.getDirectMovieDestination().isEmpty() || ajustesUI.getDirectSeriesDestination().isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Error", "Please configure destination paths in Settings first.");
            return;
        }

        // Mostrar diálogo de progreso
        ProgressDialog progressDialog = new ProgressDialog("Processing downloads", "Preparing files...");
        progressDialog.show();

        // Contador para seguir el progreso
        final int[] processedCount = {0};
        final int totalItems = downloadBasket.size();

        // Lista para almacenar tareas de descarga
        List<CompletableFuture<Void>> downloadTasks = new ArrayList<>();

        // Procesar películas
        downloadBasket.stream()
                .filter(item -> "movie".equals(item.getType()))
                .forEach(movie -> {
                    CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                        try {
                            System.out.println("Processing movie for download: " + movie.getName());
                            System.out.println("Link: " + movie.getLink());

                            // Actualizar diálogo de progreso
                            Platform.runLater(() -> progressDialog.updateProgress(
                                    "Downloading: " + movie.getName(),
                                    (double) ++processedCount[0] / totalItems
                            ));

                            // Usar el DownloadManager para iniciar la descarga
                            boolean success = DownloadManager.startMovieDownload(movie, ajustesUI, descargasUI);

                            if (!success) {
                                throw new Exception("Failed to start download for movie: " + movie.getName());
                            }

                            System.out.println("Download started for: " + movie.getName());
                        } catch (Exception e) {
                            System.err.println("Error processing movie: " + e.getMessage());
                            e.printStackTrace();
                            Platform.runLater(() -> showAlert(
                                    Alert.AlertType.ERROR,
                                    "Error",
                                    "Error processing movie " + movie.getName() + ": " + e.getMessage()
                            ));
                        }
                    });

                    downloadTasks.add(task);
                });

        // Procesar episodios
        downloadBasket.stream()
                .filter(item -> "episode".equals(item.getType()))
                .forEach(episodeItem -> {
                    // Crear una copia final del elemento para usar en la lambda
                    final DownloadBasketItem episode = episodeItem;

                    CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                        try {
                            System.out.println("Processing episode for download: " + episode.getName());
                            System.out.println("Link: " + episode.getLink());

                            // Actualizar diálogo de progreso
                            Platform.runLater(() -> progressDialog.updateProgress(
                                    "Downloading: " + episode.getName(),
                                    (double) ++processedCount[0] / totalItems
                            ));

                            // Crear estructura de carpetas para la serie
                            String seriesPath = ajustesUI.getDirectSeriesDestination() + File.separator + episode.getSeriesName();
                            String seasonPath = seriesPath + File.separator + "Season " + episode.getSeasonNumber();

                            // Crear carpetas si no existen
                            File seriesDir = new File(seriesPath);
                            if (!seriesDir.exists()) {
                                seriesDir.mkdirs();
                                System.out.println("Created series directory: " + seriesPath);
                            }

                            File seasonDir = new File(seasonPath);
                            if (!seasonDir.exists()) {
                                seasonDir.mkdirs();
                                System.out.println("Created season directory: " + seasonPath);
                            }

                            // Usar el DownloadManager para iniciar la descarga
                            boolean success = DownloadManager.startEpisodeDownload(episode, ajustesUI, descargasUI);

                            if (!success) {
                                throw new Exception("Failed to start download for episode: " + episode.getName());
                            }

                            System.out.println("Download started for: " + episode.getName());
                        } catch (Exception e) {
                            System.err.println("Error processing episode: " + e.getMessage());
                            e.printStackTrace();
                            Platform.runLater(() -> showAlert(
                                    Alert.AlertType.ERROR,
                                    "Error",
                                    "Error processing episode " + episode.getName() + ": " + e.getMessage()
                            ));
                        }
                    });

                    downloadTasks.add(task);
                });

        // Esperar a que todas las tareas se completen y luego cerrar el diálogo de progreso
        CompletableFuture.allOf(downloadTasks.toArray(new CompletableFuture[0]))
                .whenComplete((result, ex) -> {
                    Platform.runLater(() -> {
                        progressDialog.close();

                        if (ex != null) {
                            showAlert(Alert.AlertType.ERROR, "Error", "Errors occurred while processing some downloads. Check the log for details.");
                        } else {
                            showAlert(Alert.AlertType.INFORMATION, "Downloads Added", "Items have been added to the download queue.");
                        }

                        // Limpiar la cesta después de añadir a descargas
                        downloadBasket.clear();
                    });
                });
    }

    // ==================== DOWNLOADERS Y UTILIDADES ====================

    /**
     * Obtiene el downloader apropiado para un servidor específico
     */
    private DirectDownloader getDownloaderForServer(String server) {
        return DownloadManager.getDownloaderForServer(server);
    }

    /**
     * Abre el contenido en el navegador para streaming
     */
    private void streamContent(String url) {
        openStreamAsync(url, null);
    }

    /**
     * Abre el contenido en el navegador para streaming con un ID de servidor específico
     */
    private void streamContent(String url, int serverId) {
        openStreamAsync(url, serverId);
    }

    private void openStreamAsync(String url, Integer serverId) {
        String normalizedUrl = UrlNormalizer.normalizeMediaUrl(url);
        if (normalizedUrl == null || normalizedUrl.isEmpty()) {
            return;
        }

        runWithLoading(() -> {
                    if (serverId != null) {
                        videoStream.stream(normalizedUrl, serverId);
                    } else {
                        videoStream.stream(normalizedUrl);
                    }
                    return null;
                },
                NO_OP_CONSUMER,
                "Preparando streaming...",
                "No se pudo iniciar el streaming.");
    }

    private void openInDefaultBrowser(String url) {
        String normalizedUrl = UrlNormalizer.normalizeMediaUrl(url);
        if (normalizedUrl == null || normalizedUrl.isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Enlace no disponible", "No se pudo abrir el enlace de streaming.");
            return;
        }

        if (!Desktop.isDesktopSupported()) {
            showAlert(Alert.AlertType.ERROR, "Función no soportada", "La apertura del navegador predeterminado no está disponible en este sistema.");
            return;
        }

        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            showAlert(Alert.AlertType.ERROR, "Función no soportada", "La acción de abrir el navegador no está soportada en este sistema.");
            return;
        }

        try {
            desktop.browse(new URI(normalizedUrl));
        } catch (Exception e) {
            showAlert(Alert.AlertType.ERROR, "Error al abrir navegador", "No se pudo abrir el enlace en el navegador predeterminado.");
            e.printStackTrace();
        }
    }

    // ==================== PESTAÑA DE PELÍCULAS ====================

    /**
     * Crea la pestaña de películas
     */
    private Tab createMoviesTab(Stage primaryStage) {
        Tab moviesTab = new Tab("Movies");
        moviesTab.setClosable(false);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        // Sección de búsqueda
        VBox searchSection = new VBox(10);
        searchSection.setPadding(new Insets(10));
        searchSection.setStyle("-fx-border-color: #3498db; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #f8f9fa;");

        Label searchLabel = new Label("Search:");
        searchLabel.setStyle("-fx-font-weight: bold;");
        movieSearchField = new TextField();
        movieSearchField.setPromptText("Search...");

        HBox filtersLayout = new HBox(10);
        Label filterLabel = new Label("Filter by:");
        filterComboBox = new ComboBox<>();
        filterComboBox.getItems().addAll("Title", "Year", "Genre", "Language", "Quality");
        filterComboBox.setValue("Title");

        filterOptionsComboBox = new ComboBox<>();
        filterOptionsComboBox.setEditable(true);

        filterComboBox.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            updateFilterOptions();
            filterOptionsComboBox.getSelectionModel().clearSelection();
        });

        filtersLayout.getChildren().addAll(filterLabel, filterComboBox, filterOptionsComboBox);

        Button searchButton = new Button("Search");
        searchButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        searchButton.setOnAction(e -> performSearch(movieSearchField.getText()));
        movieSearchField.setOnAction(e -> searchButton.fire());

        searchSection.getChildren().addAll(searchLabel, movieSearchField, filtersLayout, searchButton);

        // Sección de resultados
        Label resultsLabel = new Label("Results:");
        resultsLabel.setStyle("-fx-font-weight: bold;");

        moviesTable = new TableView<>();
        moviesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        // Configuración de columnas
        TableColumn<Movie, String> nameCol = createColumn("Title", "title", 250);
        TableColumn<Movie, String> yearCol = createColumn("Year", "year", 80);
        TableColumn<Movie, String> genreCol = createColumn("Genre", "genre", 150);

        // Columna de idioma
        TableColumn<Movie, String> languageCol = new TableColumn<>("Language");
        languageCol.setCellValueFactory(cellData -> cellData.getValue().selectedLanguageProperty());
        languageCol.setCellFactory(column -> new TableCell<Movie, String>() {
            private final ComboBox<String> comboBox = new ComboBox<>();

            {
                // Configurar el evento de cambio
                comboBox.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                        Movie movie = getTableRow().getItem();
                        movie.selectedLanguageProperty().set(comboBox.getValue());
                        ensureMovieDetailsLoaded(movie);
                        getTableView().refresh();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Movie movie = getTableRow().getItem();
                    if (movie != null) {
                        ensureMovieDetailsLoaded(movie);

                        List<String> languages = movie.getAvailableLanguages();
                        if (languages.isEmpty()) {
                            languages = getAvailableLanguages(movie.getId());
                            movie.setAvailableLanguages(languages);
                        }

                        comboBox.getItems().setAll(languages);

                        String currentLanguage = movie.getSelectedLanguage();
                        if (currentLanguage != null && comboBox.getItems().contains(currentLanguage)) {
                            comboBox.setValue(currentLanguage);
                        } else if (!comboBox.getItems().isEmpty()) {
                            comboBox.setValue(comboBox.getItems().get(0));
                            movie.selectedLanguageProperty().set(comboBox.getValue());
                        }

                        setGraphic(comboBox);
                    }
                }
            }
        });
        languageCol.setPrefWidth(150);

        // Columna de servidor
        TableColumn<Movie, String> serverCol = new TableColumn<>("Server");
        serverCol.setCellValueFactory(cellData -> cellData.getValue().selectedServerProperty());
        serverCol.setCellFactory(column -> new TableCell<Movie, String>() {
            private final ComboBox<String> comboBox = new ComboBox<>();

            {
                // Configurar el evento de cambio
                comboBox.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                        Movie movie = getTableRow().getItem();
                        movie.selectedServerProperty().set(comboBox.getValue());
                        getTableView().refresh();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Movie movie = getTableRow().getItem();
                    if (movie != null) {
                        ensureMovieDetailsLoaded(movie);
                        movie.ensureServersForSelectedLanguage();

                        ObservableList<String> servers = movie.getAvailableServers();
                        comboBox.getItems().setAll(servers);

                        String currentServer = movie.getSelectedServer();
                        if (currentServer != null && comboBox.getItems().contains(currentServer)) {
                            comboBox.setValue(currentServer);
                        } else if (!comboBox.getItems().isEmpty()) {
                            comboBox.setValue(comboBox.getItems().get(0));
                            movie.selectedServerProperty().set(comboBox.getValue());
                        }

                        setGraphic(comboBox);
                    }
                }
            }
        });
        serverCol.setPrefWidth(150);

        // Columna de calidad
        TableColumn<Movie, String> qualityCol = createColumn("Quality", "quality", 100);

        // Columna de acciones
        TableColumn<Movie, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setCellFactory(param -> createActionButtonsCell());

        moviesTable.getColumns().addAll(nameCol, yearCol, genreCol, languageCol, serverCol, qualityCol, actionsCol);

        TableUtils.enableCopyPasteSupport(moviesTable, text -> {
            if (movieSearchField != null) {
                movieSearchField.setText(text);
                movieSearchField.positionCaret(text.length());
            }
        });

        moviesTable.setRowFactory(table -> {
            TableRow<Movie> row = new TableRow<>();
            TableUtils.installRowSelectionOnRightClick(moviesTable, row);

            ContextMenu contextMenu = TableUtils.createCopyPasteContextMenu(moviesTable, text -> {
                if (movieSearchField != null) {
                    movieSearchField.setText(text);
                    movieSearchField.positionCaret(text.length());
                }
            });

            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu));

            return row;
        });

        layout.getChildren().addAll(searchSection, resultsLabel, moviesTable);
        VBox.setVgrow(moviesTable, Priority.ALWAYS);

        moviesTab.setContent(layout);
        return moviesTab;
    }

    /**
     * Crea una columna para la tabla de películas
     */
    private <T> TableColumn<T, String> createColumn(String title, String property, double width) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        col.setPrefWidth(width);
        return col;
    }

    /**
     * Crea una celda con botones de acción para la tabla de películas
     */
    private TableCell<Movie, Void> createActionButtonsCell() {
        return new TableCell<Movie, Void>() {
            private final Button downloadBtn = new Button("Download");
            private final Button streamBtn = new Button("Stream");
            private final Button manualBtn = new Button("Descarga manual");
            private final HBox buttons = new HBox(5, downloadBtn, streamBtn, manualBtn);

            {
                downloadBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                streamBtn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
                manualBtn.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white;");

                downloadBtn.setOnAction(e -> handleDownload(getTableRow().getItem()));
                streamBtn.setOnAction(e -> handleStream(getTableRow().getItem()));
                manualBtn.setOnAction(e -> handleManualDownload(getTableRow().getItem()));
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Movie movie = getTableRow().getItem();

                    // Verificar si el servidor seleccionado es compatible para descarga
                    boolean isCompatible = isServerCompatible(movie.getSelectedServer());

                    // Habilitar/deshabilitar el botón de descarga según la compatibilidad del servidor
                    downloadBtn.setDisable(!isCompatible);

                    // Cambiar el estilo del botón según su estado
                    if (isCompatible) {
                        downloadBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                    } else {
                        downloadBtn.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white;");
                    }

                    String selectedServer = movie.getSelectedServer();
                    boolean hasServer = selectedServer != null && !selectedServer.isEmpty();
                    streamBtn.setDisable(!hasServer);
                    streamBtn.setStyle(hasServer
                            ? "-fx-background-color: #3498db; -fx-text-fill: white;"
                            : "-fx-background-color: #95a5a6; -fx-text-fill: white;");

                    boolean manualSupported = isManualServer(selectedServer);
                    manualBtn.setDisable(!manualSupported);
                    manualBtn.setVisible(manualSupported);
                    manualBtn.setManaged(manualSupported);
                    if (manualSupported) {
                        manualBtn.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white;");
                    }

                    setGraphic(buttons);
                }
            }
        };
    }

    /**
     * Realiza la búsqueda de películas con filtros
     */
    private void performSearch(String searchValue) {
        final String filterType = (String) filterComboBox.getValue();
        final String filterValue = (String) filterOptionsComboBox.getValue();

        runWithLoading(() -> {
                    ObservableList<Movie> results;
                    if (filterValue != null && !filterValue.isEmpty() && filterType != null) {
                        switch (filterType) {
                            case "Year":
                                results = connectDataBase.searchMoviesWithFilters(searchValue, filterValue, null, null, null);
                                break;
                            case "Genre":
                                results = connectDataBase.searchMoviesWithFilters(searchValue, null, filterValue, null, null);
                                break;
                            case "Language":
                                results = connectDataBase.searchMoviesWithFilters(searchValue, null, null, filterValue, null);
                                break;
                            case "Quality":
                                results = connectDataBase.searchMoviesWithFilters(searchValue, null, null, null, filterValue);
                                break;
                            default:
                                results = connectDataBase.searchMovies(searchValue);
                        }
                    } else {
                        results = connectDataBase.searchMovies(searchValue);
                    }
                    return results;
                },
                results -> moviesTable.setItems(results),
                DEFAULT_LOADING_MESSAGE,
                "No se pudieron cargar los resultados de la búsqueda.");
    }

    /**
     * Maneja la acción de descarga de una película
     */
    private void handleDownload(Movie movie) {
        if (movie == null) {
            return;
        }

        // Verificar si el servidor es compatible para la descarga
        boolean isCompatible = isServerCompatible(movie.getSelectedServer());

        if (!isCompatible) {
            showAlert(Alert.AlertType.WARNING, "Servidor incompatible", "El servidor seleccionado '" + movie.getSelectedServer() + "' no es compatible con la descarga directa.");
            return;
        }

        // Verificar si ya está en la cesta de descargas
        boolean alreadyInBasket = downloadBasket.stream()
                .anyMatch(item -> item.getId() == movie.getId() && item.getType().equals("movie"));

        if (!alreadyInBasket) {
            downloadBasket.add(new DownloadBasketItem(
                    movie.getId(),
                    movie.getTitle(),
                    "movie",
                    movie.getQuality(),
                    movie.getLink(),
                    movie.getSelectedServer(),
                    null,
                    0,
                    0
            ));
            showConfirmation("Añadido a la cesta: " + movie.getTitle());
        } else {
            showAlert(Alert.AlertType.WARNING, "Ya en la cesta", "'" + movie.getTitle() + "' ya está en la cesta de descargas.");
        }
    }

    private void handleManualDownload(Movie movie) {
        if (movie == null) {
            return;
        }

        String selectedServer = movie.getSelectedServer();
        if (!isManualServer(selectedServer)) {
            showAlert(Alert.AlertType.WARNING, "Servidor no compatible", "La descarga manual solo está disponible para PowVideo y Streamplay.");
            return;
        }

        DownloadBasketItem item = new DownloadBasketItem(
                movie.getId(),
                movie.getTitle(),
                "movie",
                movie.getQuality(),
                movie.getLink(),
                selectedServer,
                null,
                0,
                0
        );

        boolean success = DownloadManager.startManualMovieDownload(item, ajustesUI, descargasUI);
        if (success) {
            showConfirmation("Descarga manual iniciada para: " + movie.getTitle());
        } else {
            showAlert(Alert.AlertType.ERROR, "Error", "No se pudo iniciar la descarga manual para '" + movie.getTitle() + "'.");
        }
    }

    private void handleManualEpisodeDownload(Episode episode, ConnectDataBase.Season season) {
        if (episode == null) {
            return;
        }

        String selectedServer = episode.getSelectedServer();
        if (!isManualServer(selectedServer)) {
            showAlert(Alert.AlertType.WARNING, "Servidor no compatible", "La descarga manual solo está disponible para PowVideo y Streamplay.");
            return;
        }

        ConnectDataBase.Quality selectedQuality = episode.getSelectedQuality();
        if (selectedQuality == null) {
            showAlert(Alert.AlertType.WARNING, "Select a quality", "Please select a quality for the episode.");
            return;
        }

        ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());
        String baseServer = selectedServer.split(" ")[0];
        DirectFile selectedFile = directFiles.stream()
                .filter(df -> df.getQualityId() == selectedQuality.getId() && df.getServer().equalsIgnoreCase(baseServer))
                .findFirst()
                .orElse(null);

        if (selectedFile == null) {
            showAlert(Alert.AlertType.WARNING, "File not available", "No file available for the selected quality and server.");
            return;
        }

        DownloadBasketItem item = new DownloadBasketItem(
                episode.getId(),
                currentSeries.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                "episode",
                selectedQuality.getQuality(),
                selectedFile.getLink(),
                selectedFile.getServer(),
                currentSeries.getName(),
                season.getSeasonNumber(),
                episode.getEpisodeNumber()
        );

        boolean success = DownloadManager.startManualEpisodeDownload(item, ajustesUI, descargasUI);
        if (success) {
            showConfirmation("Descarga manual iniciada para el episodio " + episode.getEpisodeNumber());
        } else {
            showAlert(Alert.AlertType.ERROR, "Error", "No se pudo iniciar la descarga manual para el episodio seleccionado.");
        }
    }

    /**
     * Maneja la acción de streaming de una película
     */
    private void handleStream(Movie movie) {
        if (movie != null && !movie.getLink().isEmpty()) {
            // Determine server ID based on selected server
            String selectedServer = movie.getSelectedServer();
            int serverId = -1;

            if (selectedServer != null) {
                String baseServer = selectedServer.split(" ")[0].toLowerCase();

                if (baseServer.contains("powvideo") || baseServer.contains("streamplay")) {
                    openInDefaultBrowser(movie.getLink());
                    return;
                }

                // Check for streamtape.com (ID: 497)
                if (baseServer.contains("streamtape.com")) {
                    serverId = 497;
                }
                // Check for mixdrop.bz (ID: 15)
                else if (baseServer.contains("mixdrop.bz")) {
                    serverId = 15;
                }
                // Check for vidmoly.me (ID: 3)
                else if (baseServer.contains("vidmoly")) {
                    serverId = 3;
                }
            }

            // Stream with server ID if available
            if (serverId != -1) {
                streamContent(movie.getLink(), serverId);
            } else {
                streamContent(movie.getLink());
            }
        }
    }

    // ==================== PESTAÑA DE SERIES ====================

    /**
     * Crea la pestaña de series
     */
    private Tab createSeriesTab(Stage primaryStage) {
        Tab seriesTab = new Tab("Series");

        seriesLayout = new VBox(10);
        seriesLayout.setPadding(new Insets(10));

        // Sección de búsqueda
        VBox searchSection = new VBox(10);
        searchSection.setPadding(new Insets(10));
        searchSection.setStyle("-fx-border-color: #3498db; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #f8f9fa;");

        Label searchLabel = new Label("Search:");
        searchLabel.setStyle("-fx-font-weight: bold;");
        seriesSearchField = new TextField();
        seriesSearchField.setPromptText("Search...");

        HBox filtersLayout = new HBox(10);
        Label filterLabel = new Label("Filter by:");
        ComboBox<String> filterComboBoxSeries = new ComboBox<>();
        filterComboBoxSeries.getItems().addAll("Title", "Year", "Genre", "Language");
        filterComboBoxSeries.setValue("Title");

        ComboBox<String> filterOptionsComboBoxSeries = new ComboBox<>();
        filterOptionsComboBoxSeries.setEditable(true);

        filterComboBoxSeries.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            updateSeriesFilterOptions(filterComboBoxSeries, filterOptionsComboBoxSeries);
        });

        filtersLayout.getChildren().addAll(filterLabel, filterComboBoxSeries, filterOptionsComboBoxSeries);

        Button searchButton = new Button("Search");
        searchButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        searchButton.setOnAction(e -> {
            String selectedFilter = filterComboBoxSeries.getValue();
            String filterValue = filterOptionsComboBoxSeries.getValue();
            String searchValue = seriesSearchField.getText();

            if (selectedFilter != null && filterValue != null && !filterValue.isEmpty()) {
                switch (selectedFilter) {
                    case "Year":
                        searchSeries(searchValue, filterValue, null, null);
                        break;
                    case "Genre":
                        searchSeries(searchValue, null, filterValue, null);
                        break;
                    case "Language":
                        searchSeries(searchValue, null, null, filterValue);
                        break;
                    default:
                        searchSeries(searchValue);
                }
            } else {
                searchSeries(searchValue);
            }
        });

        seriesSearchField.setOnAction(e -> searchButton.fire());

        searchSection.getChildren().addAll(searchLabel, seriesSearchField, filtersLayout, searchButton);

        // Guardar la sección de búsqueda para poder restaurarla después
        busquedaSection = searchSection;

        // Sección de resultados
        Label resultsLabel = new Label("Results:");
        resultsLabel.setStyle("-fx-font-weight: bold;");

        seriesTable = new TableView<>();
        seriesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Series, String> nameCol = new TableColumn<>("Title");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(250);

        TableColumn<Series, String> yearCol = new TableColumn<>("Year");
        yearCol.setCellValueFactory(new PropertyValueFactory<>("year"));

        TableColumn<Series, String> ratingCol = new TableColumn<>("Rating");
        ratingCol.setCellValueFactory(new PropertyValueFactory<>("rating"));

        TableColumn<Series, String> genreCol = new TableColumn<>("Genre");
        genreCol.setCellValueFactory(new PropertyValueFactory<>("genre"));
        genreCol.setPrefWidth(150);

        seriesTable.getColumns().addAll(nameCol, yearCol, ratingCol, genreCol);

        TableUtils.enableCopyPasteSupport(seriesTable, text -> {
            if (seriesSearchField != null) {
                seriesSearchField.setText(text);
                seriesSearchField.positionCaret(text.length());
            }
        });

        seriesTable.setRowFactory(tv -> {
            TableRow<Series> row = new TableRow<>();
            TableUtils.installRowSelectionOnRightClick(seriesTable, row);
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    Series rowData = row.getItem();
                    showSeriesDetails(rowData);
                }
            });

            // Añadir efecto hover y cambiar cursor
            row.hoverProperty().addListener((observable, oldValue, newValue) -> {
                if (newValue) {
                    row.setStyle("-fx-background-color: lightgray;");
                    row.setCursor(javafx.scene.Cursor.HAND);
                } else {
                    row.setStyle("");
                    row.setCursor(javafx.scene.Cursor.DEFAULT);
                }
            });

            ContextMenu contextMenu = TableUtils.createCopyPasteContextMenu(seriesTable, text -> {
                if (seriesSearchField != null) {
                    seriesSearchField.setText(text);
                    seriesSearchField.positionCaret(text.length());
                }
            });

            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu));

            return row;
        });

        seriesLayout.getChildren().addAll(searchSection, resultsLabel, seriesTable);
        VBox.setVgrow(seriesTable, Priority.ALWAYS);

        seriesTab.setContent(seriesLayout);

        return seriesTab;
    }

    /**
     * Actualiza las opciones de filtro para series
     */
    private void updateSeriesFilterOptions(ComboBox<String> filterComboBox, ComboBox<String> optionsComboBox) {
        String selectedFilter = filterComboBox.getValue();
        optionsComboBox.getItems().clear();

        if (selectedFilter != null) {
            switch (selectedFilter) {
                case "Year":
                    optionsComboBox.getItems().addAll(connectDataBase.getUniqueYears());
                    break;
                case "Genre":
                    optionsComboBox.getItems().addAll(connectDataBase.getUniqueGenres());
                    break;
                case "Language":
                    optionsComboBox.getItems().addAll(connectDataBase.getUniqueLanguages());
                    break;
            }
        }
    }

    /**
     * Actualiza las opciones de filtro para películas
     */
    private void updateFilterOptions() {
        String selectedFilter = (String) filterComboBox.getValue();
        filterOptionsComboBox.getItems().clear();

        if (selectedFilter != null) {
            switch (selectedFilter) {
                case "Year":
                    filterOptionsComboBox.getItems().addAll(connectDataBase.getUniqueYears());
                    break;
                case "Genre":
                    filterOptionsComboBox.getItems().addAll(connectDataBase.getUniqueGenres());
                    break;
                case "Language":
                    filterOptionsComboBox.getItems().addAll(connectDataBase.getUniqueLanguages());
                    break;
                case "Quality":
                    ObservableList<ConnectDataBase.Quality> qualities = connectDataBase.getQualities();
                    for (ConnectDataBase.Quality quality : qualities) {
                        filterOptionsComboBox.getItems().add(quality.getQuality());
                    }
                    break;
            }
        }
    }

    /**
     * Busca series por nombre
     */
    private void searchSeries(String searchValue) {
        System.out.println("Searching series: " + searchValue);

        runWithLoading(() -> {
                    ObservableList<Series> results = connectDataBase.searchSeries(searchValue);
                    Map<Integer, Series> uniqueSeriesMap = new HashMap<>();
                    for (Series series : results) {
                        uniqueSeriesMap.put(series.getId(), series);
                    }
                    return FXCollections.observableArrayList(uniqueSeriesMap.values());
                },
                results -> {
                    seriesTable.setItems(results);
                    System.out.println("Results found: " + results.size());
                },
                DEFAULT_LOADING_MESSAGE,
                "No se pudieron cargar las series.");
    }

    /**
     * Busca series con filtros
     */
    private void searchSeries(String searchValue, String year, String genre, String language) {
        System.out.println("Searching series with filters: " + searchValue + ", " + year + ", " + genre + ", " + language);

        runWithLoading(() -> {
                    ObservableList<Series> results = connectDataBase.searchSeriesWithFilters(searchValue, year, genre, language);
                    Map<Integer, Series> uniqueSeriesMap = new HashMap<>();
                    for (Series series : results) {
                        uniqueSeriesMap.put(series.getId(), series);
                    }
                    return FXCollections.observableArrayList(uniqueSeriesMap.values());
                },
                results -> {
                    seriesTable.setItems(results);
                    System.out.println("Results found: " + results.size());
                },
                DEFAULT_LOADING_MESSAGE,
                "No se pudieron cargar las series.");
    }

    /**
     * Muestra los detalles de una serie seleccionada
     */

    private void showSeriesDetails(Series series) {
        currentSeries = series;
        currentSeason = 1;
        episodesBySeason.clear();
        filesByEpisode.clear();
        seriesLayout.getChildren().clear();

        Button backButton = new Button("Back to Series List");
        backButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        backButton.setOnAction(e -> {
            seriesLayout.getChildren().clear();
            seriesLayout.getChildren().addAll(busquedaSection, new Label("Results:"), seriesTable);
            VBox.setVgrow(seriesTable, Priority.ALWAYS);
        });

        Label seriesLabel = new Label("Series: " + series.getName() + " (" + series.getYear() + ") - Rating: " + series.getRating());
        seriesLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 18px;");

        runWithLoading(() -> loadSeasonsForSeries(series.getId()), seasons -> {
            if (seasons.isEmpty()) {
                Label noSeasonsLabel = new Label("No seasons found for this series.");
                seriesLayout.getChildren().addAll(backButton, seriesLabel, noSeasonsLabel);
                return;
            }

            TabPane seasonsTabPane = new TabPane();
            seasonsTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            seasons.sort(Comparator.comparingInt(ConnectDataBase.Season::getSeasonNumber));

            List<SeasonTabContext> contexts = new ArrayList<>();
            for (ConnectDataBase.Season season : seasons) {
                SeasonTabContext context = createSeasonTabContext(season);
                contexts.add(context);
                seasonsTabPane.getTabs().add(context.tab);
            }

            Button downloadSeasonButton = new Button("Download Complete Season");
            downloadSeasonButton.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white;");
            downloadSeasonButton.setOnAction(e -> {
                SeasonTabContext context = getSelectedSeasonContext(seasonsTabPane);
                if (context == null) {
                    showAlert(Alert.AlertType.WARNING, "No Season Selected", "Please select a season to download.");
                    return;
                }

                loadSeasonEpisodes(context, () -> downloadSeasonEpisodes(context));
            });

            seasonsTabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
                if (newTab != null) {
                    SeasonTabContext context = (SeasonTabContext) newTab.getUserData();
                    currentSeason = context.season.getSeasonNumber();
                    loadSeasonEpisodes(context, null);
                }
            });

            HBox actionButtons = new HBox(10, backButton, downloadSeasonButton);
            actionButtons.setPadding(new Insets(10, 0, 10, 0));
            actionButtons.setAlignment(Pos.CENTER_LEFT);

            seriesLayout.getChildren().addAll(actionButtons, seriesLabel, seasonsTabPane);
            VBox.setVgrow(seasonsTabPane, Priority.ALWAYS);

            if (!contexts.isEmpty()) {
                SeasonTabContext firstContext = contexts.get(0);
                seasonsTabPane.getSelectionModel().select(firstContext.tab);
                currentSeason = firstContext.season.getSeasonNumber();
                loadSeasonEpisodes(firstContext, null);
            }
        }, DEFAULT_LOADING_MESSAGE, "No se pudieron cargar las temporadas.");
    }

    private List<ConnectDataBase.Season> loadSeasonsForSeries(int movieId) throws Exception {
        String query = "SELECT id, movie_id, season FROM series_seasons WHERE movie_id = ? ORDER BY season";
        List<ConnectDataBase.Season> seasons = new ArrayList<>();

        try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
            stmt.setInt(1, movieId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                seasons.add(new ConnectDataBase.Season(
                        rs.getInt("id"),
                        rs.getInt("movie_id"),
                        rs.getInt("season")));
            }
        }

        return seasons;
    }

    private SeasonTabContext createSeasonTabContext(ConnectDataBase.Season season) {
        Tab seasonTab = new Tab("Season " + season.getSeasonNumber());

        VBox seasonLayout = new VBox(10);
        seasonLayout.setPadding(new Insets(10));

        HBox selectAllBox = new HBox(10);
        selectAllBox.setAlignment(Pos.CENTER_LEFT);
        CheckBox selectAllCheckbox = new CheckBox("Select All Episodes");
        selectAllBox.getChildren().add(selectAllCheckbox);

        TableView<Episode> episodesTable = new TableView<>();
        episodesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Episode, Boolean> selectCol = new TableColumn<>("");
        selectCol.setCellValueFactory(param -> param.getValue().selectedProperty());
        selectCol.setCellFactory(column -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private Episode boundEpisode;

            {
                setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                setEditable(true);
                setAlignment(Pos.CENTER);
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);

                if (boundEpisode != null) {
                    checkBox.selectedProperty().unbindBidirectional(boundEpisode.selectedProperty());
                    boundEpisode = null;
                }

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    boundEpisode = getTableRow().getItem();
                    checkBox.selectedProperty().bindBidirectional(boundEpisode.selectedProperty());
                    setGraphic(checkBox);
                }
            }

            @Override
            public void updateIndex(int i) {
                super.updateIndex(i);
                if (isEmpty() && boundEpisode != null) {
                    checkBox.selectedProperty().unbindBidirectional(boundEpisode.selectedProperty());
                    boundEpisode = null;
                }
            }
        });
        selectCol.setPrefWidth(40);

        TableColumn<Episode, Integer> episodeNumberCol = new TableColumn<>("Episode");
        episodeNumberCol.setCellValueFactory(new PropertyValueFactory<>("episodeNumber"));
        episodeNumberCol.setPrefWidth(70);

        TableColumn<Episode, String> titleCol = new TableColumn<>("Title");
        titleCol.setCellValueFactory(new PropertyValueFactory<>("title"));
        titleCol.setPrefWidth(250);

        TableColumn<Episode, String> languageCol = new TableColumn<>("Language");
        languageCol.setCellValueFactory(cellData -> cellData.getValue().selectedLanguageProperty());
        languageCol.setCellFactory(column -> new TableCell<>() {
            private final ComboBox<String> comboBox = new ComboBox<>();

            {
                comboBox.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                        Episode episode = getTableRow().getItem();
                        episode.setSelectedLanguage(comboBox.getValue());
                        getTableView().refresh();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Episode episode = getTableRow().getItem();
                    List<String> languages = getAvailableEpisodeLanguages(episode.getId());

                    comboBox.getItems().clear();
                    comboBox.getItems().addAll(languages);

                    if (item != null && !item.isEmpty() && comboBox.getItems().contains(item)) {
                        comboBox.setValue(item);
                    } else if (!comboBox.getItems().isEmpty()) {
                        comboBox.setValue(comboBox.getItems().get(0));
                        episode.setSelectedLanguage(comboBox.getValue());
                    }

                    setGraphic(comboBox);
                }
            }
        });
        languageCol.setPrefWidth(120);

        TableColumn<Episode, String> serverCol = new TableColumn<>("Server");
        serverCol.setCellValueFactory(cellData -> cellData.getValue().selectedServerProperty());
        serverCol.setCellFactory(column -> new TableCell<>() {
            private final ComboBox<String> comboBox = new ComboBox<>();

            {
                comboBox.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                        Episode episode = getTableRow().getItem();
                        episode.setSelectedServer(comboBox.getValue());
                        getTableView().refresh();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Episode episode = getTableRow().getItem();
                    String selectedLanguage = episode.getSelectedLanguage();

                    List<String> servers;
                    if (selectedLanguage != null && !selectedLanguage.isEmpty()) {
                        servers = getServersForEpisodeLanguage(episode.getId(), selectedLanguage);
                    } else {
                        servers = getAvailableEpisodeServers(episode.getId());
                    }

                    comboBox.getItems().clear();
                    comboBox.getItems().addAll(servers);

                    if (item != null && !item.isEmpty() && comboBox.getItems().contains(item)) {
                        comboBox.setValue(item);
                    } else if (!comboBox.getItems().isEmpty()) {
                        comboBox.setValue(comboBox.getItems().get(0));
                        episode.setSelectedServer(comboBox.getValue());
                    }

                    setGraphic(comboBox);
                }
            }
        });
        serverCol.setPrefWidth(120);

        TableColumn<Episode, ConnectDataBase.Quality> qualityCol = new TableColumn<>("Quality");
        qualityCol.setCellValueFactory(cellData -> cellData.getValue().selectedQualityProperty());
        qualityCol.setCellFactory(column -> new TableCell<>() {
            private final ComboBox<ConnectDataBase.Quality> comboBox = new ComboBox<>();

            {
                comboBox.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                        Episode episode = getTableRow().getItem();
                        episode.setSelectedQuality(comboBox.getValue());
                    }
                });
            }

            @Override
            protected void updateItem(ConnectDataBase.Quality item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Episode episode = getTableRow().getItem();
                    String selectedServer = episode.getSelectedServer();

                    List<ConnectDataBase.Quality> qualities;
                    if (selectedServer != null && !selectedServer.isEmpty()) {
                        qualities = getQualitiesForServer(episode.getId(), selectedServer);
                    } else {
                        qualities = new ArrayList<>(connectDataBase.getQualities());
                    }

                    comboBox.getItems().clear();
                    comboBox.getItems().addAll(qualities);

                    if (item != null && comboBox.getItems().contains(item)) {
                        comboBox.setValue(item);
                    } else if (!comboBox.getItems().isEmpty()) {
                        comboBox.setValue(comboBox.getItems().get(0));
                        episode.setSelectedQuality(comboBox.getValue());
                    }

                    setGraphic(comboBox);
                }
            }
        });
        qualityCol.setPrefWidth(100);

        TableColumn<Episode, Void> actionsCol = new TableColumn<>("Actions");
        actionsCol.setCellFactory(param -> new TableCell<>() {
            private final Button addButton = new Button("Add");
            private final Button streamButton = new Button("Stream");
            private final Button manualButton = new Button("Descarga manual");
            private final HBox buttonsBox = new HBox(5);

            {
                addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                streamButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
                manualButton.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white;");

                addButton.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        Episode episode = getTableRow().getItem();

                        boolean isInBasket = downloadBasket.stream()
                                .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                        if (isInBasket) {
                            downloadBasket.removeIf(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());
                            addButton.setText("Add");
                            addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                            episode.setSelected(false);
                            getTableView().refresh();
                        } else {
                            String selectedServer = episode.getSelectedServer();
                            ConnectDataBase.Quality selectedQuality = episode.getSelectedQuality();

                            if (selectedQuality == null) {
                                showAlert(Alert.AlertType.WARNING, "Select a quality", "Please select a quality for the episode.");
                                return;
                            }

                            if (selectedServer == null || selectedServer.isEmpty()) {
                                showAlert(Alert.AlertType.WARNING, "Select a server", "Please select a server for the episode.");
                                return;
                            }

                            if (!isServerCompatible(selectedServer)) {
                                showAlert(Alert.AlertType.WARNING, "Incompatible Server", "The selected server '" + selectedServer + "' is not compatible with direct download.");
                                return;
                            }

                            ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());
                            String baseServer = selectedServer.split(" " )[0];
                            DirectFile selectedFile = directFiles.stream()
                                    .filter(df -> df.getQualityId() == selectedQuality.getId() && df.getServer().equalsIgnoreCase(baseServer))
                                    .findFirst()
                                    .orElse(null);

                            if (selectedFile == null) {
                                showAlert(Alert.AlertType.WARNING, "File not available", "No file available for the selected quality and server.");
                                return;
                            }

                            DirectDownloader downloader = getDownloaderForServer(selectedServer);
                            if (downloader == null) {
                                showAlert(Alert.AlertType.ERROR, "Error", "No downloader available for server: " + selectedServer);
                                return;
                            }

                            DownloadBasketItem basketItem = new DownloadBasketItem(
                                    episode.getId(),
                                    currentSeries.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                                    "episode",
                                    selectedQuality.getQuality(),
                                    selectedFile.getLink(),
                                    selectedFile.getServer(),
                                    currentSeries.getName(),
                                    season.getSeasonNumber(),
                                    episode.getEpisodeNumber()
                            );

                            downloadBasket.add(basketItem);
                            addButton.setText("Remove");
                            addButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                            episode.setSelected(true);
                            getTableView().refresh();
                        }
                    }
                });

                streamButton.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        Episode episode = getTableRow().getItem();
                        String selectedServer = episode.getSelectedServer();
                        if (selectedServer == null || selectedServer.isEmpty()) {
                            showAlert(Alert.AlertType.WARNING, "Select a server", "Please select a server to stream from.");
                            return;
                        }

                        ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());
                        String baseServer = selectedServer.split(" " )[0];
                        DirectFile selectedFile = directFiles.stream()
                                .filter(df -> df.getServer().equalsIgnoreCase(baseServer))
                                .findFirst()
                                .orElse(null);

                        if (selectedFile != null) {
                            int serverId = -1;
                            String lowerServer = baseServer.toLowerCase();
                            if (lowerServer.contains("powvideo") || lowerServer.contains("streamplay")) {
                                openInDefaultBrowser(selectedFile.getLink());
                                return;
                            } else if (lowerServer.contains("streamtape.com")) {
                                serverId = 497;
                            } else if (lowerServer.contains("mixdrop.bz")) {
                                serverId = 15;
                            } else if (lowerServer.contains("vidmoly")) {
                                serverId = 3;
                            }

                            if (serverId != -1) {
                                streamContent(selectedFile.getLink(), serverId);
                            } else {
                                streamContent(selectedFile.getLink());
                            }
                        } else {
                            showAlert(Alert.AlertType.WARNING, "Stream not available", "No stream available for the selected server.");
                        }
                    }
                });
                manualButton.setOnAction(event -> {
                    if (getTableRow() != null && getTableRow().getItem() != null) {
                        handleManualEpisodeDownload(getTableRow().getItem(), season);
                    }
                });
                buttonsBox.getChildren().addAll(addButton, streamButton, manualButton);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);

                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    Episode episode = getTableRow().getItem();

                    boolean isInBasket = downloadBasket.stream()
                            .anyMatch(basketItem -> basketItem.getType().equals("episode") && basketItem.getEpisodeId() == episode.getId());

                    if (isInBasket) {
                        addButton.setText("Remove");
                        addButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                        episode.setSelected(true);
                    } else {
                        addButton.setText("Add");
                        addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                    }

                    String selectedServer = episode.getSelectedServer();
                    if (selectedServer != null && !selectedServer.isEmpty()) {
                        boolean isCompatible = isServerCompatible(selectedServer);
                        addButton.setDisable(!isCompatible);

                        if (!isCompatible) {
                            addButton.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white;");
                        }
                    }

                    boolean hasServer = selectedServer != null && !selectedServer.isEmpty();
                    streamButton.setDisable(!hasServer);
                    streamButton.setStyle(hasServer
                            ? "-fx-background-color: #3498db; -fx-text-fill: white;"
                            : "-fx-background-color: #95a5a6; -fx-text-fill: white;");

                    boolean manualSupported = hasServer && isManualServer(selectedServer);
                    boolean hasQuality = episode.getSelectedQuality() != null;
                    manualButton.setDisable(!(manualSupported && hasQuality));
                    manualButton.setVisible(manualSupported);
                    manualButton.setManaged(manualSupported);
                    if (manualSupported) {
                        manualButton.setStyle("-fx-background-color: #f39c12; -fx-text-fill: white;");
                    }

                    setGraphic(buttonsBox);
                }
            }
        });
        actionsCol.setPrefWidth(150);

        episodesTable.getColumns().addAll(selectCol, episodeNumberCol, titleCol, languageCol, serverCol, qualityCol, actionsCol);

        ObservableList<Episode> seasonEpisodes = FXCollections.observableArrayList();
        episodesTable.setItems(seasonEpisodes);

        selectAllCheckbox.setOnAction(e -> {
            boolean selected = selectAllCheckbox.isSelected();

            for (Episode episode : seasonEpisodes) {
                episode.setSelected(selected);

                if (selected) {
                    if (episode.getSelectedLanguage() == null || episode.getSelectedLanguage().isEmpty()) {
                        List<String> languages = getAvailableEpisodeLanguages(episode.getId());
                        if (!languages.isEmpty()) {
                            episode.setSelectedLanguage(languages.get(0));
                        }
                    }

                    if (episode.getSelectedServer() == null || episode.getSelectedServer().isEmpty()) {
                        List<String> servers = getAvailableEpisodeServers(episode.getId());
                        if (!servers.isEmpty()) {
                            episode.setSelectedServer(servers.get(0));
                        }
                    }

                    if (episode.getSelectedQuality() == null) {
                        List<ConnectDataBase.Quality> qualities = getQualitiesForServer(episode.getId(), episode.getSelectedServer());
                        if (!qualities.isEmpty()) {
                            episode.setSelectedQuality(qualities.get(0));
                        }
                    }
                }
            }

            episodesTable.refresh();
        });

        Button addSelectedButton = new Button("Add Selected to Basket");
        addSelectedButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
        addSelectedButton.setOnAction(e -> {
            List<Episode> selectedEpisodes = seasonEpisodes.stream()
                    .filter(Episode::isSelected)
                    .collect(Collectors.toList());

            if (selectedEpisodes.isEmpty()) {
                showAlert(Alert.AlertType.INFORMATION, "No Selection", "Please select at least one episode to add to the basket.");
                return;
            }

            int addedCount = 0;
            for (Episode episodeItem : selectedEpisodes) {
                Episode episode = episodeItem;

                boolean alreadyInBasket = downloadBasket.stream()
                        .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                if (alreadyInBasket) {
                    continue;
                }

                if (episode.getSelectedServer() == null || episode.getSelectedQuality() == null) {
                    if (episode.getSelectedServer() == null) {
                        List<String> servers = getAvailableEpisodeServers(episode.getId());
                        if (!servers.isEmpty()) {
                            episode.setSelectedServer(servers.get(0));
                        }
                    }

                    if (episode.getSelectedQuality() == null) {
                        ObservableList<ConnectDataBase.Quality> qualities = connectDataBase.getQualities();
                        if (!qualities.isEmpty()) {
                            episode.setSelectedQuality(qualities.get(0));
                        }
                    }

                    if (episode.getSelectedServer() == null || episode.getSelectedQuality() == null) {
                        continue;
                    }
                }

                if (!isServerCompatible(episode.getSelectedServer())) {
                    continue;
                }

                ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());
                String baseServer = episode.getSelectedServer().split(" " )[0];
                DirectFile selectedFile = directFiles.stream()
                        .filter(df -> df.getQualityId() == episode.getSelectedQuality().getId() && df.getServer().equalsIgnoreCase(baseServer))
                        .findFirst()
                        .orElse(null);

                if (selectedFile == null) {
                    continue;
                }

                DownloadBasketItem basketItem = new DownloadBasketItem(
                        episode.getId(),
                        currentSeries.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                        "episode",
                        episode.getSelectedQuality().getQuality(),
                        selectedFile.getLink(),
                        selectedFile.getServer(),
                        currentSeries.getName(),
                        season.getSeasonNumber(),
                        episode.getEpisodeNumber()
                );

                downloadBasket.add(basketItem);
                addedCount++;
            }

            episodesTable.refresh();

            if (addedCount > 0) {
                showConfirmation(addedCount + " episodes added to the basket");
            } else {
                showAlert(Alert.AlertType.INFORMATION, "No Episodes Added", "No new episodes were added to the basket. They might already be in the basket or no compatible servers were found.");
            }
        });

        selectAllBox.getChildren().add(addSelectedButton);
        selectAllBox.setSpacing(20);

        seasonLayout.getChildren().addAll(selectAllBox, episodesTable);
        VBox.setVgrow(episodesTable, Priority.ALWAYS);

        seasonTab.setContent(seasonLayout);

        SeasonTabContext context = new SeasonTabContext(
                season,
                seasonTab,
                episodesTable,
                seasonEpisodes,
                selectAllCheckbox,
                addSelectedButton);
        seasonTab.setUserData(context);
        return context;
    }

    private SeasonTabContext getSelectedSeasonContext(TabPane seasonsTabPane) {
        if (seasonsTabPane == null) {
            return null;
        }
        Tab selectedTab = seasonsTabPane.getSelectionModel().getSelectedItem();
        if (selectedTab == null) {
            return null;
        }
        Object data = selectedTab.getUserData();
        if (data instanceof SeasonTabContext) {
            return (SeasonTabContext) data;
        }
        return null;
    }

    private void loadSeasonEpisodes(SeasonTabContext context, Runnable onLoaded) {
        if (context == null) {
            return;
        }
        if (context.loaded) {
            if (onLoaded != null) {
                onLoaded.run();
            }
            return;
        }

        clearEpisodeCachesForSeason(context.season.getId());

        runWithLoading(() -> fetchSeasonEpisodes(context.season), episodes -> {
            context.episodes.setAll(episodes);
            context.loaded = true;
            episodesBySeason.put(context.season.getId(), context.episodes);
            context.selectAllCheckbox.setSelected(false);
            context.episodesTable.refresh();
            if (onLoaded != null) {
                onLoaded.run();
            }
        }, DEFAULT_LOADING_MESSAGE, "No se pudieron cargar los episodios.");
    }

    private ObservableList<Episode> fetchSeasonEpisodes(ConnectDataBase.Season season) throws Exception {
        ObservableList<Episode> seasonEpisodes = connectDataBase.getEpisodesBySeason(season.getId());
        seasonEpisodes.sort(Comparator.comparingInt(Episode::getEpisodeNumber));
        preloadEpisodeData(seasonEpisodes);
        return seasonEpisodes;
    }

    private void downloadSeasonEpisodes(SeasonTabContext context) {
        if (context.episodes.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Episodes", "No episodes found for this season.");
            return;
        }

        for (Episode episode : context.episodes) {
            List<String> languages = getAvailableEpisodeLanguages(episode.getId());
            if (episode.getSelectedLanguage() == null && !languages.isEmpty()) {
                episode.setSelectedLanguage(languages.get(0));
            }

            if (episode.getSelectedServer() == null) {
                List<String> servers = getAvailableEpisodeServers(episode.getId());
                String compatibleServer = servers.stream()
                        .filter(this::isServerCompatible)
                        .findFirst()
                        .orElse(servers.isEmpty() ? null : servers.get(0));
                if (compatibleServer != null) {
                    episode.setSelectedServer(compatibleServer);
                }
            }

            if (episode.getSelectedServer() != null && episode.getSelectedQuality() == null) {
                List<ConnectDataBase.Quality> qualities = getQualitiesForServer(episode.getId(), episode.getSelectedServer());
                if (!qualities.isEmpty()) {
                    episode.setSelectedQuality(qualities.get(0));
                }
            }
        }

        List<Episode> validEpisodes = context.episodes.stream()
                .filter(ep -> ep.getSelectedServer() != null
                        && ep.getSelectedQuality() != null
                        && isServerCompatible(ep.getSelectedServer()))
                .collect(Collectors.toList());

        if (validEpisodes.isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "No Valid Episodes", "No episodes with compatible download options found.");
            return;
        }

        int addedCount = 0;
        for (Episode episode : validEpisodes) {
            boolean alreadyInBasket = downloadBasket.stream()
                    .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

            if (alreadyInBasket) {
                continue;
            }

            ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());
            String baseServer = episode.getSelectedServer().split(" " )[0];
            DirectFile selectedFile = directFiles.stream()
                    .filter(df -> df.getQualityId() == episode.getSelectedQuality().getId() && df.getServer().equalsIgnoreCase(baseServer))
                    .findFirst()
                    .orElse(null);

            if (selectedFile == null) {
                continue;
            }

            DownloadBasketItem basketItem = new DownloadBasketItem(
                    episode.getId(),
                    currentSeries.getName() + " - S" + context.season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                    "episode",
                    episode.getSelectedQuality().getQuality(),
                    selectedFile.getLink(),
                    selectedFile.getServer(),
                    currentSeries.getName(),
                    context.season.getSeasonNumber(),
                    episode.getEpisodeNumber()
            );

            downloadBasket.add(basketItem);
            addedCount++;
        }

        context.episodesTable.refresh();

        if (addedCount > 0) {
            showConfirmation(addedCount + " episodes from Season " + context.season.getSeasonNumber() + " added to the basket");
        } else {
            showAlert(Alert.AlertType.INFORMATION, "No Episodes Added",
                    "No new episodes were added to the basket. They might already be in the basket or no compatible servers were found.");
        }
    }


    private TabPane findTabPane(Node node) {
        if (node == null) {
            return null;
        }

        if (node instanceof TabPane) {
            return (TabPane) node;
        }

        Parent parent = node.getParent();
        while (parent != null) {
            if (parent instanceof TabPane) {
                return (TabPane) parent;
            }
            parent = parent.getParent();
        }

        return null;
    }

    // ==================== MÉTODOS AUXILIARES PARA IDIOMAS Y SERVIDORES ====================

    /**
     * Obtiene los idiomas disponibles para una película
     */
    private List<String> getAvailableLanguages(int movieId) {
        // Comprueba si ya existe un caché para este ID de película
        if (movieLanguageCache.containsKey(movieId)) {
            return movieLanguageCache.get(movieId);
        }
        // Consultar la base de datos para los idiomas disponibles para esta película
        try {
            ObservableList<DirectFile> files = getMovieDirectFilesCached(movieId);
            Set<String> languages = new HashSet<>();

            for (DirectFile file : files) {
                String language = file.getLanguage();
                if (language != null && !language.isEmpty()) {
                    languages.add(language);
                }
            }

            List<String> result = new ArrayList<>(languages);
            // Guardar en caché
            movieLanguageCache.put(movieId, result);
            // Retornar el resultado sin imprimir en consola para mejorar rendimiento
            return result;
        } catch (Exception e) {
            System.err.println("Error getting available languages: " + e.getMessage());
            e.printStackTrace();
            List<String> fallback = Arrays.asList("Audio Español", "Audio Latino", "Subtítulo Español");
            movieLanguageCache.put(movieId, fallback);
            return fallback;
        }
    }

    /**
     * Obtiene los servidores disponibles para un idioma específico de una película
     */
    private List<String> getServersForLanguage(int movieId, String language) {
        // Comprobar si ya hay un caché para esta combinación de película e idioma
        if (movieServerCache.containsKey(movieId)) {
            Map<String, List<String>> byLanguage = movieServerCache.get(movieId);
            if (byLanguage.containsKey(language)) {
                return byLanguage.get(language);
            }
        }
        // Consultar la base de datos para servidores basados en idioma para esta película
        try {
            ObservableList<DirectFile> files = getMovieDirectFilesCached(movieId);
            Map<String, List<DirectFile>> groupedByServer = new LinkedHashMap<>();

            // Agrupar los enlaces por servidor manteniendo el orden de inserción
            for (DirectFile file : files) {
                if (!language.equals(file.getLanguage())) {
                    continue;
                }

                String server = file.getServer();
                groupedByServer.computeIfAbsent(server, k -> new ArrayList<>()).add(file);
            }

            List<String> servers = new ArrayList<>();
            Map<String, DirectFile> serverSelection = new LinkedHashMap<>();

            for (Map.Entry<String, List<DirectFile>> entry : groupedByServer.entrySet()) {
                String baseServer = entry.getKey();
                List<DirectFile> serverFiles = entry.getValue();

                if (serverFiles.size() == 1) {
                    String displayName = baseServer;
                    servers.add(displayName);
                    serverSelection.put(displayName, serverFiles.get(0));
                } else {
                    for (int i = 0; i < serverFiles.size(); i++) {
                        String displayName = baseServer + " " + (i + 1);
                        servers.add(displayName);
                        serverSelection.put(displayName, serverFiles.get(i));
                    }
                }
            }

            // Guardar en caché
            movieServerCache.computeIfAbsent(movieId, k -> new HashMap<>()).put(language, servers);
            movieServerSelectionCache.computeIfAbsent(movieId, k -> new HashMap<>()).put(language, serverSelection);
            // Retornar el resultado sin imprimir en consola para mejorar rendimiento
            return servers;
        } catch (Exception e) {
            System.err.println("Error getting servers for language: " + e.getMessage());
            e.printStackTrace();

            // Datos de respaldo en caso de error y también se almacenan en caché
            List<String> fallback;
            if ("Audio Español".equals(language)) {
                fallback = Arrays.asList("streamtape.com 1", "streamtape.com 2", "powvideo.org 1");
            } else if ("Audio Latino".equals(language)) {
                fallback = Arrays.asList("streamplay.to 1", "powvideo.org 1");
            } else {
                fallback = Arrays.asList("streamtape.com 1", "mixdrop.bz 1");
            }
            movieServerCache.computeIfAbsent(movieId, k -> new HashMap<>()).put(language, fallback);
            Map<String, Map<String, DirectFile>> byLanguage = movieServerSelectionCache.computeIfAbsent(movieId, k -> new HashMap<>());
            byLanguage.remove(language);
            return fallback;
        }
    }

    private void ensureMovieDetailsLoaded(Movie movie) {
        if (movie == null || movie.hasServerLinksLoaded()) {
            return;
        }

        try {
            ObservableList<DirectFile> files = getMovieDirectFilesCached(movie.getId());
            List<Movie.ServerLink> links = files.stream()
                    .map(file -> new Movie.ServerLink(
                            file.getLanguage(),
                            file.getServer(),
                            file.getLink(),
                            file.getQuality()))
                    .collect(Collectors.toList());

            movie.loadServerLinks(links);

            if (movie.getAvailableLanguages().isEmpty()) {
                movie.setAvailableLanguages(getAvailableLanguages(movie.getId()));
            }
        } catch (Exception e) {
            System.err.println("Error loading movie details: " + e.getMessage());
        }
    }

    /**
     * Devuelve la lista de DirectFile para una película desde caché. Si no
     * existe en caché se consulta a la base de datos y se almacena.
     *
     * @param movieId identificación de la película
     * @return lista de DirectFile para esa película
     */
    private ObservableList<DirectFile> getMovieDirectFilesCached(int movieId) {
        if (movieDirectFilesCache.containsKey(movieId)) {
            return movieDirectFilesCache.get(movieId);
        }
        // Consultar la base de datos una sola vez y almacenar
        ObservableList<DirectFile> files = connectDataBase.getMovieDirectFiles(movieId);
        movieDirectFilesCache.put(movieId, files);
        return files;
    }

    private void preloadEpisodeData(List<Episode> episodes) throws Exception {
        for (Episode episode : episodes) {
            int episodeId = episode.getId();

            List<String> languages = loadEpisodeLanguagesFromDb(episodeId);
            episodeLanguagesCache.put(episodeId, languages);

            Map<String, List<String>> serversByLanguage = new HashMap<>();
            for (String language : languages) {
                List<String> serversForLanguage = loadServersForEpisodeLanguageFromDb(episodeId, language);
                serversByLanguage.put(language, serversForLanguage);
            }
            episodeServersByLanguageCache.put(episodeId, serversByLanguage);

            List<String> allServers = loadAllServersForEpisodeFromDb(episodeId);
            episodeServersCache.put(episodeId, allServers);

            Set<String> serversToFetchQualities = new HashSet<>();
            allServers.stream()
                    .map(this::normalizeServerKey)
                    .forEach(serversToFetchQualities::add);
            serversByLanguage.values().stream()
                    .flatMap(Collection::stream)
                    .map(this::normalizeServerKey)
                    .forEach(serversToFetchQualities::add);

            Map<String, List<ConnectDataBase.Quality>> qualitiesByServer = new HashMap<>();
            for (String server : serversToFetchQualities) {
                List<ConnectDataBase.Quality> qualities = loadQualitiesForServerFromDb(episodeId, server);
                qualitiesByServer.put(server, qualities);
            }
            episodeQualitiesCache.put(episodeId, qualitiesByServer);
        }
    }

    private void clearEpisodeCachesForSeason(int seasonId) {
        ObservableList<Episode> previousEpisodes = episodesBySeason.get(seasonId);
        if (previousEpisodes != null) {
            previousEpisodes.forEach(episode -> removeEpisodeFromCaches(episode.getId()));
        }
    }

    private void clearAllEpisodeCaches() {
        episodeLanguagesCache.clear();
        episodeServersCache.clear();
        episodeServersByLanguageCache.clear();
        episodeQualitiesCache.clear();
    }

    private void removeEpisodeFromCaches(int episodeId) {
        episodeLanguagesCache.remove(episodeId);
        episodeServersCache.remove(episodeId);
        episodeServersByLanguageCache.remove(episodeId);
        episodeQualitiesCache.remove(episodeId);
    }

    private String normalizeServerKey(String serverWithOptionalIndex) {
        if (serverWithOptionalIndex == null) {
            return "";
        }
        int spaceIndex = serverWithOptionalIndex.indexOf(' ');
        if (spaceIndex == -1) {
            return serverWithOptionalIndex;
        }
        return serverWithOptionalIndex.substring(0, spaceIndex);
    }

    private List<String> defaultEpisodeLanguages() {
        List<String> languages = new ArrayList<>();
        languages.add("Audio Español");
        languages.add("Audio Latino");
        languages.add("Subtítulo Español");
        return languages;
    }

    private List<String> loadEpisodeLanguagesFromDb(int episodeId) throws Exception {
        String query = "SELECT DISTINCT language FROM links_files_download WHERE episode_id = ?";
        List<String> languages = new ArrayList<>();

        try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
            stmt.setInt(1, episodeId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String language = rs.getString("language");
                if (language != null && !language.isEmpty()) {
                    languages.add(language);
                }
            }
        }

        if (languages.isEmpty()) {
            languages.addAll(defaultEpisodeLanguages());
        }

        System.out.println("Available languages for episode " + episodeId + ": " + languages);
        return languages;
    }

    private List<String> loadServersForEpisodeLanguageFromDb(int episodeId, String language) throws Exception {
        String query = "SELECT DISTINCT s.name as server FROM links_files_download l " +
                "JOIN servers s ON l.server_id = s.id " +
                "WHERE l.episode_id = ? AND l.language = ?";
        Map<String, Integer> serverCounts = new HashMap<>();

        try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
            stmt.setInt(1, episodeId);
            stmt.setString(2, language);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String server = rs.getString("server");
                serverCounts.put(server, serverCounts.getOrDefault(server, 0) + 1);
            }
        }

        List<String> servers = buildServerListWithIndexes(serverCounts);
        if (servers.isEmpty()) {
            if ("Audio Español".equals(language)) {
                servers.add("streamtape.com");
                servers.add("powvideo.org");
            } else if ("Audio Latino".equals(language)) {
                servers.add("streamplay.to");
                servers.add("mixdrop.bz");
            } else {
                servers.add("streamtape.com");
                servers.add("powvideo.org");
            }
        }

        System.out.println("Servers for episode " + episodeId + " and language " + language + ": " + servers);
        return servers;
    }

    private List<String> loadAllServersForEpisodeFromDb(int episodeId) throws Exception {
        String query = "SELECT DISTINCT s.name as server FROM links_files_download l " +
                "JOIN servers s ON l.server_id = s.id " +
                "WHERE l.episode_id = ?";
        Map<String, Integer> serverCounts = new HashMap<>();

        try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
            stmt.setInt(1, episodeId);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                String server = rs.getString("server");
                serverCounts.put(server, serverCounts.getOrDefault(server, 0) + 1);
            }
        }

        List<String> servers = buildServerListWithIndexes(serverCounts);
        if (servers.isEmpty()) {
            servers.add("streamtape.com");
            servers.add("powvideo.org");
            servers.add("streamplay.to");
            servers.add("mixdrop.bz");
        }

        System.out.println("Available servers for episode " + episodeId + ": " + servers);
        return servers;
    }

    private List<String> buildServerListWithIndexes(Map<String, Integer> serverCounts) {
        List<String> servers = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : serverCounts.entrySet()) {
            String server = entry.getKey();
            int count = entry.getValue();

            if (count == 1) {
                servers.add(server);
            } else {
                for (int i = 1; i <= count; i++) {
                    servers.add(server + " " + i);
                }
            }
        }
        return servers;
    }

    private List<ConnectDataBase.Quality> loadQualitiesForServerFromDb(int episodeId, String serverWithOptionalIndex) throws Exception {
        String baseServer = normalizeServerKey(serverWithOptionalIndex);
        String query = "SELECT DISTINCT q.quality_id, q.quality FROM links_files_download l " +
                "JOIN qualities q ON l.quality_id = q.quality_id " +
                "JOIN servers s ON l.server_id = s.id " +
                "WHERE l.episode_id = ? AND s.name = ?";
        List<ConnectDataBase.Quality> qualities = new ArrayList<>();

        try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
            stmt.setInt(1, episodeId);
            stmt.setString(2, baseServer);
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                int qualityId = rs.getInt("quality_id");
                String qualityName = rs.getString("quality");
                qualities.add(new ConnectDataBase.Quality(qualityId, qualityName));
            }
        }

        if (qualities.isEmpty()) {
            qualities.addAll(new ArrayList<>(connectDataBase.getQualities()));
        }

        System.out.println("Qualities for episode " + episodeId + " and server " + baseServer + ": " + qualities);
        return qualities;
    }

    /**
     * Obtiene los idiomas disponibles para un episodio
     */
    private List<String> getAvailableEpisodeLanguages(int episodeId) {
        List<String> cached = episodeLanguagesCache.get(episodeId);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> languages = loadEpisodeLanguagesFromDb(episodeId);
            episodeLanguagesCache.put(episodeId, languages);
            return languages;
        } catch (Exception e) {
            System.err.println("Error getting available episode languages: " + e.getMessage());
            e.printStackTrace();
            List<String> defaultLanguages = defaultEpisodeLanguages();
            episodeLanguagesCache.put(episodeId, defaultLanguages);
            return defaultLanguages;
        }
    }

    /**
     * Obtiene los servidores disponibles para un idioma específico de un episodio
     */
    private List<String> getServersForEpisodeLanguage(int episodeId, String language) {
        Map<String, List<String>> serversByLanguage = episodeServersByLanguageCache.computeIfAbsent(episodeId, id -> new HashMap<>());
        List<String> cached = serversByLanguage.get(language);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> servers = loadServersForEpisodeLanguageFromDb(episodeId, language);
            serversByLanguage.put(language, servers);
            return servers;
        } catch (Exception e) {
            System.err.println("Error getting servers for episode language: " + e.getMessage());
            e.printStackTrace();
            List<String> defaultServers = new ArrayList<>();
            if ("Audio Español".equals(language)) {
                defaultServers.add("streamtape.com");
                defaultServers.add("powvideo.org");
            } else if ("Audio Latino".equals(language)) {
                defaultServers.add("streamplay.to");
                defaultServers.add("mixdrop.bz");
            } else {
                defaultServers.add("streamtape.com");
                defaultServers.add("powvideo.org");
            }
            serversByLanguage.put(language, defaultServers);
            return defaultServers;
        }
    }

    /**
     * Obtiene todos los servidores disponibles para un episodio
     */
    private List<String> getAvailableEpisodeServers(int episodeId) {
        List<String> cached = episodeServersCache.get(episodeId);
        if (cached != null) {
            return cached;
        }
        try {
            List<String> servers = loadAllServersForEpisodeFromDb(episodeId);
            episodeServersCache.put(episodeId, servers);
            return servers;
        } catch (Exception e) {
            System.err.println("Error getting available episode servers: " + e.getMessage());
            e.printStackTrace();
            List<String> defaultServers = new ArrayList<>();
            defaultServers.add("streamtape.com");
            defaultServers.add("powvideo.org");
            defaultServers.add("streamplay.to");
            defaultServers.add("mixdrop.bz");
            episodeServersCache.put(episodeId, defaultServers);
            return defaultServers;
        }
    }

    /**
     * Obtiene las calidades disponibles para un servidor específico de un episodio
     */
    private List<ConnectDataBase.Quality> getQualitiesForServer(int episodeId, String serverWithIndex) {
        String baseServer = normalizeServerKey(serverWithIndex);
        Map<String, List<ConnectDataBase.Quality>> qualitiesByServer = episodeQualitiesCache.computeIfAbsent(episodeId, id -> new HashMap<>());
        List<ConnectDataBase.Quality> cached = qualitiesByServer.get(baseServer);
        if (cached != null) {
            return cached;
        }
        try {
            List<ConnectDataBase.Quality> qualities = loadQualitiesForServerFromDb(episodeId, baseServer);
            qualitiesByServer.put(baseServer, qualities);
            return qualities;
        } catch (Exception e) {
            System.err.println("Error getting qualities for server: " + e.getMessage());
            e.printStackTrace();
            List<ConnectDataBase.Quality> defaultQualities = new ArrayList<>(connectDataBase.getQualities());
            qualitiesByServer.put(baseServer, defaultQualities);
            return defaultQualities;
        }
    }

    /**
     * Verifica si un servidor es compatible para descarga directa
     */
    private boolean isServerCompatible(String server) {
        if (server == null || server.isEmpty()) {
            return false;
        }

        // Extraer el nombre base del servidor (eliminar cualquier numeración como "Streamtape 1")
        String baseServer = server.split(" ")[0].toLowerCase();

        // Verificar si el servidor está en la lista de compatibles
        for (String compatibleServer : compatibleServers) {
            if (compatibleServer.toLowerCase().contains(baseServer) || baseServer.contains(compatibleServer.toLowerCase())) {
                return true;
            }
        }

        return false;
    }

    private boolean isManualServer(String server) {
        if (server == null || server.isEmpty()) {
            return false;
        }
        String lower = server.toLowerCase();
        return lower.contains("powvideo") || lower.contains("streamplay");
    }

    /**
     * Obtiene el enlace para un servidor e idioma específicos
     */
    private String getLinkForServerAndLanguage(int movieId, String serverWithIndex, String language) {
        // Extraer el nombre base del servidor y el índice
        String[] parts = serverWithIndex.split(" ");
        String baseServer = parts[0];
        int index = parts.length > 1 ? Integer.parseInt(parts[1]) - 1 : 0;

        try {
            Map<String, Map<String, DirectFile>> byLanguage = movieServerSelectionCache.get(movieId);
            if (byLanguage != null) {
                Map<String, DirectFile> serverMapping = byLanguage.get(language);
                if (serverMapping != null) {
                    DirectFile file = serverMapping.get(serverWithIndex);
                    if (file == null && index >= 0) {
                        // Compatibilidad con servidores sin enumeración almacenados
                        String fallbackKey = baseServer;
                        if (index > 0) {
                            fallbackKey = baseServer + " " + (index + 1);
                        }
                        file = serverMapping.get(fallbackKey);
                    }
                    if (file != null) {
                        return file.getLink();
                    }
                }
            }

            ObservableList<DirectFile> files = getMovieDirectFilesCached(movieId);
            List<DirectFile> filteredFiles = files.stream()
                    .filter(f -> f.getServer().equalsIgnoreCase(baseServer) && language.equals(f.getLanguage()))
                    .collect(Collectors.toList());

            if (!filteredFiles.isEmpty()) {
                movieServerSelectionCache
                        .computeIfAbsent(movieId, k -> new HashMap<>())
                        .computeIfAbsent(language, k -> new LinkedHashMap<>());

                Map<String, DirectFile> mapping = movieServerSelectionCache.get(movieId).get(language);
                for (int i = 0; i < filteredFiles.size(); i++) {
                    String displayName = baseServer + (filteredFiles.size() > 1 ? " " + (i + 1) : "");
                    mapping.put(displayName.trim(), filteredFiles.get(i));
                }

                return (index < filteredFiles.size()) ? filteredFiles.get(index).getLink() : "";
            }

            return "";
        } catch (Exception e) {
            System.err.println("Error getting link for server and language: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    /**
     * Obtiene la calidad para un enlace específico
     */
    private String getQualityForLink(int movieId, String link) {
        try {
            ObservableList<DirectFile> files = getMovieDirectFilesCached(movieId);
            return files.stream()
                    .filter(f -> f.getLink().equals(link))
                    .map(DirectFile::getQuality)
                    .findFirst()
                    .orElse("");
        } catch (Exception e) {
            System.err.println("Error getting quality for link: " + e.getMessage());
            e.printStackTrace();
            return "";
        }
    }

    /**
     * Obtiene la pestaña principal
     */
    public Tab getTab() {
        return tab;
    }

    /**
     * Muestra una alerta
     */
    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    /**
     * Muestra una confirmación
     */
    private void showConfirmation(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Confirmación");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
    }

    // ==================== CLASES DE MODELO ====================

    /**
     * Clase para representar una película
     */
    public static class Movie {
        private final IntegerProperty id = new SimpleIntegerProperty();
        private final StringProperty title = new SimpleStringProperty();
        private final StringProperty year = new SimpleStringProperty();
        private final StringProperty genre = new SimpleStringProperty();
        private final StringProperty selectedLanguage = new SimpleStringProperty();
        private final StringProperty selectedServer = new SimpleStringProperty();
        private final StringProperty link = new SimpleStringProperty();
        private final StringProperty quality = new SimpleStringProperty();
        private final ObservableList<String> availableLanguages = FXCollections.observableArrayList();
        private final ObservableList<String> availableServers = FXCollections.observableArrayList();
        private final Map<String, List<ServerLink>> serverLinksByLanguage = new HashMap<>();
        private boolean serverLinksLoaded = false;

        public static class ServerLink {
            private final String language;
            private final String server;
            private final String link;
            private final String quality;

            private final String displayName;

            public ServerLink(String language, String server, String link, String quality) {
                this(language, server, link, quality, null);
            }

            private ServerLink(String language, String server, String link, String quality, String displayName) {
                this.language = language;
                this.server = server;
                this.link = link;
                this.quality = quality;
                this.displayName = displayName;
            }

            public ServerLink withDisplayName(String displayName) {
                return new ServerLink(language, server, link, quality, displayName);
            }

            // Getters
            public String getLanguage() { return language; }
            public String getServer() { return server; }
            public String getLink() { return link; }
            public String getQuality() { return quality; }
            public String getDisplayName() { return displayName != null ? displayName : server; }
        }

        public Movie(int id, String title, String year, String genre, String language, String quality, String server, String link) {
            this.id.set(id);
            this.title.set(title);
            this.year.set(year);
            this.genre.set(genre);
            this.selectedLanguage.set(language);
            this.quality.set(quality);
            this.selectedServer.set(server);
            this.link.set(link);

            // Añadir valores iniciales a las listas
            if (language != null && !language.isEmpty()) {
                this.availableLanguages.add(language);
            }
            if (server != null && !server.isEmpty()) {
                this.availableServers.add(server);
            }

            setupListeners();
        }

        private void setupListeners() {
            // Listener para cambios de idioma
            selectedLanguage.addListener((obs, oldLang, newLang) -> {
                if (newLang != null && !newLang.isEmpty()) {
                    updateAvailableServers(newLang);
                } else {
                    availableServers.clear();
                }
            });

            // Listener para cambios de servidor
            selectedServer.addListener((obs, oldServer, newServer) -> {
                if (newServer != null && !newServer.isEmpty()) {
                    applyServerSelection(newServer);
                } else {
                    link.set(null);
                    quality.set(null);
                }
            });
        }

        private void updateAvailableServers(String language) {
            availableServers.clear();
            List<ServerLink> links = serverLinksByLanguage.get(language);
            if (links != null) {
                for (ServerLink link : links) {
                    String displayName = link.getDisplayName();
                    if (!availableServers.contains(displayName)) {
                        availableServers.add(displayName);
                    }
                }
            }
            if (!availableServers.isEmpty()) {
                String current = selectedServer.get();
                if (current == null || !availableServers.contains(current)) {
                    selectedServer.set(availableServers.get(0));
                } else {
                    applyServerSelection(current);
                }
            }
        }

        public void setAvailableLanguages(List<String> languages) {
            availableLanguages.setAll(languages != null ? languages : Collections.emptyList());

            if (availableLanguages.isEmpty()) {
                selectedLanguage.set(null);
                availableServers.clear();
            } else if (selectedLanguage.get() == null || !availableLanguages.contains(selectedLanguage.get())) {
                selectedLanguage.set(availableLanguages.get(0));
            } else {
                updateAvailableServers(selectedLanguage.get());
            }
        }

        public void setAvailableServers(List<String> servers) {
            availableServers.setAll(servers != null ? servers : Collections.emptyList());

            if (availableServers.isEmpty()) {
                selectedServer.set(null);
            } else if (selectedServer.get() == null || !availableServers.contains(selectedServer.get())) {
                selectedServer.set(availableServers.get(0));
            }
        }

        public void loadServerLinks(List<ServerLink> links) {
            serverLinksByLanguage.clear();
            availableLanguages.clear();
            availableServers.clear();

            Map<String, List<ServerLink>> groupedLinks = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> counters = new LinkedHashMap<>();

            for (ServerLink link : links) {
                if (link.getLanguage() == null || link.getLanguage().isEmpty()) {
                    continue;
                }

                Map<String, Integer> serverCount = counters
                        .computeIfAbsent(link.getLanguage(), k -> new LinkedHashMap<>());
                int index = serverCount.merge(link.getServer().toLowerCase(), 1, Integer::sum);
                String displayName = index > 1 ? link.getServer() + " " + index : link.getServer();

                groupedLinks
                        .computeIfAbsent(link.getLanguage(), k -> new ArrayList<>())
                        .add(link.withDisplayName(displayName));
            }

            serverLinksByLanguage.putAll(groupedLinks);
            availableLanguages.addAll(groupedLinks.keySet());
            serverLinksLoaded = true;

            if (!availableLanguages.isEmpty()) {
                if (selectedLanguage.get() == null || !availableLanguages.contains(selectedLanguage.get())) {
                    selectedLanguage.set(availableLanguages.get(0));
                } else {
                    updateAvailableServers(selectedLanguage.get());
                }
            } else {
                selectedLanguage.set(null);
                availableServers.clear();
            }
        }

        private void applyServerSelection(String serverDisplayName) {
            String currentLanguage = selectedLanguage.get();
            if (currentLanguage == null || currentLanguage.isEmpty()) {
                return;
            }

            List<ServerLink> links = serverLinksByLanguage.get(currentLanguage);
            if (links == null || links.isEmpty()) {
                return;
            }

            for (ServerLink serverLink : links) {
                if (serverLink.getDisplayName().equalsIgnoreCase(serverDisplayName)) {
                    link.set(serverLink.getLink());
                    quality.set(serverLink.getQuality());
                    if (!Objects.equals(selectedServer.get(), serverLink.getDisplayName())) {
                        selectedServer.set(serverLink.getDisplayName());
                    }
                    return;
                }
            }

            String fallback = links.get(0).getDisplayName();
            if (!Objects.equals(fallback, serverDisplayName)) {
                selectedServer.set(fallback);
            }
        }

        public boolean hasServerLinksLoaded() {
            return serverLinksLoaded;
        }

        public void ensureServersForSelectedLanguage() {
            String currentLanguage = selectedLanguage.get();
            if (currentLanguage != null && !currentLanguage.isEmpty()) {
                updateAvailableServers(currentLanguage);
            }
        }

        // Métodos de validación
        public boolean isValidForDownload() {
            return !link.get().isEmpty() && isServerCompatible(selectedServer.get()) && checkLinkAvailability(link.get());
        }

        private boolean isServerCompatible(String server) {
            if (server == null || server.isEmpty()) {
                return false;
            }
            String baseServer = server.split(" ")[0].toLowerCase();
            return Arrays.asList("streamtape.com", "powvideo.org", "streamplay.to", "mixdrop.bz").contains(baseServer);
        }

        private boolean checkLinkAvailability(String url) {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod("HEAD");
                return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
            } catch (Exception e) {
                return false;
            }
        }

        // Propiedades observables
        public IntegerProperty idProperty() { return id; }
        public StringProperty titleProperty() { return title; }
        public StringProperty yearProperty() { return year; }
        public StringProperty genreProperty() { return genre; }
        public StringProperty selectedLanguageProperty() { return selectedLanguage; }
        public StringProperty selectedServerProperty() { return selectedServer; }
        public StringProperty linkProperty() { return link; }
        public StringProperty qualityProperty() { return quality; }
        public ObservableList<String> getAvailableLanguages() { return availableLanguages; }
        public ObservableList<String> getAvailableServers() { return availableServers; }

        // Getters directos
        public int getId() { return id.get(); }
        public String getTitle() { return title.get(); }
        public String getYear() { return year.get(); }
        public String getGenre() { return genre.get(); }
        public String getSelectedLanguage() { return selectedLanguage.get(); }
        public String getSelectedServer() { return selectedServer.get(); }
        public String getLink() { return link.get(); }
        public String getQuality() { return quality.get(); }
        public String getServer() { return selectedServer.get(); }

        @Override
        public String toString() {
            return String.format("%s (%s) [%s/%s]", title.get(), year.get(), selectedLanguage.get(), quality.get());
        }
    }

    /**
     * Clase para representar un episodio
     */
    public static class Episode {
        private final int id;
        private final int seasonId;
        private final int episodeNumber;
        private final String title;
        private final BooleanProperty selected = new SimpleBooleanProperty(false);

        // Propiedades para almacenar las selecciones
        private final StringProperty selectedLanguage = new SimpleStringProperty();
        private final StringProperty selectedServer = new SimpleStringProperty();
        private final ObjectProperty<ConnectDataBase.Quality> selectedQuality = new SimpleObjectProperty<>();

        public Episode(int id, int seasonId, int episodeNumber, String title) {
            this.id = id;
            this.seasonId = seasonId;
            this.episodeNumber = episodeNumber;
            this.title = title;
        }

        // Getters y setters existentes
        public int getId() { return id; }
        public int getSeasonId() { return seasonId; }
        public int getEpisodeNumber() { return episodeNumber; }
        public String getTitle() { return title; }
        public boolean isSelected() { return selected.get(); }
        public void setSelected(boolean selected) { this.selected.set(selected); }
        public BooleanProperty selectedProperty() { return selected; }

        // Getters y setters para las propiedades de selección
        public String getSelectedLanguage() { return selectedLanguage.get(); }
        public void setSelectedLanguage(String language) { selectedLanguage.set(language); }
        public StringProperty selectedLanguageProperty() { return selectedLanguage; }

        public String getSelectedServer() { return selectedServer.get(); }
        public void setSelectedServer(String server) { selectedServer.set(server); }
        public StringProperty selectedServerProperty() { return selectedServer; }

        public ConnectDataBase.Quality getSelectedQuality() { return selectedQuality.get(); }
        public void setSelectedQuality(ConnectDataBase.Quality quality) { selectedQuality.set(quality); }
        public ObjectProperty<ConnectDataBase.Quality> selectedQualityProperty() { return selectedQuality; }
    }

    /**
     * Clase para representar un archivo directo
     */
    public static class DirectFile {
        private final int id;
        private final int fileId;
        private final Integer episodeId;
        private final int qualityId;
        private final String link;
        private final String language;
        private final String server;
        private final String quality;

        public DirectFile(int id, int fileId, Integer episodeId, int qualityId, String link, String language, String server, String quality) {
            this.id = id;
            this.fileId = fileId;
            this.episodeId = episodeId;
            this.qualityId = qualityId;
            this.link = link;
            this.language = language;
            this.server = server;
            this.quality = quality;
        }

        public int getId() { return id; }
        public int getFileId() { return fileId; }
        public Integer getEpisodeId() { return episodeId; }
        public int getQualityId() { return qualityId; }
        public String getLink() { return link; }
        public String getLanguage() { return language; }
        public String getServer() { return server; }
        public String getQuality() { return quality; }
    }

    /**
     * Clase para representar una serie
     */
    public static class Series {
        private final int id;
        private final String name;
        private final String year;
        private final String rating;
        private final String genre;

        public Series(int id, String name, String year, String rating, String genre) {
            this.id = id;
            this.name = name;
            this.year = year;
            this.rating = rating;
            this.genre = genre;
        }

        public int getId() { return id; }
        public String getName() { return name; }
        public String getYear() { return year; }
        public String getRating() { return rating; }
        public String getGenre() { return genre; }
    }
}
