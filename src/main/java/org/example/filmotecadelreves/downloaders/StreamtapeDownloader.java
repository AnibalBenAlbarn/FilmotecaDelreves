package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Implementación de descargador para Streamtape SIN Selenium: resuelve el enlace directo
 * por scraping con Jsoup y descarga el archivo automáticamente (sin escribir en BD).
 */
public class StreamtapeDownloader implements DirectDownloader {

    private static final int SCRAPE_TIMEOUT_MS = 15_000;
    private static final int AVAILABILITY_TIMEOUT_MS = 10_000;

    private final Map<String, ResumableHttpDownloadTask> activeDownloads = new ConcurrentHashMap<>();

    // Guarda en memoria el último enlace resuelto (no BD)
    private volatile String lastResolvedUrl = null;

    public String getLastResolvedUrl() {
        return lastResolvedUrl;
    }

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        Thread resolverThread = new Thread(() -> {
            try {
                updateDownloadStatus(directDownload, "Processing", directDownload.getProgress(), directDownload.getDownloadedBytes(), directDownload.getDownloadSpeed(), directDownload.getRemainingTime());

                String downloadUrl = resolveStreamtapeDownloadUrl(videoUrl);
                if (downloadUrl == null) {
                    updateDownloadStatus(directDownload, "Error", directDownload.getProgress(), directDownload.getDownloadedBytes(), directDownload.getDownloadSpeed(), directDownload.getRemainingTime());
                    return;
                }

                this.lastResolvedUrl = downloadUrl;

                Consumer<HttpURLConnection> headers = connection -> {
                    connection.setRequestProperty("Referer", "https://streamtape.com/");
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                };

                ResumableHttpDownloadTask task = new ResumableHttpDownloadTask(downloadUrl, directDownload, headers);
                activeDownloads.put(directDownload.getId(), task);
                task.getCompletionFuture().whenComplete((ignored, error) -> activeDownloads.remove(directDownload.getId()));
                task.start();
            } catch (Exception e) {
                updateDownloadStatus(directDownload, "Error", directDownload.getProgress(), directDownload.getDownloadedBytes(), directDownload.getDownloadSpeed(), directDownload.getRemainingTime());
            }
        }, "StreamtapeResolverThread");
        resolverThread.setDaemon(true);
        resolverThread.start();
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
                    .timeout(SCRAPE_TIMEOUT_MS)
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
        ResumableHttpDownloadTask task = activeDownloads.get(download.getId());
        if (task != null) {
            task.pause();
        }
        updateDownloadStatus(download, "Paused", download.getProgress(), download.getDownloadedBytes(), 0.0, download.getRemainingTime());
    }

    @Override
    public void resumeDownload(DescargasUI.DirectDownload download) {
        ResumableHttpDownloadTask task = activeDownloads.get(download.getId());
        if (task != null) {
            task.resume();
            updateDownloadStatus(download, "Downloading", download.getProgress(), download.getDownloadedBytes(), download.getDownloadSpeed(), download.getRemainingTime());
        } else {
            download(download.getUrl(), download.getDestinationPath(), download);
        }
    }

    @Override
    public void cancelDownload(DescargasUI.DirectDownload download) {
        ResumableHttpDownloadTask task = activeDownloads.remove(download.getId());
        if (task != null) {
            task.cancel();
        }
        updateDownloadStatus(download, "Cancelled", download.getProgress(), download.getDownloadedBytes(), 0.0, download.getRemainingTime());
    }

    @Override
    public boolean isAvailable(String url) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(AVAILABILITY_TIMEOUT_MS);
            conn.setReadTimeout(AVAILABILITY_TIMEOUT_MS);
            return conn.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            System.err.println("Error verificando disponibilidad: " + e.getMessage());
            return false;
        }
    }
}
