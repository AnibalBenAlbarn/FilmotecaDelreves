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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Implementación de descargador para el servidor Streamplay
 */
public class SeleniumStreamplay implements DirectDownloader, ManualDownloadCapable {
    private static final String PROVIDER_NAME = "Streamplay";
    private static final DateTimeFormatter LOG_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final String CHROME_PATH = resolvePath("chrome-win", "chrome.exe");
    private static final String CHROME_DRIVER_PATH = resolvePath("ChromeDriver", "chromedriver.exe");
    private static final String[] NOPECHA_EXTENSION_CANDIDATES = {
            "Extension/nopecaptcha.crx",
            "Extension/NopeCaptcha.crx"
    };
    private static final Duration DEFAULT_CAPTCHA_WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_MP4_TIMEOUTS_BEFORE_MANUAL = 2;
    private static final Pattern MP4_PATTERN = Pattern.compile("(https?://[^\"'\\s>]+?\\.mp4(?:\\?[^\"'\\s>]*)?)", Pattern.CASE_INSENSITIVE);

    private static final int CAPTCHA_GRACE_SECONDS = 10; // Tiempo para que NoCaptcha resuelva automáticamente

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);
    private final AtomicBoolean manualFallbackLaunched = new AtomicBoolean(false);
    private final Map<String, ResumableHttpDownloadTask> activeDownloads = new ConcurrentHashMap<>();

    private WebDriver driver;
    private WebDriverWait wait;
    private Thread downloadThread;
    private boolean isNopechaInstalled = false;
    private volatile boolean runHeadless = true;
    private Duration captchaWaitTimeout = DEFAULT_CAPTCHA_WAIT_TIMEOUT;
    private boolean currentSessionHeadless = true;

    public void setNopechaTimeoutSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }
        captchaWaitTimeout = Duration.ofSeconds(seconds);
    }

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
        manualFallbackLaunched.set(false);

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

                    long timeoutMillis = (manualOverride && !limitReached) ? 300000 : 120000;
                    if (!waitForManualInteraction(directDownload, title, message, timeoutMillis)) {
                        return;
                    }
                } else {
                    if (isNopechaInstalled) {
                        boolean solved = waitForNopechaResolution(PROVIDER_NAME);
                        if (!solved) {
                            logWarn("Nopecha no resolvió el captcha. Solicitando intervención manual.");
                            if (currentSessionHeadless && runHeadless) {
                                shutdownDriver();
                                setupBrowser(true);
                                driver.get(videoUrl);
                                wait.until(ExpectedConditions.jsReturnsValue("return document.readyState === 'complete';"));
                                logPageState("Página recargada en modo visible para intervención manual");
                            }
                            if (!waitForManualInteraction(
                                    directDownload,
                                    "Interacción requerida",
                                    "Nopecha agotó el tiempo. Resuelve el captcha manualmente para continuar.",
                                    120000)) {
                                return;
                            }
                        }
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

                Optional<String> downloadUrl = waitForMp4Url(videoUrl);
                if (downloadUrl.isEmpty()) {
                    if (isCancelled.get() || Thread.currentThread().isInterrupted()) {
                        updateDownloadStatus(directDownload, "Cancelled", directDownload.getProgress());
                        logWarn("Búsqueda de enlace v.mp4 detenida por cancelación.");
                    } else if (manualFallbackLaunched.get()) {
                        updateDownloadStatus(directDownload, "Manual", directDownload.getProgress());
                        logWarn("No se encontró un enlace que termine en v.mp4. Se abrió el navegador empaquetado para intervención manual.");
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
        String resolvedDriver = ChromeExecutableLocator.resolveChromeDriver(CHROME_DRIVER_PATH);
        if (resolvedDriver != null) {
            System.setProperty("webdriver.chrome.driver", resolvedDriver);
            logDebug("Usando ChromeDriver: " + resolvedDriver);
        } else {
            System.clearProperty("webdriver.chrome.driver");
            logWarn("ChromeDriver no encontrado. Selenium Manager determinará la versión adecuada automáticamente.");
        }

        ChromeOptions options = new ChromeOptions();
        String chromeBinary = ChromeExecutableLocator.resolvePackagedChromeBinary(CHROME_PATH);
        if (chromeBinary != null) {
            options.setBinary(chromeBinary);
            logDebug("Usando binario de Chrome: " + chromeBinary);
        } else {
            logWarn("No se encontró el Chrome empaquetado. Se usará el navegador predeterminado del sistema.");
        }
        boolean headlessMode = !userInteraction && runHeadless;
        logDebug("Configurando navegador (userInteraction=" + userInteraction + ", headless=" + headlessMode + ")");

        Set<Path> loadedExtensions = new HashSet<>();
        boolean popupExtensionLoaded = addExtensionFromCandidates(options, VideosStreamerManager.getPopupExtensionCandidates(), loadedExtensions);
        boolean streamtapeExtensionLoaded = addExtensionFromCandidates(options, VideosStreamerManager.getStreamtapePackagedCandidates(), loadedExtensions);
        isNopechaInstalled = addExtensionFromCandidates(options, NOPECHA_EXTENSION_CANDIDATES, loadedExtensions);

        if (!popupExtensionLoaded || !streamtapeExtensionLoaded || !isNopechaInstalled) {
            logWarn("No se pudieron cargar todas las extensiones requeridas desde Extension/.");
            throw new IllegalStateException("Extensiones requeridas no instaladas.");
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

        try {
            driver = new ChromeDriver(options);
        } catch (SessionNotCreatedException e) {
            logWarn("[Streamplay] Falló Chrome con extensiones. " + e.getMessage());
            throw e;
        }
        wait = new WebDriverWait(driver, Duration.ofSeconds(15));
        currentSessionHeadless = headlessMode;
        logDebug("Navegador inicializado. Modo headless=" + headlessMode + ", popup=" + popupExtensionLoaded + ", nopecha=" + isNopechaInstalled);
    }

    private boolean addExtensionFromCandidates(ChromeOptions options, String[] candidates, Set<Path> loadedExtensions) {
        if (candidates == null) {
            return false;
        }

        boolean installed = false;

        for (String candidate : candidates) {
            File addon = resolveAddonFile(candidate);
            if (addon == null || !addon.exists()) {
                logWarn("Extensión no encontrada (relativa): " + candidate);
                continue;
            }

            if (addon.isDirectory()) {
                logWarn("Se omitió carpeta (solo CRX): " + candidate);
                continue;
            }

            try {
                Path realPath = addon.toPath().toRealPath();
                if (!loadedExtensions.add(realPath)) {
                    logDebug("Extensión duplicada omitida: " + addon.getName());
                    return true;
                }
                logDebug("Cargando extensión: " + addon.getName() + " [" + candidate + "]");
                options.addExtensions(addon);
                installed = true;
            } catch (Exception e) {
                logWarn("Fallo instalando CRX: " + addon.getName() + " [" + candidate + "] => " + e.getMessage());
            }
        }

        return installed;
    }

    private File resolveAddonFile(String addonPath) {
        if (addonPath == null || addonPath.isBlank()) {
            return null;
        }

        Path candidate = Paths.get(addonPath.replace("\\", File.separator));
        if (candidate.isAbsolute() || addonPath.contains(":")) {
            return null;
        }

        Path base = Paths.get(System.getProperty("user.dir"));
        Path resolved = base.resolve(candidate).normalize();
        File addon = resolved.toFile();
        return addon.exists() ? addon : null;
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

    private boolean waitForManualInteraction(DescargasUI.DirectDownload directDownload,
                                             String title,
                                             String message,
                                             long timeoutMillis) throws InterruptedException {
        ProgressDialog progressDialog = new ProgressDialog(title, message, true);
        javafx.application.Platform.runLater(progressDialog::show);

        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMillis) {
            if (isCancelled.get()) {
                javafx.application.Platform.runLater(progressDialog::close);
                updateDownloadStatus(directDownload, "Cancelled", 0);
                return false;
            }

            long remainingMillis = timeoutMillis - (System.currentTimeMillis() - startTime);
            long remainingSeconds = Math.max(0, remainingMillis / 1000);
            String countdownText = String.format("Tiempo restante: %02d:%02d",
                    remainingSeconds / 60, remainingSeconds % 60);

            javafx.application.Platform.runLater(() -> progressDialog.updateCountdown(countdownText));

            if (isManualReady()) {
                logDebug("Interacción manual completada, continuando con la descarga.");
                javafx.application.Platform.runLater(progressDialog::close);
                return true;
            }

            Thread.sleep(1000);
        }

        javafx.application.Platform.runLater(progressDialog::close);
        updateDownloadStatus(directDownload, "Error", 0);
        logError("Tiempo agotado esperando que el usuario complete la interacción manual.");
        return false;
    }

    private boolean isManualReady() {
        try {
            WebElement btn = driver.findElement(By.id("btn_download"));
            if (btn.isDisplayed() && btn.isEnabled()) {
                logDebug("Botón de descarga disponible durante la espera manual.");
                return true;
            }
        } catch (NoSuchElementException ignored) {
            // El botón aún no está disponible
        }

        try {
            List<WebElement> videoList = driver.findElements(By.cssSelector("video"));
            if (!videoList.isEmpty() && videoList.get(0).isDisplayed()) {
                logDebug("Reproductor detectado durante la espera manual.");
                return true;
            }
        } catch (Exception ignored) {
            // Continuar esperando
        }

        if (findMp4Url(driver, MP4_PATTERN).isPresent()) {
            logDebug("Página con enlace mp4 detectada durante la espera manual.");
            return true;
        }

        return false;
    }

    private Optional<String> findMp4Url(WebDriver webDriver, Pattern pattern) {
        String pageSource = webDriver.getPageSource();
        Matcher matcher = pattern.matcher(pageSource);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }

        if (webDriver instanceof JavascriptExecutor executor) {
            try {
                Object result = executor.executeScript(
                        "const candidates = [];" +
                                "const video = document.querySelector('video');" +
                                "if (video) {" +
                                "  if (video.currentSrc) candidates.push(video.currentSrc);" +
                                "  if (video.src) candidates.push(video.src);" +
                                "  const source = video.querySelector('source');" +
                                "  if (source && source.src) candidates.push(source.src);" +
                                "}" +
                                "document.querySelectorAll('source[src], video[src]').forEach(el => candidates.push(el.src));" +
                                "document.querySelectorAll('[data-src], [data-file], [data-url]').forEach(el => {" +
                                "  const value = el.getAttribute('data-src') || el.getAttribute('data-file') || el.getAttribute('data-url');" +
                                "  if (value) candidates.push(value);" +
                                "});" +
                                "return candidates.find(src => src && src.includes('.mp4')) || null;"
                );
                if (result instanceof String found && !found.isBlank()) {
                    return Optional.of(found);
                }
            } catch (JavascriptException ignored) {
                // ignore JS errors
            }
        }

        return Optional.empty();
    }

    private Optional<String> waitForMp4Url(String videoUrl) {
        int attempt = 1;
        boolean timeoutArtifactCaptured = false;
        int consecutiveTimeouts = 0;
        while (!isCancelled.get()) {
            try {
                logDebug("Iniciando espera activa para enlaces v.mp4... (intento " + attempt + ")");
                WebDriverWait mp4Wait = new WebDriverWait(driver, Duration.ofSeconds(30));
                String url = mp4Wait.until(webDriver -> {
                    if (isCancelled.get()) {
                        throw new CancellationException("Descarga cancelada");
                    }
                    return findMp4Url(webDriver, MP4_PATTERN).orElse(null);
                });

                if (url != null && !url.isBlank()) {
                    logDebug("Enlace v.mp4 encontrado en el intento " + attempt + ": " + url);
                    return Optional.of(url);
                }
            } catch (TimeoutException e) {
                consecutiveTimeouts++;
                logWarn("Tiempo de espera agotado buscando el enlace mp4 (intento " + attempt + "). Se reintentará.");
                logPageState("Estado al agotar la espera de mp4 (intento " + attempt + ")");
                if (!timeoutArtifactCaptured) {
                    collectDebugArtifacts("timeout-buscando-mp4");
                    timeoutArtifactCaptured = true;
                }
                if (consecutiveTimeouts >= MAX_MP4_TIMEOUTS_BEFORE_MANUAL && manualFallbackLaunched.compareAndSet(false, true)) {
                    logWarn("Se alcanzó el límite de espera del mp4. Abriendo el navegador empaquetado para completar la descarga manualmente.");
                    launchManualFallbackBrowser(videoUrl);
                    return Optional.empty();
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
                consecutiveTimeouts = 0;
                if (!prepareForMp4Retry(attempt)) {
                    reloadPageForRetry(attempt);
                }
                attempt++;
            }
        }

        logWarn("Se detuvo la espera de enlaces v.mp4 porque la descarga fue cancelada.");
        return Optional.empty();
    }

    private void launchManualFallbackBrowser(String videoUrl) {
        Path packagedChrome = Paths.get(CHROME_PATH);
        if (!Files.isRegularFile(packagedChrome)) {
            logError("No se pudo abrir el navegador manual porque no existe el binario empaquetado en: " + packagedChrome);
            return;
        }

        List<String> command = new ArrayList<>();
        command.add(packagedChrome.toString());
        command.add("--user-data-dir=" + Paths.get("ChromeProfiles", "streamplay-manual").toAbsolutePath());
        command.add("--no-first-run");
        List<Path> extensionDirs = resolveManualExtensionDirs();
        if (!extensionDirs.isEmpty()) {
            String joined = extensionDirs.stream()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .collect(Collectors.joining(","));
            command.add("--load-extension=" + joined);
        }
        command.add(videoUrl);

        try {
            new ProcessBuilder(command).start();
            logDebug("Navegador manual iniciado con el Chrome empaquetado en: " + packagedChrome);
        } catch (IOException e) {
            logError("No se pudo lanzar el navegador manual empaquetado: " + e.getMessage());
        }
    }

    private List<Path> resolveManualExtensionDirs() {
        Set<Path> resolved = new LinkedHashSet<>();
        addIfDirectory(resolved, Paths.get("Extension", "PopUpStrictOld"));
        addIfDirectory(resolved, Paths.get("Extension", "nopecatcha old"));

        List<String> candidates = new ArrayList<>();
        for (String candidate : VideosStreamerManager.getPopupExtensionCandidates()) {
            candidates.add(candidate);
        }
        for (String candidate : VideosStreamerManager.getStreamtapePackagedCandidates()) {
            candidates.add(candidate);
        }
        for (String candidate : NOPECHA_EXTENSION_CANDIDATES) {
            candidates.add(candidate);
        }

        Path cacheDir = Paths.get("ChromeProfiles", "extensions-cache");
        for (String candidate : candidates) {
            File addon = resolveAddonFile(candidate);
            if (addon == null || !addon.exists()) {
                continue;
            }
            if (addon.isDirectory()) {
                resolved.add(addon.toPath());
                continue;
            }
            try {
                Path unpacked = extractCrx(addon, cacheDir);
                if (unpacked != null) {
                    resolved.add(unpacked);
                }
            } catch (IOException e) {
                logWarn("No se pudo extraer la extensión manual " + addon.getName() + ": " + e.getMessage());
            }
        }

        return new ArrayList<>(resolved);
    }

    private void addIfDirectory(Set<Path> resolved, Path path) {
        if (Files.isDirectory(path)) {
            resolved.add(path);
        }
    }

    private Path extractCrx(File crxFile, Path cacheDir) throws IOException {
        if (crxFile == null) {
            return null;
        }
        Files.createDirectories(cacheDir);
        String baseName = crxFile.getName().replaceAll("\\.crx$", "");
        Path targetDir = cacheDir.resolve(baseName);
        if (Files.exists(targetDir)) {
            try (java.util.stream.Stream<Path> stream = Files.list(targetDir)) {
                if (stream.findAny().isPresent()) {
                    return targetDir;
                }
            }
        }

        byte[] bytes = Files.readAllBytes(crxFile.toPath());
        int zipStart = findCrxZipStart(bytes);
        if (zipStart < 0 || zipStart >= bytes.length) {
            return null;
        }

        Files.createDirectories(targetDir);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes, zipStart, bytes.length - zipStart))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }

        return targetDir;
    }

    private int findCrxZipStart(byte[] bytes) {
        if (bytes.length < 4) {
            return -1;
        }
        if (bytes[0] != 'C' || bytes[1] != 'r' || bytes[2] != '2' || bytes[3] != '4') {
            return 0;
        }
        if (bytes.length < 12) {
            return -1;
        }
        int version = readLittleEndianInt(bytes, 4);
        if (version == 2) {
            if (bytes.length < 16) {
                return -1;
            }
            int pubKeyLength = readLittleEndianInt(bytes, 8);
            int sigLength = readLittleEndianInt(bytes, 12);
            return 16 + pubKeyLength + sigLength;
        }
        if (version == 3) {
            int headerSize = readLittleEndianInt(bytes, 8);
            return 12 + headerSize;
        }
        return -1;
    }

    private int readLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) |
                ((bytes[offset + 1] & 0xFF) << 8) |
                ((bytes[offset + 2] & 0xFF) << 16) |
                ((bytes[offset + 3] & 0xFF) << 24);
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

    private boolean waitForNopechaResolution(String providerName) {
        if (driver == null) {
            return false;
        }

        WebDriverWait captchaWait = new WebDriverWait(driver, captchaWaitTimeout);
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
            return true;
        } catch (TimeoutException e) {
            logWarn("Tiempo de espera agotado esperando la resolución automática del captcha para " + providerName + ".");
            collectDebugArtifacts("captcha-no-resuelto");
            return false;
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
