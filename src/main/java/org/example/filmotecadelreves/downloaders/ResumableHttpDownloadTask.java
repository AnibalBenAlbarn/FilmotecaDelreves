package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.UI.DescargasUI;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
    private static final long META_UPDATE_INTERVAL_MS = 1500L;
    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(20).toMillis();
    private static final int READ_TIMEOUT_MS = (int) Duration.ofSeconds(60).toMillis();
    private static final int HTTP_PRECONDITION_FAILED = 412;
    private static final int HTTP_REQUESTED_RANGE_NOT_SATISFIABLE = 416;
    private static final int HTTP_GONE = 410;
    private static final String PART_EXTENSION = ".part";
    private static final String META_EXTENSION = ".meta.json";

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
        } catch (DownloadHaltedException ex) {
            updateDownload(d -> {
                d.setStatus(ex.getStatus());
                d.setDownloadSpeed(0);
                d.setRemainingTime(0);
            });
            if (!completionFuture.isDone()) {
                completionFuture.complete(null);
            }
        } catch (RestartDeclinedException ex) {
            updateDownload(d -> {
                d.setStatus("Paused (restart required)");
                d.setDownloadSpeed(0);
                d.setRemainingTime(0);
            });
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

    private void executeDownload() throws IOException, InterruptedException, RestartDeclinedException, DownloadHaltedException {
        Path targetFile = prepareTargetFile();
        Path partFile = resolvePartFile(targetFile);
        Path metaFile = resolveMetaFile(targetFile);

        DownloadMeta meta = loadMeta(metaFile);
        if (meta == null && downloadHasMetaSnapshot()) {
            meta = new DownloadMeta();
            meta.url = downloadUrl;
            meta.etag = download.getEtag();
            meta.lastModified = download.getLastModified();
            meta.totalLength = download.getFileSize();
            meta.downloadedBytes = download.getDownloadedBytes();
            meta.updatedAt = System.currentTimeMillis();
        }

        if (!Files.exists(partFile) && meta != null) {
            deleteMeta(metaFile);
            meta = null;
        }

        long localBytes = Files.exists(partFile) ? Files.size(partFile) : 0L;
        if (meta == null) {
            meta = new DownloadMeta();
            meta.url = downloadUrl;
        }
        meta.downloadedBytes = localBytes;
        if (meta.totalLength <= 0 && download.getFileSize() > 0) {
            meta.totalLength = download.getFileSize();
        }
        if (meta.etag == null || meta.etag.isBlank()) {
            meta.etag = download.getEtag();
        }
        if (meta.lastModified == null || meta.lastModified.isBlank()) {
            meta.lastModified = download.getLastModified();
        }
        persistMeta(metaFile, meta);

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
        long knownLength = metadata.contentLength > 0 ? metadata.contentLength : meta.totalLength;
        if (knownLength > 0 && knownLength != download.getFileSize()) {
            long finalKnownLength = knownLength;
            updateDownload(d -> d.setFileSize(finalKnownLength));
        }
        if (metadata.etag != null && !metadata.etag.isBlank()) {
            String finalEtag = metadata.etag;
            updateDownload(d -> d.setEtag(finalEtag));
            meta.etag = finalEtag;
        }
        if (metadata.lastModified != null && !metadata.lastModified.isBlank()) {
            String finalLastModified = metadata.lastModified;
            updateDownload(d -> d.setLastModified(finalLastModified));
            meta.lastModified = finalLastModified;
        }
        boolean resumePossible = metadata.resumeSupported || download.isResumeSupported();
        boolean finalResumePossible = resumePossible;
        updateDownload(d -> d.setResumeSupported(finalResumePossible));

        if (knownLength > 0) {
            meta.totalLength = knownLength;
        }
        persistMeta(metaFile, meta);

        if (knownLength > 0 && localBytes >= knownLength) {
            finalizeDownload(targetFile, partFile, metaFile, knownLength);
            return;
        }

        boolean hasPartialFile = localBytes > 0 && Files.exists(partFile);
        DownloadConnection response = openDownloadConnection(meta, localBytes, hasPartialFile, targetFile, partFile, metaFile);
        HttpURLConnection connection = response.connection;
        long startingOffset = response.startingOffset;

        if (response.resumed && startingOffset != localBytes) {
            long adjusted = response.startingOffset;
            updateDownload(d -> d.setDownloadedBytes(adjusted));
            meta.downloadedBytes = adjusted;
        }

        if (response.resumed) {
            updateDownload(d -> d.setResumeSupported(true));
        }

        long totalBytes = determineTotalBytes(connection, startingOffset, knownLength, response.responseCode);
        if (totalBytes > 0) {
            long finalTotalBytes = totalBytes;
            updateDownload(d -> d.setFileSize(finalTotalBytes));
            meta.totalLength = totalBytes;
        }

        String responseEtag = connection.getHeaderField("ETag");
        if (responseEtag != null && !responseEtag.isBlank()) {
            updateDownload(d -> d.setEtag(responseEtag));
            meta.etag = responseEtag;
        }
        String responseLastModified = connection.getHeaderField("Last-Modified");
        if (responseLastModified != null && !responseLastModified.isBlank()) {
            updateDownload(d -> d.setLastModified(responseLastModified));
            meta.lastModified = responseLastModified;
        }
        String acceptRanges = connection.getHeaderField("Accept-Ranges");
        if (acceptRanges != null && !acceptRanges.isBlank()) {
            boolean supportsResume = !"none".equalsIgnoreCase(acceptRanges.trim());
            updateDownload(d -> d.setResumeSupported(supportsResume));
        }
        persistMeta(metaFile, meta);

        updateDownload(d -> {
            d.setStatus("Downloading");
            d.setDownloadSpeed(0);
            d.setRemainingTime(0);
            d.setDownloadedBytes(startingOffset);
            d.setActualFilePath(partFile.toString());
            if (totalBytes > 0) {
                double progress = Math.min(100.0, (startingOffset * 100.0) / totalBytes);
                d.setProgress(progress);
            }
        });

        long downloaded = startingOffset;
        long lastUpdateBytes = downloaded;
        long lastUpdateTime = System.currentTimeMillis();
        long lastMetaUpdateTime = lastUpdateTime;

        try (InputStream input = connection.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(partFile.toFile(), "rw")) {
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

                waitIfPaused(metaFile, meta, downloaded);

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

                if (now - lastMetaUpdateTime >= META_UPDATE_INTERVAL_MS) {
                    meta.downloadedBytes = downloaded;
                    meta.updatedAt = now;
                    persistMeta(metaFile, meta);
                    lastMetaUpdateTime = now;
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

        finalizeDownload(targetFile, partFile, metaFile, downloaded);
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
        return target;
    }

    private Path resolvePartFile(Path target) {
        return target.resolveSibling(target.getFileName() + PART_EXTENSION);
    }

    private Path resolveMetaFile(Path target) {
        return target.resolveSibling(target.getFileName() + META_EXTENSION);
    }

    private void finalizeDownload(Path targetFile, Path partFile, Path metaFile, long downloadedBytes) throws IOException {
        if (Files.exists(partFile)) {
            Files.move(partFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
        deleteMeta(metaFile);
        long finalDownloaded = downloadedBytes;
        updateDownload(d -> {
            d.setDownloadedBytes(finalDownloaded);
            d.setProgress(100);
            d.setDownloadSpeed(0);
            d.setRemainingTime(0);
            d.setStatus("Completed");
            d.setActualFilePath(targetFile.toString());
        });
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

    private DownloadConnection openDownloadConnection(DownloadMeta meta,
                                                       long localBytes,
                                                       boolean hasPartialFile,
                                                       Path targetFile,
                                                       Path partFile,
                                                       Path metaFile)
            throws IOException, InterruptedException, RestartDeclinedException, DownloadHaltedException {
        HttpURLConnection connection = openConnection("GET");
        boolean attemptingResume = hasPartialFile;
        if (attemptingResume) {
            connection.setRequestProperty("Range", "bytes=" + localBytes + "-");
            String etag = meta.etag;
            if (etag != null && !etag.isBlank()) {
                connection.setRequestProperty("If-Match", etag);
            }
            String lastModified = meta.lastModified;
            if (lastModified != null && !lastModified.isBlank()) {
                connection.setRequestProperty("If-Unmodified-Since", lastModified);
            }
        }

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_FORBIDDEN || responseCode == HTTP_GONE) {
            connection.disconnect();
            throw new DownloadHaltedException("Error (URL expirada)");
        }

        if (attemptingResume) {
            if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
                validateResumedIdentity(meta, connection, localBytes);
                return new DownloadConnection(connection, localBytes, true, responseCode);
            }
            if (responseCode == HTTP_REQUESTED_RANGE_NOT_SATISFIABLE) {
                long totalFromRange = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
                if (totalFromRange > 0 && localBytes >= totalFromRange) {
                    connection.disconnect();
                    finalizeDownload(targetFile, partFile, metaFile, totalFromRange);
                    throw new DownloadHaltedException("Completed");
                }
                connection.disconnect();
                throw new DownloadHaltedException("Error (rango inválido)");
            }
            if (responseCode == HttpURLConnection.HTTP_OK) {
                boolean canValidate = canValidateIdentity(meta, connection);
                if (!canValidate) {
                    connection.disconnect();
                    throw new DownloadHaltedException("Error (recurso cambiado)");
                }
                connection.disconnect();
                handleRestartRequired(partFile, metaFile,
                        "El servidor no aceptó reanudar la descarga.");
                HttpURLConnection restart = openConnection("GET");
                int restartCode = restart.getResponseCode();
                if (restartCode != HttpURLConnection.HTTP_OK && restartCode != HttpURLConnection.HTTP_PARTIAL) {
                    restart.disconnect();
                    throw new IOException("GET " + restartCode);
                }
                return new DownloadConnection(restart, 0, restartCode == HttpURLConnection.HTTP_PARTIAL, restartCode);
            }
            if (responseCode == HTTP_PRECONDITION_FAILED) {
                connection.disconnect();
                throw new DownloadHaltedException("Error (recurso cambiado)");
            }
        }

        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect();
            throw new IOException("GET " + responseCode);
        }

        long newOffset = (responseCode == HttpURLConnection.HTTP_PARTIAL) ? localBytes : 0L;
        if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
            validateResumedIdentity(meta, connection, localBytes);
        }
        return new DownloadConnection(connection, newOffset, responseCode == HttpURLConnection.HTTP_PARTIAL, responseCode);
    }

    private void validateResumedIdentity(DownloadMeta meta, HttpURLConnection connection, long localBytes)
            throws DownloadHaltedException {
        if (localBytes <= 0) {
            return;
        }
        if (!canValidateIdentity(meta, connection)) {
            throw new DownloadHaltedException("Error (validación fallida)");
        }
        String responseEtag = connection.getHeaderField("ETag");
        if (meta.etag != null && responseEtag != null && !meta.etag.equals(responseEtag)) {
            throw new DownloadHaltedException("Error (recurso cambiado)");
        }
        String responseLastModified = connection.getHeaderField("Last-Modified");
        if (meta.lastModified != null && responseLastModified != null
                && !meta.lastModified.equals(responseLastModified)) {
            throw new DownloadHaltedException("Error (recurso cambiado)");
        }
        long totalFromRange = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
        if (meta.totalLength > 0 && totalFromRange > 0 && meta.totalLength != totalFromRange) {
            throw new DownloadHaltedException("Error (recurso cambiado)");
        }
    }

    private boolean canValidateIdentity(DownloadMeta meta, HttpURLConnection connection) {
        boolean hasExpectation = (meta.etag != null && !meta.etag.isBlank())
                || (meta.lastModified != null && !meta.lastModified.isBlank())
                || meta.totalLength > 0;
        boolean hasResponse = (connection.getHeaderField("ETag") != null)
                || (connection.getHeaderField("Last-Modified") != null)
                || parseContentRangeTotal(connection.getHeaderField("Content-Range")) > 0
                || connection.getContentLengthLong() > 0;
        return hasExpectation && hasResponse;
    }

    private void handleRestartRequired(Path partFile, Path metaFile, String reason)
            throws IOException, InterruptedException, RestartDeclinedException {
        CompletableFuture<Boolean> decisionFuture = download.askToRestartDownload(reason);
        boolean restart;
        try {
            restart = decisionFuture.get();
        } catch (InterruptedException interrupted) {
            decisionFuture.cancel(true);
            throw interrupted;
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IOException("No se pudo confirmar el reinicio de la descarga", cause);
        }

        if (!restart) {
            throw new RestartDeclinedException("User declined to restart download after resume failure");
        }

        try {
            Files.deleteIfExists(partFile);
        } catch (IOException ignored) {
        }
        deleteMeta(metaFile);

        updateDownload(d -> {
            d.setDownloadedBytes(0);
            d.setProgress(0);
            d.setDownloadSpeed(0);
            d.setRemainingTime(0);
            d.setStatus("Restarting");
            d.setEtag(null);
            d.setLastModified(null);
            d.setResumeSupported(false);
        });
    }

    private long determineTotalBytes(HttpURLConnection connection,
                                     long startingOffset,
                                     long knownLength,
                                     int responseCode) {
        long contentLength = connection.getContentLengthLong();
        if (contentLength <= 0) {
            long totalFromRange = parseContentRangeTotal(connection.getHeaderField("Content-Range"));
            if (totalFromRange > 0) {
                return totalFromRange;
            }
            return knownLength;
        }
        if (startingOffset > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL) {
            return startingOffset + contentLength;
        }
        return contentLength;
    }

    private long parseContentRangeTotal(String contentRange) {
        if (contentRange == null || contentRange.isBlank()) {
            return -1;
        }
        int slashIndex = contentRange.lastIndexOf('/');
        if (slashIndex < 0 || slashIndex + 1 >= contentRange.length()) {
            return -1;
        }
        String totalPart = contentRange.substring(slashIndex + 1).trim();
        if ("*".equals(totalPart)) {
            return -1;
        }
        try {
            return Long.parseLong(totalPart);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void waitIfPaused(Path metaFile, DownloadMeta meta, long downloadedBytes) throws InterruptedException {
        if (!paused.get()) {
            return;
        }
        meta.downloadedBytes = downloadedBytes;
        meta.updatedAt = System.currentTimeMillis();
        persistMeta(metaFile, meta);
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

    private boolean downloadHasMetaSnapshot() {
        return (download.getEtag() != null && !download.getEtag().isBlank())
                || (download.getLastModified() != null && !download.getLastModified().isBlank())
                || download.getFileSize() > 0
                || download.getDownloadedBytes() > 0;
    }

    private DownloadMeta loadMeta(Path metaFile) {
        if (!Files.exists(metaFile)) {
            return null;
        }
        try {
            String content = Files.readString(metaFile);
            JSONParser parser = new JSONParser();
            Object parsed = parser.parse(content);
            if (!(parsed instanceof JSONObject json)) {
                return null;
            }
            DownloadMeta meta = new DownloadMeta();
            meta.url = asString(json.get("url"));
            meta.etag = asString(json.get("etag"));
            meta.lastModified = asString(json.get("lastModified"));
            meta.totalLength = asLong(json.get("totalLength"));
            meta.downloadedBytes = asLong(json.get("downloadedBytes"));
            meta.updatedAt = asLong(json.get("updatedAt"));
            return meta;
        } catch (IOException | ParseException ignored) {
            return null;
        }
    }

    private void persistMeta(Path metaFile, DownloadMeta meta) {
        if (meta == null) {
            return;
        }
        JSONObject json = new JSONObject();
        json.put("url", meta.url);
        json.put("etag", meta.etag);
        json.put("lastModified", meta.lastModified);
        json.put("totalLength", meta.totalLength);
        json.put("downloadedBytes", meta.downloadedBytes);
        json.put("updatedAt", meta.updatedAt);
        try {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    Files.newOutputStream(metaFile), StandardCharsets.UTF_8))) {
                writer.write(json.toJSONString());
            }
        } catch (IOException ignored) {
        }
    }

    private void deleteMeta(Path metaFile) {
        try {
            Files.deleteIfExists(metaFile);
        } catch (IOException ignored) {
        }
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString();
        return str.isBlank() ? null : str;
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return -1L;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException ex) {
            return -1L;
        }
    }

    private static final class RestartDeclinedException extends Exception {
        private RestartDeclinedException(String message) {
            super(message);
        }
    }

    private static final class DownloadHaltedException extends Exception {
        private final String status;

        private DownloadHaltedException(String status) {
            super(status);
            this.status = status;
        }

        private String getStatus() {
            return status;
        }
    }

    private static final class RemoteMetadata {
        long contentLength = -1;
        boolean resumeSupported;
        String etag;
        String lastModified;
    }

    private static final class DownloadMeta {
        String url;
        String etag;
        String lastModified;
        long totalLength = -1;
        long downloadedBytes;
        long updatedAt;
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
