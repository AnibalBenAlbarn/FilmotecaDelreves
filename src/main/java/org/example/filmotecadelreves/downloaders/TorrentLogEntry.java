package org.example.filmotecadelreves.downloaders;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.Level;

/**
 * Represents a single log entry generated during the lifecycle of a torrent
 * download.  Entries are grouped by {@link Step} so the UI can show a
 * chronological list of the actions performed (preparation, tracker
 * communication, download progress, etc.).
 */
public final class TorrentLogEntry implements Serializable {

    /** Formatter used to display timestamps in a human friendly way. */
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final long timestamp;
    private final Level level;
    private final Step step;
    private final String message;
    private final boolean generatedByHealthCheck;

    public TorrentLogEntry(long timestamp,
                           Level level,
                           Step step,
                           String message,
                           boolean generatedByHealthCheck) {
        this.timestamp = timestamp;
        this.level = Objects.requireNonNull(level, "level");
        this.step = Objects.requireNonNull(step, "step");
        this.message = Objects.requireNonNull(message, "message");
        this.generatedByHealthCheck = generatedByHealthCheck;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public Level getLevel() {
        return level;
    }

    public Step getStep() {
        return step;
    }

    public String getMessage() {
        return message;
    }

    public boolean isGeneratedByHealthCheck() {
        return generatedByHealthCheck;
    }

    /**
     * @return the timestamp formatted for UI consumption
     */
    public String getFormattedTimestamp() {
        return FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }

    @Override
    public String toString() {
        return '[' + getFormattedTimestamp() + "] " + step.getDisplayName() + " "
                + level.getName() + ": " + message;
    }

    /**
     * Defines the high-level phase that produced the log entry.  The display
     * name is localized in Spanish because the rest of the UI is presented in
     * Spanish.
     */
    public enum Step {
        SESSION("Sesión"),
        PREPARATION("Preparación"),
        VALIDATION("Validación"),
        TRACKER("Trackers"),
        DHT("DHT"),
        DOWNLOAD("Descarga"),
        COMPLETED("Completado"),
        ERROR("Error"),
        HEALTHCHECK("Diagnóstico");

        private final String displayName;

        Step(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }
}

