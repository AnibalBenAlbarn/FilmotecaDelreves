package org.example.filmotecadelreves.moviesad;


import java.io.Serializable;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.IntegerProperty;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 *Enhanced TorrentState class to track the state of a torrent download*/
public class TorrentState implements Serializable {
    private static final long serialVersionUID = 2L;

    // Source information
    private String torrentSource;
    private String destinationPath;

    // Download statistics
    private final long bytesDownloaded;
    private final int piecesComplete;
    private final int piecesTotal;

    // Observable properties for UI
    private final transient DoubleProperty progress;
    private final transient StringProperty status;
    private final transient StringProperty name;
    private final transient DoubleProperty downloadSpeed;
    private final transient DoubleProperty uploadSpeed; // Added upload speed property
    private final transient LongProperty fileSize;
    private final transient IntegerProperty peers;
    private final transient IntegerProperty seeds;
    private final transient LongProperty remainingTime;

    // File information
    private String fileName;

    // Unique identifier for the torrent
    private String torrentId;

    // Timestamps
    private final LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime lastUpdatedAt;

    // Additional metadata
    private String hash;
    private String comment;
    private String createdBy;
    private int priority = 5; // 1-10, default is 5

    /**
     *Constructor for TorrentState*/
    public TorrentState(String torrentSource, String destinationPath, long bytesDownloaded, int piecesComplete, int piecesTotal) {
        this.torrentSource = torrentSource;
        this.destinationPath = destinationPath;
        this.bytesDownloaded = bytesDownloaded;
        this.piecesComplete = piecesComplete;
        this.piecesTotal = piecesTotal;

        // Initialize observable properties
        this.progress = new SimpleDoubleProperty(0);
        this.status = new SimpleStringProperty("En espera");
        this.name = new SimpleStringProperty(extractFileName(torrentSource));
        this.downloadSpeed = new SimpleDoubleProperty(0);
        this.uploadSpeed = new SimpleDoubleProperty(0); // Initialize upload speed
        this.fileSize = new SimpleLongProperty(0);
        this.peers = new SimpleIntegerProperty(0);
        this.seeds = new SimpleIntegerProperty(0);
        this.remainingTime = new SimpleLongProperty(-1); // -1 indicates "calculating"

        // Extract file name from torrentSource
        this.fileName = extractFileName(torrentSource);

        // Generate a unique ID for this torrent
        this.torrentId = generateTorrentId();

        // Set timestamps
        this.createdAt = LocalDateTime.now();
        this.lastUpdatedAt = this.createdAt;

        System.out.println("TorrentState created: " + torrentSource + " -> " + destinationPath);
    }

    /**
     *Extract file name from path*/
    private String extractFileName(String path) {
        if (path == null) return "";

        // Handle magnet links
        if (path.startsWith("magnet:")) {
            // Try to extract name from magnet link
            String[] parts = path.split("&");
            for (String part : parts) {
                if (part.startsWith("dn=")) {
                    try {
                        return java.net.URLDecoder.decode(part.substring(3), "UTF-8");
                    } catch (Exception e) {
                        return "Magnet-" + UUID.randomUUID().toString().substring(0, 8);
                    }
                }
            }
            return "Magnet-" + UUID.randomUUID().toString().substring(0, 8);
        }

        // Handle file paths
        int lastSlash = path.lastIndexOf('/');
        int lastBackslash = path.lastIndexOf('\\');
        int lastSeparator = Math.max(lastSlash, lastBackslash);

        if (lastSeparator >= 0 && lastSeparator < path.length() - 1) {
            return path.substring(lastSeparator + 1);
        } else {
            return path;
        }
    }

    /**
     *Generate a unique torrent ID*/
    private String generateTorrentId() {
        // Generate a unique ID based on file name and current time
        return UUID.randomUUID().toString();
    }

    /**
     *Get torrent source*/
    public String getTorrentSource() {
        return torrentSource;
    }

    /**
     *Set torrent source*/
    public void setTorrentSource(String torrentSource) {
        this.torrentSource = torrentSource;

        // Update file name if source changes
        this.fileName = extractFileName(torrentSource);

        // Update name property for UI
        if (this.name != null) {
            this.name.set(this.fileName);
        }

        updateLastUpdated();
    }

    /**
     *Get destination path*/
    public String getDestinationPath() {
        return destinationPath;
    }

    /**
     *Set destination path*/
    public void setDestinationPath(String destinationPath) {
        this.destinationPath = destinationPath;
        updateLastUpdated();
    }

    /**
     *Get bytes downloaded*/
    public long getBytesDownloaded() {
        return bytesDownloaded;
    }

    /**
     *Get pieces complete*/
    public int getPiecesComplete() {
        return piecesComplete;
    }

    /**
     *Get total pieces*/
    public int getPiecesTotal() {
        return piecesTotal;
    }

    /**
     *Get progress*/
    public double getProgress() {
        return progress.get();
    }

    /**
     *Set progress*/
    public void setProgress(double progress) {
        this.progress.set(progress);
        updateLastUpdated();
    }

    /**
     *Get progress property*/
    public DoubleProperty progressProperty() {
        return progress;
    }

    /**
     *Get status*/
    public String getStatus() {
        return status.get();
    }

    /**
     *Set status*/
    public void setStatus(String status) {
        System.out.println("Changing state of " + torrentSource + " to: " + status);
        this.status.set(status);

        // Update timestamps based on status
        if ("Descargando".equals(status) && startedAt == null) {
            startedAt = LocalDateTime.now();
        } else if ("Completado".equals(status) && completedAt == null) {
            completedAt = LocalDateTime.now();
        }

        updateLastUpdated();
    }

    /**
     *Get status property*/
    public StringProperty statusProperty() {
        return status;
    }

    /**
     *Get file name*/
    public String getFileName() {
        return fileName;
    }

    /**
     *Set file name*/
    public void setFileName(String fileName) {
        this.fileName = fileName;

        // Update name property for UI
        if (this.name != null) {
            this.name.set(fileName);
        }

        updateLastUpdated();
    }

    /**
     *Get name*/
    public String getName() {
        return name.get();
    }

    /**
     *Set name*/
    public void setName(String name) {
        this.name.set(name);
        updateLastUpdated();
    }

    /**
     *Get name property*/
    public StringProperty nameProperty() {
        return name;
    }

    /**
     *Get download speed*/
    public double getDownloadSpeed() {
        return downloadSpeed.get();
    }

    /**
     *Set download speed*/
    public void setDownloadSpeed(double speed) {
        this.downloadSpeed.set(speed);
        updateLastUpdated();
    }

    /**
     *Get download speed property*/
    public DoubleProperty downloadSpeedProperty() {
        return downloadSpeed;
    }

    /**
     *Get upload speed*/
    public double getUploadSpeed() {
        return uploadSpeed.get();
    }

    /**
     *Set upload speed*/
    public void setUploadSpeed(double speed) {
        this.uploadSpeed.set(speed);
        updateLastUpdated();
    }

    /**
     *Get upload speed property*/
    public DoubleProperty uploadSpeedProperty() {
        return uploadSpeed;
    }

    /**
     *Get file size*/
    public long getFileSize() {
        return fileSize.get();
    }

    /**
     *Set file size*/
    public void setFileSize(long size) {
        this.fileSize.set(size);
        updateLastUpdated();
    }

    /**
     *Get file size property*/
    public LongProperty fileSizeProperty() {
        return fileSize;
    }

    /**
     *Get peers count*/
    public int getPeers() {
        return peers.get();
    }

    /**
     *Set peers count*/
    public void setPeers(int peerCount) {
        this.peers.set(peerCount);
        updateLastUpdated();
    }

    /**
     *Get peers property*/
    public IntegerProperty peersProperty() {
        return peers;
    }

    /**
     *Get seeds count*/
    public int getSeeds() {
        return seeds.get();
    }

    /**
     *Set seeds count*/
    public void setSeeds(int seedCount) {
        this.seeds.set(seedCount);
        updateLastUpdated();
    }

    /**
     *Get seeds property*/
    public IntegerProperty seedsProperty() {
        return seeds;
    }

    /**
     *Get remaining time*/
    public long getRemainingTime() {
        return remainingTime.get();
    }

    /**
     *Set remaining time*/
    public void setRemainingTime(long seconds) {
        this.remainingTime.set(seconds);
        updateLastUpdated();
    }

    /**
     *Get remaining time property*/
    public LongProperty remainingTimeProperty() {
        return remainingTime;
    }

    /**
     *Get torrent ID*/
    public String getTorrentId() {
        return torrentId;
    }

    /**
     *Set torrent ID*/
    public void setTorrentId(String torrentId) {
        this.torrentId = torrentId;
    }

    /**
     *Get creation timestamp*/
    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     *Get formatted creation timestamp*/
    public String getCreatedAtFormatted() {
        return formatDateTime(createdAt);
    }

    /**
     *Get start timestamp*/
    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    /**
     *Get formatted start timestamp*/
    public String getStartedAtFormatted() {
        return formatDateTime(startedAt);
    }

    /**
     *Get completion timestamp*/
    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    /**
     *Get formatted completion timestamp*/
    public String getCompletedAtFormatted() {
        return formatDateTime(completedAt);
    }

    /**
     *Get last update timestamp*/
    public LocalDateTime getLastUpdatedAt() {
        return lastUpdatedAt;
    }

    /**
     *Get formatted last update timestamp*/
    public String getLastUpdatedAtFormatted() {
        return formatDateTime(lastUpdatedAt);
    }

    /**
     *Format a timestamp*/
    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /**
     *Update last updated timestamp*/
    private void updateLastUpdated() {
        this.lastUpdatedAt = LocalDateTime.now();
    }

    /**
     *Get torrent hash*/
    public String getHash() {
        return hash;
    }

    /**
     *Set torrent hash*/
    public void setHash(String hash) {
        this.hash = hash;
    }

    /**
     *Get torrent comment*/
    public String getComment() {
        return comment;
    }

    /**
     *Set torrent comment*/
    public void setComment(String comment) {
        this.comment = comment;
    }

    /**
     *Get torrent creator*/
    public String getCreatedBy() {
        return createdBy;
    }

    /**
     *Set torrent creator*/
    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    /**
     *Get download priority*/
    public int getPriority() {
        return priority;
    }

    /**
     *Set download priority (1-10)*/
    public void setPriority(int priority) {
        if (priority < 1) priority = 1;
        if (priority > 10) priority = 10;
        this.priority = priority;
    }

    /**
     *Calculate download time*/
    public long getDownloadTime() {
        if (startedAt == null || completedAt == null) return -1;
        return java.time.Duration.between(startedAt, completedAt).getSeconds();
    }

    /**
     *Format download time as string*/
    public String getDownloadTimeFormatted() {
        long seconds = getDownloadTime();
        if (seconds < 0) return "N/A";

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        } else {
            return String.format("%d:%02d", minutes, secs);
        }
    }

    /**
     *Calculate average download speed*/
    public double getAverageDownloadSpeed() {
        if (startedAt == null || completedAt == null || fileSize.get() <= 0) return 0;

        long seconds = java.time.Duration.between(startedAt, completedAt).getSeconds();
        if (seconds <= 0) return 0;

        return (fileSize.get() / 1024.0) / seconds; // KB/s
    }

    /**
     *Reset state for resuming download*/
    public void resetForResume() {
        setProgress(0);
        setStatus("En espera");
        setDownloadSpeed(0);
        setUploadSpeed(0); // Reset upload speed too
        setPeers(0);
        setSeeds(0);
        setRemainingTime(-1);
        startedAt = null;
        completedAt = null;
        updateLastUpdated();
    }

    /**
     *String representation*/
    @Override
    public String toString() {
        return "TorrentState{" +
                "torrentId='" + torrentId + '\'' +
                ", torrentSource='" + torrentSource + '\'' +
                ", destinationPath='" + destinationPath + '\'' +
                ", fileName='" + fileName + '\'' +
                ", progress=" + getProgress() +
                ", status='" + getStatus() + '\'' +
                ", fileSize=" + getFileSize() +
                ", createdAt=" + getCreatedAtFormatted() +
                '}';
    }

    /**
     *Equals method*/
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TorrentState that = (TorrentState) o;
        return torrentId != null && torrentId.equals(that.torrentId);
    }

    /**
     *Hash code*/
    @Override
    public int hashCode() {
        return torrentId != null ? torrentId.hashCode() : 0;
    }
}