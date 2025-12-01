package org.example.filmotecadelreves.downloaders;


import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.example.filmotecadelreves.moviesad.DownloadLimitManager;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Implementación del descargador para el servidor Powvideo
 */
public class SeleniumPowvideo implements DirectDownloader, ManualDownloadCapable {
    private static final String PROVIDER_NAME = "PowVideo";
    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String CHROME_DRIVER_PATH = resolvePath("ChromeDriver", "chromedriver.exe");
    private static final String CHROME_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final String[] NOPECHA_EXTENSION_CANDIDATES = {
            "Extension/NopeCaptcha.crx",
            "lib/nopecha.crx",
            "C:\\Users\\Anibal\\IdeaProjects\\FilmotecaDelreves\\Extension\\NopeCaptcha.crx"
    };
    private static final Duration CAPTCHA_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private static final int CAPTCHA_GRACE_SECONDS = 10;
    private static final int MAX_HEADLESS_ATTEMPTS_BEFORE_FALLBACK = 5;

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final Map<String, ResumableHttpDownloadTask> activeDownloads = new ConcurrentHashMap<>();

    private WebDriver driver;
    private WebDriverWait wait;
    private Thread downloadThread;
    private boolean isNopechaInstalled = false;
    private volatile boolean runHeadless = true;
    private boolean currentSessionHeadless = true;

    @Override
    public void download(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        startDownload(videoUrl, destinationPath, directDownload, false);
    }

    @Override
    public void downloadManual(String videoUrl, String destinationPath, DescargasUI.DirectDownload directDownload) {
        startDownload(videoUrl, destinationPath, directDownload, true);
    }

    private void startDownload(String videoUrl,
                               String destinationPath,
                               DescargasUI.DirectDownload directDownload,
                               boolean manualMode) {
        isCancelled.set(false);

        downloadThread = new Thread(() -> {
            try {
                boolean limitReached = DownloadLimitManager.isPowvideoStreamplayLimitReached();
                boolean fallbackToVisible = false;
                boolean manualOverride = manualMode;

                updateDownloadStatus(directDownload, "Processing", 0);

                while (!isCancelled.get()) {
                    boolean requiresUserInteraction = manualOverride || limitReached || fallbackToVisible;
                    setupBrowser(requiresUserInteraction);

                    logDebug("Abriendo enlace original: " + videoUrl);
                    driver.get(videoUrl);
                    wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
                    logPageState("Página inicial cargada");

                    ProgressDialog manualDialog = null;

                    try {
                        if ((limitReached || manualOverride) && !fallbackToVisible) {
                            if (manualOverride && !limitReached) {
                                logDebug("Modo manual solicitado por el usuario. Esperando interacción.");
                            } else {
                                logWarn("Límite de descargas alcanzado. Se requiere intervención manual para resolver el captcha.");
                            }

                            String title = (manualOverride && !limitReached)
                                    ? "Descarga manual en curso"
                                    : "Esperando resolución de CAPTCHA";
                            String message = (manualOverride && !limitReached)
                                    ? "Se abrió una ventana independiente. Resuelva el captcha y pulse los botones correspondientes."
                                    : "Por favor, resuelva el CAPTCHA en el navegador";

                            manualDialog = new ProgressDialog(title, message, true);
                            ProgressDialog finalManualDialog = manualDialog;
                            javafx.application.Platform.runLater(finalManualDialog::show);

                            long startTime = System.currentTimeMillis();
                            long timeoutMillis = (manualOverride && !limitReached) ? 300000 : 120000;

                            while (System.currentTimeMillis() - startTime < timeoutMillis) {
                                if (isCancelled.get()) {
                                    updateDownloadStatus(directDownload, "Cancelled", 0);
                                    return;
                                }

                                long remainingMillis = timeoutMillis - (System.currentTimeMillis() - startTime);
                                long remainingSeconds = Math.max(0, remainingMillis / 1000);
                                String countdownText = String.format("Tiempo restante: %02d:%02d",
                                        remainingSeconds / 60, remainingSeconds % 60);

                                ProgressDialog dialogRef = manualDialog;
                                javafx.application.Platform.runLater(() -> dialogRef.updateCountdown(countdownText));

                                try {
                                    WebElement btn = driver.findElement(By.id("btn_download"));
                                    if (btn.isDisplayed() && btn.isEnabled()) {
                                        logDebug("Captcha resuelto manualmente, continuando con la descarga.");
                                        break;
                                    }
                                } catch (NoSuchElementException ignored) {
                                    // El botón aún no está disponible
                                }

                                if (driver.getPageSource().contains("v.mp4")) {
                                    logDebug("Página de video detectada durante la espera manual.");
                                    break;
                                }

                                Thread.sleep(1000);
                            }

                            if (System.currentTimeMillis() - startTime >= timeoutMillis) {
                                updateDownloadStatus(directDownload, "Error", 0);
                                logError("Tiempo agotado esperando que el usuario complete la interacción manual.");
                                return;
                            }
                        } else if (fallbackToVisible) {
                            manualDialog = new ProgressDialog(
                                    "Interacción requerida",
                                    "Se activó el modo visible. Utilice el navegador para resolver el captcha y continuar.");
                            ProgressDialog finalManualDialog = manualDialog;
                            javafx.application.Platform.runLater(finalManualDialog::show);
                        } else {
                            if (isNopechaInstalled) {
                                waitForNopechaResolution("PowVideo");
                            } else {
                                logWarn("Extensión NoPeCaptcha no disponible, se continúa sin esperar la resolución automática del captcha.");
                            }
                        }

                        try {
                            Thread.sleep(CAPTCHA_GRACE_SECONDS * 1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }

                        clickProceedButton();
                        logPageState("Estado tras pulsar botón principal");

                        Optional<String> downloadUrl = waitForMp4Url();
                        if (downloadUrl.isEmpty()) {
                            if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                                updateDownloadStatus(directDownload, "Cancelled", directDownload.getProgress());
                                logWarn("Búsqueda de enlace v.mp4 detenida por cancelación.");
                            } else {
                                updateDownloadStatus(directDownload, "Error", 0);
                                logWarn("No se encontró un enlace que termine en v.mp4.");
                                collectDebugArtifacts("mp4-no-encontrado");
                            }
                            return;
                        }

                        String videoSrc = downloadUrl.get();
                        logDebug("Enlace del video detectado: " + videoSrc);
                        logPageState("Estado tras detectar enlace de video");

                        shutdownDriver();

                        directDownload.setDestinationPath(destinationPath);
                        String resolvedName = directDownload.getName();
                        if (resolvedName != null && !resolvedName.toLowerCase().endsWith(".mp4")) {
                            directDownload.setName(resolvedName + ".mp4");
                        }

                        startResumableDownload(videoSrc, videoUrl, directDownload);
                        return;
                    } catch (HeadlessFallbackRequiredException fallback) {
                        if (fallbackToVisible) {
                            logError("Se solicitó modo visible a pesar de estar activo: " + fallback.getMessage());
                            updateDownloadStatus(directDownload, "Error", 0);
                            return;
                        }

                        logWarn("El modo headless no logró encontrar el enlace del video. Reintentando en modo visible para permitir la intervención del usuario.");
                        fallbackToVisible = true;
                        updateDownloadStatus(directDownload, "Waiting", directDownload.getProgress());
                    } finally {
                        if (manualDialog != null) {
                            manualDialog.close();
                        }
                    }

                    shutdownDriver();
                }

            } catch (Exception e) {
                logException("Error en la descarga de Powvideo", e);
                updateDownloadStatus(directDownload, "Error", 0);
            } finally {
                shutdownDriver();
            }
        });

        downloadThread.start();
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
        isCancelled.set(true);
        if (downloadThread != null && downloadThread.isAlive()) {
            downloadThread.interrupt();
        }
        ResumableHttpDownloadTask task = activeDownloads.remove(download.getId());
        if (task != null) {
            task.cancel();
        }
        updateDownloadStatus(download, "Cancelled", download.getProgress());
    }

    /**
     * Controla si el navegador debe ejecutarse en modo headless cuando no se requiere interacción del usuario.
     *
     * @param runHeadless true para ejecutar en segundo plano, false para mostrar la ventana del navegador.
     */
    public void setRunHeadless(boolean runHeadless) {
        this.runHeadless = runHeadless;
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
     * Configura el navegador Chrome con las opciones necesarias.
     * @param userInteraction Si es true, no se usa el modo headless para permitir la interacción del usuario
     */
    private void setupBrowser(boolean userInteraction) {
        String driverPath = ChromeExecutableLocator.resolveChromeDriver(CHROME_DRIVER_PATH);
        if (driverPath != null) {
            System.setProperty("webdriver.chrome.driver", driverPath);
            logDebug("Usando ChromeDriver en: " + driverPath);
        } else {
            System.clearProperty("webdriver.chrome.driver");
            logWarn("ChromeDriver empaquetado no disponible. Selenium Manager resolverá la versión adecuada.");
        }

        ChromeOptions options = new ChromeOptions();
        String chromeBinary = ChromeExecutableLocator.resolveChromeBinary(CHROME_PATH);
        if (chromeBinary != null) {
            options.setBinary(chromeBinary);
            logDebug("Usando binario de Chrome: " + chromeBinary);
        } else {
            logWarn("No se encontró un binario de Chrome personalizado. Se usará el navegador predeterminado del sistema.");
        }
        boolean headlessMode = !userInteraction && runHeadless;
        logDebug("Configurando navegador (userInteraction=" + userInteraction + ", headless=" + headlessMode + ")");

        // Cargar extensiones
        boolean popupExtensionLoaded = addExtensionFromCandidates(options, VideosStreamerManager.getPopupExtensionCandidates());
        if (!popupExtensionLoaded) {
            logWarn("No se pudo cargar la extensión de bloqueo de popups.");
        } else {
            logDebug("Extensión de bloqueo de popups cargada correctamente.");
        }

        // Solo cargar la extensión NoPecha si no se requiere interacción del usuario
        if (!userInteraction) {
            isNopechaInstalled = addExtensionFromCandidates(options, NOPECHA_EXTENSION_CANDIDATES);
            if (!isNopechaInstalled) {
                logWarn("No se pudo cargar la extensión NoPeCaptcha.");
            }
        } else {
            isNopechaInstalled = false;
        }

        options.addArguments(
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-backgrounding-occluded-windows",
                "--disable-renderer-backgrounding",
                "--disable-background-timer-throttling",
                "--window-size=1920,1080",
                "--remote-allow-origins=*"
        );

        // Usar modo headless solo si no se requiere interacción del usuario
        if (headlessMode) {
            options.addArguments("--headless=new");
        }

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        currentSessionHeadless = headlessMode;
        logDebug("Navegador inicializado. Modo headless=" + headlessMode + ", popup=" + popupExtensionLoaded + ", nopecha=" + isNopechaInstalled);
    }

    private void shutdownDriver() {
        if (driver == null) {
            return;
        }
        try {
            driver.quit();
        } catch (WebDriverException e) {
            logWarn("Error cerrando el navegador: " + e.getMessage());
        } finally {
            driver = null;
            wait = null;
        }
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

    private void clickProceedButton() {
        try {
            WebElement btnDownload = wait.until(ExpectedConditions.elementToBeClickable(By.id("btn_download")));
            clickElement(btnDownload);
            logDebug("Botón 'Continuar al video' pulsado correctamente.");
        } catch (TimeoutException timeoutException) {
            logWarn("No se pudo localizar el botón 'Continuar al video' después del tiempo de espera.");
            collectDebugArtifacts("boton-continuar-no-disponible");
        }
    }

    private Optional<String> waitForMp4Url() {
        Pattern pattern = Pattern.compile("(https?://[^\"'\\s>]+?v\\.mp4(?:\\?[^\"'\\s>]*)?)", Pattern.CASE_INSENSITIVE);
        int attempt = 1;
        boolean timeoutArtifactCaptured = false;
        while (!isCancelled.get()) {
            if (Thread.currentThread().isInterrupted()) {
                logWarn("Espera de enlaces v.mp4 interrumpida externamente.");
                return Optional.empty();
            }
            try {
                logDebug("Iniciando espera activa para enlaces v.mp4... (intento " + attempt + ")");
                WebDriverWait mp4Wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                String url = mp4Wait.until(webDriver -> {
                    if (isCancelled.get()) {
                        throw new CancellationException("Descarga cancelada");
                    }
                    String pageSource = webDriver.getPageSource();
                    Matcher matcher = pattern.matcher(pageSource);
                    if (matcher.find()) {
                        return matcher.group(1);
                    }
                    return null;
                });

                if (url != null && !url.isBlank()) {
                    logDebug("Enlace v.mp4 encontrado en el intento " + attempt + ": " + url);
                    return Optional.of(url);
                }
            } catch (TimeoutException e) {
                logWarn("Tiempo de espera agotado buscando el enlace mp4 (intento " + attempt + "). Se reintentará.");
                logPageState("Estado al agotar la espera de mp4 (intento " + attempt + ")");
                if (!timeoutArtifactCaptured) {
                    collectDebugArtifacts("timeout-buscando-mp4");
                    timeoutArtifactCaptured = true;
                }
                if (currentSessionHeadless && attempt >= MAX_HEADLESS_ATTEMPTS_BEFORE_FALLBACK) {
                    throw new HeadlessFallbackRequiredException("Se alcanzó el máximo de intentos permitidos en modo headless.");
                }
                if (!prepareForMp4Retry(attempt)) {
                    reloadPageForRetry(attempt);
                }
                attempt++;
            } catch (WebDriverException e) {
                if (wasInterrupted(e)) {
                    logWarn("Espera de enlace mp4 interrumpida: " + e.getMessage());
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
                throw e;
            } catch (CancellationException cancelled) {
                logWarn("Esperando enlace mp4 cancelada por el usuario.");
                return Optional.empty();
            }
        }

        logWarn("Se detuvo la espera de enlaces v.mp4 porque la descarga fue cancelada.");
        return Optional.empty();
    }

    private void reloadPageForRetry(int attempt) {
        if (driver == null || isCancelled.get()) {
            return;
        }
        try {
            logDebug("Realizando recarga completa de la página antes del reintento " + (attempt + 1) + ".");
            driver.navigate().refresh();
            wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
            logPageState("Página recargada tras reintento fallido (intento " + attempt + ")");
            if (isNopechaInstalled) {
                waitForNopechaResolution("PowVideo");
            }
            try {
                Thread.sleep(CAPTCHA_GRACE_SECONDS * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (tryClickProceedButton()) {
                logDebug("Botón 'Continuar al video' pulsado tras recargar la página (reintento " + (attempt + 1) + ").");
            }
        } catch (Exception ex) {
            logError("Error al recargar la página para un nuevo reintento: " + ex.getMessage());
        }
    }

    private boolean prepareForMp4Retry(int attempt) {
        if (driver == null || isCancelled.get()) {
            return true;
        }

        boolean actionTaken = false;
        try {
            if (tryClickProceedButton()) {
                logDebug("Botón 'Continuar al video' pulsado nuevamente (reintento " + attempt + ").");
                actionTaken = true;
            }
        } catch (Exception e) {
            logWarn("No se pudo pulsar nuevamente el botón 'Continuar al video' en el reintento " + attempt + ": " + e.getMessage());
        }

        if (!actionTaken) {
            actionTaken = closeExtraWindows();
        }

        if (!actionTaken) {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        return actionTaken;
    }

    private boolean tryClickProceedButton() {
        if (driver == null) {
            return false;
        }

        try {
            WebElement btn = driver.findElement(By.id("btn_download"));
            if (btn.isDisplayed() && btn.isEnabled()) {
                clickElement(btn);
                return true;
            }
        } catch (NoSuchElementException | StaleElementReferenceException ignored) {
            return false;
        }
        return false;
    }

    private void clickElement(WebElement element) {
        try {
            element.click();
        } catch (Exception clickEx) {
            ((JavascriptExecutor) driver).executeScript("arguments[0].click();", element);
        }
    }

    private boolean closeExtraWindows() {
        if (driver == null) {
            return false;
        }

        try {
            String currentHandle = driver.getWindowHandle();
            boolean closedAny = false;
            for (String handle : driver.getWindowHandles()) {
                if (!handle.equals(currentHandle)) {
                    driver.switchTo().window(handle);
                    driver.close();
                    closedAny = true;
                }
            }
            driver.switchTo().window(currentHandle);
            return closedAny;
        } catch (Exception e) {
            logWarn("No se pudieron cerrar ventanas emergentes adicionales: " + e.getMessage());
            return false;
        }
    }

    private boolean wasInterrupted(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
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

    private void startResumableDownload(String fileUrl, String referer, DescargasUI.DirectDownload directDownload) {
        if (fileUrl == null || fileUrl.isBlank()) {
            updateDownloadStatus(directDownload, "Error", 0);
            return;
        }

        Consumer<HttpURLConnection> headerConfigurer = connection -> {
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (referer != null && !referer.isBlank()) {
                connection.setRequestProperty("Referer", referer);
            }
        };

        ResumableHttpDownloadTask task = new ResumableHttpDownloadTask(fileUrl, directDownload, headerConfigurer);
        activeDownloads.put(directDownload.getId(), task);
        task.getCompletionFuture().whenComplete((ignored, error) -> activeDownloads.remove(directDownload.getId()));
        task.start();
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

    private static class HeadlessFallbackRequiredException extends RuntimeException {
        HeadlessFallbackRequiredException(String message) {
            super(message);
        }
    }
}