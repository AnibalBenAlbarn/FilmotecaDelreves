package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementación de descargador para Streamtape SIN Selenium: resuelve el enlace directo
 * por scraping con Jsoup y descarga el archivo automáticamente (sin escribir en BD).
 */
public class StreamtapeDownloader implements DirectDownloader {

    private static final int CONNECT_TIMEOUT_MS = (int) Duration.ofSeconds(15).toMillis();
    private static final int READ_TIMEOUT_MS = (int) Duration.ofSeconds(60).toMillis();

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);
    private Thread downloadThread;

    // Guarda en memoria el último enlace resuelto (no BD)
    private volatile String lastResolvedUrl = null;

    public String getLastResolvedUrl() {
        return lastResolvedUrl;
    }

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        isCancelled.set(false);
        isPaused.set(false);

        downloadThread = new Thread(() -> {
            try {
                updateDownloadStatus(directDownload, "Processing", 0, 0, 0.0, 0);

                // 1) Resolver URL de descarga directa desde el enlace original (probablemente el de la BD)
                String downloadUrl = resolveStreamtapeDownloadUrl(videoUrl);
                if (downloadUrl == null) {
                    updateDownloadStatus(directDownload, "Error", 0, 0, 0.0, 0);
                    return;
                }

                // Guardar en memoria (campo de instancia)
                this.lastResolvedUrl = downloadUrl;

                // 3) Preparar nombre de archivo
                String fileName = directDownload.getName();
                if (fileName == null || fileName.isBlank()) {
                    fileName = "streamtape_video";
                }
                if (!fileName.toLowerCase().endsWith(".mp4")) {
                    fileName += ".mp4";
                }

                // 4) Descargar
                updateDownloadStatus(directDownload, "Downloading", 1, 0, 0.0, 0);
                downloadWithProgress(downloadUrl, destinationPath, fileName, directDownload);

                if (!isCancelled.get()) {
                    updateDownloadStatus(directDownload, "Completed", 100, directDownload.getDownloadedBytes(), 0.0, 0);
                }

            } catch (Exception e) {
                e.printStackTrace();
                updateDownloadStatus(directDownload, "Error", directDownload.getProgress(), directDownload.getDownloadedBytes(), directDownload.getDownloadSpeed(), directDownload.getRemainingTime());
            }
        }, "StreamtapeDownloaderThread");

        downloadThread.start();
    }

    /**
     * Resuelve el enlace de descarga directa de Streamtape:
     *  - Normaliza /e/ -> /v/
     *  - Extrae token de la sección norobot y el host oculto en #ideoooolink
     */
    private String resolveStreamtapeDownloadUrl(String link) {
        try {
            if (link == null || link.isBlank()) {
                return null;
            }

            if (link.contains("/e/")) {
                link = link.replace("/e/", "/v/");
            }

            Document doc = Jsoup.connect(link)
                    .userAgent("Mozilla/5.0")
                    .timeout(CONNECT_TIMEOUT_MS)
                    .get();

            String html = doc.html();

            // document.getElementById('norobotlink').innerHTML = ...
            Pattern norobotLinkPattern = Pattern.compile("document\\.getElementById\\('norobotlink'\\)\\.innerHTML = (.+?);", Pattern.DOTALL);
            Matcher norobotLinkMatcher = norobotLinkPattern.matcher(html);
            if (!norobotLinkMatcher.find()) {
                return null;
            }
            String norobotLinkContent = norobotLinkMatcher.group(1);

            // token=XXXX
            Pattern tokenPattern = Pattern.compile("token=([^&']+)");
            Matcher tokenMatcher = tokenPattern.matcher(norobotLinkContent);
            if (!tokenMatcher.find()) {
                return null;
            }
            String token = tokenMatcher.group(1);

            Elements el = doc.select("div#ideoooolink[style='display:none;']");
            if (el.isEmpty()) {
                return null;
            }
            String streamHost = Objects.requireNonNull(el.first()).text().trim();

            // Ensamblar URL final
            String base = "https:/" + streamHost; // streamHost ya contiene una barra inicial
            return base + "&token=" + token + "&dl=1";
        } catch (Exception e) {
            System.err.println("Error resolviendo Streamtape: " + e.getMessage());
            return null;
        }
    }

    private void downloadWithProgress(String fileUrl, String outputPath, String fileName, DescargasUI.DirectDownload dd) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(fileUrl).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Referer", "https://streamtape.com/");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        long fileSize = connection.getContentLengthLong();
        dd.setFileSize(fileSize);

        File destDir = new File(outputPath);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new Exception("No se pudo crear el directorio: " + outputPath);
        }
        File outputFile = new File(destDir, sanitize(fileName));

        long lastUpdateTime = System.currentTimeMillis();
        long lastDownloadedBytes = 0L;

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(outputFile)) {

            byte[] buf = new byte[8192];
            int n;
            long totalRead = 0L;

            while ((n = in.read(buf)) != -1) {
                if (isCancelled.get()) {
                    updateDownloadStatus(dd, "Cancelled", dd.getProgress(), dd.getDownloadedBytes(), 0.0, dd.getRemainingTime());
                    return;
                }
                while (isPaused.get()) {
                    Thread.sleep(200);
                    if (isCancelled.get()) {
                        updateDownloadStatus(dd, "Cancelled", dd.getProgress(), dd.getDownloadedBytes(), 0.0, dd.getRemainingTime());
                        return;
                    }
                }

                out.write(buf, 0, n);
                totalRead += n;
                dd.setDownloadedBytes(totalRead);

                long now = System.currentTimeMillis();
                if (now - lastUpdateTime >= 500) {
                    long deltaBytes = totalRead - lastDownloadedBytes;
                    long elapsedMs = now - lastUpdateTime;
                    long speedBps = elapsedMs > 0 ? (deltaBytes * 1000) / elapsedMs : 0;
                    int progress = fileSize > 0 ? (int) Math.min(100, (totalRead * 100 / fileSize)) : dd.getProgress();
                    long remainingSeconds = (speedBps > 0 && fileSize > 0)
                            ? Math.max(0, (fileSize - totalRead) / speedBps)
                            : dd.getRemainingTime();
                    updateDownloadStatus(dd, "Downloading", progress, totalRead, bytesPerSecondToMBps(speedBps), remainingSeconds);
                    lastUpdateTime = now;
                    lastDownloadedBytes = totalRead;
                }
            }

            dd.setDownloadedBytes(totalRead);
            updateDownloadStatus(dd, "Completed", 100, totalRead, 0.0, 0);
        } finally {
            connection.disconnect();
        }
    }

    private String sanitize(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        // Evitar nombres problemáticos en Windows
        if (cleaned.matches("(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])$")) {
            cleaned = "_" + cleaned;
        }
        return new String(cleaned.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    private double bytesPerSecondToMBps(long bytesPerSecond) {
        return bytesPerSecond / (1024.0 * 1024.0);
    }

    private void updateDownloadStatus(DescargasUI.DirectDownload download, String status,
                                      int progress, long downloadedBytes, double speedMBps, long remainingSeconds) {
        CompletableFuture.runAsync(() -> {
            try {
                download.setStatus(status);
                if (progress >= 0) {
                    download.setProgress(progress);
                }
                if (downloadedBytes >= 0) {
                    download.setDownloadedBytes(downloadedBytes);
                }
                if (speedMBps >= 0) {
                    download.setDownloadSpeed(speedMBps);
                }
                if (remainingSeconds >= 0) {
                    download.setRemainingTime(remainingSeconds);
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void pauseDownload(DescargasUI.DirectDownload download) {
        isPaused.set(true);
        updateDownloadStatus(download, "Paused", download.getProgress(), download.getDownloadedBytes(), 0.0, download.getRemainingTime());
    }

    @Override
    public void resumeDownload(DescargasUI.DirectDownload download) {
        isPaused.set(false);
        updateDownloadStatus(download, "Downloading", download.getProgress(), download.getDownloadedBytes(), download.getDownloadSpeed(), download.getRemainingTime());
    }

    @Override
    public void cancelDownload(DescargasUI.DirectDownload download) {
        isCancelled.set(true);
        if (downloadThread != null && downloadThread.isAlive()) {
            downloadThread.interrupt();
        }
        updateDownloadStatus(download, "Cancelled", download.getProgress(), download.getDownloadedBytes(), 0.0, download.getRemainingTime());
    }

    @Override
    public boolean isAvailable(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            System.err.println("Error verificando disponibilidad: " + e.getMessage());
            return false;
        }
    }
}
