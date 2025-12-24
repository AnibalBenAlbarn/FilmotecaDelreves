package org.example.filmotecadelreves.UI;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.Cursor;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.example.filmotecadelreves.library.*;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.css.PseudoClass;

public class MiBibliotecaUI {
    private static final Logger LOGGER = Logger.getLogger(MiBibliotecaUI.class.getName());
    private static final PseudoClass SELECTED_PSEUDO_CLASS = PseudoClass.getPseudoClass("selected");
    private final Tab tab;
    private final TabPane libraryTabs = new TabPane();
    private final LibraryConfigManager configManager = new LibraryConfigManager();
    private final LibraryCatalogStore catalogStore = new LibraryCatalogStore();
    private final LibraryScanner libraryScanner = new LibraryScanner();
    private final MetadataScraper metadataScraper = new MetadataScraper();
    private final ObservableList<LibraryEntry> libraries = FXCollections.observableArrayList();
    private final Window owner;
    private final AtomicReference<Node> selectedCard = new AtomicReference<>();

    public MiBibliotecaUI(Window owner) {
        this.owner = owner;
        this.tab = new Tab("Biblioteca");
        this.tab.setClosable(false);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));

        VBox header = new VBox(6);
        Label title = new Label("Biblioteca personal");
        title.getStyleClass().add("library-title");
        Label subtitle = new Label("Gestiona tus pelis y series descargadas con estilo Netflix.");
        subtitle.getStyleClass().add("library-subtitle");
        header.getChildren().addAll(title, subtitle);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_LEFT);
        Button newLibraryButton = new Button("Nueva biblioteca");
        newLibraryButton.setOnAction(event -> openLibraryWizard());
        actions.getChildren().add(newLibraryButton);

        VBox top = new VBox(10, header, actions);
        root.setTop(top);

        libraryTabs.getStyleClass().add("library-tabs");
        root.setCenter(libraryTabs);

        tab.setContent(root);

        loadLibraries();
        if (libraries.isEmpty()) {
            Platform.runLater(this::openLibraryWizard);
        }
    }

    public Tab getTab() {
        return tab;
    }

    private void loadLibraries() {
        configManager.ensureLibrariesFileExists();
        configManager.ensureDataDirExists();
        libraries.setAll(configManager.loadLibraries());
        libraryTabs.getTabs().clear();
        for (LibraryEntry entry : libraries) {
            libraryTabs.getTabs().add(buildLibraryTab(entry));
        }
    }

    private Tab buildLibraryTab(LibraryEntry entry) {
        String suffix = entry.getType() == LibraryEntry.LibraryType.MOVIES ? "Películas" : "Series";
        Tab libraryTab = new Tab(entry.getName() + " · " + suffix);
        libraryTab.setClosable(false);

        BorderPane container = new BorderPane();
        container.setPadding(new Insets(12));

        HBox toolbar = new HBox(10);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        Label locationLabel = new Label("Ruta: " + entry.getRootPath());
        locationLabel.getStyleClass().add("library-location");
        Button scanButton = new Button("Escanear biblioteca");
        Button scrapeButton = new Button("Scrapear metadatos");
        toolbar.getChildren().addAll(locationLabel, scanButton, scrapeButton);

        VBox content = new VBox(12);
        content.getChildren().add(toolbar);

        StackPane mainPane = new StackPane();
        mainPane.setPadding(new Insets(10, 0, 0, 0));
        content.getChildren().add(mainPane);

        libraryTab.setContent(content);

        AtomicReference<Runnable> refreshView = new AtomicReference<>();
        refreshView.set(() -> {
            LibraryCatalog catalog = catalogStore.loadCatalog(configManager.getLibraryDataDir(entry));
            Runnable viewRef = refreshView.get();
            Node view = entry.getType() == LibraryEntry.LibraryType.MOVIES
                    ? buildMoviesView(entry, catalog, viewRef)
                    : buildSeriesView(entry, catalog, viewRef);
            mainPane.getChildren().setAll(view);
        });

        refreshView.get().run();

        scanButton.setOnAction(event -> scanLibrary(entry, mainPane, refreshView.get()));
        scrapeButton.setOnAction(event -> openScrapeDialog(entry, mainPane, refreshView.get()));

        return libraryTab;
    }

    private Node buildMoviesView(LibraryEntry entry, LibraryCatalog catalog, Runnable refreshView) {
        VBox container = new VBox(12);

        TextField searchField = new TextField();
        searchField.setPromptText("Buscar por título, director o género...");

        ComboBox<String> genreFilter = new ComboBox<>();
        ComboBox<String> directorFilter = new ComboBox<>();
        genreFilter.setPromptText("Género");
        directorFilter.setPromptText("Director");

        List<String> genres = catalog.getMovies().stream()
                .flatMap(item -> item.getGenres().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        List<String> directors = catalog.getMovies().stream()
                .map(MediaItem::getDirector)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        genreFilter.getItems().setAll(genres);
        directorFilter.getItems().setAll(directors);

        HBox filters = new HBox(10, searchField, genreFilter, directorFilter);
        filters.setAlignment(Pos.CENTER_LEFT);

        FlowPane grid = new FlowPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setPrefWrapLength(1000);

        Runnable applyFilters = () -> {
            String query = searchField.getText() == null ? "" : searchField.getText().toLowerCase();
            String genre = genreFilter.getValue();
            String director = directorFilter.getValue();
            List<MediaItem> filtered = catalog.getMovies().stream()
                    .filter(item -> query.isBlank() || matchesQuery(item, query))
                    .filter(item -> genre == null || item.getGenres().contains(genre))
                    .filter(item -> director == null || director.equalsIgnoreCase(item.getDirector()))
                    .collect(Collectors.toList());
            grid.getChildren().setAll(filtered.stream()
                    .map(item -> buildMovieCard(entry, catalog, item, refreshView))
                    .collect(Collectors.toList()));
        };

        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyFilters.run());
        genreFilter.valueProperty().addListener((obs, oldValue, newValue) -> applyFilters.run());
        directorFilter.valueProperty().addListener((obs, oldValue, newValue) -> applyFilters.run());

        applyFilters.run();

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("library-scroll");

        container.getChildren().addAll(filters, scrollPane);
        return container;
    }

    private Node buildSeriesView(LibraryEntry entry, LibraryCatalog catalog, Runnable refreshView) {
        VBox container = new VBox(12);

        TextField searchField = new TextField();
        searchField.setPromptText("Buscar series...");

        FlowPane grid = new FlowPane();
        grid.setHgap(16);
        grid.setVgap(16);
        grid.setPrefWrapLength(1000);

        Runnable applyFilters = () -> {
            String query = searchField.getText() == null ? "" : searchField.getText().toLowerCase();
            List<SeriesEntry> filtered = catalog.getSeries().stream()
                    .filter(item -> query.isBlank() || matchesSeriesQuery(item, query))
                    .sorted(Comparator.comparing(SeriesEntry::getTitle))
                    .collect(Collectors.toList());
            grid.getChildren().setAll(filtered.stream()
                    .map(series -> buildSeriesCard(entry, catalog, series, refreshView))
                    .collect(Collectors.toList()));
        };

        searchField.textProperty().addListener((obs, oldValue, newValue) -> applyFilters.run());
        applyFilters.run();

        ScrollPane scrollPane = new ScrollPane(grid);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("library-scroll");

        container.getChildren().addAll(searchField, scrollPane);
        return container;
    }

    private Node buildMovieCard(LibraryEntry entry, LibraryCatalog catalog, MediaItem item, Runnable refreshView) {
        VBox card = new VBox(8);
        card.getStyleClass().add("library-card");
        card.setAlignment(Pos.TOP_LEFT);
        card.setPrefWidth(180);
        ContextMenu contextMenu = createMovieContextMenu(entry, catalog, item, refreshView);
        configureCardInteractions(card, () -> openFile(item.getFilePath()), contextMenu);

        StackPane posterPane = new StackPane();
        posterPane.getStyleClass().add("library-poster");
        Node poster = createPosterContent(item.getPosterPath(), item.getTitle());
        posterPane.getChildren().add(poster);

        Label title = new Label(item.getTitle());
        title.getStyleClass().add("library-card-title");
        Label meta = new Label(buildMeta(item));
        meta.getStyleClass().add("library-card-meta");

        card.getChildren().addAll(posterPane, title, meta);
        return card;
    }

    private Node buildSeriesCard(LibraryEntry entry, LibraryCatalog catalog, SeriesEntry series, Runnable refreshView) {
        VBox card = new VBox(8);
        card.getStyleClass().add("library-card");
        card.setAlignment(Pos.TOP_LEFT);
        card.setPrefWidth(180);
        ContextMenu contextMenu = createSeriesContextMenu(entry, catalog, series, refreshView);
        configureCardInteractions(card, () -> openSeriesDialog(series), contextMenu);

        StackPane posterPane = new StackPane();
        posterPane.getStyleClass().add("library-poster");
        Node poster = createPosterContent(series.getPosterPath(), series.getTitle());
        posterPane.getChildren().add(poster);

        Label title = new Label(series.getTitle());
        title.getStyleClass().add("library-card-title");
        Label meta = new Label(series.getSeasons().size() + " temporadas");
        meta.getStyleClass().add("library-card-meta");

        card.getChildren().addAll(posterPane, title, meta);
        return card;
    }

    private void configureCardInteractions(Region card, Runnable openAction, ContextMenu contextMenu) {
        card.setCursor(Cursor.HAND);
        card.setFocusTraversable(true);
        card.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                selectCard(card);
                if (event.getClickCount() == 2) {
                    openAction.run();
                }
            }
        });
        card.setOnContextMenuRequested(event -> {
            selectCard(card);
            if (contextMenu != null) {
                contextMenu.show(card, event.getScreenX(), event.getScreenY());
            }
        });
        card.setOnKeyPressed(event -> {
            switch (event.getCode()) {
                case ENTER, SPACE -> {
                    selectCard(card);
                    openAction.run();
                }
                default -> {
                }
            }
        });
    }

    private void selectCard(Node card) {
        Node previous = selectedCard.getAndSet(card);
        if (previous != null && previous != card) {
            previous.pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, false);
        }
        card.pseudoClassStateChanged(SELECTED_PSEUDO_CLASS, true);
        card.requestFocus();
    }

    private Node createPosterContent(String posterPath, String title) {
        if (posterPath != null && !posterPath.isBlank()) {
            File file = new File(posterPath);
            if (file.exists()) {
                Image image = new Image(file.toURI().toString(), 160, 240, true, true, true);
                ImageView imageView = new ImageView(image);
                imageView.setFitWidth(160);
                imageView.setFitHeight(240);
                imageView.setPreserveRatio(true);
                return imageView;
            }
        }
        StackPane placeholder = new StackPane();
        Rectangle backdrop = new Rectangle(160, 240);
        backdrop.getStyleClass().add("library-poster-placeholder");
        Label label = new Label(title == null ? "Sin carátula" : title);
        label.getStyleClass().add("library-poster-text");
        label.setWrapText(true);
        label.setMaxWidth(140);
        placeholder.getChildren().addAll(backdrop, label);
        return placeholder;
    }

    private String buildMeta(MediaItem item) {
        StringBuilder builder = new StringBuilder();
        if (item.getYear() != null) {
            builder.append(item.getYear());
        }
        if (item.getDirector() != null && !item.getDirector().isBlank()) {
            if (!builder.isEmpty()) {
                builder.append(" · ");
            }
            builder.append(item.getDirector());
        }
        return builder.toString();
    }

    private boolean matchesQuery(MediaItem item, String query) {
        String normalizedQuery = normalizeSearchText(query);
        if (normalizedQuery.isBlank()) {
            return true;
        }
        List<String> haystack = new ArrayList<>();
        haystack.add(item.getTitle());
        haystack.add(item.getScrapedTitle());
        haystack.add(item.getDirector());
        haystack.add(item.getFilePath());
        if (item.getYear() != null) {
            haystack.add(String.valueOf(item.getYear()));
        }
        haystack.addAll(item.getGenres());
        return haystack.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeSearchText)
                .anyMatch(text -> text.contains(normalizedQuery));
    }

    private boolean matchesSeriesQuery(SeriesEntry series, String query) {
        String normalizedQuery = normalizeSearchText(query);
        if (normalizedQuery.isBlank()) {
            return true;
        }
        List<String> haystack = new ArrayList<>();
        haystack.add(series.getTitle());
        haystack.add(series.getScrapedTitle());
        return haystack.stream()
                .filter(Objects::nonNull)
                .map(this::normalizeSearchText)
                .anyMatch(text -> text.contains(normalizedQuery));
    }

    private String normalizeSearchText(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase();
        normalized = normalized.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
        return normalized.replaceAll("\\s{2,}", " ");
    }

    private void scanLibrary(LibraryEntry entry, StackPane container, Runnable refreshView) {
        ProgressIndicator indicator = new ProgressIndicator();
        container.getChildren().setAll(indicator);

        Task<LibraryCatalog> scanTask = new Task<>() {
            @Override
            protected LibraryCatalog call() throws Exception {
                return libraryScanner.scanLibrary(entry);
            }
        };

        scanTask.setOnSucceeded(event -> {
            LibraryCatalog catalog = scanTask.getValue();
            catalogStore.saveCatalog(configManager.getLibraryDataDir(entry), catalog);
            refreshView.run();
        });
        scanTask.setOnFailed(event -> {
            showAlert("Error", "No se pudo escanear la biblioteca: " + scanTask.getException().getMessage());
            refreshView.run();
        });

        new Thread(scanTask).start();
    }

    private void openScrapeDialog(LibraryEntry entry, StackPane container, Runnable refreshView) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Scrapear metadatos");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<MetadataScraper.Provider> providerComboBox = new ComboBox<>();
        providerComboBox.getItems().setAll(MetadataScraper.Provider.values());
        providerComboBox.setValue(resolveProvider(entry.getScraperProvider()));

        TextField apiKeyField = new TextField();
        apiKeyField.setPromptText("Token / ApiKey");
        apiKeyField.setText(entry.getScraperApiKey() == null ? "" : entry.getScraperApiKey());

        VBox content = new VBox(10,
                new Label("Proveedor"), providerComboBox,
                new Label("Token / ApiKey"), apiKeyField,
                new Label("El scraping descargará carátulas y metadatos para filtros."));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(button -> button);

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }

        MetadataScraper.Provider provider = providerComboBox.getValue();
        if (provider == MetadataScraper.Provider.TRAKT || provider == MetadataScraper.Provider.JUSTWATCH) {
            showAlert("No disponible", "Este proveedor aún no está implementado.");
            return;
        }

        entry.setScraperProvider(provider.name());
        String rawToken = apiKeyField.getText();
        String normalizedToken = normalizeToken(rawToken);
        entry.setScraperApiKey(normalizedToken);
        configManager.saveLibraries(libraries);
        LOGGER.info(() -> "Iniciando scraping para biblioteca '" + entry.getName() + "' con proveedor " + provider);

        ProgressBar progressBar = new ProgressBar(0);
        Label status = new Label("Iniciando scraping...");
        VBox progressBox = new VBox(10, status, progressBar);
        progressBox.setAlignment(Pos.CENTER);
        container.getChildren().setAll(progressBox);

        Task<LibraryCatalog> scrapeTask = new Task<>() {
            @Override
            protected LibraryCatalog call() throws Exception {
                LibraryCatalog catalog = catalogStore.loadCatalog(configManager.getLibraryDataDir(entry));
                int total = entry.getType() == LibraryEntry.LibraryType.MOVIES
                        ? catalog.getMovies().size()
                        : catalog.getSeries().size();
                int index = 0;
                for (MediaItem item : catalog.getMovies()) {
                    if (entry.getType() != LibraryEntry.LibraryType.MOVIES) {
                        break;
                    }
                    index++;
                    updateMessage("Scrapeando " + item.getTitle() + " (" + index + "/" + total + ")");
                    updateProgress(index, total);
                    MediaMetadata metadata;
                    try {
                        metadata = metadataScraper.fetchMovie(item.getTitle(), provider, normalizedToken);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error scrapeando película '" + item.getTitle() + "'", e);
                        throw e;
                    }
                    applyMetadata(entry, item, metadata);
                }
                for (SeriesEntry series : catalog.getSeries()) {
                    if (entry.getType() != LibraryEntry.LibraryType.SERIES) {
                        break;
                    }
                    index++;
                    updateMessage("Scrapeando " + series.getTitle() + " (" + index + "/" + total + ")");
                    updateProgress(index, total);
                    MediaMetadata metadata;
                    try {
                        metadata = metadataScraper.fetchSeries(series.getTitle(), provider, normalizedToken);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error scrapeando serie '" + series.getTitle() + "'", e);
                        throw e;
                    }
                    applyMetadata(entry, series, metadata);
                }
                return catalog;
            }
        };

        progressBar.progressProperty().bind(scrapeTask.progressProperty());
        status.textProperty().bind(scrapeTask.messageProperty());

        scrapeTask.setOnSucceeded(event -> {
            LibraryCatalog updated = scrapeTask.getValue();
            catalogStore.saveCatalog(configManager.getLibraryDataDir(entry), updated);
            refreshView.run();
        });

        scrapeTask.setOnFailed(event -> {
            showAlert("Error", "No se pudo completar el scraping: " + scrapeTask.getException().getMessage());
            refreshView.run();
        });

        new Thread(scrapeTask).start();
    }

    private void applyMetadata(LibraryEntry entry, MediaItem item, MediaMetadata metadata) throws IOException, InterruptedException {
        if (metadata == null) {
            return;
        }
        item.setScrapedTitle(metadata.getTitle());
        item.setYear(metadata.getYear());
        item.setDirector(metadata.getDirector());
        item.setOverview(metadata.getOverview());
        item.setGenres(metadata.getGenres());
        if (metadata.getPosterUrl() != null) {
            Path posterPath = configManager.getLibraryDataDir(entry)
                    .resolve("posters")
                    .resolve(item.getId() + ".jpg");
            metadataScraper.downloadImage(metadata.getPosterUrl(), posterPath);
            item.setPosterPath(posterPath.toString());
        }
    }

    private void applyMetadata(LibraryEntry entry, SeriesEntry series, MediaMetadata metadata) throws IOException, InterruptedException {
        if (metadata == null) {
            return;
        }
        series.setScrapedTitle(metadata.getTitle());
        if (metadata.getPosterUrl() != null) {
            Path posterPath = configManager.getLibraryDataDir(entry)
                    .resolve("posters")
                    .resolve(series.getId() + ".jpg");
            metadataScraper.downloadImage(metadata.getPosterUrl(), posterPath);
            series.setPosterPath(posterPath.toString());
        }
    }

    private void openSeriesDialog(SeriesEntry series) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle(series.getTitle());

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));

        TreeItem<SeriesNode> rootItem = new TreeItem<>(new SeriesNode(series.getTitle(), null));
        rootItem.setExpanded(true);
        for (Map.Entry<Integer, List<EpisodeItem>> season : series.getSeasons().entrySet()) {
            TreeItem<SeriesNode> seasonItem = new TreeItem<>(new SeriesNode("Temporada " + season.getKey(), null));
            for (EpisodeItem episode : season.getValue()) {
                TreeItem<SeriesNode> episodeItem = new TreeItem<>(new SeriesNode(episode.getTitle(), episode));
                seasonItem.getChildren().add(episodeItem);
            }
            rootItem.getChildren().add(seasonItem);
        }
        TreeView<SeriesNode> treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(false);
        treeView.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(SeriesNode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setContextMenu(null);
                } else {
                    setText(item.getLabel());
                    if (item.getEpisode() != null) {
                        setContextMenu(createEpisodeContextMenu(item.getEpisode()));
                    } else {
                        setContextMenu(null);
                    }
                }
            }
        });
        treeView.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            TreeItem<SeriesNode> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() != null && selected.getValue().getEpisode() != null) {
                openFile(selected.getValue().getEpisode().getFilePath());
            }
        });

        root.setCenter(treeView);

        Scene scene = new Scene(root, 480, 520);
        dialog.setScene(scene);
        dialog.showAndWait();
    }

    private ContextMenu createMovieContextMenu(LibraryEntry entry, LibraryCatalog catalog, MediaItem item, Runnable refreshView) {
        MenuItem openItem = new MenuItem("Ver película");
        openItem.setOnAction(event -> openFile(item.getFilePath()));

        MenuItem revealItem = new MenuItem("Mostrar en el explorador");
        revealItem.setOnAction(event -> openInExplorer(item.getFilePath()));

        MenuItem propertiesItem = new MenuItem("Propiedades");
        propertiesItem.setOnAction(event -> showMovieProperties(item));

        MenuItem manualScrape = new MenuItem("Scrapear manualmente...");
        manualScrape.setOnAction(event -> openManualScrapeDialog(entry, catalog, item, refreshView));

        return new ContextMenu(openItem, revealItem, propertiesItem, manualScrape);
    }

    private ContextMenu createSeriesContextMenu(LibraryEntry entry, LibraryCatalog catalog, SeriesEntry series, Runnable refreshView) {
        MenuItem openItem = new MenuItem("Ver temporadas");
        openItem.setOnAction(event -> openSeriesDialog(series));

        MenuItem revealItem = new MenuItem("Mostrar en el explorador");
        revealItem.setOnAction(event -> {
            String filePath = findSeriesRepresentativeFile(series);
            if (filePath != null) {
                openInExplorer(filePath);
            } else {
                showAlert("Sin archivos", "No se encontró un episodio para mostrar.");
            }
        });

        MenuItem propertiesItem = new MenuItem("Propiedades");
        propertiesItem.setOnAction(event -> showSeriesProperties(series));

        MenuItem manualScrape = new MenuItem("Scrapear manualmente...");
        manualScrape.setOnAction(event -> openManualScrapeDialog(entry, catalog, series, refreshView));

        return new ContextMenu(openItem, revealItem, propertiesItem, manualScrape);
    }

    private ContextMenu createEpisodeContextMenu(EpisodeItem episode) {
        MenuItem openItem = new MenuItem("Ver episodio");
        openItem.setOnAction(event -> openFile(episode.getFilePath()));

        MenuItem revealItem = new MenuItem("Mostrar en el explorador");
        revealItem.setOnAction(event -> openInExplorer(episode.getFilePath()));

        MenuItem propertiesItem = new MenuItem("Propiedades");
        propertiesItem.setOnAction(event -> showEpisodeProperties(episode));

        return new ContextMenu(openItem, revealItem, propertiesItem);
    }

    private void openManualScrapeDialog(LibraryEntry entry, LibraryCatalog catalog, MediaItem item, Runnable refreshView) {
        MetadataScraper.Provider provider = resolveProvider(entry.getScraperProvider());
        if (provider != MetadataScraper.Provider.TMDB) {
            showAlert("No disponible", "La búsqueda manual solo está disponible con TMDB.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Scrapeo manual");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField titleField = new TextField(item.getScrapedTitle() == null ? item.getTitle() : item.getScrapedTitle());
        TextField yearField = new TextField(item.getYear() == null ? "" : String.valueOf(item.getYear()));
        TextField directorField = new TextField(item.getDirector() == null ? "" : item.getDirector());
        TextField genreField = new TextField(item.getGenres().isEmpty() ? "" : String.join(", ", item.getGenres()));

        ListView<MetadataSearchResult> results = new ListView<>();
        results.setPrefHeight(220);

        Button searchButton = new Button("Buscar");
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setVisible(false);

        searchButton.setOnAction(event -> {
            Integer year = parseYearField(yearField.getText());
            String director = blankToNull(directorField.getText());
            String genre = blankToNull(genreField.getText());
            String query = titleField.getText().trim();
            if (query.isBlank()) {
                showAlert("Falta información", "Escribe un título para buscar.");
                return;
            }
            indicator.setVisible(true);
            results.getItems().clear();
            Task<List<MetadataSearchResult>> task = new Task<>() {
                @Override
                protected List<MetadataSearchResult> call() throws Exception {
                    return metadataScraper.searchMovies(query, year, director, genre, provider, entry.getScraperApiKey());
                }
            };
            task.setOnSucceeded(done -> {
                results.getItems().setAll(task.getValue());
                indicator.setVisible(false);
            });
            task.setOnFailed(done -> {
                indicator.setVisible(false);
                showAlert("Error", "No se pudo buscar: " + task.getException().getMessage());
            });
            new Thread(task).start();
        });

        HBox searchRow = new HBox(10, searchButton, indicator);
        VBox content = new VBox(10,
                new Label("Título"), titleField,
                new Label("Año"), yearField,
                new Label("Director"), directorField,
                new Label("Género"), genreField,
                searchRow,
                new Label("Resultados"), results);
        dialog.getDialogPane().setContent(content);

        Button applyButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (results.getSelectionModel().getSelectedItem() == null) {
                showAlert("Selecciona una opción", "Elige un resultado antes de aplicar.");
                event.consume();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }
        MetadataSearchResult selected = results.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Task<Void> applyTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                MediaMetadata metadata = metadataScraper.fetchTmdbById(selected.getId(), "movie", entry.getScraperApiKey());
                applyMetadata(entry, item, metadata);
                return null;
            }
        };
        applyTask.setOnSucceeded(done -> {
            catalogStore.saveCatalog(configManager.getLibraryDataDir(entry), catalog);
            refreshView.run();
        });
        applyTask.setOnFailed(done -> showAlert("Error", "No se pudo aplicar el scraping: " + applyTask.getException().getMessage()));
        new Thread(applyTask).start();
    }

    private void openManualScrapeDialog(LibraryEntry entry, LibraryCatalog catalog, SeriesEntry series, Runnable refreshView) {
        MetadataScraper.Provider provider = resolveProvider(entry.getScraperProvider());
        if (provider != MetadataScraper.Provider.TMDB) {
            showAlert("No disponible", "La búsqueda manual solo está disponible con TMDB.");
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Scrapeo manual");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        TextField titleField = new TextField(series.getScrapedTitle() == null ? series.getTitle() : series.getScrapedTitle());
        TextField yearField = new TextField();
        TextField directorField = new TextField();
        TextField genreField = new TextField();

        ListView<MetadataSearchResult> results = new ListView<>();
        results.setPrefHeight(220);

        Button searchButton = new Button("Buscar");
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setVisible(false);

        searchButton.setOnAction(event -> {
            Integer year = parseYearField(yearField.getText());
            String director = blankToNull(directorField.getText());
            String genre = blankToNull(genreField.getText());
            String query = titleField.getText().trim();
            if (query.isBlank()) {
                showAlert("Falta información", "Escribe un título para buscar.");
                return;
            }
            indicator.setVisible(true);
            results.getItems().clear();
            Task<List<MetadataSearchResult>> task = new Task<>() {
                @Override
                protected List<MetadataSearchResult> call() throws Exception {
                    return metadataScraper.searchSeries(query, year, director, genre, provider, entry.getScraperApiKey());
                }
            };
            task.setOnSucceeded(done -> {
                results.getItems().setAll(task.getValue());
                indicator.setVisible(false);
            });
            task.setOnFailed(done -> {
                indicator.setVisible(false);
                showAlert("Error", "No se pudo buscar: " + task.getException().getMessage());
            });
            new Thread(task).start();
        });

        HBox searchRow = new HBox(10, searchButton, indicator);
        VBox content = new VBox(10,
                new Label("Título"), titleField,
                new Label("Año"), yearField,
                new Label("Creador/Director"), directorField,
                new Label("Género"), genreField,
                searchRow,
                new Label("Resultados"), results);
        dialog.getDialogPane().setContent(content);

        Button applyButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        applyButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (results.getSelectionModel().getSelectedItem() == null) {
                showAlert("Selecciona una opción", "Elige un resultado antes de aplicar.");
                event.consume();
            }
        });

        Optional<ButtonType> result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return;
        }
        MetadataSearchResult selected = results.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Task<Void> applyTask = new Task<>() {
            @Override
            protected Void call() throws Exception {
                MediaMetadata metadata = metadataScraper.fetchTmdbById(selected.getId(), "tv", entry.getScraperApiKey());
                applyMetadata(entry, series, metadata);
                return null;
            }
        };
        applyTask.setOnSucceeded(done -> {
            catalogStore.saveCatalog(configManager.getLibraryDataDir(entry), catalog);
            refreshView.run();
        });
        applyTask.setOnFailed(done -> showAlert("Error", "No se pudo aplicar el scraping: " + applyTask.getException().getMessage()));
        new Thread(applyTask).start();
    }

    private Integer parseYearField(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private void showMovieProperties(MediaItem item) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Propiedades");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);

        GridPane grid = buildPropertiesGrid();
        addPropertyRow(grid, 0, "Nombre original", item.getTitle());
        addPropertyRow(grid, 1, "Nombre scrappeado", item.getScrapedTitle());
        addPropertyRow(grid, 2, "Año", item.getYear() == null ? null : String.valueOf(item.getYear()));
        addPropertyRow(grid, 3, "Director", item.getDirector());
        addPropertyRow(grid, 4, "Género", item.getGenres().isEmpty() ? null : String.join(", ", item.getGenres()));
        addPropertyRow(grid, 5, "Formato", getFileExtension(item.getFilePath()));
        addPropertyRow(grid, 6, "Archivo", new File(item.getFilePath()).getName());
        addPropertyRow(grid, 7, "Ruta", item.getFilePath());
        dialog.getDialogPane().setContent(grid);
        dialog.showAndWait();
    }

    private void showSeriesProperties(SeriesEntry series) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Propiedades");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);

        GridPane grid = buildPropertiesGrid();
        addPropertyRow(grid, 0, "Nombre original", series.getTitle());
        addPropertyRow(grid, 1, "Nombre scrappeado", series.getScrapedTitle());
        addPropertyRow(grid, 2, "Temporadas", String.valueOf(series.getSeasons().size()));
        long episodes = series.getSeasons().values().stream().mapToLong(List::size).sum();
        addPropertyRow(grid, 3, "Episodios", String.valueOf(episodes));
        String sample = findSeriesRepresentativeFile(series);
        if (sample != null) {
            addPropertyRow(grid, 4, "Carpeta", new File(sample).getParent());
        }
        dialog.getDialogPane().setContent(grid);
        dialog.showAndWait();
    }

    private void showEpisodeProperties(EpisodeItem episode) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Propiedades");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.OK);

        GridPane grid = buildPropertiesGrid();
        addPropertyRow(grid, 0, "Título", episode.getTitle());
        addPropertyRow(grid, 1, "Temporada", String.valueOf(episode.getSeason()));
        addPropertyRow(grid, 2, "Episodio", String.valueOf(episode.getEpisode()));
        addPropertyRow(grid, 3, "Formato", getFileExtension(episode.getFilePath()));
        addPropertyRow(grid, 4, "Archivo", new File(episode.getFilePath()).getName());
        addPropertyRow(grid, 5, "Ruta", episode.getFilePath());
        dialog.getDialogPane().setContent(grid);
        dialog.showAndWait();
    }

    private GridPane buildPropertiesGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(6);
        grid.setPadding(new Insets(10));
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(120);
        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelCol, valueCol);
        return grid;
    }

    private void addPropertyRow(GridPane grid, int row, String label, String value) {
        Label labelNode = new Label(label + ":");
        Label valueNode = new Label(value == null || value.isBlank() ? "—" : value);
        valueNode.setWrapText(true);
        grid.add(labelNode, 0, row);
        grid.add(valueNode, 1, row);
    }

    private String getFileExtension(String filePath) {
        if (filePath == null) {
            return "";
        }
        int index = filePath.lastIndexOf('.');
        if (index < 0 || index == filePath.length() - 1) {
            return "";
        }
        return filePath.substring(index + 1).toLowerCase();
    }

    private String findSeriesRepresentativeFile(SeriesEntry series) {
        return series.getSeasons().values().stream()
                .flatMap(List::stream)
                .findFirst()
                .map(EpisodeItem::getFilePath)
                .orElse(null);
    }

    private void openInExplorer(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }
        File file = new File(filePath);
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer", "/select,", file.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", file.getAbsolutePath()).start();
            } else {
                File parent = file.getParentFile();
                if (parent != null) {
                    new ProcessBuilder("xdg-open", parent.getAbsolutePath()).start();
                }
            }
        } catch (IOException e) {
            showAlert("Error", "No se pudo abrir el explorador: " + e.getMessage());
        }
    }

    private void openFile(String filePath) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(new File(filePath));
            } else {
                showAlert("Error", "Tu sistema no soporta apertura directa de archivos.");
            }
        } catch (IOException e) {
            showAlert("Error", "No se pudo abrir el archivo: " + e.getMessage());
        }
    }

    private void openLibraryWizard() {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.setTitle("Nueva biblioteca");

        AtomicReference<LibraryEntry.LibraryType> selectedType = new AtomicReference<>(LibraryEntry.LibraryType.MOVIES);
        TextField nameField = new TextField();
        nameField.setPromptText("Nombre de la biblioteca");

        ToggleGroup typeGroup = new ToggleGroup();
        RadioButton movies = new RadioButton("Películas");
        movies.setToggleGroup(typeGroup);
        movies.setSelected(true);
        RadioButton series = new RadioButton("Series");
        series.setToggleGroup(typeGroup);

        VBox stepOne = new VBox(10,
                new Label("Paso 1 · Tipología"),
                nameField,
                new HBox(10, movies, series));
        stepOne.setPadding(new Insets(12));

        TextField pathField = new TextField();
        pathField.setPromptText("Carpeta raíz");
        Button browse = new Button("Buscar carpeta");
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            File folder = chooser.showDialog(dialog);
            if (folder != null) {
                pathField.setText(folder.getAbsolutePath());
            }
        });

        VBox stepTwo = new VBox(10,
                new Label("Paso 2 · Selecciona la carpeta"),
                new HBox(10, pathField, browse));
        stepTwo.setPadding(new Insets(12));

        Label summary = new Label();
        summary.setWrapText(true);
        VBox stepThree = new VBox(10,
                new Label("Paso 3 · Confirmación"),
                summary);
        stepThree.setPadding(new Insets(12));

        StackPane steps = new StackPane(stepOne, stepTwo, stepThree);
        stepTwo.setVisible(false);
        stepThree.setVisible(false);

        Button back = new Button("Atrás");
        Button next = new Button("Siguiente");
        Button finish = new Button("Crear biblioteca");
        finish.setVisible(false);
        back.setDisable(true);

        HBox controls = new HBox(10, back, next, finish);
        controls.setAlignment(Pos.CENTER_RIGHT);

        VBox layout = new VBox(10, steps, controls);
        layout.setPadding(new Insets(12));

        Scene scene = new Scene(layout, 480, 260);
        dialog.setScene(scene);

        final int[] stepIndex = {0};

        Runnable updateStep = () -> {
            stepOne.setVisible(stepIndex[0] == 0);
            stepTwo.setVisible(stepIndex[0] == 1);
            stepThree.setVisible(stepIndex[0] == 2);
            back.setDisable(stepIndex[0] == 0);
            next.setVisible(stepIndex[0] < 2);
            finish.setVisible(stepIndex[0] == 2);
            if (stepIndex[0] == 2) {
                summary.setText("Nombre: " + nameField.getText() + "\nTipo: " +
                        (movies.isSelected() ? "Películas" : "Series") + "\nRuta: " + pathField.getText());
            }
        };

        next.setOnAction(event -> {
            if (stepIndex[0] == 0) {
                selectedType.set(movies.isSelected() ? LibraryEntry.LibraryType.MOVIES : LibraryEntry.LibraryType.SERIES);
                if (nameField.getText().isBlank()) {
                    showAlert("Falta información", "Indica un nombre para la biblioteca.");
                    return;
                }
            }
            if (stepIndex[0] == 1 && pathField.getText().isBlank()) {
                showAlert("Falta información", "Selecciona una carpeta para analizar.");
                return;
            }
            stepIndex[0]++;
            updateStep.run();
        });

        back.setOnAction(event -> {
            stepIndex[0]--;
            updateStep.run();
        });

        finish.setOnAction(event -> {
            LibraryEntry entry = configManager.createLibrary(
                    nameField.getText().trim(),
                    selectedType.get(),
                    pathField.getText().trim()
            );
            libraries.add(entry);
            configManager.saveLibraries(libraries);
            libraryTabs.getTabs().add(buildLibraryTab(entry));
            dialog.close();
        });

        dialog.showAndWait();
    }

    private void showAlert(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    private MetadataScraper.Provider resolveProvider(String value) {
        if (value == null) {
            return MetadataScraper.Provider.TMDB;
        }
        try {
            return MetadataScraper.Provider.valueOf(value);
        } catch (IllegalArgumentException e) {
            return MetadataScraper.Provider.TMDB;
        }
    }

    private static class SeriesNode {
        private final String label;
        private final EpisodeItem episode;

        private SeriesNode(String label, EpisodeItem episode) {
            this.label = label;
            this.episode = episode;
        }

        public String getLabel() {
            return label;
        }

        public EpisodeItem getEpisode() {
            return episode;
        }
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        return trimmed.isBlank() ? null : trimmed;
    }
}
