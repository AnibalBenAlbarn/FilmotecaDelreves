package org.example.filmotecadelreves.UI;

import org.example.filmotecadelreves.downloaders.TorrentDownloader;
import org.example.filmotecadelreves.moviesad.ConnectDataBase;
import org.example.filmotecadelreves.moviesad.DelayedLoadingDialog;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.example.filmotecadelreves.moviesad.TorrentState;
import org.example.filmotecadelreves.scrapers.ScraperProgressTracker;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Callback;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class TorrentDownloadUI {
    private Tab tab;
    private TorrentDownloader torrentDownloader;
    private AjustesUI ajustesUI;
    private DescargasUI descargasUI;
    private ConnectDataBase connectDataBase;
    private final ScraperProgressTracker scraperProgressTracker;
    private ComboBox<String> filterComboBox;
    private ComboBox<String> filterOptionsComboBox;
    private VBox seriesLayout;
    private TableView<Series> seriesTable;
    private TableView<Movie> peliculasTable;
    private VBox busquedaSection;

    // Carrito de descargas
    private final ObservableList<DownloadBasketItem> downloadBasket = FXCollections.observableArrayList();
    private TableView<DownloadBasketItem> basketTable;

    // Datos para la navegación de series
    private Series currentSeries;
    private int currentSeason;
    private final Map<Integer, List<Episode>> episodesBySeason = new HashMap<>();
    private final Map<Integer, List<TorrentFile>> torrentFilesByEpisode = new HashMap<>();

    // Directorio para archivos temporales
    private final String TEMP_DIR = System.getProperty("java.io.tmpdir") + File.separator + "torrent_downloader";

    public TorrentDownloadUI(AjustesUI ajustesUI, DescargasUI descargasUI, Stage primaryStage, ScraperProgressTracker scraperProgressTracker) {
        this.ajustesUI = ajustesUI;
        this.descargasUI = descargasUI;
        this.scraperProgressTracker = scraperProgressTracker;

        // No inicializar el TorrentDownloader aquí, se recibirá desde MainUI
        this.connectDataBase = new ConnectDataBase(ajustesUI.getTorrentDatabasePath());

        // Crear directorio temporal si no existe
        createTempDirectory();

        tab = new Tab("Torrent Download");
        tab.setClosable(false);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        // Crear el carrito de descargas
        HBox basketSection = createDownloadBasket();
        layout.getChildren().add(basketSection);

        TabPane subTabs = new TabPane();
        subTabs.getTabs().add(createPeliculasTab(primaryStage));
        subTabs.getTabs().add(createSeriesTab(primaryStage));

        layout.getChildren().add(subTabs);

        tab.setContent(layout);

        // Cargar datos iniciales después de que la UI esté completamente inicializada
        Platform.runLater(this::loadInitialData);
    }

    private Window getWindow() {
        if (tab != null && tab.getContent() != null && tab.getContent().getScene() != null) {
            return tab.getContent().getScene().getWindow();
        }
        return null;
    }

    private <T> void runWithLoading(Callable<T> operation, Consumer<T> onSuccess, String loadingMessage, String errorMessage) {
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
        });

        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
        loadingDialog.start();
    }

    /**
     * Método para actualizar la referencia al TorrentDownloader
     * @param torrentDownloader La instancia de TorrentDownloader a usar
     */
    public void updateDownloader(TorrentDownloader torrentDownloader) {
        this.torrentDownloader = torrentDownloader;
        System.out.println("TorrentDownloadUI: TorrentDownloader actualizado");
    }

    /**
     * Replace the database used by the torrent interface. Called when the
     * user saves a new database path in AjustesUI.
     *
     * @param newPath path to the new torrent database
     */
    public void updateDatabase(String newPath) {
        if (connectDataBase != null) {
            connectDataBase.closeConnection();
        }
        connectDataBase = new ConnectDataBase(newPath);
        episodesBySeason.clear();
        torrentFilesByEpisode.clear();
        loadInitialData();
    }

    private void createTempDirectory() {
        try {
            Path tempDir = Paths.get(TEMP_DIR);
            if (!Files.exists(tempDir)) {
                Files.createDirectories(tempDir);
                System.out.println("Directorio temporal creado: " + tempDir.toAbsolutePath());
            }
        } catch (IOException e) {
            System.err.println("Error al crear directorio temporal: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void loadInitialData() {
        runWithLoading(() -> new InitialData(
                        connectDataBase.getLatestTorrentMovies(10, Movie.class),
                        connectDataBase.getLatestTorrentSeries(10, Series.class)
                ),
                data -> {
                    if (peliculasTable != null) {
                        peliculasTable.setItems(data.movies);
                    }
                    if (seriesTable != null) {
                        seriesTable.setItems(data.series);
                    }
                    System.out.println("Datos iniciales cargados: " + data.movies.size() + " películas, " + data.series.size() + " series");
                },
                "Cargando datos iniciales...",
                "No se pudieron cargar los datos iniciales.");
    }

    private static class InitialData {
        private final ObservableList<Movie> movies;
        private final ObservableList<Series> series;

        private InitialData(ObservableList<Movie> movies, ObservableList<Series> series) {
            this.movies = movies;
            this.series = series;
        }
    }

    @NotNull
    private HBox createDownloadBasket() {
        HBox basketSection = new HBox(10);
        basketSection.setPadding(new Insets(10));
        basketSection.setStyle("-fx-border-color: #3498db; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #f8f9fa;");

        VBox basketInfo = new VBox(5);
        Label basketLabel = new Label("Cesta de Descargas");
        basketLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label basketCount = new Label("0 elementos");
        basketCount.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> downloadBasket.size() + " elementos",
                downloadBasket
        ));
        basketInfo.getChildren().addAll(basketLabel, basketCount);

        Button viewBasketButton = new Button("Ver Cesta");
        viewBasketButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        viewBasketButton.setOnAction(e -> showDownloadBasket(basketSection.getScene().getWindow()));

        Button addToDownloadsButton = new Button("Añadir a Descargas");
        addToDownloadsButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
        addToDownloadsButton.setOnAction(e -> addBasketToDownloads());
        addToDownloadsButton.disableProperty().bind(javafx.beans.binding.Bindings.isEmpty(downloadBasket));

        HBox buttons = new HBox(10, viewBasketButton, addToDownloadsButton);

        basketSection.getChildren().addAll(basketInfo, new Pane(), buttons);
        HBox.setHgrow(basketSection.getChildren().get(1), Priority.ALWAYS);

        return basketSection;
    }

    private void showDownloadBasket(javafx.stage.Window owner) {
        Stage basketStage = new Stage();
        basketStage.initOwner(owner);
        basketStage.setTitle("Cesta de Descargas");

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(20));

        Label titleLabel = new Label("Elementos en la cesta de descargas");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        // Tabla para mostrar los elementos de la cesta
        basketTable = new TableView<>();
        basketTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<DownloadBasketItem, String> nameCol = new TableColumn<>("Nombre");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(300);

        TableColumn<DownloadBasketItem, String> typeColBasket = new TableColumn<>("Tipo");
        typeColBasket.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColBasket.setCellFactory(column -> new TableCell<DownloadBasketItem, String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.equals("movie") ? "Película" : "Episodio");
                }
            }
        });

        TableColumn<DownloadBasketItem, String> qualityColBasket = new TableColumn<>("Calidad");
        qualityColBasket.setCellValueFactory(new PropertyValueFactory<>("quality"));

        TableColumn<DownloadBasketItem, String> linkColBasket = new TableColumn<>("Enlace");
        linkColBasket.setCellValueFactory(new PropertyValueFactory<>("torrentLink"));
        linkColBasket.setPrefWidth(200);

        TableColumn<DownloadBasketItem, Void> actionsColBasket = new TableColumn<>("Acciones");
        actionsColBasket.setCellFactory(param -> new TableCell<DownloadBasketItem, Void>() {
            private final Button removeButton = new Button("Eliminar");

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

        basketTable.getColumns().addAll(nameCol, typeColBasket, qualityColBasket, linkColBasket, actionsColBasket);
        basketTable.setItems(downloadBasket);

        HBox buttonsBox = new HBox(10);
        buttonsBox.setAlignment(Pos.CENTER_RIGHT);

        Button clearButton = new Button("Vaciar Cesta");
        clearButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
        clearButton.setOnAction(e -> downloadBasket.clear());

        Button addToDownloadsButton = new Button("Añadir a Descargas");
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

    private void addBasketToDownloads() {
        if (downloadBasket.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "Cesta vacía", "No hay elementos en la cesta para descargar.");
            return;
        }

        if (ajustesUI.getMovieDestination().isEmpty() || ajustesUI.getSeriesDestination().isEmpty()) {
            showAlert(Alert.AlertType.ERROR, "Error", "Por favor, configura las rutas de destino en Ajustes primero.");
            return;
        }

        // Verificar que el TorrentDownloader está disponible
        if (torrentDownloader == null) {
            showAlert(Alert.AlertType.ERROR, "Error", "El gestor de descargas no está disponible. Por favor, reinicie la aplicación.");
            return;
        }

        // Mostrar diálogo de progreso
        ProgressDialog progressDialog = new ProgressDialog("Procesando descargas", "Preparando archivos torrent...");
        progressDialog.show();

        // Agrupar episodios por serie para crear la estructura de carpetas
        Map<String, List<DownloadBasketItem>> episodesBySeries = downloadBasket.stream()
                .filter(item -> "episode".equals(item.getType()))
                .collect(Collectors.groupingBy(DownloadBasketItem::getSeriesName));

        // Contador para seguir el progreso final
        int[] processedCount = {0};
        final int totalItems = downloadBasket.size();

        // Lista para almacenar las tareas de descarga
        List<CompletableFuture<Void>> downloadTasks = new ArrayList<>();

        // Procesar películas
        downloadBasket.stream()
                .filter(item -> "movie".equals(item.getType()))
                .forEach(movie -> {
                    CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                        try {
                            System.out.println("Procesando película para descarga: " + movie.getName());
                            System.out.println("Enlace torrent: " + movie.getTorrentLink());

                            // Actualizar diálogo de progreso
                            Platform.runLater(() -> progressDialog.updateProgress(
                                    "Descargando torrent para: " + movie.getName(),
                                    (double) ++processedCount[0] / totalItems
                            ));

                            // Descargar el archivo torrent desde la URL
                            String torrentFilePath = downloadTorrentFile(movie.getTorrentLink(), movie.getName());

                            if (torrentFilePath != null) {
                                TorrentState torrentState = new TorrentState(
                                        movie.getName(),
                                        ajustesUI.getMovieDestination(),
                                        0,
                                        0,
                                        100
                                );
                                torrentState.setStatus("En espera");

                                // Establecer el nombre de la película en la propiedad name
                                torrentState.setName(movie.getName());

                                // Establecer el tamaño del archivo si está disponible
                                // Esto se actualizará durante la descarga
                                torrentState.setFileSize(0);

                                // Añadir a la lista de descargas
                                Platform.runLater(() -> descargasUI.addTorrentDownload(torrentState));

                                // Iniciar la descarga con el archivo torrent descargado
                                torrentDownloader.downloadTorrent(
                                        torrentFilePath,
                                        torrentState
                                );

                                System.out.println("Descarga iniciada para: " + movie.getName());
                            } else {
                                System.err.println("Error al descargar el archivo torrent para: " + movie.getName());
                                Platform.runLater(() -> showAlert(
                                        Alert.AlertType.ERROR,
                                        "Error de descarga",
                                        "No se pudo descargar el archivo torrent para: " + movie.getName()
                                ));
                            }
                        } catch (Exception e) {
                            System.err.println("Error al procesar película: " + e.getMessage());
                            e.printStackTrace();
                            Platform.runLater(() -> showAlert(
                                    Alert.AlertType.ERROR,
                                    "Error",
                                    "Error al procesar película " + movie.getName() + ": " + e.getMessage()
                            ));
                        }
                    });

                    downloadTasks.add(task);
                });

        // Procesar episodios agrupados por serie
        episodesBySeries.forEach((seriesName, episodes) -> {
            episodes.forEach(episode -> {
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
                        System.out.println("Procesando episodio para descarga: " + episode.getName());
                        System.out.println("Enlace torrent: " + episode.getTorrentLink());

                        // Actualizar diálogo de progreso
                        Platform.runLater(() -> progressDialog.updateProgress(
                                "Descargando torrent para: " + episode.getName(),
                                (double) ++processedCount[0] / totalItems
                        ));

                        // Descargar el archivo torrent desde la URL
                        String torrentFilePath = downloadTorrentFile(episode.getTorrentLink(), episode.getName());

                        if (torrentFilePath != null) {
                            // Crear la estructura de carpetas: SeriesDestination/NombreSerie/Temporada X/
                            String destinationPath;

                            if (ajustesUI.isCreateSubfolders()) {
                                destinationPath = ajustesUI.getSeriesDestination() + File.separator + seriesName + File.separator + "Temporada " + episode.getSeasonNumber();
                            } else {
                                destinationPath = ajustesUI.getSeriesDestination() + File.separator + seriesName;
                            }

                            // Asegurar que el directorio de destino existe
                            File destDir = new File(destinationPath);
                            if (!destDir.exists() && !destDir.mkdirs()) {
                                throw new IOException("No se pudo crear el directorio de destino: " + destinationPath);
                            }

                            TorrentState torrentState = new TorrentState(
                                    episode.getName(),
                                    destinationPath,
                                    0,
                                    0,
                                    100
                            );
                            torrentState.setStatus("En espera");

                            // Establecer el nombre del episodio en la propiedad name
                            torrentState.setName(episode.getName());

                            // Establecer el tamaño del archivo si está disponible
                            // Esto se actualizará durante la descarga
                            torrentState.setFileSize(0);

                            // Añadir a la lista de descargas
                            Platform.runLater(() -> descargasUI.addTorrentDownload(torrentState));

                            // Iniciar la descarga con el archivo torrent descargado
                            torrentDownloader.downloadTorrent(
                                    torrentFilePath,
                                    torrentState
                            );

                            System.out.println("Descarga iniciada para: " + episode.getName());
                        } else {
                            System.err.println("Error al descargar el archivo torrent para: " + episode.getName());
                            Platform.runLater(() -> showAlert(
                                    Alert.AlertType.ERROR,
                                    "Error de descarga",
                                    "No se pudo descargar el archivo torrent para: " + episode.getName()
                            ));
                        }
                    } catch (Exception e) {
                        System.err.println("Error al procesar episodio: " + e.getMessage());
                        e.printStackTrace();
                        Platform.runLater(() -> showAlert(
                                Alert.AlertType.ERROR,
                                "Error",
                                "Error al procesar episodio " + episode.getName() + ": " + e.getMessage()
                        ));
                    }
                });

                downloadTasks.add(task);
            });
        });

        // Esperar a que todas las tareas terminen y luego cerrar el diálogo de progreso
        CompletableFuture.allOf(downloadTasks.toArray(new CompletableFuture[0]))
                .whenComplete((result, ex) -> {
                    Platform.runLater(() -> {
                        progressDialog.close();

                        if (ex != null) {
                            showAlert(Alert.AlertType.ERROR, "Error", "Se produjeron errores al procesar algunas descargas. Revise el registro para más detalles.");
                        } else {
                            showAlert(Alert.AlertType.INFORMATION, "Descargas añadidas", "Los elementos se han añadido a la cola de descargas.");
                        }

                        // Limpiar la cesta después de añadir a descargas
                        downloadBasket.clear();
                    });
                });
    }

    /**
     * Descarga un archivo torrent desde una URL y lo guarda como un archivo temporal
     * @param torrentUrl URL del archivo torrent
     * @param itemName Nombre del elemento para generar un nombre de archivo único
     * @return Ruta al archivo torrent descargado, o null si hubo un error
     */
    private String downloadTorrentFile(String torrentUrl, String itemName) {
        try {
            // Sanitizar el nombre del elemento para usarlo como nombre de archivo
            String sanitizedName = itemName.replaceAll("[^a-zA-Z0-9.-]", "_");
            String fileName = sanitizedName + "_" + System.currentTimeMillis() + ".torrent";
            String filePath = TEMP_DIR + File.separator + fileName;

            System.out.println("Descargando torrent desde: " + torrentUrl);
            System.out.println("Guardando en: " + filePath);

            // Crear conexión HTTP
            URL url = new URL(torrentUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            // Verificar respuesta
            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                System.err.println("Error al descargar torrent. Código de respuesta: " + responseCode);
                return null;
            }

            // Descargar el archivo
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(filePath)) {

                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }

                System.out.println("Archivo torrent descargado correctamente: " + filePath);
                return filePath;
            }

        } catch (Exception e) {
            System.err.println("Error al descargar archivo torrent: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private Tab createPeliculasTab(Stage primaryStage) {
        Tab peliculasTab = new Tab("Películas");

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        // Búsqueda
        busquedaSection = new VBox(10);
        busquedaSection.setPadding(new Insets(10));
        busquedaSection.setStyle("-fx-border-color: #3498db; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #f8f9fa;");
        Label busquedaLabel = new Label("Búsqueda:");
        busquedaLabel.setStyle("-fx-font-weight: bold;");
        TextField searchField = new TextField();
        searchField.setPromptText("Buscar...");
        HBox filtersMoviesLayout = new HBox(10);
        Label filterLabelMovies = new Label("Filtrar por:");
        filterComboBox = new ComboBox<>();
        filterComboBox.getItems().addAll("Nombre", "Año", "Género", "Director", "Formato");
        filterComboBox.setValue("Nombre");
        filterComboBox.setOnAction(e -> updateFilterOptions());
        filterOptionsComboBox = new ComboBox<>();
        filterOptionsComboBox.setEditable(true);
        filtersMoviesLayout.getChildren().addAll(filterLabelMovies, filterComboBox, filterOptionsComboBox);

        Button searchMoviesButton = new Button("Buscar");
        searchMoviesButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        searchMoviesButton.setOnAction(e -> {
            String selectedFilter = filterComboBox.getValue();
            String filterValue = filterOptionsComboBox.getValue();
            String searchValue = searchField.getText();

            if (selectedFilter != null && filterValue != null && !filterValue.isEmpty()) {
                switch (selectedFilter) {
                    case "Año":
                        buscarPelicula(searchValue, filterValue, null, null, null);
                        break;
                    case "Género":
                        buscarPelicula(searchValue, null, filterValue, null, null);
                        break;
                    case "Director":
                        buscarPelicula(searchValue, null, null, filterValue, null);
                        break;
                    case "Formato":
                        buscarPelicula(searchValue, null, null, null, filterValue);
                        break;
                    default:
                        buscarPelicula(searchValue);
                }
            } else {
                buscarPelicula(searchValue);
            }
        });

        busquedaSection.getChildren().addAll(busquedaLabel, searchField, filtersMoviesLayout, searchMoviesButton);

        // Resultados
        Label resultsLabel = new Label("Resultados:");
        resultsLabel.setStyle("-fx-font-weight: bold;");

        peliculasTable = new TableView<>();
        peliculasTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Movie, String> nameCol = new TableColumn<>("Nombre");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("title"));
        nameCol.setPrefWidth(250);

        TableColumn<Movie, String> yearCol = new TableColumn<>("Año");
        yearCol.setCellValueFactory(new PropertyValueFactory<>("year"));

        TableColumn<Movie, String> genreCol = new TableColumn<>("Género");
        genreCol.setCellValueFactory(new PropertyValueFactory<>("genre"));
        genreCol.setPrefWidth(150);

        TableColumn<Movie, String> directorCol = new TableColumn<>("Director");
        directorCol.setCellValueFactory(new PropertyValueFactory<>("director"));
        directorCol.setPrefWidth(150);

        // Columna de calidad con selector desplegable
        TableColumn<Movie, String> qualityCol = new TableColumn<>("Calidad");
        qualityCol.setCellFactory(column -> new TableCell<Movie, String>() {
            private final ComboBox<ConnectDataBase.Quality> qualityCombo = new ComboBox<>();

            {
                qualityCombo.setOnAction(event -> {
                    Movie movie = getTableRow() != null ? getTableRow().getItem() : null;
                    if (movie == null) {
                        return;
                    }
                    ConnectDataBase.Quality selectedQuality = qualityCombo.getValue();
                    if (selectedQuality == null) {
                        return;
                    }
                    movie.findTorrentFileByQualityId(selectedQuality.getId())
                            .ifPresent(torrentFile -> {
                                movie.setQuality(selectedQuality.getQuality());
                                movie.setTorrentLink(torrentFile.getTorrentLink());
                                getTableView().refresh();
                            });
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);

                if (empty) {
                    setGraphic(null);
                    return;
                }

                Movie movie = getTableRow() != null ? getTableRow().getItem() : null;
                if (movie == null) {
                    setGraphic(null);
                    return;
                }

                ObservableList<ConnectDataBase.Quality> qualityOptions = movie.buildQualityOptions();
                qualityCombo.setItems(qualityOptions);

                ConnectDataBase.Quality selectedQuality = null;
                if (movie.getQuality() != null) {
                    selectedQuality = qualityOptions.stream()
                            .filter(q -> q.getQuality().equals(movie.getQuality()))
                            .findFirst()
                            .orElse(null);
                }

                if (selectedQuality == null && !qualityOptions.isEmpty()) {
                    selectedQuality = qualityOptions.get(qualityOptions.size() - 1);
                    movie.findTorrentFileByQualityId(selectedQuality.getId())
                            .ifPresent(torrentFile -> {
                                movie.setQuality(selectedQuality.getQuality());
                                movie.setTorrentLink(torrentFile.getTorrentLink());
                            });
                }

                qualityCombo.setDisable(qualityOptions.isEmpty());
                qualityCombo.setValue(selectedQuality);
                setGraphic(qualityOptions.isEmpty() ? null : qualityCombo);
            }
        });

        TableColumn<Movie, String> linkCol = new TableColumn<>("Enlace");
        linkCol.setCellValueFactory(new PropertyValueFactory<>("torrentLink"));
        linkCol.setPrefWidth(200);

        TableColumn<Movie, Void> actionsCol = new TableColumn<>("Acciones");
        actionsCol.setCellFactory(new Callback<TableColumn<Movie, Void>, TableCell<Movie, Void>>() {
            @Override
            public TableCell<Movie, Void> call(TableColumn<Movie, Void> param) {
                return new TableCell<Movie, Void>() {
                    private final Button addButton = new Button("Añadir a Cesta");

                    {
                        addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                        addButton.setOnAction(event -> {
                            Movie movie = getTableView().getItems().get(getIndex());

                            // Verificar si ya existe en la cesta
                            boolean alreadyInBasket = downloadBasket.stream()
                                    .anyMatch(item -> item.getId() == movie.getId() && item.getType().equals("movie"));

                            if (!alreadyInBasket) {
                                downloadBasket.add(new DownloadBasketItem(
                                        movie.getId(),
                                        movie.getTitle(),
                                        "movie",
                                        movie.getQuality(),
                                        movie.getTorrentLink(),
                                        null,
                                        0,
                                        0
                                ));
                                showAlert(Alert.AlertType.INFORMATION, "Añadido a la cesta", "Se ha añadido '" + movie.getTitle() + "' a la cesta de descargas.");
                            } else {
                                showAlert(Alert.AlertType.WARNING, "Ya en la cesta", "'" + movie.getTitle() + "' ya está en la cesta de descargas.");
                            }
                        });
                    }

                    @Override
                    protected void updateItem(Void item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty) {
                            setGraphic(null);
                        } else {
                            setGraphic(addButton);
                        }
                    }
                };
            }
        });

        peliculasTable.getColumns().addAll(nameCol, yearCol, genreCol, directorCol, qualityCol, linkCol, actionsCol);

        layout.getChildren().addAll(busquedaSection, resultsLabel, peliculasTable);
        VBox.setVgrow(peliculasTable, Priority.ALWAYS);

        peliculasTab.setContent(layout);

        return peliculasTab;
    }

    private Tab createSeriesTab(Stage primaryStage) {
        Tab seriesTab = new Tab("Series");

        seriesLayout = new VBox(10);
        seriesLayout.setPadding(new Insets(10));

        // Búsqueda
        busquedaSection = new VBox(10);
        busquedaSection.setPadding(new Insets(10));
        busquedaSection.setStyle("-fx-border-color: #3498db; -fx-border-width: 1; -fx-border-radius: 5; -fx-background-color: #f8f9fa;");
        Label busquedaLabel = new Label("Búsqueda:");
        busquedaLabel.setStyle("-fx-font-weight: bold;");
        TextField searchField = new TextField();
        searchField.setPromptText("Buscar...");
        HBox filtersSeriesLayout = new HBox(10);
        Label filterLabelSeries = new Label("Filtrar por:");
        ComboBox<String> filterComboBoxSeries = new ComboBox<>();
        filterComboBoxSeries.getItems().addAll("Nombre", "Año", "Género", "Director", "Formato");
        filterComboBoxSeries.setValue("Nombre");
        ComboBox<String> filterOptionsComboBoxSeries = new ComboBox<>();
        filterOptionsComboBoxSeries.setEditable(true);
        filtersSeriesLayout.getChildren().addAll(filterLabelSeries, filterComboBoxSeries, filterOptionsComboBoxSeries);

        Button searchSeriesButton = getButton(filterComboBoxSeries, filterOptionsComboBoxSeries, searchField);
        searchSeriesButton.setStyle("-fx-background-color: #3498db; -fx-text-fill:white;");

        busquedaSection.getChildren().addAll(busquedaLabel, searchField, filtersSeriesLayout, searchSeriesButton);

        // Resultados
        Label resultsLabel = new Label("Resultados:");
        resultsLabel.setStyle("-fx-font-weight: bold;");

        seriesTable = new TableView<>();
        seriesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

        TableColumn<Series, String> nameCol = new TableColumn<>("Nombre");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(250);

        TableColumn<Series, String> yearCol = new TableColumn<>("Año");
        yearCol.setCellValueFactory(new PropertyValueFactory<>("year"));

        TableColumn<Series, String> ratingCol = new TableColumn<>("Nota");
        ratingCol.setCellValueFactory(new PropertyValueFactory<>("rating"));

        TableColumn<Series, String> genreCol = new TableColumn<>("Género");
        genreCol.setCellValueFactory(new PropertyValueFactory<>("genre"));
        genreCol.setPrefWidth(150);

        TableColumn<Series, String> directorCol = new TableColumn<>("Director");
        directorCol.setCellValueFactory(new PropertyValueFactory<>("director"));
        directorCol.setPrefWidth(150);

        // Nueva columna para calidad
        TableColumn<Series, String> qualityCol = new TableColumn<>("Calidad");
        qualityCol.setCellFactory(column -> {
            return new TableCell<Series, String>() {
                private final ComboBox<ConnectDataBase.Quality> qualityCombo = new ComboBox<>();

                {
                    qualityCombo.setOnAction(event -> {
                        if (getTableRow() != null && getTableRow().getItem() != null) {
                            Series series = getTableRow().getItem();
                            // Guardar la calidad seleccionada para usarla al mostrar los detalles
                            ConnectDataBase.Quality selectedQuality = qualityCombo.getValue();
                            if (selectedQuality != null) {
                                // Podríamos almacenar esta selección en un mapa para recordarla
                                // cuando el usuario haga clic en la serie
                            }
                        }
                    });
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);

                    if (empty) {
                        setGraphic(null);
                    } else {
                        Series series = getTableRow() != null ? getTableRow().getItem() : null;
                        if (series != null) {
                            // Cargar solo las calidades disponibles para esta serie específica
                            ObservableList<ConnectDataBase.Quality> qualities = connectDataBase.getQualitiesForSeries(series.getId());
                            qualityCombo.setItems(qualities);

                            // Seleccionar la primera calidad por defecto
                            if (!qualities.isEmpty()) {
                                qualityCombo.setValue(qualities.get(0));
                            }

                            setGraphic(qualityCombo);
                        } else {
                            setGraphic(null);
                        }
                    }
                }
            };
        });

        seriesTable.getColumns().addAll(nameCol, yearCol, ratingCol, genreCol, directorCol, qualityCol);

        seriesTable.setRowFactory(tv -> {
            TableRow<Series> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    Series rowData = row.getItem();
                    // Obtener la calidad seleccionada en la fila
                    ConnectDataBase.Quality selectedQuality = null;

                    // Iterar por las celdas de la fila para encontrar la que contiene el ComboBox
                    for (int i = 0; i < row.getChildrenUnmodifiable().size(); i++) {
                        if (i < seriesTable.getColumns().size() && seriesTable.getColumns().get(i) == qualityCol) {
                            TableCell<Series, ?> cell = (TableCell<Series, ?>) row.getChildrenUnmodifiable().get(i);
                            if (cell != null && cell.getGraphic() instanceof ComboBox) {
                                @SuppressWarnings("unchecked")
                                ComboBox<ConnectDataBase.Quality> comboBox = (ComboBox<ConnectDataBase.Quality>) cell.getGraphic();
                                selectedQuality = comboBox.getValue();
                                break;
                            }
                        }
                    }

                    // Mostrar detalles de la serie con la calidad seleccionada
                    showSeriesDetails(rowData, selectedQuality);
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

        seriesLayout.getChildren().addAll(busquedaSection, resultsLabel, seriesTable);
        VBox.setVgrow(seriesTable, Priority.ALWAYS);

        seriesTab.setContent(seriesLayout);

        return seriesTab;
    }

    @NotNull
    private Button getButton(ComboBox<String> filterComboBoxSeries, ComboBox<String> filterOptionsComboBoxSeries, TextField searchField) {
        Button searchSeriesButton = new Button("Buscar");
        searchSeriesButton.setOnAction(e -> {
            String selectedFilter = filterComboBoxSeries.getValue();
            String filterValue = filterOptionsComboBoxSeries.getValue();
            String searchValue = searchField.getText();

            if (selectedFilter != null && filterValue != null && !filterValue.isEmpty()) {
                switch (selectedFilter) {
                    case "Año":
                        buscarSerie(searchValue, filterValue, null, null, null);
                        break;
                    case "Género":
                        buscarSerie(searchValue, null, filterValue, null, null);
                        break;
                    case "Director":
                        buscarSerie(searchValue, null, null, filterValue, null);
                        break;
                    case "Formato":
                        buscarSerie(searchValue, null, null, null, filterValue);
                        break;
                    default:
                        buscarSerie(searchValue);
                }
            } else {
                buscarSerie(searchValue);
            }
        });
        return searchSeriesButton;
    }

    private void updateFilterOptions() {
        String selectedFilter = filterComboBox.getValue();
        filterOptionsComboBox.getItems().clear();

        if (selectedFilter != null) {
            switch (selectedFilter) {
                case "Año":
                    filterOptionsComboBox.getItems().addAll(connectDataBase.getUniqueYears());
                    break;
                case "Género":
                    filterOptionsComboBox.getItems().addAll(connectDataBase.getUniqueGenres());
                    break;
                case "Director":
                    filterOptionsComboBox.getItems().addAll(connectDataBase.getUniqueDirectors());
                    break;
                case "Formato":
                    ObservableList<ConnectDataBase.Quality> qualities = connectDataBase.getQualities();
                    for (ConnectDataBase.Quality quality : qualities) {
                        filterOptionsComboBox.getItems().add(quality.getQuality());
                    }
                    break;
            }
        }
    }

    private void buscarPelicula(String searchValue) {
        System.out.println("Buscando película: " + searchValue);
        runWithLoading(() -> connectDataBase.searchTorrentMovies(searchValue),
                results -> {
                    peliculasTable.setItems(results);
                    System.out.println("Resultados encontrados: " + results.size());
                },
                "Buscando películas...",
                "No se pudieron cargar las películas.");
    }

    private void buscarPelicula(String searchValue, String year, String genre, String director, String quality) {
        System.out.println("Buscando película con filtros: " + searchValue + ", " + year + ", " + genre + ", " + director + ", " + quality);
        runWithLoading(() -> connectDataBase.searchTorrentMoviesWithFilters(searchValue, year, genre, director, quality),
                results -> {
                    peliculasTable.setItems(results);
                    System.out.println("Resultados encontrados: " + results.size());
                },
                "Buscando películas...",
                "No se pudieron cargar las películas.");
    }

    private void buscarSerie(String searchValue) {
        System.out.println("Buscando serie: " + searchValue);
        runWithLoading(() -> connectDataBase.searchTorrentSeries(searchValue),
                results -> {
                    seriesTable.setItems(results);
                    System.out.println("Resultados encontrados: " + results.size());
                },
                "Buscando series...",
                "No se pudieron cargar las series.");
    }

    private void buscarSerie(String searchValue, String year, String genre, String director, String format) {
        System.out.println("Buscando serie con filtros: " + searchValue + ", " + year + ", " + genre + ", " + director + ", " + format);
        runWithLoading(() -> connectDataBase.searchTorrentSeriesWithFilters(searchValue, year, genre, director),
                results -> {
                    seriesTable.setItems(results);
                    System.out.println("Resultados encontrados: " + results.size());
                },
                "Buscando series...",
                "No se pudieron cargar las series.");
    }

    private void showSeriesDetails(Series series) {
        showSeriesDetails(series, null);
    }

    private void showSeriesDetails(Series series, ConnectDataBase.Quality defaultQuality) {
        currentSeries = series;
        seriesLayout.getChildren().clear();

        Label seriesLabel = new Label("Detalles de la Serie: " + series.getName());
        seriesLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 16px;");

        // Selector de calidad global para la serie
        HBox qualityBox = new HBox(10);
        qualityBox.setAlignment(Pos.CENTER_LEFT);
        Label qualityLabel = new Label("Calidad preferida:");
        ComboBox<ConnectDataBase.Quality> globalQualityCombo = new ComboBox<>();

        // Cargar solo las calidades disponibles para esta serie específica
        ObservableList<ConnectDataBase.Quality> qualities = connectDataBase.getQualitiesForSeries(series.getId());
        globalQualityCombo.setItems(qualities);

        // Si se proporcionó una calidad por defecto, usarla
        if (defaultQuality != null) {
            globalQualityCombo.setValue(defaultQuality);
        } else if (!qualities.isEmpty()) {
            globalQualityCombo.setValue(qualities.get(qualities.size() - 1)); // Seleccionar la calidad más alta por defecto
        }

        qualityBox.getChildren().addAll(qualityLabel, globalQualityCombo);

        // Crear pestañas para las temporadas
        TabPane seasonsTabPane = new TabPane();

        // Cargar temporadas desde la base de datos, filtrando por la calidad seleccionada
        Integer qualityId = globalQualityCombo.getValue() != null ? globalQualityCombo.getValue().getId() : null;
        List<ConnectDataBase.Season> seasons = connectDataBase.getSeasonsTorrent(series.getId(), qualityId);

        // Actualizar temporadas cuando cambia la calidad seleccionada
        globalQualityCombo.setOnAction(e -> {
            ConnectDataBase.Quality selectedQuality = globalQualityCombo.getValue();
            if (selectedQuality != null) {
                // Recargar las temporadas con la nueva calidad
                List<ConnectDataBase.Season> updatedSeasons = connectDataBase.getSeasonsTorrent(series.getId(), selectedQuality.getId());
                updateSeasonTabs(seasonsTabPane, updatedSeasons, series, selectedQuality);
            }
        });

        // Crear las pestañas iniciales
        updateSeasonTabs(seasonsTabPane, seasons, series, globalQualityCombo.getValue());

        Button backButton = new Button("Atrás");
        backButton.setStyle("-fx-background-color: #3498db; -fx-text-fill: white;");
        backButton.setOnAction(e -> {
            seriesLayout.getChildren().clear();
            seriesLayout.getChildren().addAll(busquedaSection, new Label("Resultados:"), seriesTable);
            VBox.setVgrow(seriesTable, Priority.ALWAYS);
        });

        seriesLayout.getChildren().addAll(backButton, seriesLabel, qualityBox, seasonsTabPane);
        VBox.setVgrow(seasonsTabPane, Priority.ALWAYS);
    }

    /**
     * Actualiza las pestañas de temporadas con los datos filtrados por calidad
     */
    private void updateSeasonTabs(TabPane seasonsTabPane, List<ConnectDataBase.Season> seasons, Series series, ConnectDataBase.Quality selectedQuality) {
        // Limpiar pestañas existentes
        seasonsTabPane.getTabs().clear();

        for (ConnectDataBase.Season season : seasons) {
            Tab seasonTab = new Tab("Temporada " + season.getSeasonNumber());
            seasonTab.setClosable(false);

            VBox seasonLayout = new VBox(10);
            seasonLayout.setPadding(new Insets(10));

            // Tabla de episodios
            TableView<Episode> episodesTable = new TableView<>();
            episodesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

            TableColumn<Episode, Boolean> selectCol = new TableColumn<>("");
            selectCol.setCellValueFactory(param -> {
                Episode episode = param.getValue();
                SimpleBooleanProperty booleanProp = new SimpleBooleanProperty(false);

                // Verificar si el episodio ya está en la cesta
                boolean isInBasket = downloadBasket.stream()
                        .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                booleanProp.set(isInBasket);

                // Actualizar la cesta cuando cambia la selección
                booleanProp.addListener((obs, oldValue, newValue) -> {
                    if (newValue) {
                        // Añadir a la cesta si no está ya
                        if (!isInBasket) {
                            // Obtener los archivos torrent para este episodio
                            ObservableList<TorrentFile> torrentFiles = connectDataBase.getTorrentFiles(episode.getId());

                            if (!torrentFiles.isEmpty()) {
                                // Usar la calidad seleccionada globalmente
                                ConnectDataBase.Quality quality = selectedQuality;

                                // Buscar el archivo torrent para la calidad seleccionada
                                TorrentFile selectedTorrent = torrentFiles.stream()
                                        .filter(tf -> tf.getQualityId() == quality.getId())
                                        .findFirst()
                                        .orElse(null);

                                // Si no hay torrent para la calidad seleccionada, usar la mejor disponible
                                if (selectedTorrent == null) {
                                    selectedTorrent = torrentFiles.stream()
                                            .sorted((tf1, tf2) -> Integer.compare(tf2.getQualityId(), tf1.getQualityId()))
                                            .findFirst()
                                            .orElse(torrentFiles.get(0));
                                }

                                DownloadBasketItem basketItem = new DownloadBasketItem(
                                        episode.getId(),
                                        series.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                                        "episode",
                                        selectedTorrent.getQuality(),
                                        selectedTorrent.getTorrentLink(),
                                        series.getName(),
                                        season.getSeasonNumber(),
                                        episode.getEpisodeNumber()
                                );

                                downloadBasket.add(basketItem);
                            } else {
                                showAlert(Alert.AlertType.WARNING, "No hay torrents disponibles", "No se encontraron archivos torrent para este episodio.");
                                booleanProp.set(false);
                            }
                        }
                    } else {
                        // Eliminar de la cesta
                        downloadBasket.removeIf(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());
                    }
                });

                return booleanProp;
            });
            selectCol.setCellFactory(p -> {
                CheckBox checkBox = new CheckBox();

                TableCell<Episode, Boolean> cell = new TableCell<>() {
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

            TableColumn<Episode, Integer> episodeNumberCol = new TableColumn<>("Episodio");
            episodeNumberCol.setCellValueFactory(new PropertyValueFactory<>("episodeNumber"));

            TableColumn<Episode, String> titleCol = new TableColumn<>("Título");
            titleCol.setCellValueFactory(new PropertyValueFactory<>("title"));
            titleCol.setPrefWidth(250);

            TableColumn<Episode, Void> actionsCol = new TableColumn<>("Acciones");
            actionsCol.setCellFactory(new Callback<TableColumn<Episode, Void>, TableCell<Episode, Void>>() {
                @Override
                public TableCell<Episode, Void> call(TableColumn<Episode, Void> param) {
                    return new TableCell<Episode, Void>() {
                        private final Button addButton = new Button("Añadir");

                        {
                            addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");

                            addButton.setOnAction(event -> {
                                if (getTableRow() != null && getTableRow().getItem() != null) {
                                    Episode episode = getTableRow().getItem();

                                    // Verificar si ya está en la cesta
                                    boolean isInBasket = downloadBasket.stream()
                                            .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                                    if (isInBasket) {
                                        // Eliminar de la cesta
                                        downloadBasket.removeIf(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());
                                        addButton.setText("Añadir");
                                        addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");

                                        // Actualizar el checkbox
                                        episodesTable.refresh();
                                    } else {
                                        // Obtener los archivos torrent para este episodio con la calidad seleccionada
                                        ObservableList<TorrentFile> torrentFiles = connectDataBase.getTorrentFiles(episode.getId());

                                        // Buscar el archivo torrent para la calidad seleccionada
                                        TorrentFile selectedTorrent = torrentFiles.stream()
                                                .filter(tf -> tf.getQualityId() == selectedQuality.getId())
                                                .findFirst()
                                                .orElse(null);

                                        if (selectedTorrent == null) {
                                            showAlert(Alert.AlertType.WARNING, "Calidad no disponible", "No hay un archivo torrent disponible para la calidad seleccionada.");
                                            return;
                                        }

                                        // Añadir a la cesta
                                        DownloadBasketItem basketItem = new DownloadBasketItem(
                                                episode.getId(),
                                                series.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                                                "episode",
                                                selectedQuality.getQuality(),
                                                selectedTorrent.getTorrentLink(),
                                                series.getName(),
                                                season.getSeasonNumber(),
                                                episode.getEpisodeNumber()
                                        );

                                        downloadBasket.add(basketItem);
                                        addButton.setText("Quitar");
                                        addButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");

                                        // Actualizar el checkbox
                                        episodesTable.refresh();
                                    }
                                }
                            });
                        }

                        @Override
                        protected void updateItem(Void item, boolean empty) {
                            super.updateItem(item, empty);
                            if (empty) {
                                setGraphic(null);
                            } else {
                                // Verificar si el episodio ya está en la cesta
                                if (getTableRow() != null && getTableRow().getItem() != null) {
                                    Episode episode = getTableRow().getItem();
                                    boolean isInBasket = downloadBasket.stream()
                                            .anyMatch(basketItem -> basketItem.getType().equals("episode") && basketItem.getEpisodeId() == episode.getId());

                                    if (isInBasket) {
                                        addButton.setText("Quitar");
                                        addButton.setStyle("-fx-background-color: #e74c3c; -fx-text-fill: white;");
                                    } else {
                                        addButton.setText("Añadir");
                                        addButton.setStyle("-fx-background-color: #2ecc71; -fx-text-fill: white;");
                                    }
                                }

                                setGraphic(addButton);
                            }
                        }
                    };
                }
            });

            episodesTable.getColumns().addAll(selectCol, episodeNumberCol, titleCol, actionsCol);

            // Cargar episodios para esta temporada con la consulta optimizada y filtrada por calidad
            ObservableList<Episode> seasonEpisodes = connectDataBase.getEpisodesForSeason(
                    series.getName(),
                    season.getSeasonNumber(),
                    selectedQuality != null ? selectedQuality.getId() : null
            );
            episodesTable.setItems(seasonEpisodes);

            // Añadir checkbox para seleccionar todos los episodios
            HBox selectAllBox = new HBox(10);
            CheckBox selectAllCheckbox = new CheckBox("Seleccionar todos");
            selectAllCheckbox.setOnAction(e -> {
                boolean selected = selectAllCheckbox.isSelected();

                if (selected) {
                    // Añadir todos los episodios a la cesta
                    for (Episode episode : seasonEpisodes) {
                        // Verificar si ya está en la cesta
                        boolean isInBasket = downloadBasket.stream()
                                .anyMatch(item -> item.getType().equals("episode") && item.getEpisodeId() == episode.getId());

                        if (!isInBasket) {
                            // Obtener los archivos torrent para este episodio
                            ObservableList<TorrentFile> torrentFiles = connectDataBase.getTorrentFiles(episode.getId());

                            if (!torrentFiles.isEmpty()) {
                                // Buscar el archivo torrent para la calidad seleccionada
                                TorrentFile selectedTorrent = torrentFiles.stream()
                                        .filter(tf -> tf.getQualityId() == selectedQuality.getId())
                                        .findFirst()
                                        .orElse(null);

                                // Si no hay torrent para la calidad seleccionada, continuar con el siguiente episodio
                                if (selectedTorrent == null) {
                                    continue;
                                }

                                DownloadBasketItem basketItem = new DownloadBasketItem(
                                        episode.getId(),
                                        series.getName() + " - S" + season.getSeasonNumber() + "E" + episode.getEpisodeNumber() + " - " + episode.getTitle(),
                                        "episode",
                                        selectedTorrent.getQuality(),
                                        selectedTorrent.getTorrentLink(),
                                        series.getName(),
                                        season.getSeasonNumber(),
                                        episode.getEpisodeNumber()
                                );

                                downloadBasket.add(basketItem);
                            }
                        }
                    }
                } else {
                    // Eliminar todos los episodios de esta temporada de la cesta
                    downloadBasket.removeIf(item -> item.getType().equals("episode") && item.getSeasonNumber() == season.getSeasonNumber() && item.getSeriesName().equals(series.getName()));
                }

                // Actualizar la tabla
                episodesTable.refresh();
            });

            selectAllBox.getChildren().add(selectAllCheckbox);

            seasonLayout.getChildren().addAll(selectAllBox, episodesTable);
            VBox.setVgrow(episodesTable, Priority.ALWAYS);

            seasonTab.setContent(seasonLayout);
            seasonsTabPane.getTabs().add(seasonTab);
        }
    }

    public Tab getTab() {
        return tab;
    }

    private void showAlert(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    // Clase para representar una película
    public static class Movie {
        private final int id;
        private final String title;
        private final String year;
        private final String genre;
        private final String director;
        private final Map<Integer, TorrentFile> torrentFilesByQuality;
        private String quality;
        private String torrentLink;

        public Movie(int id, String title, String year, String genre, String director) {
            this.id = id;
            this.title = title;
            this.year = year;
            this.genre = genre;
            this.director = director;
            this.torrentFilesByQuality = new LinkedHashMap<>();
            this.quality = null;
            this.torrentLink = null;
        }

        public void addTorrentFile(TorrentFile torrentFile) {
            if (torrentFile == null) {
                return;
            }
            torrentFilesByQuality.put(torrentFile.getQualityId(), torrentFile);
        }

        public Collection<TorrentFile> getTorrentFiles() {
            return torrentFilesByQuality.values();
        }

        public Optional<TorrentFile> findTorrentFileByQualityId(int qualityId) {
            return Optional.ofNullable(torrentFilesByQuality.get(qualityId));
        }

        public ObservableList<ConnectDataBase.Quality> buildQualityOptions() {
            ObservableList<ConnectDataBase.Quality> qualities = FXCollections.observableArrayList();
            torrentFilesByQuality.values().stream()
                    .sorted(Comparator.comparingInt(TorrentFile::getQualityId))
                    .forEach(file -> qualities.add(new ConnectDataBase.Quality(file.getQualityId(), file.getQuality())));
            return qualities;
        }

        public void selectBestAvailableQuality() {
            torrentFilesByQuality.values().stream()
                    .max(Comparator.comparingInt(TorrentFile::getQualityId))
                    .ifPresent(file -> {
                        this.quality = file.getQuality();
                        this.torrentLink = file.getTorrentLink();
                    });
        }

        // Getter para calidad
        public String getQuality() {
            return quality;
        }

        // Setter para calidad
        public void setQuality(String quality) {
            this.quality = quality;
        }

        public String getTorrentLink() {
            return torrentLink;
        }

        // Setter para torrentLink
        public void setTorrentLink(String torrentLink) {
            this.torrentLink = torrentLink;
        }

        public int getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getYear() {
            return year;
        }

        public String getGenre() {
            return genre;
        }

        public String getDirector() {
            return director;
        }
    }

    // Clase para representar un episodio
    public static class Episode {
        private final int id;
        private final int seasonId;
        private final int episodeNumber;
        private final String title;
        private boolean selected;

        public Episode(int id, int seasonId, int episodeNumber, String title) {
            this.id = id;
            this.seasonId = seasonId;
            this.episodeNumber = episodeNumber;
            this.title = title;
            this.selected = false;
        }

        public int getId() {
            return id;
        }

        public int getSeasonId() {
            return seasonId;
        }

        public int getEpisodeNumber() {
            return episodeNumber;
        }

        public String getTitle() {
            return title;
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }
    }

    // Clase para representar un archivo torrent
    public static class TorrentFile {
        private final int id;
        private final int torrentId;
        private final Integer episodeId;
        private final int qualityId;
        private final String torrentLink;
        private final String quality;

        public TorrentFile(int id, int torrentId, Integer episodeId, int qualityId, String torrentLink, String quality) {
            this.id = id;
            this.torrentId = torrentId;
            this.episodeId = episodeId;
            this.qualityId = qualityId;
            this.torrentLink = torrentLink;
            this.quality = quality;
        }

        public int getId() {
            return id;
        }

        public int getTorrentId() {
            return torrentId;
        }

        public Integer getEpisodeId() {
            return episodeId;
        }

        public int getQualityId() {
            return qualityId;
        }

        public String getTorrentLink() {
            return torrentLink;
        }

        public String getQuality() {
            return quality;
        }
    }

    // Clase para representar un elemento en la cesta de descargas
    public static class DownloadBasketItem {
        private final int id;
        private final String name;
        private final String type; // "movie" o "episode"
        private String quality;
        private String torrentLink;
        private final String seriesName; // Solo para episodios
        private final int seasonNumber; // Solo para episodios
        private final int episodeNumber; // Solo para episodios

        public DownloadBasketItem(int id, String name, String type, String quality, String torrentLink, String seriesName, int seasonNumber, int episodeNumber) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.quality = quality;
            this.torrentLink = torrentLink;
            this.seriesName = seriesName;
            this.seasonNumber = seasonNumber;
            this.episodeNumber = episodeNumber;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getType() {
            return type;
        }

        public String getQuality() {
            return quality;
        }

        public void setQuality(String quality) {
            this.quality = quality;
        }

        public String getTorrentLink() {
            return torrentLink;
        }

        public void setTorrentLink(String torrentLink) {
            this.torrentLink = torrentLink;
        }

        public String getSeriesName() {
            return seriesName;
        }

        public int getSeasonNumber() {
            return seasonNumber;
        }

        public int getEpisodeNumber() {
            return episodeNumber;
        }

        public int getEpisodeId() {
            return id;
        }
    }

    public static class Series {
        private final int id;
        private final String name;
        private final String year;
        private final String rating;
        private final String genre;
        private final String director;

        public Series(int id, String name, String year, String rating, String genre, String director){
            this.id = id;
            this.name = name;
            this.year = year;
            this.rating = rating;
            this.genre = genre;
            this.director = director;
        }

        public int getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getYear() {
            return year;
        }

        public String getRating() {
            return rating;
        }

        public String getGenre() {
            return genre;
        }

        public String getDirector() {
            return director;
        }
    }
}
