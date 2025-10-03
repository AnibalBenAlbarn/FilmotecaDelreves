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

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementación de descargador para el servidor Mixdrop
 */
public class MixdropDownloader implements DirectDownloader {
    // Configuración
    private static final String CHROME_DRIVER_PATH = resolvePath("ChromeDriver", "chromedriver.exe");
    private static final String CHROME_BINARY_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final int WAIT_TIME_SECONDS = 10; // Tiempo de espera para Mixdrop
    private static final int MAX_ATTEMPTS = 3;      // Número máximo de intentos

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private WebDriver driver;
    private Thread downloadThread;

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        isCancelled.set(false);
        isPaused.set(false);

        downloadThread = new Thread(() -> {
            try {
                // Actualizar estado a "Procesando"
                updateDownloadStatus(directDownload, "Processing", 0);

                // Esperar el tiempo configurado
                System.out.println("Esperando " + WAIT_TIME_SECONDS + " segundos antes de iniciar la descarga...");
                for (int i = 0; i < WAIT_TIME_SECONDS; i++) {
                    if (isCancelled.get()) {
                        updateDownloadStatus(directDownload, "Cancelled", 0);
                        return;
                    }
                    Thread.sleep(1000);
                }

                // Configuración de ChromeDriver
                System.setProperty("webdriver.chrome.driver", CHROME_DRIVER_PATH);
                ChromeOptions options = new ChromeOptions();
                options.setBinary(CHROME_BINARY_PATH);
                options.addArguments("--no-sandbox", "--disable-dev-shm-usage", "--window-size=1920,1080", "--remote-allow-origins=*");
                driver = new ChromeDriver(options);
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));

                updateDownloadStatus(directDownload, "Processing", 10);

                // 1. Abrir la página del video en Mixdrop
                driver.get(videoUrl);

                updateDownloadStatus(directDownload, "Processing", 20);

                // 2. Esperar y hacer clic en el primer botón de descarga
                WebElement firstDownloadBtn = wait.until(ExpectedConditions.elementToBeClickable(
                        By.cssSelector("a.btn.btn3.download-btn.player")));
                firstDownloadBtn.click();

                updateDownloadStatus(directDownload, "Processing", 30);

                // 3. Esperar a que se abra una nueva pestaña y cambiar a ella
                String originalWindow = driver.getWindowHandle();
                Set<String> handles = driver.getWindowHandles();
                // Esperamos a que aparezca la nueva pestaña
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
                // Dar tiempo a que cargue la nueva pestaña
                Thread.sleep(2000);

                updateDownloadStatus(directDownload, "Processing", 40);

                // 4. Cerrar popups y anuncios haciendo clic varias veces sobre el body
                for (int i = 0; i < 5; i++) {
                    try {
                        WebElement body = driver.findElement(By.tagName("body"));
                        body.click();
                        Thread.sleep(500);
                    } catch (Exception e) {
                        // Ignorar excepciones si no se puede hacer clic
                    }
                }

                updateDownloadStatus(directDownload, "Processing", 50);

                // 5. Esperar a que aparezca el segundo botón de descarga visible y clickeable
                WebElement secondDownloadBtn = wait.until(ExpectedConditions.elementToBeClickable(
                        By.cssSelector("a.btn.btn3.download-btn")));

                // Espera adicional de 4 segundos
                Thread.sleep(4000);

                updateDownloadStatus(directDownload, "Processing", 60);

                // 6. Simular varios clics (2 o 3 veces) para iniciar la descarga
                for (int i = 0; i < 3; i++) {
                    secondDownloadBtn.click();
                    Thread.sleep(500);
                }

                updateDownloadStatus(directDownload, "Processing", 70);

                // 7. Extraer la URL de descarga del atributo href del botón
                String downloadUrl = secondDownloadBtn.getAttribute("href");
                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    throw new Exception("No se encontró la URL de descarga.");
                }

                updateDownloadStatus(directDownload, "Processing", 80);

                // Cerrar el navegador antes de iniciar la descarga por HTTP
                driver.quit();
                driver = null;

                updateDownloadStatus(directDownload, "Processing", 90);

                // Crear nombre de archivo
                String fileName = directDownload.getName();
                if (!fileName.toLowerCase().endsWith(".mp4")) {
                    fileName += ".mp4";
                }

                // 8. Descargar el archivo usando HttpURLConnection
                downloadFile(downloadUrl, fileName, destinationPath, directDownload);

                System.out.println("Descarga completada: " + fileName);

            } catch (Exception e) {
                System.err.println("Error en la descarga de Mixdrop: " + e.getMessage());
                e.printStackTrace();
                updateDownloadStatus(directDownload, "Error", 0);
            } finally {
                if (driver != null) {
                    try {
                        driver.quit();
                    } catch (Exception e) {
                        // Ignorar excepciones al cerrar el navegador
                    }
                    driver = null;
                }
            }
        });

        downloadThread.start();
    }

    @Override
    public void pauseDownload(DescargasUI.DirectDownload download) {
        isPaused.set(true);
        updateDownloadStatus(download, "Paused", download.getProgress());
    }

    @Override
    public void resumeDownload(DescargasUI.DirectDownload download) {
        isPaused.set(false);
        updateDownloadStatus(download, "Downloading", download.getProgress());
    }

    @Override
    public void cancelDownload(DescargasUI.DirectDownload download) {
        isCancelled.set(true);
        if (downloadThread != null && downloadThread.isAlive()) {
            downloadThread.interrupt();
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
     * Descarga el archivo desde la URL
     */
    private void downloadFile(String fileUrl, String fileName, String outputPath, DescargasUI.DirectDownload directDownload) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // Establecer cabeceras para simular una petición real
        connection.setRequestProperty("Referer", "https://mixdrop.co/");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");
        connection.setRequestMethod("GET");
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Error en la descarga: HTTP " + connection.getResponseCode());
        }

        // Crear el directorio de salida si no existe
        File outDir = new File(outputPath);
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new Exception("No se pudo crear el directorio: " + outputPath);
        }

        long fileSize = connection.getContentLengthLong();
        directDownload.setFileSize(fileSize);
        long startTime = System.currentTimeMillis();

        File outputFile = new File(outDir, fileName);
        try (InputStream in = connection.getInputStream();
             FileOutputStream fos = new FileOutputStream(outputFile)) {

            updateDownloadStatus(directDownload, "Downloading", 1);

            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;

            while ((bytesRead = in.read(buffer)) != -1) {
                // Verificar si la descarga fue cancelada
                if (isCancelled.get()) {
                    System.out.println("Descarga cancelada por el usuario");
                    updateDownloadStatus(directDownload, "Cancelled", (int)((double)totalRead / fileSize * 100));
                    return;
                }

                // Verificar si la descarga está pausada
                while (isPaused.get() && !isCancelled.get()) {
                    Thread.sleep(500);
                }

                fos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                // Actualizar progreso
                int progressPercent = (int)((double)totalRead / fileSize * 100);
                updateDownloadStatus(directDownload, "Downloading", progressPercent);

                // Imprimir progreso en consola
                printProgress(startTime, totalRead, fileSize);
            }

            updateDownloadStatus(directDownload, "Completed", 100);
        }
    }

    /**
     * Imprime el progreso de la descarga en la consola
     */
    private void printProgress(long startTime, long downloaded, long total) {
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = (downloaded / 1024.0) / (elapsed / 1000.0); // KB/s
        double percent = (downloaded * 100.0) / total;

        long remaining = (long) ((total - downloaded) / (speed * 1024));

        System.out.printf("\rProgreso: %.2f%% | Velocidad: %.2f MB/s | Tiempo restante: %02d:%02d",
                percent,
                speed / 1024,
                remaining / 60,
                remaining % 60);
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