package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Implementación de descargador para el servidor Mixdrop
 */
public class MixdropDownloader implements DirectDownloader {
    // Configuración
    private static final String PRIMARY_CHROME_DRIVER = resolvePath("chrome-win", "chromedriver.exe");
    private static final String FALLBACK_CHROME_DRIVER = resolvePath("ChromeDriver", "chromedriver.exe");
    private static final String[] CHROME_DRIVER_CANDIDATES = {PRIMARY_CHROME_DRIVER, FALLBACK_CHROME_DRIVER};
    private static final String CHROME_BINARY_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final int WAIT_TIME_SECONDS = 10; // Tiempo de espera para Mixdrop

    private final Map<String, ResumableHttpDownloadTask> activeDownloads = new ConcurrentHashMap<>();

    private WebDriver driver;
    private final Map<String, Thread> resolverThreads = new ConcurrentHashMap<>();

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        Thread resolverThread = new Thread(() -> {
            try {
                updateDownloadStatus(directDownload, "Processing", 0);

                for (int i = 0; i < WAIT_TIME_SECONDS; i++) {
                    if (Thread.currentThread().isInterrupted()) {
                        updateDownloadStatus(directDownload, "Cancelled", directDownload.getProgress());
                        return;
                    }
                    Thread.sleep(1000);
                }

                String driverPath = ChromeExecutableLocator.resolveChromeDriver(CHROME_DRIVER_CANDIDATES);
                if (driverPath != null) {
                    System.setProperty("webdriver.chrome.driver", driverPath);
                } else {
                    System.clearProperty("webdriver.chrome.driver");
                    System.err.println("ChromeDriver empaquetado no disponible. Selenium Manager resolverá la versión adecuada.");
                }
                ChromeOptions options = new ChromeOptions();
                String chromeBinary = ChromeExecutableLocator.resolvePackagedChromeBinary(CHROME_BINARY_PATH);
                if (chromeBinary != null) {
                    options.setBinary(chromeBinary);
                } else {
                    System.err.println("No se encontró el Chrome empaquetado. Se usará el navegador predeterminado del sistema.");
                }
                options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080", "--remote-allow-origins=*");
                driver = new ChromeDriver(options);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

                updateDownloadStatus(directDownload, "Processing", 10);
                driver.get(videoUrl);
                updateDownloadStatus(directDownload, "Processing", 20);

                WebElement firstDownloadBtn = wait.until(ExpectedConditions.elementToBeClickable(
                        By.cssSelector("a.btn.btn3.download-btn.player")));
                firstDownloadBtn.click();
                updateDownloadStatus(directDownload, "Processing", 30);

                String originalWindow = driver.getWindowHandle();
                Set<String> handles = driver.getWindowHandles();
                for (int i = 0; i < 10 && handles.size() == 1; i++) {
                    Thread.sleep(500);
                    handles = driver.getWindowHandles();
                }
                for (String handle : handles) {
                    if (!handle.equals(originalWindow)) {
                        driver.switchTo().window(handle);
                        break;
                    }
                }
                Thread.sleep(2000);
                updateDownloadStatus(directDownload, "Processing", 40);

                for (int i = 0; i < 5; i++) {
                    try {
                        WebElement body = driver.findElement(By.tagName("body"));
                        body.click();
                        Thread.sleep(500);
                    } catch (Exception ignored) {
                    }
                }

                updateDownloadStatus(directDownload, "Processing", 50);
                WebElement secondDownloadBtn = wait.until(ExpectedConditions.elementToBeClickable(
                        By.cssSelector("a.btn.btn3.download-btn")));

                Thread.sleep(4000);
                updateDownloadStatus(directDownload, "Processing", 60);
                for (int i = 0; i < 3; i++) {
                    secondDownloadBtn.click();
                    Thread.sleep(500);
                }

                updateDownloadStatus(directDownload, "Processing", 70);
                String downloadUrl = secondDownloadBtn.getAttribute("href");
                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    throw new Exception("No se encontró la URL de descarga.");
                }

                updateDownloadStatus(directDownload, "Processing", 80);
                if (driver != null) {
                    driver.quit();
                    driver = null;
                }
                updateDownloadStatus(directDownload, "Processing", 90);

                Consumer<HttpURLConnection> headers = connection -> {
                    connection.setRequestProperty("Referer", "https://mixdrop.co/");
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                };

                ResumableHttpDownloadTask task = new ResumableHttpDownloadTask(downloadUrl, directDownload, headers);
                activeDownloads.put(directDownload.getId(), task);
                task.getCompletionFuture().whenComplete((ignored, error) -> activeDownloads.remove(directDownload.getId()));
                task.start();

            } catch (Exception e) {
                System.err.println("Error en la descarga de Mixdrop: " + e.getMessage());
                updateDownloadStatus(directDownload, "Error", 0);
            } finally {
                if (driver != null) {
                    try {
                        driver.quit();
                    } catch (Exception ignored) {
                    }
                    driver = null;
                }
                resolverThreads.remove(directDownload.getId());
            }
        }, "MixdropResolverThread");
        resolverThreads.put(directDownload.getId(), resolverThread);
        resolverThread.setDaemon(true);
        resolverThread.start();
    }

    @Override
    public void pauseDownload(DescargasUI.DirectDownload download) {
        ResumableHttpDownloadTask task = activeDownloads.get(download.getId());
        if (task != null) {
            task.pause();
        }
        updateDownloadStatus(download, "Paused", download.getProgress());
    }

    @Override
    public void resumeDownload(DescargasUI.DirectDownload download) {
        ResumableHttpDownloadTask task = activeDownloads.get(download.getId());
        if (task != null) {
            task.resume();
            updateDownloadStatus(download, "Downloading", download.getProgress());
        } else {
            download(download.getUrl(), download.getDestinationPath(), download);
        }
    }

    @Override
    public void cancelDownload(DescargasUI.DirectDownload download) {
        Thread resolver = resolverThreads.remove(download.getId());
        if (resolver != null) {
            resolver.interrupt();
        }
        ResumableHttpDownloadTask task = activeDownloads.remove(download.getId());
        if (task != null) {
            task.cancel();
        }
        updateDownloadStatus(download, "Cancelled", download.getProgress());
    }

    @Override
    public boolean isAvailable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            int responseCode = connection.getResponseCode();
            return (responseCode == HttpURLConnection.HTTP_OK);
        } catch (Exception e) {
            System.err.println("Error verificando disponibilidad: " + e.getMessage());
            return false;
        }
    }

    /**
     * Actualiza el estado de la descarga
     */
    private void updateDownloadStatus(DescargasUI.DirectDownload download, String status, int progress) {
        CompletableFuture.runAsync(() -> {
            download.setStatus(status);
            download.setProgress(progress);
        });
    }

    private static String resolvePath(String first, String... more) {
        Path path = Paths.get(first, more);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
        }
        return path.toAbsolutePath().toString();
    }
}
