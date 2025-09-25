package org.example.filmotecadelreves.downloaders;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a diagnostic run executed against a torrent.  The report contains
 * a list of individual checks so the UI can display a breakdown similar to
 * desktop torrent clients (session status, metadata availability, trackers,
 * etc.).
 */
public final class TorrentHealthReport implements Serializable {

    private final String infoHash;
    private final List<Check> checks;
    private final long generatedAt;

    TorrentHealthReport(String infoHash, List<Check> checks, long generatedAt) {
        this.infoHash = infoHash;
        this.checks = Collections.unmodifiableList(new ArrayList<>(checks));
        this.generatedAt = generatedAt;
    }

    public String getInfoHash() {
        return infoHash;
    }

    public List<Check> getChecks() {
        return checks;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public boolean isHealthy() {
        for (Check check : checks) {
            if (!check.passed) {
                return false;
            }
        }
        return true;
    }

    /**
     * One individual verification performed during the diagnostic.
     */
    public static final class Check implements Serializable {
        private final String name;
        private final boolean passed;
        private final String details;

        public Check(String name, boolean passed, String details) {
            this.name = name;
            this.passed = passed;
            this.details = details;
        }

        public String getName() {
            return name;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getDetails() {
            return details;
        }
    }
}

