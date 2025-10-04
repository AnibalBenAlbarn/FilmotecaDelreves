package org.example.filmotecadelreves.downloaders;


import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.example.filmotecadelreves.moviesad.DownloadLimitManager;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementación del descargador para el servidor Powvideo
 */
public class SeleniumPowvideo implements DirectDownloader {
    private static final String CHROME_DRIVER_PATH = resolvePath("ChromeDriver", "chromedriver.exe");
    private static final String CHROME_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final String[] NOPECHA_EXTENSION_CANDIDATES = {
            "Extension/NopeCaptcha.crx",
            "lib/nopecha.crx",
            "C:\\Users\\Anibal\\IdeaProjects\\FilmotecaDelreves\\Extension\\NopeCaptcha.crx"
    };
    private static final Duration CAPTCHA_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicBoolean isPaused = new AtomicBoolean(false);

    private WebDriver driver;
    private WebDriverWait wait;
    private Thread downloadThread;
    private boolean isNopechaInstalled = false;

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        isCancelled.set(false);
        isPaused.set(false);

        downloadThread = new Thread(() -> {
            try {
                // Verificar límite de descargas
                boolean limitReached = DownloadLimitManager.isPowvideoStreamplayLimitReached();

                // Actualizar estado a "Processing"
                updateDownloadStatus(directDownload, "Processing", 0);

                // Configurar navegador
                setupBrowser(limitReached);

                // Si se ha alcanzado el límite, mostrar navegador para que el usuario resuelva el captcha
                if (limitReached) {
                    System.out.println("Límite de descargas alcanzado para PowVideo. El usuario debe resolver el captcha.");

                    // Mostrar diálogo de progreso con cuenta atrás
                    ProgressDialog progressDialog = new ProgressDialog(
                            "Esperando resolución de CAPTCHA",
                            "Por favor, resuelva el CAPTCHA en el navegador",
                            true);

                    javafx.application.Platform.runLater(() -> {
                        progressDialog.show();
                    });

                    // Abrir la URL y esperar a que el usuario resuelva el captcha
                    driver.get(videoUrl);

                    // Esperar 2 minutos como máximo para que el usuario resuelva el captcha
                    long startTime = System.currentTimeMillis();
                    long timeoutMillis = 120000; // 2 minutos

                    while (System.currentTimeMillis() - startTime < timeoutMillis) {
                        // Verificar si se ha cancelado la descarga
                        if (isCancelled.get()) {
                            javafx.application.Platform.runLater(() -> progressDialog.close());
                            updateDownloadStatus(directDownload, "Cancelled", 0);
                            return;
                        }

                        // Actualizar cuenta atrás
                        long remainingMillis = timeoutMillis - (System.currentTimeMillis() - startTime);
                        long remainingSeconds = remainingMillis / 1000;
                        String countdownText = String.format("Tiempo restante: %02d:%02d",
                                remainingSeconds / 60, remainingSeconds % 60);

                        javafx.application.Platform.runLater(() -> {
                            progressDialog.updateCountdown(countdownText);
                        });

                        // Verificar si ya se puede detectar el video
                        try {
                            if (driver.getPageSource().contains(".mp4")) {
                                System.out.println("Captcha resuelto por el usuario, continuando con la descarga.");
                                break;
                            }
                        } catch (Exception e) {
                            // Ignorar errores durante la comprobación
                        }

                        // Esperar un poco antes de la siguiente comprobación
                        Thread.sleep(1000);
                    }

                    // Cerrar el diálogo de progreso
                    javafx.application.Platform.runLater(() -> progressDialog.close());

                    // Si se agotó el tiempo, mostrar error
                    if (System.currentTimeMillis() - startTime >= timeoutMillis) {
                        updateDownloadStatus(directDownload, "Error", 0);
                        System.err.println("Tiempo agotado esperando que el usuario resuelva el captcha.");
                        return;
                    }
                } else {
                    // Obtener enlace de descarga de forma automática
                    driver.get(videoUrl);
                    wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));

                    // Esperar a que la extensión resuelva el captcha inicial antes de continuar
                    if (isNopechaInstalled) {
                        waitForNopechaResolution("PowVideo");
                    } else {
                        System.out.println("Extensión NoPeCaptcha no disponible, se continúa sin esperar la resolución automática del captcha.");
                    }

                    // Intentar obtener el enlace de descarga
                    Actions actions = new Actions(driver);
                    int attempt = 1;
                    while (true) {
                        // En intento impar: click en el body
                        if (attempt % 2 != 0) {
                            try {
                                actions.moveByOffset(10, 10).click().perform();
                                System.out.println("Intento " + attempt + ": Click en el body realizado.");
                            } catch (Exception e) {
                                System.out.println("Intento " + attempt + ": Error al hacer click en el body: " + e.getMessage());
                            }
                        } else {
                            // En intento par: click en el botón
                            try {
                                // Se vuelve a buscar el botón para evitar la referencia obsoleta
                                WebElement btnDownload = wait.until(ExpectedConditions.presenceOfElementLocated(By.id("btn_download")));
                                btnDownload.click();
                                System.out.println("Intento " + attempt + ": Click en el botón 'Continuar al video' realizado.");
                            } catch (Exception e) {
                                System.out.println("Intento " + attempt + ": Error al hacer click en el botón: " + e.getMessage());
                            }
                        }
                        Thread.sleep(1000);

                        // Después de cada click par se verifica si se detecta ".mp4" en el HTML
                        if (attempt % 2 == 0) {
                            if (driver.getPageSource().contains(".mp4")) {
                                System.out.println("Archivo .mp4 detectado en el HTML.");
                                break;
                            }
                        }
                        attempt++;
                    }
                }

                // Esperar 5 segundos para que se cargue la nueva página
                Thread.sleep(5000);

                // Buscar la etiqueta <video> y extraer la URL del mp4
                WebElement videoPlayer = wait.until(ExpectedConditions.presenceOfElementLocated(By.cssSelector("video")));
                String videoSrc = videoPlayer.getAttribute("src");
                System.out.println("Enlace del video: " + videoSrc);

                if (videoSrc == null || videoSrc.isEmpty()) {
                    updateDownloadStatus(directDownload, "Error", 0);
                    return;
                }

                // Crear directorio de destino si no existe
                File destDir = new File(destinationPath);
                if (!destDir.exists() && !destDir.mkdirs()) {
                    throw new Exception("No se pudo crear el directorio de destino: " + destinationPath);
                }

                // Descargar archivo
                downloadFile(videoSrc, destinationPath, directDownload.getName(), directDownload);

                // Incrementar contador de descargas solo si la descarga fue exitosa
                if ("Completed".equals(directDownload.getStatus())) {
                    DownloadLimitManager.incrementPowvideoStreamplayCount();
                }

            } catch (Exception e) {
                System.err.println("Error en la descarga de Powvideo: " + e.getMessage());
                e.printStackTrace();
                updateDownloadStatus(directDownload, "Error", 0);
            } finally {
                if (driver != null) {
                    driver.quit();
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
     * Configura el navegador Chrome con las opciones necesarias.
     * @param userInteraction Si es true, no se usa el modo headless para permitir la interacción del usuario
     */
    private void setupBrowser(boolean userInteraction) {
        System.setProperty("webdriver.chrome.driver", CHROME_DRIVER_PATH);
        ChromeOptions options = new ChromeOptions();
        options.setBinary(CHROME_PATH);

        // Cargar extensiones
        if (!addExtensionFromCandidates(options, VideosStreamerManager.getPopupExtensionCandidates())) {
            System.out.println("Advertencia: no se pudo cargar la extensión de bloqueo de popups para Powvideo.");
        }

        // Solo cargar la extensión NoPecha si no se requiere interacción del usuario
        if (!userInteraction) {
            isNopechaInstalled = addExtensionFromCandidates(options, NOPECHA_EXTENSION_CANDIDATES);
            if (!isNopechaInstalled) {
                System.out.println("Advertencia: no se pudo cargar la extensión NoPeCaptcha para Powvideo.");
            }
        } else {
            isNopechaInstalled = false;
        }

        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--window-size=1920,1080",
                "--remote-allow-origins=*"
        );

        // Usar modo headless solo si no se requiere interacción del usuario
        if (!userInteraction) {
            options.addArguments("--headless=new");
        }

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
    }

    private void waitForNopechaResolution(String providerName) {
        if (driver == null) {
            return;
        }

        WebDriverWait captchaWait = new WebDriverWait(driver, CAPTCHA_WAIT_TIMEOUT);
        try {
            captchaWait.until(webDriver -> {
                try {
                    Object solved = ((JavascriptExecutor) webDriver).executeScript(
                            "const hasVideo = !!document.querySelector('video[src]');" +
                                    "if (hasVideo) { return true; }" +
                                    "const hasMp4 = document.documentElement.innerHTML.includes('.mp4');" +
                                    "if (hasMp4) { return true; }" +
                                    "const button = document.querySelector('#btn_download, #btn_downl, #btn_continue');" +
                                    "if (button && !button.disabled) { return true; }" +
                                    "const frames = Array.from(document.querySelectorAll('iframe')).filter(frame => {" +
                                    "  const src = (frame.getAttribute('src') || '').toLowerCase();" +
                                    "  const id = (frame.id || '').toLowerCase();" +
                                    "  const cls = (frame.className || '').toLowerCase();" +
                                    "  return src.includes('captcha') || src.includes('hcaptcha') || src.includes('recaptcha') ||" +
                                    "         id.includes('captcha') || cls.includes('captcha');" +
                                    "});" +
                                    "if (frames.length === 0) {" +
                                    "  const challenges = Array.from(document.querySelectorAll('[data-sitekey]'));" +
                                    "  return challenges.length === 0;" +
                                    "}" +
                                    "return false;"
                    );
                    return solved instanceof Boolean && (Boolean) solved;
                } catch (JavascriptException e) {
                    return false;
                }
            });
            System.out.println("Captcha inicial resuelto automáticamente para " + providerName + ".");
        } catch (TimeoutException e) {
            System.out.println("Tiempo de espera agotado esperando la resolución automática del captcha para " + providerName + ".");
        }
    }

    private boolean addExtensionFromCandidates(ChromeOptions options, String[] candidates) {
        if (candidates == null) {
            return false;
        }

        for (String candidate : candidates) {
            File addon = resolveAddonFile(candidate);
            if (addon != null && addon.exists() && addon.isFile()) {
                options.addExtensions(addon);
                return true;
            }
        }

        return false;
    }

    private File resolveAddonFile(String addonPath) {
        if (addonPath == null || addonPath.isEmpty()) {
            return null;
        }

        File addon = new File(addonPath);
        if (addon.exists()) {
            return addon;
        }

        if (addonPath.contains(":")) {
            String normalized = addonPath.replace("\\", File.separator);
            addon = new File(normalized);
            if (addon.exists()) {
                return addon;
            }
        }

        return null;
    }

    /**
     * Descarga el archivo desde la URL.
     */
    private void downloadFile(String fileUrl, String outputPath, String fileName, DescargasUI.DirectDownload directDownload) {
        try {
            // Asegurar que el nombre del archivo termine en .mp4
            if (!fileName.toLowerCase().endsWith(".mp4")) {
                fileName += ".mp4";
            }

            // Crear la ruta completa del archivo
            String filePath = outputPath + File.separator + fileName;

            // Obtener información del archivo
            URL url = new URL(fileUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.connect();

            long fileSize = connection.getContentLengthLong();
            directDownload.setFileSize(fileSize);

            long startTime = System.currentTimeMillis();

            // Iniciar descarga
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(filePath)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                long lastUpdateTime = System.currentTimeMillis();
                long lastDownloadedBytes = 0;

                System.out.println("\\nIniciando descarga...");
                System.out.println("Tamaño: " + formatSize(fileSize));

                updateDownloadStatus(directDownload, "Downloading", 1, 0, 0, 0);

                while ((bytesRead = in.read(buffer)) != -1) {
                    // Verificar cancelación
                    if (isCancelled.get()) {
                        System.out.println("Descarga cancelada por el usuario");
                        updateDownloadStatus(
                                directDownload,
                                "Cancelled",
                                (int)((double)totalRead / fileSize * 100),
                                totalRead,
                                0,
                                0
                        );
                        return;
                    }

                    // Verificar pausa
                    while (isPaused.get()) {
                        if (isCancelled.get()) {
                            System.out.println("Descarga cancelada durante pausa");
                            updateDownloadStatus(
                                    directDownload,
                                    "Cancelled",
                                    (int)((double)totalRead / fileSize * 100),
                                    totalRead,
                                    0,
                                    0
                            );
                            return;
                        }
                        Thread.sleep(500);
                    }

                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    // Actualizar métricas cada segundo
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime >= 1000) {
                        double elapsedSeconds = (currentTime - lastUpdateTime) / 1000.0;
                        double bytesPerSecond = (totalRead - lastDownloadedBytes) / elapsedSeconds;
                        double speedMBps = bytesPerSecond / (1024 * 1024);

                        // Calcular tiempo restante
                        long remainingBytes = fileSize - totalRead;
                        long remainingSeconds = bytesPerSecond > 0 ? (long)(remainingBytes / bytesPerSecond) : 0;

                        // Calcular porcentaje de progreso
                        int progressPercent = (int)((double)totalRead / fileSize * 100);

                        updateDownloadStatus(
                                directDownload,
                                "Downloading",
                                progressPercent,
                                totalRead,
                                speedMBps,
                                remainingSeconds
                        );

                        lastUpdateTime = currentTime;
                        lastDownloadedBytes = totalRead;

                        printProgress(startTime, totalRead, fileSize);
                    }
                }

                System.out.println("\\nDescarga completada: " + fileName);
                updateDownloadStatus(directDownload, "Completed", 100, totalRead, 0, 0);
            }
        } catch (Exception e) {
            System.err.println("Error en la descarga: " + e.getMessage());
            e.printStackTrace();
            updateDownloadStatus(
                    directDownload,
                    "Error",
                    directDownload.getProgress(),
                    directDownload.getDownloadedBytes(),
                    0,
                    0
            );
        }
    }

    /**
     * Imprime el progreso de la descarga en la consola.
     */
    private void printProgress(long startTime, long downloaded, long total) {
        double percent = (downloaded * 100.0) / total;
        long elapsed = System.currentTimeMillis() - startTime;
        double speed = elapsed > 0 ? (downloaded / (elapsed / 1000.0)) / (1024 * 1024) : 0;
        long eta = speed > 0 ? (long)((total - downloaded) / (speed * 1024 * 1024)) : 0;

        System.out.printf("\rProgreso: %.1f%% | Velocidad: %.2f MB/s | ETA: %02d:%02d",
                percent, speed, eta / 60, eta % 60);
    }

    /**
     * Formatea el tamaño en bytes a una representación legible.
     */
    private String formatSize(long bytes) {
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = (int) (Math.log(bytes) / Math.log(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, unit), units[unit]);
    }

    /**
     * Actualiza el estado de la descarga con todos los detalles.
     */
    private void updateDownloadStatus(DescargasUI.DirectDownload download, String status, int progress, long downloadedBytes, double speed, long remainingTime) {
        CompletableFuture.runAsync(() -> {
            download.setStatus(status);
            download.setProgress(progress);
            download.setDownloadedBytes(downloadedBytes);
            download.setDownloadSpeed(speed);
            download.setRemainingTime(remainingTime);
        });
    }

    /**
     * Método sobrecargado para actualizar el estado de la descarga sin detalles adicionales.
     */
    private void updateDownloadStatus(DescargasUI.DirectDownload download, String status, int progress) {
        updateDownloadStatus(download, status, progress, 0, 0, 0);
    }

    private static String resolvePath(String first, String... more) {
        Path path = Paths.get(first, more);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
        }
        return path.toAbsolutePath().toString();
    }
}