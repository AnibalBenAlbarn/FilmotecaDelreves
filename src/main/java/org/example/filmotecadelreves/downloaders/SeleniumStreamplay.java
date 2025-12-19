package org.example.filmotecadelreves.downloaders;

import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.ManualDownloadCapable;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.example.filmotecadelreves.moviesad.DownloadLimitManager;
import org.example.filmotecadelreves.moviesad.ProgressDialog;
import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.StandardOpenOption;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implementación de descargador para el servidor Streamplay
 */
public class SeleniumStreamplay implements DirectDownloader, ManualDownloadCapable {
    private static final String PROVIDER_NAME = "Streamplay";
    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String CHROME_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final String[] NOPECHA_EXTENSION_CANDIDATES = {
            "Extension/nopecatcha old",
            "Extension/NopeCaptcha.crx",
            "lib/nopecha.crx",
            "C:\\Users\\Anibal\\IdeaProjects\\FilmotecaDelreves\\Extension\\NopeCaptcha.crx"
    };
    private static final Duration CAPTCHA_WAIT_TIMEOUT = Duration.ofSeconds(60);

    private static final int CAPTCHA_GRACE_SECONDS = 10; // Tiempo para que NoCaptcha resuelva automáticamente

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final Map<String, ResumableHttpDownloadTask> activeDownloads = new ConcurrentHashMap<>();

    private WebDriver driver;
    private WebDriverWait wait;
    private Thread downloadThread;
    private boolean isNopechaInstalled = false;
    private volatile boolean runHeadless = true;

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
                boolean manualOverride = manualMode;

                updateDownloadStatus(directDownload, "Processing", 0);

                boolean requiresInteraction = manualOverride || limitReached;
                setupBrowser(requiresInteraction);

                logDebug("Abriendo enlace original: " + videoUrl);
                driver.get(videoUrl);
                wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
                logPageState("Página inicial cargada");

                ProgressDialog progressDialog = null;

                if (requiresInteraction) {
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

                    progressDialog = new ProgressDialog(title, message, true);
                    ProgressDialog finalProgressDialog = progressDialog;
                    javafx.application.Platform.runLater(finalProgressDialog::show);

                    long startTime = System.currentTimeMillis();
                    long timeoutMillis = (manualOverride && !limitReached) ? 300000 : 120000;

                    while (System.currentTimeMillis() - startTime < timeoutMillis) {
                        if (isCancelled.get()) {
                            javafx.application.Platform.runLater(finalProgressDialog::close);
                            updateDownloadStatus(directDownload, "Cancelled", 0);
                            return;
                        }

                        long remainingMillis = timeoutMillis - (System.currentTimeMillis() - startTime);
                        long remainingSeconds = Math.max(0, remainingMillis / 1000);
                        String countdownText = String.format("Tiempo restante: %02d:%02d",
                                remainingSeconds / 60, remainingSeconds % 60);

                        javafx.application.Platform.runLater(() -> finalProgressDialog.updateCountdown(countdownText));

                        try {
                            WebElement btn = driver.findElement(By.id("btn_download"));
                            if (btn.isDisplayed() && btn.isEnabled()) {
                                logDebug("Captcha resuelto manualmente, continuando con la descarga.");
                                break;
                            }
                        } catch (NoSuchElementException ignored) {
                            // El botón aún no está disponible
                        }

                        try {
                            List<WebElement> videoList = driver.findElements(By.cssSelector("video"));
                            if (!videoList.isEmpty() && videoList.get(0).isDisplayed()) {
                                logDebug("Reproductor detectado durante la espera manual.");
                                break;
                            }
                        } catch (Exception ignored) {
                            // Continuar esperando
                        }

                        if (driver.getPageSource().contains("v.mp4")) {
                            logDebug("Página de video detectada durante la espera manual.");
                            break;
                        }

                        Thread.sleep(1000);
                    }

                    javafx.application.Platform.runLater(finalProgressDialog::close);

                    if (System.currentTimeMillis() - startTime >= timeoutMillis) {
                        updateDownloadStatus(directDownload, "Error", 0);
                        logError("Tiempo agotado esperando que el usuario complete la interacción manual.");
                        return;
                    }
                } else {
                    if (isNopechaInstalled) {
                        waitForNopechaResolution(PROVIDER_NAME);
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

            } catch (Exception e) {
                logException("Error en la descarga de Streamplay", e);
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
     * Controla si el navegador debe ejecutarse en modo headless o visible.
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
     *
     * @param userInteraction Si es true, no se usa el modo headless para permitir la interacción del usuario
     */
    private void setupBrowser(boolean userInteraction) {
        System.clearProperty("webdriver.chrome.driver");
        logDebug("webdriver.chrome.driver limpiado. Selenium Manager elegirá el ChromeDriver adecuado.");

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

        boolean popupExtensionLoaded = addExtensionFromCandidates(options, VideosStreamerManager.getPopupExtensionCandidates());
        if (!popupExtensionLoaded) {
            logWarn("No se pudo cargar la extensión de bloqueo de popups.");
        } else {
            logDebug("Extensión de bloqueo de popups cargada correctamente.");
        }

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

        if (headlessMode) {
            options.addArguments("--headless=new");
        }

        driver = new ChromeDriver(options);
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        logDebug("Navegador inicializado. Modo headless=" + headlessMode + ", popup=" + popupExtensionLoaded + ", nopecha=" + isNopechaInstalled);
    }

    private boolean addExtensionFromCandidates(ChromeOptions options, String[] candidates) {
        if (candidates == null) {
            return false;
        }

        List<String> unpackedExtensions = new ArrayList<>();
        boolean installed = false;

        for (String candidate : candidates) {
            File addon = resolveAddonFile(candidate);
            if (addon == null || !addon.exists()) {
                continue;
            }

            if (addon.isDirectory()) {
                unpackedExtensions.add(addon.getAbsolutePath());
                installed = true;
                continue;
            }

            try {
                options.addExtensions(addon);
                installed = true;
            } catch (Exception e) {
                logWarn("Fallo instalando la extensión desde CRX, intentando modo unpacked: " + addon.getAbsolutePath());
                Optional<Path> unpacked = unpackCrxAsUnpacked(addon);
                if (unpacked.isPresent()) {
                    unpackedExtensions.add(unpacked.get().toString());
                    installed = true;
                }
            }
        }

        if (!unpackedExtensions.isEmpty()) {
            options.addArguments("--load-extension=" + String.join(",", unpackedExtensions));
        }

        return installed;
    }

    private Optional<Path> unpackCrxAsUnpacked(File crxFile) {
        try (FileInputStream fis = new FileInputStream(crxFile)) {
            byte[] header = fis.readNBytes(16);
            if (header.length < 16 || header[0] != 'C' || header[1] != 'r' || header[2] != '2' || header[3] != '4') {
                logWarn("El archivo CRX no tiene cabecera válida: " + crxFile.getAbsolutePath());
                return Optional.empty();
            }

            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(8);
            long publicKeyLength = Integer.toUnsignedLong(buffer.getInt());
            long signatureLength = Integer.toUnsignedLong(buffer.getInt());
            long skipBytes = publicKeyLength + signatureLength;

            if (skipBytes > 0) {
                fis.skipNBytes(skipBytes);
            }

            Path tempDir = Files.createTempDirectory("crx-unpacked-");

            try (ZipInputStream zis = new ZipInputStream(fis)) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    Path entryPath = tempDir.resolve(entry.getName());
                    if (entry.isDirectory()) {
                        Files.createDirectories(entryPath);
                    } else {
                        Files.createDirectories(entryPath.getParent());
                        Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            logDebug("Extensión desempaquetada en: " + tempDir);
            return Optional.of(tempDir);
        } catch (IOException e) {
            logWarn("No se pudo desempaquetar la extensión CRX: " + e.getMessage());
            return Optional.empty();
        }
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

    private void clickProceedButton() {
        final int maxAttempts = 5;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                WebElement btnDownload = wait.until(ExpectedConditions.elementToBeClickable(By.id("btn_download")));
                try {
                    btnDownload.click();
                } catch (WebDriverException clickEx) {
                    ((JavascriptExecutor) driver).executeScript("arguments[0].click();", btnDownload);
                }
                logDebug("Botón 'Proceed to video' pulsado correctamente.");
                return;
            } catch (StaleElementReferenceException | ElementClickInterceptedException e) {
                logWarn("Reintentando pulsar el botón 'Proceed to video' por cambio en el DOM (intento " + attempt + ")");
                sleepSilently(300);
            } catch (TimeoutException timeoutException) {
                logWarn("No se pudo localizar el botón 'Proceed to video' después del tiempo de espera.");
                collectDebugArtifacts("boton-proceed-no-disponible");
                return;
            }
        }
        logWarn("Se agotaron los intentos para pulsar el botón 'Proceed to video'.");
        collectDebugArtifacts("boton-proceed-reintentos-agotados");
    }

    private Optional<String> waitForMp4Url() {
        Pattern pattern = Pattern.compile("(https?://[^\"'\\s>]+?v\\.mp4(?:\\?[^\"'\\s>]*)?)", Pattern.CASE_INSENSITIVE);
        int attempt = 1;
        boolean timeoutArtifactCaptured = false;
        while (!isCancelled.get()) {
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
                if (!prepareForMp4Retry(attempt)) {
                    reloadPageForRetry(attempt);
                }
                attempt++;
            } catch (CancellationException cancelled) {
                logWarn("Esperando enlace mp4 cancelada por el usuario.");
                return Optional.empty();
            } catch (WebDriverException e) {
                logError("Error consultando el enlace mp4: " + e.getMessage());
                collectDebugArtifacts("error-obteniendo-mp4");
                if (!prepareForMp4Retry(attempt)) {
                    reloadPageForRetry(attempt);
                }
                attempt++;
            }
        }

        logWarn("Se detuvo la espera de enlaces v.mp4 porque la descarga fue cancelada.");
        return Optional.empty();
    }

    private boolean prepareForMp4Retry(int attempt) {
        if (driver == null || isCancelled.get()) {
            return true;
        }

        boolean actionTaken = false;
        try {
            if (tryClickProceedButton()) {
                logDebug("Botón 'Proceed to video' pulsado nuevamente (reintento " + attempt + ").");
                actionTaken = true;
            }
        } catch (Exception e) {
            logWarn("No se pudo pulsar nuevamente el botón 'Proceed to video' en el reintento " + attempt + ": " + e.getMessage());
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
                waitForNopechaResolution(PROVIDER_NAME);
            }
            try {
                Thread.sleep(CAPTCHA_GRACE_SECONDS * 1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (tryClickProceedButton()) {
                logDebug("Botón 'Proceed to video' pulsado tras recargar la página (reintento " + (attempt + 1) + ").");
            }
        } catch (Exception ex) {
            logError("Error al recargar la página para un nuevo reintento: " + ex.getMessage());
        }
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

    private void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
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
