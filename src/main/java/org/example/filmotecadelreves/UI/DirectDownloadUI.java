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
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import javafx.application.Platform;
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
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.CompletableFuture;
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
    private static ConnectDataBase connectDataBase;

    // ==================== COMPONENTES DE UI ====================
    // Componentes de búsqueda
    private ComboBox filterComboBox;
    private ComboBox filterOptionsComboBox;
    private VBox seriesLayout;
    private TableView seriesTable;
    private TableView moviesTable;
    private VBox busquedaSection;

    // ==================== DOWNLOADERS ====================
    private StreamtapeDownloader streamtapeDownloader;
    private SeleniumPowvideo powvideoDownloader;
    private SeleniumStreamplay streamplayDownloader;
    private MixdropDownloader mixdropDownloader;
    private VideoStream videoStream;
    private Map<String, DirectDownloader> downloaders = new HashMap<>();

    // ==================== CESTA DE DESCARGAS ====================
    private final ObservableList<DownloadBasketItem> downloadBasket = FXCollections.observableArrayList();
    private TableView<DownloadBasketItem> basketTable;

    // ==================== DATOS DE NAVEGACIÓN ====================
    private Series currentSeries;
    private int currentSeason;
    private final Map<Integer, List<Episode>> episodesBySeason = new HashMap<>();
    private final Map<Integer, List<DirectFile>> filesByEpisode = new HashMap<>();

    // ==================== CACHÉ PARA ENLACES DIRECTOS DE PELÍCULAS ====================
    /**
     * Cache local que almacena la lista de enlaces directos (DirectFile) por
     * identificación de película. Esto evita que se repitan las consultas a
     * la base de datos para obtener los enlaces de una misma película cada
     * vez que el usuario interactúa con los combos de idioma/servidor.
     */
    private final Map<Integer, ObservableList<DirectFile>> movieDirectFilesCache = new HashMap<>();

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
    public DirectDownloadUI(AjustesUI ajustesUI, DescargasUI descargasUI, Stage primaryStage) {
        this.ajustesUI = ajustesUI;
        this.descargasUI = descargasUI;
        // Use the database path specified in the user settings instead of a hardcoded name
        this.connectDataBase = new ConnectDataBase(ajustesUI.getDirectDatabasePath());

        // Inicializar downloaders
        initializeDownloaders();

        // Configurar la interfaz de usuario
        setupUI(primaryStage);

        // Cargar datos iniciales
        loadInitialData();
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
        this.mixdropDownloader = new MixdropDownloader();
        this.videoStream = new VideoStream();

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
        // Cargar películas recientes
        ObservableList<Movie> latestMovies = connectDataBase.getLatestMovies(10);
        if (moviesTable != null) {
            moviesTable.setItems(latestMovies);
        }

        // Cargar series recientes
        ObservableList<Series> latestSeries = connectDataBase.getLatestSeries(10, DirectDownloadUI.Series.class);
        if (seriesTable != null) {
            seriesTable.setItems(latestSeries);
        }

        System.out.println("Initial data loaded: " + latestMovies.size() + " movies, " + latestSeries.size() + " series");
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
        addToDownloadsButton.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(downloadBasket));

        HBox buttons = new HBox(10, viewBasketButton, addToDownloadsButton);

        basketSection.getChildren().addAll(basketInfo, new Pane(), buttons);
        HBox.setHgrow(basketSection.getChildren().get(1), Priority.ALWAYS);

        return basketSection;
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

        if (ajustesUI.getMovieDestination().isEmpty() || ajustesUI.getSeriesDestination().isEmpty()) {
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
                            String seriesPath = ajustesUI.getSeriesDestination() + File.separator + episode.getSeriesName();
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
        try {
            // Use our VideoStream class instead of the default browser
            videoStream.stream(url);
        } catch (Exception e) {
            System.err.println("Error opening browser: " + e.getMessage());
            e.printStackTrace();
            showAlert(Alert.AlertType.ERROR, "Error", "Could not open browser: " + e.getMessage());
        }
    }

    /**
     * Abre el contenido en el navegador para streaming con un ID de servidor específico
     */
    private void streamContent(String url, int serverId) {
        try {
            // Use our VideoStream class with server ID
            videoStream.stream(url, serverId);
        } catch (Exception e) {
            System.err.println("Error opening browser: " + e.getMessage());
            e.printStackTrace();
            showAlert(Alert.AlertType.ERROR, "Error", "Could not open browser: " + e.getMessage());
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
        TextField searchField = new TextField();
        searchField.setPromptText("Search...");

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
        searchButton.setOnAction(e -> performSearch(searchField.getText()));

        searchSection.getChildren().addAll(searchLabel, searchField, filtersLayout, searchButton);

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
                        String newLanguage = comboBox.getValue();

                        // Actualizar el idioma seleccionado
                        movie.selectedLanguageProperty().set(newLanguage);

                        // Cargar los servidores disponibles para este idioma
                        List<String> servers = getServersForLanguage(movie.getId(), newLanguage);
                        movie.setAvailableServers(servers);

                        // Actualizar la tabla para refrescar el combobox de servidores
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
                        // Obtener los idiomas disponibles para esta película. Si la película ya tiene
                        // idiomas precalculados (establecidos desde la consulta inicial), usarlos para
                        // evitar nuevas consultas a la base de datos. En caso contrario, consultar.
                        List<String> languages;
                        if (movie.getAvailableLanguages() != null && !movie.getAvailableLanguages().isEmpty()) {
                            languages = movie.getAvailableLanguages();
                        } else {
                            languages = getAvailableLanguages(movie.getId());
                            // Guardar en la película para uso futuro
                            movie.setAvailableLanguages(languages);
                        }

                        // Limpiar y configurar los elementos
                        comboBox.getItems().clear();
                        comboBox.getItems().addAll(languages);

                        // Establecer el valor seleccionado
                        if (item != null && !item.isEmpty() && comboBox.getItems().contains(item)) {
                            comboBox.setValue(item);
                        } else if (!comboBox.getItems().isEmpty()) {
                            comboBox.setValue(comboBox.getItems().get(0));
                            movie.selectedLanguageProperty().set(comboBox.getValue());

                            // Cargar los servidores disponibles para este idioma. Si ya están
                            // precalculados para la película e idioma seleccionado, reutilizarlos.
                            List<String> servers;
                            if (movie.getAvailableServers() != null && !movie.getAvailableServers().isEmpty()) {
                                servers = movie.getAvailableServers();
                            } else {
                                servers = getServersForLanguage(movie.getId(), comboBox.getValue());
                                movie.setAvailableServers(servers);
                            }
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
                        String newServer = comboBox.getValue();

                        // Actualizar el servidor seleccionado
                        movie.selectedServerProperty().set(newServer);

                        // Actualizar el enlace y la calidad basados en el servidor seleccionado
                        String selectedLanguage = movie.getSelectedLanguage();
                        String selectedServer = newServer;

                        // Obtener el enlace para este servidor y idioma
                        String link = getLinkForServerAndLanguage(movie.getId(), selectedServer, selectedLanguage);
                        if (link != null && !link.isEmpty()) {
                            movie.linkProperty().set(link);

                            // También actualizar la calidad si es necesario
                            String quality = getQualityForLink(movie.getId(), link);
                            if (quality != null && !quality.isEmpty()) {
                                movie.qualityProperty().set(quality);
                            }
                        }
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
                        // Obtener los servidores disponibles para el idioma seleccionado.
                        String selectedLanguage = movie.getSelectedLanguage();
                        List<String> servers;
                        if (movie.getAvailableServers() != null && !movie.getAvailableServers().isEmpty()) {
                            servers = movie.getAvailableServers();
                        } else {
                            servers = getServersForLanguage(movie.getId(), selectedLanguage);
                            movie.setAvailableServers(servers);
                        }

                        // Limpiar y configurar los elementos
                        comboBox.getItems().clear();
                        comboBox.getItems().addAll(servers);

                        // Establecer el valor seleccionado
                        if (item != null && !item.isEmpty() && comboBox.getItems().contains(item)) {
                            comboBox.setValue(item);
                        } else if (!comboBox.getItems().isEmpty()) {
                            comboBox.setValue(comboBox.getItems().get(0));
                            movie.selectedServerProperty().set(comboBox.getValue());

                            // Actualizar el enlace y la calidad basados en el servidor seleccionado
                            String link = getLinkForServerAndLanguage(movie.getId(), comboBox.getValue(), selectedLanguage);
                            if (link != null && !link.isEmpty()) {
                                movie.linkProperty().set(link);

                                // También actualizar la calidad si es necesario
                                String quality = getQualityForLink(movie.getId(), link);
                                if (quality != null && !quality.isEmpty()) {
                                    movie.qualityProperty().set(quality);
                                }
                            }
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

        layout.getChildren().addAll(searchSection, resultsLabel, moviesTable);
        VBox.setVgrow(moviesTable, Priority.ALWAYS);

        // Cargar datos iniciales
        loadInitialMoviesData();

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
            private final HBox buttons = new HBox(5, downloadBtn, streamBtn);

            {
                downloadBtn.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                streamBtn.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");

                downloadBtn.setOnAction(e -> handleDownload(getTableRow().getItem()));
                streamBtn.setOnAction(e -> handleStream(getTableRow().getItem()));
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

                    setGraphic(buttons);
                }
            }
        };
    }

    /**
     * Realiza la búsqueda de películas con filtros
     */
    private void performSearch(String searchValue) {
        String filterType = (String) filterComboBox.getValue();
        String filterValue = (String) filterOptionsComboBox.getValue();

        // Realizar la búsqueda en un hilo en segundo plano para evitar bloquear el hilo de la UI.
        Task<ObservableList<Movie>> searchTask = new Task<>() {
            @Override
            protected ObservableList<Movie> call() {
                ObservableList<Movie> results;
                if (filterValue != null && !filterValue.isEmpty()) {
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
            }
        };

        searchTask.setOnSucceeded(e -> {
            ObservableList<Movie> results = searchTask.getValue();
            moviesTable.setItems(results);
        });
        searchTask.setOnFailed(e -> {
            // En caso de error, imprimir la excepción para depuración
            Throwable ex = searchTask.getException();
            if (ex != null) {
                ex.printStackTrace();
            }
        });

        Thread thread = new Thread(searchTask);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Carga los datos iniciales de películas
     */
    private void loadInitialMoviesData() {
        ObservableList<Movie> movies = connectDataBase.getLatestMovies(10);
        moviesTable.setItems(movies);
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

                // Check for powvideo.org (ID: 1)
                if (baseServer.contains("powvideo.org")) {
                    serverId = 1;
                }
                // Check for streamplay.to (ID: 21)
                else if (baseServer.contains("streamplay.to")) {
                    serverId = 21;
                }
                // Check for streamtape.com (ID: 497)
                else if (baseServer.contains("streamtape.com")) {
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
        TextField searchField = new TextField();
        searchField.setPromptText("Search...");

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
            String searchValue = searchField.getText();

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

        searchSection.getChildren().addAll(searchLabel, searchField, filtersLayout, searchButton);

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

        seriesTable.setRowFactory(tv -> {
            TableRow<Series> row = new TableRow<>();
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

            return row;
        });

        seriesLayout.getChildren().addAll(searchSection, resultsLabel, seriesTable);
        VBox.setVgrow(seriesTable, Priority.ALWAYS);

        seriesTab.setContent(seriesLayout);

        // Cargar datos iniciales
        ObservableList<Series> latestSeries = connectDataBase.getLatestSeries(10, DirectDownloadUI.Series.class);
        seriesTable.setItems(latestSeries);

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

        ObservableList<Series> results = connectDataBase.searchSeries(searchValue);

        // Eliminar duplicados basados en el ID
        Map<Integer, Series> uniqueSeriesMap = new HashMap<>();
        for (Series series : results) {
            uniqueSeriesMap.put(series.getId(), series);
        }

        ObservableList<Series> uniqueResults = FXCollections.observableArrayList(uniqueSeriesMap.values());
        seriesTable.setItems(uniqueResults);

        System.out.println("Results found: " + uniqueResults.size());
    }

    /**
     * Busca series con filtros
     */
    private void searchSeries(String searchValue, String year, String genre, String language) {
        System.out.println("Searching series with filters: " + searchValue + ", " + year + ", " + genre + ", " + language);

        ObservableList<Series> results = connectDataBase.searchSeriesWithFilters(searchValue, year, genre, language);

        // Eliminar duplicados basados en el ID
        Map<Integer, Series> uniqueSeriesMap = new HashMap<>();
        for (Series series : results) {
            uniqueSeriesMap.put(series.getId(), series);
        }

        ObservableList<Series> uniqueResults = FXCollections.observableArrayList(uniqueSeriesMap.values());
        seriesTable.setItems(uniqueResults);

        System.out.println("Results found: " + uniqueResults.size());
    }

    /**
     * Muestra los detalles de una serie seleccionada
     */
    private void showSeriesDetails(Series series) {
        currentSeries = series;
        seriesLayout.getChildren().clear();

        // Botón para volver a la lista de series
        Button backButton = new Button("Back to Series List");
        backButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        backButton.setOnAction(e -> {
            seriesLayout.getChildren().clear();
            seriesLayout.getChildren().addAll(busquedaSection, new Label("Results:"), seriesTable);
            VBox.setVgrow(seriesTable, Priority.ALWAYS);
        });

        // Título de la serie con información
        Label seriesLabel = new Label("Series: " + series.getName() + " (" + series.getYear() + ") - Rating: " + series.getRating());
        seriesLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 18px;");

        try {
            // Cargar temporadas desde la base de datos - CORREGIDO: Usar movie_id en lugar de series_id
            String query = "SELECT id, movie_id, season FROM series_seasons " +
                    "WHERE movie_id = ? " +
                    "ORDER BY season";

            System.out.println("Executing query for seasons with movie_id = " + series.getId());

            List<ConnectDataBase.Season> seasons = new ArrayList<>();

            try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
                stmt.setInt(1, series.getId());
                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    int id = rs.getInt("id");
                    int movieId = rs.getInt("movie_id");
                    int seasonNumber = rs.getInt("season");
                    seasons.add(new ConnectDataBase.Season(id, movieId, seasonNumber));
                }
            }

            System.out.println("Seasons found: " + seasons.size());

            if (seasons.isEmpty()) {
                Label noSeasonsLabel = new Label("No seasons found for this series.");
                seriesLayout.getChildren().addAll(backButton, seriesLabel, noSeasonsLabel);
                return;
            }

            // Crear pestañas para temporadas
            TabPane seasonsTabPane = new TabPane();
            seasonsTabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

            // Ordenar temporadas por número
            seasons.sort(Comparator.comparingInt(ConnectDataBase.Season::getSeasonNumber));

            for (ConnectDataBase.Season season : seasons) {
                Tab seasonTab = new Tab("Season " + season.getSeasonNumber());

                VBox seasonLayout = new VBox(10);
                seasonLayout.setPadding(new Insets(10));

                // Checkbox para seleccionar todos los episodios
                HBox selectAllBox = new HBox(10);
                CheckBox selectAllCheckbox = new CheckBox("Select All Episodes");
                selectAllBox.getChildren().add(selectAllCheckbox);
                selectAllBox.setAlignment(Pos.CENTER_LEFT);

                // Tabla de episodios
                TableView<Episode> episodesTable = new TableView<>();
                episodesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

                // Columna de selección
                TableColumn<Episode, Boolean> selectCol = new TableColumn<>("");
                selectCol.setCellValueFactory(param -> {
                    Episode episode = param.getValue();
                    SimpleBooleanProperty booleanProp = new SimpleBooleanProperty(episode.isSelected());

                    // Actualizar el estado de selección cuando cambia la propiedad
                    booleanProp.addListener((obs, oldValue, newValue) -> {
                        episode.setSelected(newValue);
                    });

                    return booleanProp;
                });
                selectCol.setCellFactory(p -> {
                    CheckBox checkBox = new CheckBox();

                    TableCell<Episode, Boolean> cell = new TableCell<Episode, Boolean>() {
                        @Override
                        protected void updateItem(Boolean item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty) {
                                setGraphic(null);
                            } else {
                                checkBox.setSelected(item);
                                setGraphic(checkBox);
                            }
                        }
                    };

                    checkBox.selectedProperty().addListener((obs, wasSelected, isSelected) -> {
                        if (cell.getTableRow() != null && cell.getTableRow().getItem() != null) {
                            cell.getTableRow().getItem().setSelected(isSelected);
                            cell.commitEdit(isSelected);
                        }
                    });

                    cell.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    cell.setEditable(true);
                    return cell;
                });
                selectCol.setPrefWidth(40);

                // Columna de número de episodio
                TableColumn<Episode, Integer> episodeNumberCol = new TableColumn<>("Episode");
                episodeNumberCol.setCellValueFactory(new PropertyValueFactory<>("episodeNumber"));
                episodeNumberCol.setPrefWidth(70);

                // Columna de título
                TableColumn<Episode, String> titleCol = new TableColumn<>("Title");
                titleCol.setCellValueFactory(new PropertyValueFactory<>("title"));
                titleCol.setPrefWidth(250);

                // Columna de idioma con ComboBox
                TableColumn<Episode, String> languageCol = new TableColumn<>("Language");
                languageCol.setCellValueFactory(cellData -> cellData.getValue().selectedLanguageProperty());
                languageCol.setCellFactory(column -> {
                    return new TableCell<Episode, String>() {
                        private final ComboBox<String> comboBox = new ComboBox<>();

                        {
                            comboBox.setOnAction(event -> {
                                if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                                    Episode episode = getTableRow().getItem();
                                    episode.setSelectedLanguage(comboBox.getValue());

                                    // Actualizar la tabla para refrescar las opciones de servidor
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

                                // Mantener la selección actual si existe
                                if (item != null && !item.isEmpty() && comboBox.getItems().contains(item)) {
                                    comboBox.setValue(item);
                                } else if (!comboBox.getItems().isEmpty()) {
                                    comboBox.setValue(comboBox.getItems().get(0));
                                    episode.setSelectedLanguage(comboBox.getValue());
                                }

                                setGraphic(comboBox);
                            }
                        }
                    };
                });
                languageCol.setPrefWidth(120);

                // Columna de servidor con ComboBox
                TableColumn<Episode, String> serverCol = new TableColumn<>("Server");
                serverCol.setCellValueFactory(cellData -> cellData.getValue().selectedServerProperty());
                serverCol.setCellFactory(column -> {
                    return new TableCell<Episode, String>() {
                        private final ComboBox<String> comboBox = new ComboBox<>();

                        {
                            comboBox.setOnAction(event -> {
                                if (getTableRow() != null && getTableRow().getItem() != null && comboBox.getValue() != null) {
                                    Episode episode = getTableRow().getItem();
                                    episode.setSelectedServer(comboBox.getValue());

                                    // Actualizar la tabla para refrescar las opciones de calidad
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

                                // Obtener el idioma seleccionado
                                String selectedLanguage = episode.getSelectedLanguage();

                                List<String> servers;
                                if (selectedLanguage != null && !selectedLanguage.isEmpty()) {
                                    servers = getServersForEpisodeLanguage(episode.getId(), selectedLanguage);
                                } else {
                                    servers = getAvailableEpisodeServers(episode.getId());
                                }

                                comboBox.getItems().clear();
                                comboBox.getItems().addAll(servers);

                                // Mantener la selección actual si existe
                                if (item != null && !item.isEmpty() && comboBox.getItems().contains(item)) {
                                    comboBox.setValue(item);
                                } else if (!comboBox.getItems().isEmpty()) {
                                    comboBox.setValue(comboBox.getItems().get(0));
                                    episode.setSelectedServer(comboBox.getValue());
                                }

                                setGraphic(comboBox);
                            }
                        }
                    };
                });
                serverCol.setPrefWidth(120);

                // Columna de calidad con ComboBox
                TableColumn<Episode, ConnectDataBase.Quality> qualityCol = new TableColumn<>("Quality");
                qualityCol.setCellValueFactory(cellData -> cellData.getValue().selectedQualityProperty());
                qualityCol.setCellFactory(column -> {
                    return new TableCell<Episode, ConnectDataBase.Quality>() {
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

                                // Obtener servidor seleccionado
                                String selectedServer = episode.getSelectedServer();

                                // Obtener calidades basadas en el servidor o todas las calidades
                                List<ConnectDataBase.Quality> qualities;
                                if (selectedServer != null && !selectedServer.isEmpty()) {
                                    // Obtener calidades para este servidor
                                    qualities = getQualitiesForServer(episode.getId(), selectedServer);
                                } else {
                                    // Cargar todas las calidades
                                    qualities = new ArrayList<>(connectDataBase.getQualities());
                                }

                                comboBox.getItems().clear();
                                comboBox.getItems().addAll(qualities);

                                // Mantener la selección actual si existe
                                if (item != null && comboBox.getItems().contains(item)) {
                                    comboBox.setValue(item);
                                } else if (!comboBox.getItems().isEmpty()) {
                                    comboBox.setValue(comboBox.getItems().get(0));
                                    episode.setSelectedQuality(comboBox.getValue());
                                }

                                setGraphic(comboBox);
                            }
                        }
                    };
                });
                qualityCol.setPrefWidth(100);

                // Columna de acciones
                TableColumn<Episode, Void> actionsCol = new TableColumn<>("Actions");
                actionsCol.setCellFactory(param -> new TableCell<Episode, Void>() {
                    private final Button addButton = new Button("Add");
                    private final Button streamButton = new Button("Stream");
                    private final HBox buttonsBox = new HBox(5);

                    {
                        addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                        streamButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");

                        addButton.setOnAction(event -> {
                            if (getTableRow() != null && getTableRow().getItem() != null) {
                                Episode episode = getTableRow().getItem();

                                // Verificar si ya está en la cesta
                                boolean isInBasket = downloadBasket.stream()
                                        .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                                if (isInBasket) {
                                    // Eliminar de la cesta
                                    downloadBasket.removeIf(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId()
                                    );
                                    addButton.setText("Add");
                                    addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");

                                    // Actualizar checkbox
                                    episode.setSelected(false);
                                    getTableView().refresh();
                                } else {
                                    // Obtener servidor y calidad seleccionados
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

                                    // Verificar si el servidor es compatible para descarga
                                    boolean isCompatible = isServerCompatible(selectedServer);

                                    if (!isCompatible) {
                                        showAlert(Alert.AlertType.WARNING, "Incompatible Server", "The selected server '" + selectedServer + "' is not compatible with direct download.");
                                        return;
                                    }

                                    // Obtener archivos para este episodio
                                    ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());

                                    // Encontrar archivo para la calidad y servidor seleccionados
                                    String baseServer = selectedServer.split(" ")[0];
                                    DirectFile selectedFile = directFiles.stream()
                                            .filter(df -> df.getQualityId() == selectedQuality.getId() && df.getServer().equalsIgnoreCase(baseServer))
                                            .findFirst()
                                            .orElse(null);

                                    if (selectedFile == null) {
                                        showAlert(Alert.AlertType.WARNING, "File not available", "No file available for the selected quality and server.");
                                        return;
                                    }

                                    // Verificar si el enlace está disponible
                                    DirectDownloader downloader = getDownloaderForServer(selectedServer);
                                    if (downloader == null) {
                                        showAlert(Alert.AlertType.ERROR, "Error", "No downloader available for server: " + selectedServer);
                                        return;
                                    }

                                    // Obtener información de serie y temporada
                                    Series series = currentSeries;
                                    int seasonNumber = season.getSeasonNumber(); // Usar directamente el número de temporada

                                    // Añadir a la cesta
                                    DownloadBasketItem basketItem = new DownloadBasketItem(
                                            episode.getId(),
                                            series.getName() + " - S" + seasonNumber + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                                            "episode",
                                            selectedQuality.getQuality(),
                                            selectedFile.getLink(),
                                            selectedFile.getServer(),
                                            series.getName(),
                                            seasonNumber,
                                            episode.getEpisodeNumber()
                                    );

                                    downloadBasket.add(basketItem);
                                    addButton.setText("Remove");
                                    addButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");

                                    // Actualizar checkbox
                                    episode.setSelected(true);
                                    getTableView().refresh();
                                }
                            }
                        });

                        streamButton.setOnAction(event -> {
                            if (getTableRow() != null && getTableRow().getItem() != null) {
                                Episode episode = getTableRow().getItem();
                                // Obtener servidor seleccionado
                                String selectedServer = episode.getSelectedServer();
                                if (selectedServer == null || selectedServer.isEmpty()) {
                                    showAlert(Alert.AlertType.WARNING, "Select a server", "Please select a server to stream from.");
                                    return;
                                }

                                // Obtener archivos para este episodio
                                ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());

                                // Encontrar archivo para el servidor seleccionado
                                String baseServer = selectedServer.split(" ")[0];
                                DirectFile selectedFile = directFiles.stream()
                                        .filter(df -> df.getServer().equalsIgnoreCase(baseServer))
                                        .findFirst()
                                        .orElse(null);

                                if (selectedFile != null) {
                                    // Determine server ID
                                    int serverId = -1;

                                    // Check for powvideo.org (ID: 1)
                                    if (baseServer.toLowerCase().contains("powvideo.org")) {
                                        serverId = 1;
                                    }
                                    // Check for streamplay.to (ID: 21)
                                    else if (baseServer.toLowerCase().contains("streamplay.to")) {
                                        serverId = 21;
                                    }
                                    // Check for streamtape.com (ID: 497)
                                    else if (baseServer.toLowerCase().contains("streamtape.com")) {
                                        serverId = 497;
                                    }
                                    // Check for mixdrop.bz (ID: 15)
                                    else if (baseServer.toLowerCase().contains("mixdrop.bz")) {
                                        serverId = 15;
                                    }
                                    // Check for vidmoly.me (ID: 3)
                                    else if (baseServer.toLowerCase().contains("vidmoly")) {
                                        serverId = 3;
                                    }

                                    // Stream with server ID if available
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
                        buttonsBox.getChildren().addAll(addButton, streamButton);
                    }

                    @Override
                    protected void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);

                        if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                            setGraphic(null);
                        } else {
                            Episode episode = getTableRow().getItem();

                            // Verificar si el episodio ya está en la cesta
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

                            // Verificar si el servidor es compatible para descarga
                            String selectedServer = episode.getSelectedServer();
                            if (selectedServer != null && !selectedServer.isEmpty()) {
                                boolean isCompatible = isServerCompatible(selectedServer);
                                addButton.setDisable(!isCompatible);

                                if (!isCompatible) {
                                    addButton.setStyle("-fx-background-color: #95a5a6; -fx-text-fill: white;");
                                }
                            }

                            setGraphic(buttonsBox);
                        }
                    }
                });
                actionsCol.setPrefWidth(150);

                episodesTable.getColumns().addAll(selectCol, episodeNumberCol, titleCol, languageCol, serverCol, qualityCol, actionsCol);

                // Cargar episodios para esta temporada
                ObservableList<Episode> seasonEpisodes = connectDataBase.getEpisodesBySeason(season.getId());

                // Ordenar episodios por número
                seasonEpisodes.sort(Comparator.comparingInt(Episode::getEpisodeNumber));

                episodesTable.setItems(seasonEpisodes);

                // Configurar el checkbox para seleccionar todos los episodios
                selectAllCheckbox.setOnAction(e -> {
                    boolean selected = selectAllCheckbox.isSelected();

                    // Actualizar el estado de selección de todos los episodios
                    for (Episode episode : seasonEpisodes) {
                        episode.setSelected(selected);

                        // Si está seleccionado, asegurarse de que tenga valores por defecto
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

                    // Actualizar la tabla para reflejar los cambios
                    episodesTable.refresh();
                });

                // Botón para añadir episodios seleccionados a la cesta
                Button addSelectedButton = new Button("Add Selected to Basket");
                addSelectedButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                addSelectedButton.setOnAction(e -> {
                    // Filtrar episodios seleccionados
                    List<Episode> selectedEpisodes = seasonEpisodes.stream()
                            .filter(Episode::isSelected)
                            .collect(Collectors.toList());

                    if (selectedEpisodes.isEmpty()) {
                        showAlert(Alert.AlertType.INFORMATION, "No Selection", "Please select at least one episode to add to the basket.");
                        return;
                    }

                    // Procesar cada episodio seleccionado
                    int addedCount = 0;
                    for (Episode episodeItem : selectedEpisodes) {
                        // Crear una copia final del episodio para usar en la lambda
                        final Episode episode = episodeItem;

                        // Verificar si ya está en la cesta
                        boolean alreadyInBasket = downloadBasket.stream()
                                .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                        if (alreadyInBasket) {
                            continue; // Ya está en la cesta, pasar al siguiente
                        }

                        // Verificar si el episodio tiene selecciones válidas
                        if (episode.getSelectedServer() == null || episode.getSelectedQuality() == null) {
                            // Intentar establecer valores predeterminados
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

                            // Verificar de nuevo
                            if (episode.getSelectedServer() == null || episode.getSelectedQuality() == null) {
                                continue; // No tiene selecciones válidas, pasar al siguiente
                            }
                        }

                        // Verificar si el servidor es compatible
                        if (!isServerCompatible(episode.getSelectedServer())) {
                            continue; // Servidor no compatible, pasar al siguiente
                        }

                        // Obtener archivos para este episodio
                        ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());

                        // Encontrar archivo para la calidad y servidor seleccionados
                        String baseServer = episode.getSelectedServer().split(" ")[0];
                        DirectFile selectedFile = directFiles.stream()
                                .filter(df -> df.getQualityId() == episode.getSelectedQuality().getId() && df.getServer().equalsIgnoreCase(baseServer))
                                .findFirst()
                                .orElse(null);

                        if (selectedFile == null) {
                            continue; // No hay archivo disponible, pasar al siguiente
                        }

                        // Añadir a la cesta
                        DownloadBasketItem basketItem = new DownloadBasketItem(
                                episode.getId(),
                                series.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                                "episode",
                                episode.getSelectedQuality().getQuality(),
                                selectedFile.getLink(),
                                selectedFile.getServer(),
                                series.getName(),
                                season.getSeasonNumber(),
                                episode.getEpisodeNumber()
                        );

                        downloadBasket.add(basketItem);
                        addedCount++;
                    }

                    // Actualizar la tabla
                    episodesTable.refresh();

                    // Mostrar confirmación
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
                seasonsTabPane.getTabs().add(seasonTab);
            }

            // Añadir botón para descargar temporada completa
            Button downloadSeasonButton = new Button("Download Complete Season");
            downloadSeasonButton.setStyle("-fx-background-color: #e67e22; -fx-text-fill: white;");
            downloadSeasonButton.setOnAction(e -> {
                // Obtener la pestaña seleccionada
                Tab selectedTab = seasonsTabPane.getSelectionModel().getSelectedItem();
                if (selectedTab == null) {
                    showAlert(Alert.AlertType.WARNING, "No Season Selected", "Please select a season to download.");
                    return;
                }

                // Obtener el número de temporada
                String tabText = selectedTab.getText();
                int seasonNumber = Integer.parseInt(tabText.replace("Season ", ""));

                // Encontrar la temporada correspondiente
                ConnectDataBase.Season selectedSeason = seasons.stream()
                        .filter(s -> s.getSeasonNumber() == seasonNumber)
                        .findFirst()
                        .orElse(null);

                if (selectedSeason == null) {
                    showAlert(Alert.AlertType.ERROR, "Season Not Found", "Could not find the selected season.");
                    return;
                }

                // Cargar todos los episodios de la temporada
                ObservableList<Episode> allEpisodes = connectDataBase.getEpisodesBySeason(selectedSeason.getId());

                // Verificar si hay episodios
                if (allEpisodes.isEmpty()) {
                    showAlert(Alert.AlertType.WARNING, "No Episodes", "No episodes found for this season.");
                    return;
                }

                // Configurar valores predeterminados para todos los episodios
                for (Episode episodeItem : allEpisodes) {
                    // Crear una copia final del episodio para usar en lambdas si es necesario
                    final Episode episode = episodeItem;

                    // Establecer idioma predeterminado
                    List<String> languages = getAvailableEpisodeLanguages(episode.getId());
                    if (!languages.isEmpty()) {
                        episode.setSelectedLanguage(languages.get(0));
                    }

                    // Establecer servidor predeterminado (preferiblemente compatible)
                    List<String> servers = getAvailableEpisodeServers(episode.getId());
                    String compatibleServer = servers.stream()
                            .filter(DirectDownloadUI.this::isServerCompatible)
                            .findFirst()
                            .orElse(servers.isEmpty() ? null : servers.get(0));

                    if (compatibleServer != null) {
                        episode.setSelectedServer(compatibleServer);
                    }

                    // Establecer calidad predeterminada
                    if (episode.getSelectedServer() != null) {
                        List<ConnectDataBase.Quality> qualities = getQualitiesForServer(episode.getId(), episode.getSelectedServer());
                        if (!qualities.isEmpty()) {
                            episode.setSelectedQuality(qualities.get(0));
                        }
                    }
                }

                // Filtrar episodios con configuración válida
                List<Episode> validEpisodes = allEpisodes.stream()
                        .filter(ep -> ep.getSelectedServer() != null &&
                                ep.getSelectedQuality() != null &&
                                isServerCompatible(ep.getSelectedServer()))
                        .collect(Collectors.toList());

                if (validEpisodes.isEmpty()) {
                    showAlert(Alert.AlertType.WARNING, "No Valid Episodes", "No episodes with compatible download options found.");
                    return;
                }

                // Añadir episodios a la cesta
                int addedCount = 0;
                for (Episode episodeItem : validEpisodes) {
                    // Crear una copia final del episodio para usar en lambdas si es necesario
                    final Episode episode = episodeItem;

                    // Verificar si ya está en la cesta
                    boolean alreadyInBasket = downloadBasket.stream()
                            .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                    if (alreadyInBasket) {
                        continue; // Ya está en la cesta, pasar al siguiente
                    }

                    // Obtener archivos para este episodio
                    ObservableList<DirectFile> directFiles = connectDataBase.getDirectFiles(episode.getId());

                    // Encontrar archivo para la calidad y servidor seleccionados
                    String baseServer = episode.getSelectedServer().split(" ")[0];
                    DirectFile selectedFile = directFiles.stream()
                            .filter(df -> df.getQualityId() == episode.getSelectedQuality().getId() &&
                                    df.getServer().equalsIgnoreCase(baseServer))
                            .findFirst()
                            .orElse(null);

                    if (selectedFile == null) {
                        continue; // No hay archivo disponible, pasar al siguiente
                    }

                    // Añadir a la cesta
                    DownloadBasketItem basketItem = new DownloadBasketItem(
                            episode.getId(),
                            series.getName() + " - S" + seasonNumber + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                            "episode",
                            episode.getSelectedQuality().getQuality(),
                            selectedFile.getLink(),
                            selectedFile.getServer(),
                            series.getName(),
                            seasonNumber,
                            episode.getEpisodeNumber()
                    );

                    downloadBasket.add(basketItem);
                    addedCount++;
                }

                // Mostrar confirmación
                if (addedCount > 0) {
                    showConfirmation(addedCount + " episodes from Season " + seasonNumber + " added to the basket");
                } else {
                    showAlert(Alert.AlertType.INFORMATION, "No Episodes Added",
                            "No new episodes were added to the basket. They might already be in the basket or no compatible servers were found.");
                }
            });

            // Crear layout para los botones de acción
            HBox actionButtons = new HBox(10, backButton, downloadSeasonButton);
            actionButtons.setPadding(new Insets(10, 0, 10, 0));
            actionButtons.setAlignment(Pos.CENTER_LEFT);

            seriesLayout.getChildren().addAll(actionButtons, seriesLabel, seasonsTabPane);
            VBox.setVgrow(seasonsTabPane, Priority.ALWAYS);
        } catch (Exception e) {
            System.err.println("Error getting seasons: " + e.getMessage());
            e.printStackTrace();

            Label errorLabel = new Label("Error loading seasons: " + e.getMessage());
            errorLabel.setStyle("-fx-text-fill: red;");
            seriesLayout.getChildren().addAll(backButton, seriesLabel, errorLabel);
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
                // Obtener el idioma del enlace
                String language = getLanguageFromLink(file.getLink());
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
            Map<String, Integer> serverCounts = new HashMap<>();

            // Contar cuántos enlaces hay para cada servidor con el idioma seleccionado
            for (DirectFile file : files) {
                String fileLanguage = getLanguageFromLink(file.getLink());
                if (language.equals(fileLanguage)) {
                    String server = file.getServer();
                    serverCounts.put(server, serverCounts.getOrDefault(server, 0) + 1);
                }
            }

            // Crear lista de servidores con enumeración para duplicados
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

            // Guardar en caché
            movieServerCache.computeIfAbsent(movieId, k -> new HashMap<>()).put(language, servers);
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
            return fallback;
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

    /**
     * Obtiene los idiomas disponibles para un episodio
     */
    private List<String> getAvailableEpisodeLanguages(int episodeId) {
        try {
            // Consulta SQL para obtener los idiomas disponibles para este episodio
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

            // Si no hay idiomas en la base de datos, usar valores predeterminados
            if (languages.isEmpty()) {
                languages.add("Audio Español");
                languages.add("Audio Latino");
                languages.add("Subtítulo Español");
            }

            System.out.println("Available languages for episode " + episodeId + ": " + languages);
            return languages;
        } catch (Exception e) {
            System.err.println("Error getting available episode languages: " + e.getMessage());
            e.printStackTrace();

            // Valores predeterminados en caso de error
            List<String> defaultLanguages = new ArrayList<>();
            defaultLanguages.add("Audio Español");
            defaultLanguages.add("Audio Latino");
            defaultLanguages.add("Subtítulo Español");
            return defaultLanguages;
        }
    }

    /**
     * Obtiene los servidores disponibles para un idioma específico de un episodio
     */
    private List<String> getServersForEpisodeLanguage(int episodeId, String language) {
        try {
            // Consulta SQL para obtener los servidores disponibles para este episodio y idioma
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

            // Crear lista de servidores con enumeración para duplicados
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

            // Si no hay servidores, usar valores predeterminados
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
        } catch (Exception e) {
            System.err.println("Error getting servers for episode language: " + e.getMessage());
            e.printStackTrace();

            // Valores predeterminados en caso de error
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
            return defaultServers;
        }
    }

    /**
     * Obtiene todos los servidores disponibles para un episodio
     */
    private List<String> getAvailableEpisodeServers(int episodeId) {
        try {
            // Consulta SQL para obtener todos los servidores disponibles para este episodio
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

            // Crear lista de servidores con enumeración para duplicados
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

            // Si no hay servidores, usar valores predeterminados
            if (servers.isEmpty()) {
                servers.add("streamtape.com");
                servers.add("powvideo.org");
                servers.add("streamplay.to");
                servers.add("mixdrop.bz");
            }

            System.out.println("Available servers for episode " + episodeId + ": " + servers);
            return servers;
        } catch (Exception e) {
            System.err.println("Error getting available episode servers: " + e.getMessage());
            e.printStackTrace();

            // Valores predeterminados en caso de error
            List<String> defaultServers = new ArrayList<>();
            defaultServers.add("streamtape.com");
            defaultServers.add("powvideo.org");
            defaultServers.add("streamplay.to");
            defaultServers.add("mixdrop.bz");
            return defaultServers;
        }
    }

    /**
     * Obtiene las calidades disponibles para un servidor específico de un episodio
     */
    private List<ConnectDataBase.Quality> getQualitiesForServer(int episodeId, String serverWithIndex) {
        try {
            // Extraer el nombre base del servidor
            String baseServer = serverWithIndex.split(" ")[0];

            // Consulta SQL para obtener las calidades disponibles para este episodio y servidor
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

            // Si no hay calidades, obtener todas las calidades disponibles
            if (qualities.isEmpty()) {
                qualities.addAll(connectDataBase.getQualities());
            }

            System.out.println("Qualities for episode " + episodeId + " and server " + baseServer + ": " + qualities);
            return qualities;
        } catch (Exception e) {
            System.err.println("Error getting qualities for server: " + e.getMessage());
            e.printStackTrace();

            // Devolver todas las calidades en caso de error
            return new ArrayList<>(connectDataBase.getQualities());
        }
    }

    /**
     * Obtiene el idioma de un enlace
     */
    private String getLanguageFromLink(String link) {
        try {
            // Consultar la base de datos para obtener el idioma de este enlace
            String query = "SELECT language FROM links_files_download WHERE link = ?";
            try (PreparedStatement stmt = connectDataBase.getConnection().prepareStatement(query)) {
                stmt.setString(1, link);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return rs.getString("language");
                }
            }

            // Si no se encuentra en la base de datos, usar la lógica de fallback
            if (link.contains("hqq.tv")) {
                return "Audio Español";
            } else if (link.contains("powvideo") && link.contains("mrjazi7gex6o")) {
                return "Subtítulo Español";
            } else if (link.contains("powvideo")) {
                return "Audio Español";
            } else if (link.contains("streamplay.to")) {
                return "Audio Latino";
            } else if (link.contains("vidmoly")) {
                return "Audio Español";
            } else {
                return "Audio Español"; // Default
            }
        } catch (Exception e) {
            System.err.println("Error getting language from link: " + e.getMessage());
            e.printStackTrace();
            return "Audio Español"; // Default
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

    /**
     * Obtiene el enlace para un servidor e idioma específicos
     */
    private String getLinkForServerAndLanguage(int movieId, String serverWithIndex, String language) {
        // Extraer el nombre base del servidor y el índice
        String[] parts = serverWithIndex.split(" ");
        String baseServer = parts[0];
        int index = parts.length > 1 ? Integer.parseInt(parts[1]) - 1 : 0;

        try {
            ObservableList<DirectFile> files = getMovieDirectFilesCached(movieId);
            List<DirectFile> filteredFiles = files.stream()
                    .filter(f -> {
                        String fileLanguage = getLanguageFromLink(f.getLink());
                        return f.getServer().equalsIgnoreCase(baseServer) && fileLanguage.equals(language);
                    })
                    .collect(Collectors.toList());

            return (index < filteredFiles.size()) ? filteredFiles.get(index).getLink() : "";
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

        public static class ServerLink {
            private final String language;
            private final String server;
            private final String link;
            private final String quality;

            public ServerLink(String language, String server, String link, String quality) {
                this.language = language;
                this.server = server;
                this.link = link;
                this.quality = quality;
            }

            // Getters
            public String getLanguage() { return language; }
            public String getServer() { return server; }
            public String getLink() { return link; }
            public String getQuality() { return quality; }
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
                    if (!availableServers.isEmpty()) {
                        selectedServer.set(availableServers.get(0));
                    }
                }
            });

            // Listener para cambios de servidor
            selectedServer.addListener((obs, oldServer, newServer) -> {
                if (newServer != null && !newServer.isEmpty()) {
                    updateLinkAndQuality(newServer);
                }
            });
        }

        private void updateAvailableServers(String language) {
            availableServers.clear();
            List<ServerLink> links = serverLinksByLanguage.get(language);
            if (links != null) {
                for (ServerLink link : links) {
                    if (!availableServers.contains(link.getServer())) {
                        availableServers.add(link.getServer());
                    }
                }
            }
        }

        private void updateLinkAndQuality(String server) {
            String currentLanguage = selectedLanguage.get();
            if (currentLanguage != null && !currentLanguage.isEmpty()) {
                List<ServerLink> links = serverLinksByLanguage.get(currentLanguage);
                if (links != null) {
                    for (ServerLink serverLink : links) {
                        if (serverLink.getServer().equals(server)) {
                            link.set(serverLink.getLink());
                            quality.set(serverLink.getQuality());
                            break;
                        }
                    }
                }
            }
        }

        public void setAvailableLanguages(List<String> languages) {
            availableLanguages.clear();
            if (languages != null) {
                availableLanguages.addAll(languages);
            }

            // Establecer el idioma seleccionado si no está establecido
            if ((selectedLanguage.get() == null || selectedLanguage.get().isEmpty()) && !availableLanguages.isEmpty()) {
                selectedLanguage.set(availableLanguages.get(0));
            }
        }

        public void setAvailableServers(List<String> servers) {
            availableServers.clear();
            if (servers != null) {
                availableServers.addAll(servers);
            }

            // Establecer el servidor seleccionado si no está establecido
            if ((selectedServer.get() == null || selectedServer.get().isEmpty()) && !availableServers.isEmpty()) {
                selectedServer.set(availableServers.get(0));
            }
        }

        public void loadServerLinks(List<ServerLink> links) {
            serverLinksByLanguage.clear();
            availableLanguages.clear();

            // Agrupar enlaces por idioma
            Map<String, List<ServerLink>> groupedLinks = new HashMap<>();
            for (ServerLink link : links) {
                String language = link.getLanguage();
                if (!groupedLinks.containsKey(language)) {
                    groupedLinks.put(language, new ArrayList<>());
                }
                groupedLinks.get(language).add(link);
            }

            serverLinksByLanguage.putAll(groupedLinks);
            availableLanguages.addAll(groupedLinks.keySet());

            // Selección inicial
            if (!availableLanguages.isEmpty()) {
                selectedLanguage.set(availableLanguages.get(0));
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
        private boolean selected;

        // Propiedades para almacenar las selecciones
        private final StringProperty selectedLanguage = new SimpleStringProperty();
        private final StringProperty selectedServer = new SimpleStringProperty();
        private final ObjectProperty<ConnectDataBase.Quality> selectedQuality = new SimpleObjectProperty<>();

        public Episode(int id, int seasonId, int episodeNumber, String title) {
            this.id = id;
            this.seasonId = seasonId;
            this.episodeNumber = episodeNumber;
            this.title = title;
            this.selected = false;
        }

        // Getters y setters existentes
        public int getId() { return id; }
        public int getSeasonId() { return seasonId; }
        public int getEpisodeNumber() { return episodeNumber; }
        public String getTitle() { return title; }
        public boolean isSelected() { return selected; }
        public void setSelected(boolean selected) { this.selected = selected; }

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
        private final String server;
        private final String quality;

        public DirectFile(int id, int fileId, Integer episodeId, int qualityId, String link, String server, String quality) {
            this.id = id;
            this.fileId = fileId;
            this.episodeId = episodeId;
            this.qualityId = qualityId;
            this.link = link;
            this.server = server;
            this.quality = quality;
        }

        public int getId() { return id; }
        public int getFileId() { return fileId; }
        public Integer getEpisodeId() { return episodeId; }
        public int getQualityId() { return qualityId; }
        public String getLink() { return link; }
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