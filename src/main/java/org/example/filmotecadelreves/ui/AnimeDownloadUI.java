package org.example.filmotecadelreves.ui;

import org.example.filmotecadelreves.moviesad.ConnectDataBase;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Simple UI component for browsing anime downloads.
 */
public class AnimeDownloadUI {
    private final Tab tab;
    private final ConnectDataBase connectDataBase;

    public AnimeDownloadUI() {
        this.connectDataBase = new ConnectDataBase("directanime_dw_db");

        tab = new Tab("Anime Download");
        tab.setClosable(false);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        TabPane subTabs = new TabPane();
        subTabs.getTabs().add(createAnimeTab());

        layout.getChildren().add(subTabs);

        tab.setContent(layout);
    }

    private Tab createAnimeTab() {
        Tab animeTab = new Tab("Anime");

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(10));

        // Search section
        VBox searchSection = new VBox(10);
        searchSection.setPadding(new Insets(10));
        searchSection.setStyle("-fx-border-color: black; -fx-border-width: 1; -fx-border-radius: 5;");
        Label searchLabel = new Label("Búsqueda:");
        TextField searchField = new TextField();
        searchField.setPromptText("Buscar...");
        Button searchButton = new Button("Buscar");
        searchSection.getChildren().addAll(searchLabel, searchField, searchButton);

        // Results section
        VBox resultsSection = new VBox(10);
        resultsSection.setPadding(new Insets(10));
        resultsSection.setStyle("-fx-border-color: black; -fx-border-width: 1; -fx-border-radius: 5;");
        Label resultsLabel = new Label("Resultados de la búsqueda:");
        TableView<String> animeTable = new TableView<>();
        animeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        animeTable.getColumns().add(createColumn("Nombre"));
        animeTable.getColumns().add(createColumn("Link"));
        resultsSection.getChildren().addAll(resultsLabel, animeTable);

        layout.getChildren().addAll(searchSection, resultsSection);

        animeTab.setContent(layout);

        // Search actions
        searchButton.setOnAction(event -> searchAnime(animeTable, searchField.getText()));

        return animeTab;
    }

    private TableColumn<String, String> createColumn(String title) {
        TableColumn<String, String> column = new TableColumn<>(title);
        column.setMinWidth(100);
        return column;
    }

    private void searchAnime(TableView<String> table, String query) {
        try (Connection connection = connectDataBase.getConnection();
             Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM anime WHERE name LIKE '%" + query + "%'");) {

            table.getItems().clear();
            while (rs.next()) {
                String anime = rs.getString("link");
                table.getItems().add(anime);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public Tab getTab() {
        return tab;
    }
}
