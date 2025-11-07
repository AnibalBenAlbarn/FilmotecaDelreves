package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.UI.DescargasUI;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Small utility that handles HTTP downloads with pause/resume support using the Range header.
 * The task keeps the {@link DescargasUI.DirectDownload} model synchronised with the persisted
 * state so that downloads can continue even after restarting the application.
 */
public class ResumableHttpDownloadTask implements Runnable {

    private static final int BUFFER_SIZE = 8192;
    private static final long UI_UPDATE_INTERVAL_MS = 500L;
    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(20).toMillis();
    private static final int READ_TIMEOUT_MS = (int) Duration.ofSeconds(60).toMillis();
    private static final int HTTP_PRECONDITION_FAILED = 412;
    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;

    private final String downloadUrl;
    private final DescargasUI.DirectDownload download;
    private final Consumer<HttpURLConnection> headerConfigurer;
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private final CompletableFuture<Void> completionFuture = new CompletableFuture<>();

    private Thread workerThread;

    public ResumableHttpDownloadTask(String downloadUrl,
                                     DescargasUI.DirectDownload download,
                                     Consumer<HttpURLConnection> headerConfigurer) {
        this.downloadUrl = Objects.requireNonNull(downloadUrl, "downloadUrl");
        this.download = Objects.requireNonNull(download, "download");
        this.headerConfigurer = headerConfigurer != null ? headerConfigurer : connection -> { };
    }

    public CompletableFuture<Void> start() {
        if (workerThread != null) {
            return completionFuture;
        }
        workerThread = new Thread(this, "DirectDownload-" + download.getId());
        workerThread.setDaemon(true);
        workerThread.start();
        return completionFuture;
    }

    @Override
    public void run() {
        try {
            executeDownload();
            if (!completionFuture.isDone()) {
                completionFuture.complete(null);
            }
        } catch (Exception ex) {
            if (cancelled.get()) {
                updateDownload(d -> {
                    d.setStatus("Cancelled");
                    d.setDownloadSpeed(0);
                    d.setRemainingTime(0);
                });
                if (!completionFuture.isDone()) {
                    completionFuture.complete(null);
                }
            } else {
                updateDownload(d -> {
                    d.setStatus("Error");
                    d.setDownloadSpeed(0);
                });
                if (!completionFuture.isDone()) {
                    completionFuture.completeExceptionally(ex);
                }
            }
        }
    }

    public void pause() {
        paused.set(true);
    }

    public void resume() {
        if (paused.compareAndSet(true, false)) {
            synchronized (pauseLock) {
                pauseLock.notifyAll();
            }
        }
    }

    public void cancel() {
        cancelled.set(true);
        resume();
        Thread thread = workerThread;
        if (thread != null) {
            thread.interrupt();
        }
    }

    public CompletableFuture<Void> getCompletionFuture() {
        return completionFuture;
    }

    private void executeDownload() throws IOException, InterruptedException {
        Path targetFile = prepareTargetFile();
        long localBytes = Files.exists(targetFile) ? Files.size(targetFile) : 0L;

        if (download.getDownloadedBytes() != localBytes) {
            long finalLocalBytes = localBytes;
            updateDownload(d -> {
                d.setDownloadedBytes(finalLocalBytes);
                long total = d.getFileSize();
                if (total > 0) {
                    double progress = Math.min(100.0, (finalLocalBytes * 100.0) / total);
                    d.setProgress(progress);
                }
            });
        }

        RemoteMetadata metadata = fetchRemoteMetadata();
        long knownLength = metadata.contentLength > 0 ? metadata.contentLength : download.getFileSize();
        if (knownLength > 0 && knownLength != download.getFileSize()) {
            long finalKnownLength = knownLength;
            updateDownload(d -> d.setFileSize(finalKnownLength));
        }
        if (metadata.etag != null && !metadata.etag.isBlank()) {
            String finalEtag = metadata.etag;
            updateDownload(d -> d.setEtag(finalEtag));
        }
        if (metadata.lastModified != null && !metadata.lastModified.isBlank()) {
            String finalLastModified = metadata.lastModified;
            updateDownload(d -> d.setLastModified(finalLastModified));
        }
        boolean resumePossible = metadata.resumeSupported || download.isResumeSupported();
        boolean finalResumePossible = resumePossible;
        updateDownload(d -> d.setResumeSupported(finalResumePossible));

        if (knownLength > 0 && localBytes >= knownLength) {
            long total = knownLength;
            updateDownload(d -> {
                d.setDownloadedBytes(total);
                d.setProgress(100);
                d.setDownloadSpeed(0);
                d.setRemainingTime(0);
                d.setStatus("Completed");
            });
            return;
        }

        DownloadConnection response = openDownloadConnection(localBytes, resumePossible, targetFile);
        HttpURLConnection connection = response.connection;
        long startingOffset = response.startingOffset;

        if (response.resumed && startingOffset != localBytes) {
            long adjusted = response.startingOffset;
            updateDownload(d -> d.setDownloadedBytes(adjusted));
        }

        long totalBytes = determineTotalBytes(connection, startingOffset, knownLength, response.responseCode);
        if (totalBytes > 0) {
            long finalTotalBytes = totalBytes;
            updateDownload(d -> d.setFileSize(finalTotalBytes));
        }

        String responseEtag = connection.getHeaderField("ETag");
        if (responseEtag != null && !responseEtag.isBlank()) {
            updateDownload(d -> d.setEtag(responseEtag));
        }
        String responseLastModified = connection.getHeaderField("Last-Modified");
        if (responseLastModified != null && !responseLastModified.isBlank()) {
            updateDownload(d -> d.setLastModified(responseLastModified));
        }
        String acceptRanges = connection.getHeaderField("Accept-Ranges");
        if (acceptRanges != null && !acceptRanges.isBlank()) {
            boolean supportsResume = !"none".equalsIgnoreCase(acceptRanges.trim());
            updateDownload(d -> d.setResumeSupported(supportsResume));
        }

        updateDownload(d -> {
            d.setStatus("Downloading");
            d.setDownloadSpeed(0);
            d.setRemainingTime(0);
            d.setDownloadedBytes(startingOffset);
            if (totalBytes > 0) {
                double progress = Math.min(100.0, (startingOffset * 100.0) / totalBytes);
                d.setProgress(progress);
            }
        });

        long downloaded = startingOffset;
        long lastUpdateBytes = downloaded;
        long lastUpdateTime = System.currentTimeMillis();

        try (InputStream input = connection.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(targetFile.toFile(), "rw")) {
            if (startingOffset > 0) {
                raf.seek(startingOffset);
            } else {
                raf.setLength(0);
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                if (cancelled.get()) {
                    updateDownload(d -> {
                        d.setStatus("Cancelled");
                        d.setDownloadSpeed(0);
                        d.setRemainingTime(0);
                    });
                    return;
                }

                waitIfPaused();

                int read = input.read(buffer);
                if (read == -1) {
                    break;
                }
                raf.write(buffer, 0, read);
                downloaded += read;

                long now = System.currentTimeMillis();
                if (now - lastUpdateTime >= UI_UPDATE_INTERVAL_MS) {
                    long deltaBytes = downloaded - lastUpdateBytes;
                    long elapsedMillis = now - lastUpdateTime;
                    double speedBps = elapsedMillis > 0 ? (deltaBytes * 1000.0) / elapsedMillis : 0.0;
                    double speedMBps = speedBps / (1024.0 * 1024.0);
                    long remainingSeconds = (totalBytes > 0 && speedBps > 0)
                            ? Math.max(0L, Math.round((totalBytes - downloaded) / speedBps))
                            : download.getRemainingTime();
                    double progress = totalBytes > 0
                            ? Math.min(100.0, (downloaded * 100.0) / totalBytes)
                            : download.getProgress();
                    long progressBytes = downloaded;
                    updateDownload(d -> {
                        d.setDownloadedBytes(progressBytes);
                        if (totalBytes > 0) {
                            d.setProgress(progress);
                            d.setRemainingTime(remainingSeconds);
                        }
                        d.setDownloadSpeed(speedMBps);
                    });
                    lastUpdateTime = now;
                    lastUpdateBytes = downloaded;
                }
            }
        } finally {
            connection.disconnect();
        }

        if (cancelled.get()) {
            updateDownload(d -> {
                d.setStatus("Cancelled");
                d.setDownloadSpeed(0);
                d.setRemainingTime(0);
            });
            return;
        }

        long finalDownloaded = downloaded;
        updateDownload(d -> {
            d.setDownloadedBytes(finalDownloaded);
            if (totalBytes > 0) {
                d.setProgress(100);
            }
            d.setDownloadSpeed(0);
            d.setRemainingTime(0);
            d.setStatus("Completed");
        });
    }

    private Path prepareTargetFile() throws IOException {
        Path target = download.resolveTargetFilePath();
        if (target == null) {
            throw new IOException("Ruta de destino no configurada para la descarga directa");
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        updateDownload(d -> d.setActualFilePath(target.toString()));
        return target;
    }

    private RemoteMetadata fetchRemoteMetadata() {
        RemoteMetadata metadata = new RemoteMetadata();
        try {
            HttpURLConnection connection = openConnection("HEAD");
            try {
                int code = connection.getResponseCode();
                if (code >= 200 && code < 400) {
                    metadata.contentLength = connection.getContentLengthLong();
                    String etag = connection.getHeaderField("ETag");
                    if (etag != null) {
                        metadata.etag = etag;
                    }
                    String lastModified = connection.getHeaderField("Last-Modified");
                    if (lastModified != null) {
                        metadata.lastModified = lastModified;
                    }
                    String acceptRanges = connection.getHeaderField("Accept-Ranges");
                    if (acceptRanges != null && !acceptRanges.isBlank()) {
                        metadata.resumeSupported = !"none".equalsIgnoreCase(acceptRanges.trim());
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // Algunos servidores no admiten HEAD; continuaremos sin esta metadata.
        }
        return metadata;
    }

    private DownloadConnection openDownloadConnection(long localBytes,
                                                       boolean resumePossible,
                                                       Path targetFile) throws IOException {
        HttpURLConnection connection = openConnection("GET");
        boolean attemptingResume = resumePossible && localBytes > 0 && Files.exists(targetFile);
        if (attemptingResume) {
            connection.setRequestProperty("Range", "bytes=" + localBytes + "-");
            String etag = download.getEtag();
            if (etag != null && !etag.isBlank()) {
                connection.setRequestProperty("If-Match", etag);
            }
            String lastModified = download.getLastModified();
            if (lastModified != null && !lastModified.isBlank()) {
                connection.setRequestProperty("If-Unmodified-Since", lastModified);
            }
        }

        int responseCode = connection.getResponseCode();
        if (attemptingResume) {
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                return new DownloadConnection(connection, localBytes, true, responseCode);
            }
            if (responseCode == HttpURLConnection.HTTP_OK
                    || responseCode == HTTP_PRECONDITION_FAILED
                    || responseCode == HTTP_REQUESTED_RANGE_NOT_SATISFIABLE) {
                connection.disconnect();
                try {
                    Files.deleteIfExists(targetFile);
                } catch (IOException ignored) {
                }
                updateDownload(d -> {
                    d.setDownloadedBytes(0);
                    d.setProgress(0);
                });
                HttpURLConnection restart = openConnection("GET");
                int restartCode = restart.getResponseCode();
                if (restartCode != HttpURLConnection.HTTP_OK && restartCode != HttpURLConnection.HTTP_PARTIAL) {
                    restart.disconnect();
                    throw new IOException("GET " + restartCode);
                }
                return new DownloadConnection(restart, 0, restartCode == HttpURLConnection.HTTP_PARTIAL, restartCode);
            }
        }

        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("GET " + responseCode);
        }

        long newOffset = (responseCode == HttpURLConnection.HTTP_PARTIAL) ? localBytes : 0L;
        return new DownloadConnection(connection, newOffset, responseCode == HttpURLConnection.HTTP_PARTIAL, responseCode);
    }

    private long determineTotalBytes(HttpURLConnection connection,
                                     long startingOffset,
                                     long knownLength,
                                     int responseCode) {
        long contentLength = connection.getContentLengthLong();
        if (contentLength <= 0) {
            return knownLength;
        }
        if (startingOffset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL) {
            return startingOffset + contentLength;
        }
        return contentLength;
    }

    private void waitIfPaused() throws InterruptedException {
        if (!paused.get()) {
            return;
        }
        updateDownload(d -> {
            d.setStatus("Paused");
            d.setDownloadSpeed(0);
        });
        synchronized (pauseLock) {
            while (paused.get() && !cancelled.get()) {
                pauseLock.wait(250L);
            }
        }
        if (!cancelled.get()) {
            updateDownload(d -> d.setStatus("Downloading"));
        }
    }

    private HttpURLConnection openConnection(String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        headerConfigurer.accept(connection);
        return connection;
    }

    private void updateDownload(Consumer<DescargasUI.DirectDownload> consumer) {
        CompletableFuture.runAsync(() -> {
            try {
                consumer.accept(download);
            } catch (Exception ignored) {
            }
        });
    }

    private static final class RemoteMetadata {
        long contentLength = -1;
        boolean resumeSupported;
        String etag;
        String lastModified;
    }

    private static final class DownloadConnection {
        final HttpURLConnection connection;
        final long startingOffset;
        final boolean resumed;
        final int responseCode;

        private DownloadConnection(HttpURLConnection connection, long startingOffset, boolean resumed, int responseCode) {
            this.connection = connection;
            this.startingOffset = startingOffset;
            this.resumed = resumed;
            this.responseCode = responseCode;
        }
    }
}
