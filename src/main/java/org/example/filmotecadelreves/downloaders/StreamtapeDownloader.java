package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementación de descargador para el servidor Streamtape
 */
public class StreamtapeDownloader implements DirectDownloader {
    private static final String CHROME_DRIVER_PATH = "lib/chromedriver-win64/chromedriver.exe";
    private static final String CHROME_PATH = "lib/chrome-win64/chrome.exe";
    private static final String EXTENSION_PATH = "lib/Streamtape.crx";

    private static final int WAIT_TIME_SECONDS = 5;
    private static final int MAX_ATTEMPTS = 3;

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private WebDriver driver;
    private WebDriverWait wait;
    private Thread downloadThread;

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        isCancelled.set(false);
        isPaused.set(false);

        downloadThread = new Thread(() -> {
            try {
                updateDownloadStatus(directDownload, "Processing", 0, 0, 0, 0);
                initializeBrowser();
                String downloadUrl = handleCopyAndGetUrl(videoUrl);

                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    updateDownloadStatus(directDownload, "Error", 0, 0, 0, 0);
                    return;
                }

                System.out.println("Esperando " + WAIT_TIME_SECONDS + " segundos...");
                for (int i = 0; i < WAIT_TIME_SECONDS; i++) {
                    if (isCancelled.get()) {
                        updateDownloadStatus(directDownload, "Cancelled", 0, 0, 0, 0);
                        return;
                    }
                    Thread.sleep(1000);
                }

                updateDownloadStatus(directDownload, "Downloading", 1, 0, 0, 0);
                String fileName = directDownload.getName();
                if (!fileName.toLowerCase().endsWith(".mp4")) {
                    fileName += ".mp4";
                }

                downloadWithProgress(downloadUrl, destinationPath, fileName, directDownload);

            } catch (Exception e) {
                System.err.println("Error en la descarga: " + e.getMessage());
                updateDownloadStatus(directDownload, "Error", 0, 0, 0, 0);
            } finally {
                closeBrowser();
            }
        });
        downloadThread.start();
    }

    private void downloadWithProgress(String fileUrl, String outputPath, String fileName, DescargasUI.DirectDownload directDownload) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Referer", "https://streamtape.com/");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        int fileSize = connection.getContentLength();
        directDownload.setFileSize(fileSize);

        long startTime = System.currentTimeMillis();
        File destDir = new File(outputPath);
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new Exception("No se pudo crear el directorio: " + outputPath);
        }

        File outputFile = new File(destDir, fileName);

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(outputFile)) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long lastUpdateTime = System.currentTimeMillis();
            long lastDownloadedBytes = 0;

            System.out.println("\nIniciando descarga:");
            System.out.println("URL: " + fileUrl);
            System.out.println("Tamaño: " + formatSize(fileSize));

            while ((bytesRead = in.read(buffer)) != -1) {
                if (isCancelled.get()) {
                    System.out.println("Descarga cancelada por el usuario");
                    updateDownloadStatus(directDownload, "Cancelled",
                            getProgress(totalRead, fileSize), totalRead, 0, 0);
                    return;
                }

                while (isPaused.get()) {
                    Thread.sleep(500);
                    if (isCancelled.get()) {
                        System.out.println("Descarga cancelada durante pausa");
                        updateDownloadStatus(directDownload, "Cancelled",
                                getProgress(totalRead, fileSize), totalRead, 0, 0);
                        return;
                    }
                }

                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastUpdateTime >= 1000) {
                    double elapsedSeconds = (currentTime - lastUpdateTime) / 1000.0;
                    double bytesPerSecond = (totalRead - lastDownloadedBytes) / elapsedSeconds;
                    double speedMBps = bytesPerSecond / (1024 * 1024);
                    long remainingSeconds = (long) ((fileSize - totalRead) / bytesPerSecond);

                    updateDownloadStatus(directDownload, "Downloading",
                            getProgress(totalRead, fileSize), totalRead, speedMBps, remainingSeconds);

                    lastUpdateTime = currentTime;
                    lastDownloadedBytes = totalRead;
                    printProgress(startTime, totalRead, fileSize);
                }
            }

            System.out.println("\nDescarga completada con éxito!");
            updateDownloadStatus(directDownload, "Completed", 100, totalRead, 0, 0);

        } catch (Exception e) {
            System.err.println("Error en la descarga: " + e.getMessage());
            updateDownloadStatus(directDownload, "Error",
                    getProgress(outputFile.length(), fileSize), outputFile.length(), 0, 0);
            throw e;
        }
    }

    private int getProgress(long downloaded, long total) {
        return (int) ((double) downloaded / total * 100);
    }

    // Resto de métodos se mantienen igual hasta initializeBrowser()

    private void initializeBrowser() {
        System.setProperty("webdriver.chrome.driver", CHROME_DRIVER_PATH);
        ChromeOptions options = new ChromeOptions();
        options.setBinary(CHROME_PATH);
        options.addExtensions(new File(EXTENSION_PATH));
        options.addArguments(
                "--disable-notifications",
                "--window-size=1920,1080",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--remote-allow-origins=*"
        );
        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    private String handleCopyAndGetUrl(String videoUrl) throws Exception {
        int attempts = 0;
        String downloadUrl = null;

        while (attempts < MAX_ATTEMPTS && downloadUrl == null) {
            attempts++;
            try {
                driver.get(videoUrl);
                wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
                WebElement copyBtn = wait.until(ExpectedConditions.elementToBeClickable(By.id("copyButton")));
                copyBtn.click();

                try {
                    Alert alert = wait.until(ExpectedConditions.alertIsPresent());
                    alert.accept();
                } catch (TimeoutException e) {
                    System.out.println("Alerta no detectada");
                }

                Thread.sleep(1500);
                downloadUrl = getClipboardContent();

                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    System.out.println("Intento " + attempts + ": URL no obtenida");
                    continue;
                }

                return downloadUrl;

            } catch (Exception e) {
                System.err.println("Intento " + attempts + " fallido: " + e.getMessage());
                if (attempts >= MAX_ATTEMPTS) throw e;
                Thread.sleep(2000);
            }
        }
        return null;
    }

    private String getClipboardContent() throws Exception {
        try {
            return (String) Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .getData(DataFlavor.stringFlavor);
        } catch (Exception e) {
            System.err.println("Error en portapapeles: " + e.getMessage());
            return null;
        }
    }

    private void printProgress(long startTime, long downloaded, long total) {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = (downloaded / 1024.0) / (elapsed / 1000.0);
        double percent = (downloaded * 100.0) / total;
        long remaining = (long) ((total - downloaded) / (speed * 1024));

        System.out.printf("\rProgreso: %.2f%% | Velocidad: %.2f MB/s | Tiempo restante: %02d:%02d",
                percent, speed / 1024, remaining / 60, remaining % 60);
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log(bytes) / Math.log(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private void closeBrowser() {
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                System.err.println("Error cerrando navegador: " + e.getMessage());
            }
            driver = null;
        }
    }

    private void updateDownloadStatus(DescargasUI.DirectDownload download, String status, int progress,
                                      long downloadedBytes, double speed, long remainingTime) {
        CompletableFuture.runAsync(() -> {
            download.setStatus(status);
            download.setProgress(progress);
            download.setDownloadedBytes(downloadedBytes);
            download.setDownloadSpeed(speed);
            download.setRemainingTime(remainingTime);
        });
    }

    @Override
    public void pauseDownload(DescargasUI.DirectDownload download) {
        isPaused.set(true);
        updateDownloadStatus(download, "Paused", download.getProgress(),
                download.getDownloadedBytes(), 0, download.getRemainingTime());
    }

    @Override
    public void resumeDownload(DescargasUI.DirectDownload download) {
        isPaused.set(false);
        updateDownloadStatus(download, "Downloading", download.getProgress(),
                download.getDownloadedBytes(), download.getDownloadSpeed(), download.getRemainingTime());
    }

    @Override
    public void cancelDownload(DescargasUI.DirectDownload download) {
        isCancelled.set(true);
        if (downloadThread != null && downloadThread.isAlive()) {
            downloadThread.interrupt();
        }
        updateDownloadStatus(download, "Cancelled", download.getProgress(),
                download.getDownloadedBytes(), 0, 0);
    }

    @Override
    public boolean isAvailable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } catch (Exception e) {
            System.err.println("Error verificando disponibilidad: " + e.getMessage());
            return false;
        }
    }
}