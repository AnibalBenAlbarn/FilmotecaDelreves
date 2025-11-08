package org.example.filmotecadelreves.moviesad;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Simple dialog that shows a loading indicator if an operation takes
 * longer than a configurable delay. The dialog is automatically closed
 * when {@link #stop()} is invoked before the delay expires.
 */
public class DelayedLoadingDialog {
    private static final Map<Window, SharedDialog> ACTIVE_DIALOGS = new WeakHashMap<>();
    private static final Object LOCK = new Object();

    private final SharedDialog sharedDialog;
    private final PauseTransition delay;

    /**
     * Creates a new delayed loading dialog with the default delay of two seconds.
     *
     * @param owner   window that owns the dialog (may be {@code null})
     * @param message message shown under the spinner
     */
    public DelayedLoadingDialog(Window owner, String message) {
        this(owner, message, Duration.millis(200));
    }

    /**
     * Creates a new delayed loading dialog.
     *
     * @param owner        window that owns the dialog (may be {@code null})
     * @param message      message shown under the spinner
     * @param delayDuration duration to wait before displaying the dialog
     */
    public DelayedLoadingDialog(Window owner, String message, Duration delayDuration) {
        synchronized (LOCK) {
            sharedDialog = ACTIVE_DIALOGS.computeIfAbsent(owner, SharedDialog::new);
        }

        delay = new PauseTransition(delayDuration);
        delay.setOnFinished(event -> sharedDialog.show(message));
    }

    /**
     * Starts the delay countdown. If the delay expires before {@link #stop()} is
     * called, the dialog is shown.
     */
    public void start() {
        Platform.runLater(() -> {
            sharedDialog.incrementUsage();
            delay.playFromStart();
        });
    }

    /**
     * Stops the countdown and closes the dialog if it is visible.
     */
    public void stop() {
        Platform.runLater(() -> {
            delay.stop();
            sharedDialog.decrementUsage();
            if (!sharedDialog.isInUse()) {
                synchronized (LOCK) {
                    ACTIVE_DIALOGS.entrySet().removeIf(entry -> entry.getValue() == sharedDialog);
                }
            }
        });
    }

    private static class SharedDialog {
        private final Stage stage;
        private final Label messageLabel;
        private int usageCount;

        private SharedDialog(Window owner) {
            stage = new Stage(StageStyle.TRANSPARENT);
            stage.setResizable(false);
            stage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) {
                stage.initOwner(owner);
            }

            VBox layout = new VBox(12);
            layout.setAlignment(Pos.CENTER);
            layout.setPadding(new Insets(20));
            layout.setStyle("-fx-background-color: rgba(30,30,30,0.85); -fx-background-radius: 12px;");

            ProgressIndicator indicator = new ProgressIndicator();
            indicator.setPrefSize(60, 60);

            messageLabel = new Label();
            messageLabel.setStyle("-fx-text-fill: white; -fx-font-size: 14px;");

            layout.getChildren().addAll(indicator, messageLabel);

            Scene scene = new Scene(layout);
            scene.setFill(Color.TRANSPARENT);
            stage.setScene(scene);
            stage.setOnCloseRequest(event -> event.consume());
        }

        private void incrementUsage() {
            usageCount++;
        }

        private void show(String message) {
            messageLabel.setText(message);
            if (!stage.isShowing()) {
                stage.show();
                stage.centerOnScreen();
            }
        }

        private void decrementUsage() {
            if (usageCount > 0) {
                usageCount--;
            }
            if (usageCount == 0) {
                stage.hide();
            }
        }

        private boolean isInUse() {
            return usageCount > 0 || stage.isShowing();
        }
    }
}

