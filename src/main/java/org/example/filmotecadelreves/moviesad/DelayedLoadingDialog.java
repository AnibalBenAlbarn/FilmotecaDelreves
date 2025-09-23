package org.example.filmotecadelreves.moviesad;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Simple dialog that shows a loading indicator if an operation takes
 * longer than a configurable delay. The dialog is automatically closed
 * when {@link #stop()} is invoked before the delay expires.
 */
public class DelayedLoadingDialog {
    private final Stage dialogStage;
    private final PauseTransition delay;

    /**
     * Creates a new delayed loading dialog with the default delay of two seconds.
     *
     * @param owner   window that owns the dialog (may be {@code null})
     * @param message message shown under the spinner
     */
    public DelayedLoadingDialog(Window owner, String message) {
        this(owner, message, Duration.seconds(2));
    }

    /**
     * Creates a new delayed loading dialog.
     *
     * @param owner        window that owns the dialog (may be {@code null})
     * @param message      message shown under the spinner
     * @param delayDuration duration to wait before displaying the dialog
     */
    public DelayedLoadingDialog(Window owner, String message, Duration delayDuration) {
        dialogStage = new Stage(StageStyle.UNDECORATED);
        dialogStage.setResizable(false);
        dialogStage.initModality(Modality.WINDOW_MODAL);
        if (owner != null) {
            dialogStage.initOwner(owner);
        }

        VBox layout = new VBox(12);
        layout.setAlignment(Pos.CENTER);
        layout.setPadding(new Insets(20));
        layout.setStyle("-fx-background-color: rgba(30,30,30,0.85); -fx-background-radius: 12px;");

        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setPrefSize(60, 60);

        Label messageLabel = new Label(message);
        messageLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

        layout.getChildren().addAll(indicator, messageLabel);

        Scene scene = new Scene(layout);
        dialogStage.setScene(scene);

        delay = new PauseTransition(delayDuration);
        delay.setOnFinished(event -> {
            if (!dialogStage.isShowing()) {
                dialogStage.show();
                dialogStage.centerOnScreen();
            }
        });

        dialogStage.setOnCloseRequest(event -> event.consume());
    }

    /**
     * Starts the delay countdown. If the delay expires before {@link #stop()} is
     * called, the dialog is shown.
     */
    public void start() {
        Platform.runLater(() -> {
            delay.playFromStart();
        });
    }

    /**
     * Stops the countdown and closes the dialog if it is visible.
     */
    public void stop() {
        Platform.runLater(() -> {
            delay.stop();
            if (dialogStage.isShowing()) {
                dialogStage.close();
            }
        });
    }
}

