package org.example.filmotecadelreves.moviesad;


import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.RotateTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * A panel that displays database statistics at the bottom of the application
 */
public class DatabaseStatusPanel extends HBox {
    private final ConnectDataBase directDB;
    private final ConnectDataBase torrentDB;

    private final Label directMoviesCountLabel;
    private final Label directSeriesCountLabel;
    private final Label torrentMoviesCountLabel;
    private final Label torrentSeriesCountLabel;

    private final Label directStatusLabel;
    private final Label torrentStatusLabel;
    private final Circle directStatusIndicator;
    private final Circle torrentStatusIndicator;

    private final Label directLastUpdateLabel;
    private final Label torrentLastUpdateLabel;

    private final RotateTransition directRotateTransition;
    private final RotateTransition torrentRotateTransition;

    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm");

    public DatabaseStatusPanel(ConnectDataBase directDB, ConnectDataBase torrentDB) {
        this.directDB = directDB;
        this.torrentDB = torrentDB;

// Configure the panel
        setAlignment(Pos.CENTER_RIGHT);
        setPadding(new Insets(5, 10, 5, 10));
        setSpacing(10);
        setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #cccccc; -fx-border-width: 1 0 0 0;");

// Create the status indicators
        directStatusIndicator = new Circle(5);
        directStatusIndicator.setFill(Color.GREEN);

        torrentStatusIndicator = new Circle(5);
        torrentStatusIndicator.setFill(Color.GREEN);

// Create the rotate transitions for the indicators
        directRotateTransition = new RotateTransition(Duration.seconds(2), directStatusIndicator);
        directRotateTransition.setByAngle(360);
        directRotateTransition.setCycleCount(Animation.INDEFINITE);

        torrentRotateTransition = new RotateTransition(Duration.seconds(2), torrentStatusIndicator);
        torrentRotateTransition.setByAngle(360);
        torrentRotateTransition.setCycleCount(Animation.INDEFINITE);

// Create the labels with smaller font
        Font labelFont = Font.font("System", FontWeight.BOLD, 10);
        Font valueFont = Font.font("System", 10);

// Direct DB Labels
        Label directMoviesLabel = new Label("Direct Películas:");
        directMoviesLabel.setFont(labelFont);
        directMoviesCountLabel = new Label("0");
        directMoviesCountLabel.setFont(valueFont);

        Label directSeriesLabel = new Label("Direct Series:");
        directSeriesLabel.setFont(labelFont);
        directSeriesCountLabel = new Label("0");
        directSeriesCountLabel.setFont(valueFont);

        Label directStatusTextLabel = new Label("Estado Direct:");
        directStatusTextLabel.setFont(labelFont);
        directStatusLabel = new Label("Actualizada");
        directStatusLabel.setFont(valueFont);

        Label directUpdateDateLabel = new Label("Última act. Direct:");
        directUpdateDateLabel.setFont(labelFont);
        directLastUpdateLabel = new Label("N/A");
        directLastUpdateLabel.setFont(valueFont);

// Torrent DB Labels
        Label torrentMoviesLabel = new Label("Torrent Películas:");
        torrentMoviesLabel.setFont(labelFont);
        torrentMoviesCountLabel = new Label("0");
        torrentMoviesCountLabel.setFont(valueFont);

        Label torrentSeriesLabel = new Label("Torrent Series:");
        torrentSeriesLabel.setFont(labelFont);
        torrentSeriesCountLabel = new Label("0");
        torrentSeriesCountLabel.setFont(valueFont);

        Label torrentStatusTextLabel = new Label("Estado Torrent:");
        torrentStatusTextLabel.setFont(labelFont);
        torrentStatusLabel = new Label("Actualizada");
        torrentStatusLabel.setFont(valueFont);

        Label torrentUpdateDateLabel = new Label("Última act. Torrent:");
        torrentUpdateDateLabel.setFont(labelFont);
        torrentLastUpdateLabel = new Label("N/A");
        torrentLastUpdateLabel.setFont(valueFont);

// Add all components to the panel
        getChildren().addAll(
                directMoviesLabel, directMoviesCountLabel,
                directSeriesLabel, directSeriesCountLabel,
                directStatusTextLabel, directStatusIndicator, directStatusLabel,
                directUpdateDateLabel, directLastUpdateLabel,

                torrentMoviesLabel, torrentMoviesCountLabel,
                torrentSeriesLabel, torrentSeriesCountLabel,
                torrentStatusTextLabel, torrentStatusIndicator, torrentStatusLabel,
                torrentUpdateDateLabel, torrentLastUpdateLabel
        );

// Start periodic updates
        startPeriodicUpdates();

// Initial update
        updateStatistics();
    }

    /**
     * Updates the statistics displayed in the panel
     */
    public void updateStatistics() {
// Get Direct DB stats
        int directMoviesCount = 0;
        int directSeriesCount = 0;
        boolean isDirectUpdating = false;
        Date directLastUpdateDate = null;

// Get Torrent DB stats
        int torrentMoviesCount = 0;
        int torrentSeriesCount = 0;
        boolean isTorrentUpdating = false;
        Date torrentLastUpdateDate = null;

// Get Direct DB stats if connected
        if (directDB != null && directDB.isConnected()) {
            try {
                directMoviesCount = getDirectMoviesCount();
                directSeriesCount = getDirectSeriesCount();
                isDirectUpdating = directDB.isUpdating();
                directLastUpdateDate = directDB.getLastUpdateDate();
            } catch (Exception e) {
                System.err.println("Error getting Direct DB stats: " + e.getMessage());
            }
        }

// Get Torrent DB stats if connected
        if (torrentDB != null && torrentDB.isConnected()) {
            try {
                torrentMoviesCount = getTorrentMoviesCount();
                torrentSeriesCount = getTorrentSeriesCount();
                isTorrentUpdating = torrentDB.isUpdating();
                torrentLastUpdateDate = torrentDB.getLastUpdateDate();
            } catch (Exception e) {
                System.err.println("Error getting Torrent DB stats: " + e.getMessage());
            }
        }

// Final values for lambda
        final int finalDirectMoviesCount = directMoviesCount;
        final int finalDirectSeriesCount = directSeriesCount;
        final boolean finalIsDirectUpdating = isDirectUpdating;
        final Date finalDirectLastUpdateDate = directLastUpdateDate;

        final int finalTorrentMoviesCount = torrentMoviesCount;
        final int finalTorrentSeriesCount = torrentSeriesCount;
        final boolean finalIsTorrentUpdating = isTorrentUpdating;
        final Date finalTorrentLastUpdateDate = torrentLastUpdateDate;

        Platform.runLater(() -> {
// Update Direct DB stats
            directMoviesCountLabel.setText(String.valueOf(finalDirectMoviesCount));
            directSeriesCountLabel.setText(String.valueOf(finalDirectSeriesCount));

            if (finalIsDirectUpdating) {
                directStatusLabel.setText("Actualizando");
                directStatusIndicator.setFill(Color.ORANGE);
                if (directRotateTransition.getStatus() != Animation.Status.RUNNING) {
                    directRotateTransition.play();
                }
            } else {
                directStatusLabel.setText("Actualizada");
                directStatusIndicator.setFill(Color.GREEN);
                directRotateTransition.stop();
                directStatusIndicator.setRotate(0);
            }

            if (finalDirectLastUpdateDate != null) {
                directLastUpdateLabel.setText(dateFormat.format(finalDirectLastUpdateDate));
            } else {
                directLastUpdateLabel.setText("N/A");
            }

// Update Torrent DB stats
            torrentMoviesCountLabel.setText(String.valueOf(finalTorrentMoviesCount));
            torrentSeriesCountLabel.setText(String.valueOf(finalTorrentSeriesCount));

            if (finalIsTorrentUpdating) {
                torrentStatusLabel.setText("Actualizando");
                torrentStatusIndicator.setFill(Color.ORANGE);
                if (torrentRotateTransition.getStatus() != Animation.Status.RUNNING) {
                    torrentRotateTransition.play();
                }
            } else {
                torrentStatusLabel.setText("Actualizada");
                torrentStatusIndicator.setFill(Color.GREEN);
                torrentRotateTransition.stop();
                torrentStatusIndicator.setRotate(0);
            }

            if (finalTorrentLastUpdateDate != null) {
                torrentLastUpdateLabel.setText(dateFormat.format(finalTorrentLastUpdateDate));
            } else {
                torrentLastUpdateLabel.setText("N/A");
            }
        });
    }

    /**
     * Starts periodic updates of the statistics
     */
    private void startPeriodicUpdates() {
        Timeline timeline = new Timeline(
                new KeyFrame(Duration.seconds(5), event -> updateStatistics())
        );
        timeline.setCycleCount(Animation.INDEFINITE);
        timeline.play();
    }

    /**
     * Gets the count of movies in the Direct DB
     */
    private int getDirectMoviesCount() {
        if (directDB == null || !directDB.isConnected()) {
            return 0;
        }
        return directDB.getMoviesCount();
    }

    /**
     * Gets the count of series in the Direct DB
     */
    private int getDirectSeriesCount() {
        if (directDB == null || !directDB.isConnected()) {
            return 0;
        }
        return directDB.getSeriesCount();
    }

    /**
     * Gets the count of movies in the Torrent DB
     */
    private int getTorrentMoviesCount() {
        if (torrentDB == null || !torrentDB.isConnected()) {
            return 0;
        }

        try {
// Consulta específica para contar películas en la base de datos de torrent
            Connection conn = torrentDB.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM torrent_downloads WHERE type = 'movie'");

            if (rs.next()) {
                return rs.getInt("count");
            }
        } catch (Exception e) {
            System.err.println("Error counting torrent movies: " + e.getMessage());
        }

        return 0;
    }

    /**
     * Gets the count of series in the Torrent DB
     */
    private int getTorrentSeriesCount() {
        if (torrentDB == null || !torrentDB.isConnected()) {
            return 0;
        }

        try {
// Consulta específica para contar series en la base de datos de torrent
            Connection conn = torrentDB.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT COUNT(*) as count FROM torrent_downloads WHERE type = 'series'");

            if (rs.next()) {
                return rs.getInt("count");
            }
        } catch (Exception e) {
            System.err.println("Error counting torrent series: " + e.getMessage());
        }

        return 0;
    }
}