package org.example.filmotecadelreves.moviesad;


import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * Diálogo para mostrar el progreso de una operación
 */
public class ProgressDialog {
    private final Stage dialogStage;
    private final ProgressBar progressBar;
    private final Label statusLabel;
    private Label countdownLabel;

    /**
     * Constructor del diálogo de progreso
     * @param title Título del diálogo
     * @param initialStatus Estado inicial a mostrar
     */
    public ProgressDialog(String title, String initialStatus) {
        dialogStage = new Stage();
        dialogStage.setTitle(title);
        dialogStage.setResizable(false);
        dialogStage.initModality(Modality.APPLICATION_MODAL);

        VBox layout = new VBox(10);
        layout.setPadding(new Insets(20));
        layout.setAlignment(Pos.CENTER);

        statusLabel = new Label(initialStatus);
        progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);

        layout.getChildren().addAll(statusLabel, progressBar);

        Scene scene = new Scene(layout);
        dialogStage.setScene(scene);
    }

    /**
     * Constructor del diálogo de progreso con cuenta atrás opcional
     * @param title Título del diálogo
     * @param initialStatus Estado inicial a mostrar
     * @param showCountdown Si se debe mostrar una cuenta atrás
     */
    public ProgressDialog(String title, String initialStatus, boolean showCountdown) {
        this(title, initialStatus);

        if (showCountdown) {
            VBox layout = (VBox) dialogStage.getScene().getRoot();
            countdownLabel = new Label("");
            layout.getChildren().add(countdownLabel);
        }
    }

    /**
     * Muestra el diálogo
     */
    public void show() {
        dialogStage.show();
    }

    /**
     * Cierra el diálogo
     */
    public void close() {
        Platform.runLater(() -> dialogStage.close());
    }

    /**
     * Actualiza el progreso y el estado del diálogo
     * @param status Texto de estado a mostrar
     * @param progress Progreso (0-1)
     */
    public void updateProgress(String status, double progress) {
        Platform.runLater(() -> {
            statusLabel.setText(status);
            progressBar.setProgress(progress);
        });
    }

    /**
     * Actualiza el texto de la cuenta atrás
     * @param countdownText Texto de la cuenta atrás
     */
    public void updateCountdown(String countdownText) {
        if (countdownLabel != null) {
            Platform.runLater(() -> countdownLabel.setText(countdownText));
        }
    }

    /**
     * Verifica si el diálogo está cerrado
     * @return true si el diálogo está cerrado, false en caso contrario
     */
    public boolean isClosed() {
        return !dialogStage.isShowing();
    }
}