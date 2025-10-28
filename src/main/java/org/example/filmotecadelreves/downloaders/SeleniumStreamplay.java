package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.example.filmotecadelreves.moviesad.DownloadLimitManager;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.example.filmotecadelreves.util.ChromeStealthConfigurator;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementación de descargador para el servidor Streamplay
 */
public class SeleniumStreamplay implements DirectDownloader {
    private static final String PROVIDER_NAME = "Streamplay";
    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String CHROME_DRIVER_PATH = resolvePath("ChromeDriver", "chromedriver.exe");
    private static final String CHROME_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final String[] NOPECHA_EXTENSION_CANDIDATES = {
            "Extension/NopeCaptcha.crx",
            "lib/nopecha.crx",
            "C:\\Users\\Anibal\\IdeaProjects\\FilmotecaDelreves\\Extension\\NopeCaptcha.crx"
    };
    private static final Duration CAPTCHA_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private static final int CAPTCHA_GRACE_SECONDS = 10; // Tiempo para que NoCaptcha resuelva automáticamente

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

                // Si se ha alcanzado el límite, mostrar navegador para que el usuario resuelva el captcha
                if (limitReached) {
                    logWarn("Límite de descargas alcanzado. Se requiere intervención del usuario para resolver el captcha.");

                    // Mostrar diálogo de progreso con cuenta atrás
                    ProgressDialog progressDialog = new ProgressDialog(
                            "Esperando resolución de CAPTCHA",
                            "Por favor, resuelva el CAPTCHA en el navegador",
                            true);

                    javafx.application.Platform.runLater(() -> {
                        progressDialog.show();
                    });

                    // Configurar navegador para interacción del usuario
                    setupBrowserForUserInteraction();

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
                            List<WebElement> videoList = driver.findElements(By.cssSelector("video"));
                            if (!videoList.isEmpty() && videoList.get(0).isDisplayed()) {
                                logDebug("Captcha resuelto por el usuario, continuando con la descarga.");
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
                        logError("Tiempo agotado esperando que el usuario resuelva el captcha.");
                        return;
                    }
                } else {
                    // Configurar navegador para descarga automática
                    setupBrowser();
                }

                // Obtener enlace de descarga
                String downloadUrl = getDownloadLink(videoUrl);

                if (downloadUrl == null || downloadUrl.isEmpty()) {
                    updateDownloadStatus(directDownload, "Error", 0);
                    return;
                }

                // Crear nombre de archivo
                String fileName = directDownload.getName();
                if (!fileName.toLowerCase().endsWith(".mp4")) {
                    fileName += ".mp4";
                }

                // Descargar archivo
                downloadFile(downloadUrl, destinationPath, fileName, directDownload);

                // Incrementar contador de descargas solo si la descarga fue exitosa
                if ("Completed".equals(directDownload.getStatus())) {
                    DownloadLimitManager.incrementPowvideoStreamplayCount();
                }

            } catch (Exception e) {
                logException("Error en la descarga de Streamplay", e);
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
            logError("Error verificando disponibilidad: " + e.getMessage());
            return false;
        }
    }

    /**
     * Configura el navegador para descarga automática.
     */
    private void setupBrowser() {
        System.setProperty("webdriver.chrome.driver", CHROME_DRIVER_PATH);
        ChromeOptions options = new ChromeOptions();
        options.setBinary(CHROME_PATH);
        logDebug("Configurando navegador en modo headless para resolución automática.");

        ChromeStealthConfigurator.applyHumanLikeDefaults(options, "streamplay", true);

        // Cargar extensiones
        boolean popupExtensionLoaded = addExtensionFromCandidates(options, VideosStreamerManager.getPopupExtensionCandidates());
        if (!popupExtensionLoaded) {
            logWarn("No se pudo cargar la extensión de bloqueo de popups.");
        } else {
            logDebug("Extensión de bloqueo de popups cargada correctamente.");
        }
        isNopechaInstalled = addExtensionFromCandidates(options, NOPECHA_EXTENSION_CANDIDATES);
        if (!isNopechaInstalled) {
            logWarn("No se pudo cargar la extensión NoPeCaptcha.");
        } else {
            logDebug("Extensión NoPeCaptcha cargada correctamente.");
        }

        driver = new ChromeDriver(options);
        ChromeStealthConfigurator.maskAutomation(driver);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        logDebug("Navegador inicializado en modo headless con extensiones: popup=" + popupExtensionLoaded + ", nopecha=" + isNopechaInstalled);
    }

    /**
     * Configura el navegador para interacción del usuario (resolución de captcha).
     */
    private void setupBrowserForUserInteraction() {
        System.setProperty("webdriver.chrome.driver", CHROME_DRIVER_PATH);
        ChromeOptions options = new ChromeOptions();
        options.setBinary(CHROME_PATH);
        logDebug("Configurando navegador visible para interacción del usuario.");

        ChromeStealthConfigurator.applyHumanLikeDefaults(options, "streamplay", false);

        // Cargar solo la extensión de bloqueo de popups
        boolean popupExtensionLoaded = addExtensionFromCandidates(options, VideosStreamerManager.getPopupExtensionCandidates());
        if (!popupExtensionLoaded) {
            logWarn("No se pudo cargar la extensión de bloqueo de popups (modo usuario).");
        } else {
            logDebug("Extensión de bloqueo de popups cargada correctamente (modo usuario).");
        }

        driver = new ChromeDriver(options);
        ChromeStealthConfigurator.maskAutomation(driver);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        isNopechaInstalled = false;
        logDebug("Navegador inicializado en modo visible para interacción del usuario.");
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
     * Obtiene el enlace de descarga desde la página de Streamplay.
     * Se espera que la página se cargue y se realizan varios intentos de hacer click en el botón "Proceed to video"
     * (id "btn_download"). En cada intento par se espera que desaparezca cualquier overlay (div con alto z-index)
     * y, si es necesario, se usa un click vía JavaScript. El ciclo continúa hasta que se carga el reproductor de video.
     */
    private String getDownloadLink(String videoUrl) throws Exception {
        logDebug("Abriendo enlace original: " + videoUrl);
        driver.get(videoUrl);
        wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
        logPageState("Página inicial cargada");

        // Permitir que la extensión NoCaptcha resuelva el desafío inicial
        if (isNopechaInstalled) {
            waitForNopechaResolution("Streamplay");
        } else {
            logWarn("Extensión NoPeCaptcha no disponible, se continúa sin esperar la resolución automática del captcha.");
        }

        // Tiempo de gracia adicional solicitado (10 segundos)
        try {
            Thread.sleep(CAPTCHA_GRACE_SECONDS * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        clickProceedButton();
        logPageState("Estado tras pulsar botón principal");

        // Esperar a que la página posterior cargue el contenido del video
        wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
        logPageState("Página posterior cargada");

        Optional<String> downloadUrl = waitForMp4Url();
        if (downloadUrl.isEmpty()) {
            logWarn("No se encontró un enlace que termine en v.mp4.");
            collectDebugArtifacts("mp4-no-encontrado");
            return "";
        }

        logDebug("Enlace del video detectado: " + downloadUrl.get());
        logPageState("Estado tras detectar enlace de video");
        return downloadUrl.get();
    }

    private void clickProceedButton() {
        try {
            WebElement btnDownload = wait.until(ExpectedConditions.elementToBeClickable(By.id("btn_download")));
            try {
                btnDownload.click();
            } catch (Exception clickEx) {
                ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btnDownload);
            }
            logDebug("Botón 'Proceed to video' pulsado correctamente.");
        } catch (TimeoutException timeoutException) {
            logWarn("No se pudo localizar el botón 'Proceed to video' después del tiempo de espera.");
            collectDebugArtifacts("boton-proceed-no-disponible");
        }
    }

    private Optional<String> waitForMp4Url() {
        Pattern pattern = Pattern.compile("(https?://[^\"'\\s>]+?v\\.mp4(?:\\?[^\"'\\s>]*)?)", Pattern.CASE_INSENSITIVE);
        try {
            logDebug("Iniciando espera activa para enlaces que terminen en v.mp4...");
            WebDriverWait mp4Wait = new WebDriverWait(driver, Duration.ofSeconds(30));
            boolean found = mp4Wait.until(webDriver -> {
                String pageSource = webDriver.getPageSource();
                Matcher matcher = pattern.matcher(pageSource);
                return matcher.find();
            });

            if (!found) {
                logWarn("Finalizó la espera sin detectar enlaces v.mp4.");
                return Optional.empty();
            }

            String pageSource = driver.getPageSource();
            Matcher matcher = pattern.matcher(pageSource);
            if (matcher.find()) {
                String url = matcher.group(1);
                logDebug("Enlace v.mp4 encontrado: " + url);
                return Optional.of(url);
            }
        } catch (TimeoutException e) {
            logWarn("Tiempo de espera agotado buscando el enlace mp4.");
            logPageState("Estado al agotar la espera de mp4");
            collectDebugArtifacts("timeout-buscando-mp4");
        }
        return Optional.empty();
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
            logDebug("Captcha inicial resuelto automáticamente para " + providerName + ".");
        } catch (TimeoutException e) {
            logWarn("Tiempo de espera agotado esperando la resolución automática del captcha para " + providerName + ".");
            collectDebugArtifacts("captcha-no-resuelto");
        }
    }

    /**
     * Descarga el archivo desde la URL utilizando la lógica de actualización de estado.
     */
    private void downloadFile(String fileUrl, String outputPath, String fileName, DescargasUI.DirectDownload directDownload) {
        try {
            if (!fileName.toLowerCase().endsWith(".mp4")) {
                fileName += ".mp4";
            }
            String filePath = outputPath + File.separator + fileName;

            URL url = new URL(fileUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.connect();

            long fileSize = connection.getContentLengthLong();
            directDownload.setFileSize(fileSize);

            long startTime = System.currentTimeMillis();

            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(filePath)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalRead = 0;
                long lastUpdateTime = System.currentTimeMillis();
                long lastDownloadedBytes = 0;

                logDebug("Iniciando descarga del archivo. Tamaño estimado: " + formatSize(fileSize));

                updateDownloadStatus(directDownload, "Downloading", 1, 0, 0, 0);

                while ((bytesRead = in.read(buffer)) != -1) {
                    if (isCancelled.get()) {
                        logWarn("Descarga cancelada por el usuario.");
                        updateDownloadStatus(directDownload, "Cancelled", (int)((double)totalRead / fileSize * 100), totalRead, 0, 0);
                        return;
                    }

                    while (isPaused.get()) {
                        if (isCancelled.get()) {
                            logWarn("Descarga cancelada durante la pausa.");
                            updateDownloadStatus(directDownload, "Cancelled", (int)((double)totalRead / fileSize * 100), totalRead, 0, 0);
                            return;
                        }
                        Thread.sleep(500);
                    }

                    out.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

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

                        updateDownloadStatus(directDownload, "Downloading", progressPercent, totalRead, speedMBps, remainingSeconds);

                        lastUpdateTime = currentTime;
                        lastDownloadedBytes = totalRead;

                        printProgress(startTime, totalRead, fileSize);
                    }
                }

                logDebug("Descarga completada: " + fileName);
                updateDownloadStatus(directDownload, "Completed", 100, totalRead, 0, 0);
            }
        } catch (Exception e) {
            logException("Error en la descarga", e);
            updateDownloadStatus(directDownload, "Error", directDownload.getProgress(), directDownload.getDownloadedBytes(), 0, 0);
        }
    }

    /**
     * Obtiene el tamaño del archivo.
     */
    private long getFileSize(String fileUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(fileUrl).openConnection();
        conn.setRequestMethod("HEAD");
        return conn.getContentLengthLong();
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
        int unit = (int)(Math.log(bytes) / Math.log(1024));
        return String.format("%.2f %s", bytes / Math.pow(1024, unit), units[unit]);
    }

    /**
     * Actualiza el estado de la descarga con toda la información.
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
     * Método sobrecargado para actualizar el estado sin detalles adicionales.
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

    private void logDebug(String message) {
        System.out.printf("[%s][%s][DEBUG] %s%n", LocalDateTime.now().format(LOG_TIME_FORMATTER), PROVIDER_NAME, message);
    }

    private void logWarn(String message) {
        System.out.printf("[%s][%s][WARN] %s%n", LocalDateTime.now().format(LOG_TIME_FORMATTER), PROVIDER_NAME, message);
    }

    private void logError(String message) {
        System.err.printf("[%s][%s][ERROR] %s%n", LocalDateTime.now().format(LOG_TIME_FORMATTER), PROVIDER_NAME, message);
    }

    private void logException(String context, Exception exception) {
        logError(context + ": " + exception.getMessage());
        exception.printStackTrace(System.err);
        collectDebugArtifacts("excepcion-" + sanitizeForFileName(context));
    }

    private void collectDebugArtifacts(String reason) {
        if (driver == null) {
            return;
        }
        String safeReason = sanitizeForFileName(reason);
        savePageSourceForDebug(safeReason);
        captureScreenshotForDebug(safeReason);
    }

    private void savePageSourceForDebug(String reason) {
        if (driver == null) {
            return;
        }
        try {
            Path debugDir = Paths.get("debug");
            Files.createDirectories(debugDir);
            Path output = debugDir.resolve(PROVIDER_NAME.toLowerCase() + "-" + reason + "-" + System.currentTimeMillis() + ".html");
            Files.write(output, driver.getPageSource().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            logDebug("Volcado del DOM guardado en: " + output.toAbsolutePath());
        } catch (Exception e) {
            logError("No se pudo guardar el volcado del DOM: " + e.getMessage());
        }
    }

    private void captureScreenshotForDebug(String reason) {
        if (!(driver instanceof TakesScreenshot)) {
            return;
        }
        try {
            Path debugDir = Paths.get("debug");
            Files.createDirectories(debugDir);
            Path screenshotTarget = debugDir.resolve(PROVIDER_NAME.toLowerCase() + "-" + reason + "-" + System.currentTimeMillis() + ".png");
            File screenshot = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Files.copy(screenshot.toPath(), screenshotTarget, StandardCopyOption.REPLACE_EXISTING);
            logDebug("Captura de pantalla guardada en: " + screenshotTarget.toAbsolutePath());
        } catch (Exception e) {
            logError("No se pudo guardar la captura de pantalla: " + e.getMessage());
        }
    }

    private String sanitizeForFileName(String input) {
        return input.replaceAll("[^a-zA-Z0-9-_]", "_");
    }

    private void logPageState(String context) {
        if (driver == null) {
            return;
        }
        try {
            String currentUrl = driver.getCurrentUrl();
            logDebug(context + " -> URL actual: " + currentUrl);
            List<WebElement> videos = driver.findElements(By.tagName("video"));
            long visibleVideos = videos.stream().filter(this::isElementVisible).count();
            logDebug(context + " -> Videos detectados: total=" + videos.size() + ", visibles=" + visibleVideos);
            List<WebElement> iframes = driver.findElements(By.tagName("iframe"));
            logDebug(context + " -> Iframes detectados: " + iframes.size());
        } catch (Exception e) {
            logError("No se pudo registrar el estado de la página ('" + context + "'): " + e.getMessage());
        }
    }

    private boolean isElementVisible(WebElement element) {
        try {
            return element.isDisplayed();
        } catch (Exception e) {
            return false;
        }
    }
}