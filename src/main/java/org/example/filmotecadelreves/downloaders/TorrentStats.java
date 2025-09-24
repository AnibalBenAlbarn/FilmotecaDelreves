package org.example.filmotecadelreves.downloaders;

import com.frostwire.jlibtorrent.Sha1Hash;
import com.frostwire.jlibtorrent.TorrentStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Maintains a rolling window of statistics for a torrent managed by jlibtorrent.
 * <p>
 * jlibtorrent exposes a rich {@link TorrentStatus} object with the raw figures
 * (download/upload rates, amount of data transferred, swarm information, etc.).
 * The JavaFX UI, however, needs derived values such as average rates, historical
 * samples for charts and human friendly metadata like the current state or the
 * estimated time of arrival.  The upstream library does not provide a helper
 * class for this, therefore we keep our own representation inside the
 * application.
 */
public class TorrentStats {

    /**
     * Immutable snapshot of the most relevant torrent metrics.
     */
    public static final class Sample {
        private final long timestamp;
        private final double progress;
        private final long downloadedBytes;
        private final long totalBytes;
        private final int downloadRate;
        private final int uploadRate;
        private final int peers;
        private final int seeds;
        private final TorrentStatus.State state;

        private Sample(long timestamp,
                       double progress,
                       long downloadedBytes,
                       long totalBytes,
                       int downloadRate,
                       int uploadRate,
                       int peers,
                       int seeds,
                       TorrentStatus.State state) {
            this.timestamp = timestamp;
            this.progress = progress;
            this.downloadedBytes = downloadedBytes;
            this.totalBytes = totalBytes;
            this.downloadRate = downloadRate;
            this.uploadRate = uploadRate;
            this.peers = peers;
            this.seeds = seeds;
            this.state = state;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public double getProgress() {
            return progress;
        }

        public long getDownloadedBytes() {
            return downloadedBytes;
        }

        public long getTotalBytes() {
            return totalBytes;
        }

        public int getDownloadRate() {
            return downloadRate;
        }

        public int getUploadRate() {
            return uploadRate;
        }

        public int getPeers() {
            return peers;
        }

        public int getSeeds() {
            return seeds;
        }

        public TorrentStatus.State getState() {
            return state;
        }
    }

    private final Sha1Hash infoHash;
    private final int maxSamples;
    private final Deque<Sample> samples;

    private long lastUpdated;
    private double averageDownloadRate;
    private double averageUploadRate;
    private double peakDownloadRate;
    private double peakUploadRate;
    private long estimatedTimeRemaining;
    private long totalDownloaded;
    private long totalUploaded;
    private long totalWanted;
    private long totalWasted;
    private double distributedCopies;
    private double lastProgress;
    private TorrentStatus.State currentState;

    public TorrentStats(Sha1Hash infoHash, int maxSamples) {
        this.infoHash = infoHash;
        this.maxSamples = Math.max(1, maxSamples);
        this.samples = new ArrayDeque<>(this.maxSamples);
        this.lastUpdated = 0L;
        this.averageDownloadRate = 0.0;
        this.averageUploadRate = 0.0;
        this.peakDownloadRate = 0.0;
        this.peakUploadRate = 0.0;
        this.estimatedTimeRemaining = -1L;
        this.totalDownloaded = 0L;
        this.totalUploaded = 0L;
        this.totalWanted = 0L;
        this.totalWasted = 0L;
        this.distributedCopies = 0.0;
        this.lastProgress = 0.0;
        this.currentState = TorrentStatus.State.values()[0];
    }

    /**
     * Update the rolling statistics with the latest {@link TorrentStatus}.
     *
     * @param status current jlibtorrent status
     */
    public synchronized void update(TorrentStatus status) {
        if (status == null) {
            return;
        }

        Sample sample = new Sample(
                System.currentTimeMillis(),
                status.progress(),
                status.totalDone(),
                status.total(),
                status.downloadRate(),
                status.uploadRate(),
                status.numPeers(),
                status.numSeeds(),
                status.state()
        );

        samples.addLast(sample);
        if (samples.size() > maxSamples) {
            samples.removeFirst();
        }

        lastUpdated = sample.getTimestamp();
        lastProgress = sample.getProgress();
        currentState = sample.getState();
        totalDownloaded = status.totalDone();
        totalUploaded = status.totalUpload();
        totalWanted = status.totalWanted();
        totalWasted = calculateTotalWasted(status);
        distributedCopies = status.distributedCopies();

        recalculateRates();
        recalculateEta(status);
    }

    private long calculateTotalWasted(TorrentStatus status) {
        long failed = 0L;
        long redundant = 0L;

        try {
            failed = status.totalFailedBytes();
        } catch (NoSuchMethodError ignored) {
            // Older jlibtorrent builds might not expose failed bytes
        }

        try {
            redundant = status.totalRedundantBytes();
        } catch (NoSuchMethodError ignored) {
            // Older jlibtorrent builds might not expose redundant bytes
        }

        long total = failed + redundant;
        return total >= 0 ? total : 0L;
    }

    private void recalculateRates() {
        double downloadSum = 0.0;
        double uploadSum = 0.0;
        int count = 0;
        for (Sample sample : samples) {
            downloadSum += sample.getDownloadRate();
            uploadSum += sample.getUploadRate();
            count++;
        }

        if (count > 0) {
            averageDownloadRate = downloadSum / count;
            averageUploadRate = uploadSum / count;
        } else {
            averageDownloadRate = 0.0;
            averageUploadRate = 0.0;
        }

        Sample latest = samples.peekLast();
        if (latest != null) {
            peakDownloadRate = Math.max(peakDownloadRate, latest.getDownloadRate());
            peakUploadRate = Math.max(peakUploadRate, latest.getUploadRate());
        }
    }

    private void recalculateEta(TorrentStatus status) {
        long remaining = Math.max(0L, status.total() - status.totalDone());
        int currentDownloadRate = status.downloadRate();
        if (currentDownloadRate > 0) {
            estimatedTimeRemaining = remaining / currentDownloadRate;
        } else {
            estimatedTimeRemaining = -1L;
        }
    }

    public Sha1Hash getInfoHash() {
        return infoHash;
    }

    public String getInfoHashHex() {
        return infoHash != null ? infoHash.toHex() : "";
    }

    public synchronized long getLastUpdated() {
        return lastUpdated;
    }

    public synchronized double getAverageDownloadRate() {
        return averageDownloadRate;
    }

    public synchronized double getAverageUploadRate() {
        return averageUploadRate;
    }

    public synchronized double getPeakDownloadRate() {
        return peakDownloadRate;
    }

    public synchronized double getPeakUploadRate() {
        return peakUploadRate;
    }

    public synchronized long getEstimatedTimeRemaining() {
        return estimatedTimeRemaining;
    }

    public synchronized long getTotalDownloaded() {
        return totalDownloaded;
    }

    public synchronized long getTotalUploaded() {
        return totalUploaded;
    }

    public synchronized long getTotalWanted() {
        return totalWanted;
    }

    public synchronized long getTotalWasted() {
        return totalWasted;
    }

    public synchronized double getDistributedCopies() {
        return distributedCopies;
    }

    public synchronized double getLastProgress() {
        return lastProgress;
    }

    public synchronized TorrentStatus.State getCurrentState() {
        return currentState;
    }

    public synchronized Sample getLatestSample() {
        return samples.peekLast();
    }

    public synchronized List<Sample> getSamples() {
        return Collections.unmodifiableList(new ArrayList<>(samples));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TorrentStats that = (TorrentStats) o;
        return Objects.equals(getInfoHashHex(), that.getInfoHashHex());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getInfoHashHex());
    }
}
