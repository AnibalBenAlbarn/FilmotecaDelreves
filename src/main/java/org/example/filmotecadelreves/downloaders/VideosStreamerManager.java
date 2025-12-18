package org.example.filmotecadelreves.downloaders;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Point;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.UnsupportedCommandException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Manager class for streaming videos from different servers.
 * Handles server-specific configurations and browser setup.
 */
public class VideosStreamerManager {
    private WebDriver driver;
    private volatile boolean headless;
    protected static final String CHROME_DRIVER_PATH = buildRelativePath("ChromeDriver", "chromedriver.exe");
    protected static final String CHROME_PATH = buildRelativePath("chrome-win", "chrome.exe");

    private static final long EXTENSION_INITIALIZATION_DELAY_MS = 3000L;
    private static final long POST_NAVIGATION_DELAY_MS = 500L;

    // Addon paths
    protected static final String POPUP_EXTENSION_RELATIVE = buildRelativePath("Extension", "PopUpStrictOld.crx");
    private static final String POPUP_EXTENSION = POPUP_EXTENSION_RELATIVE;

    protected static final String[] POPUP_EXTENSION_CANDIDATES = {
            buildRelativePath("Extension", "PopUpStrictOld"),
            POPUP_EXTENSION,
            "lib/PopUp Strict.crx"
    };

    protected static final String STREAMTAPE_EXTENSION_RELATIVE = buildRelativePath("Extension", "StreamtapeDownloader.crx");
    private static final String STREAMTAPE_EXTENSION = STREAMTAPE_EXTENSION_RELATIVE;
    protected static final String[] STREAMTAPE_PACKAGED_CANDIDATES = {
            STREAMTAPE_EXTENSION,
            "lib/Streamtape.crx"
    };

    protected static final String[] STREAMTAPE_UNPACKED_CANDIDATES = {
            "Extension/streamtape-extension-master",
            "Extension/StreamtapeDownloader"
    };

    protected static final String ADBLOCK_PATH = "lib/adblock2.crx";

    // Thread to monitor for ESC key
    private Thread escMonitorThread;
    private boolean running = false;

    // List of server configurations
    private final List<ServerConfig> serverConfigs;

    /**
     * Initializes the VideosStreamerManager with server configurations.
     */
    public VideosStreamerManager() {
        this(null);
    }

    /**
     * Initializes the VideosStreamerManager with custom server configurations.
     *
     * @param customServerConfigs Optional list of server configurations. When {@code null},
     *                            the default configuration set is used.
     */
    protected VideosStreamerManager(List<ServerConfig> customServerConfigs) {
        // Set the path to the ChromeDriver
        String packagedDriver = getAbsolutePath(CHROME_DRIVER_PATH);
        String resolvedDriver = ChromeExecutableLocator.resolveChromeDriver(packagedDriver);
        if (resolvedDriver != null) {
            System.setProperty("webdriver.chrome.driver", resolvedDriver);
            System.out.println("Using ChromeDriver: " + resolvedDriver);
        } else {
            System.clearProperty("webdriver.chrome.driver");
            System.out.println("ChromeDriver no encontrado. Selenium Manager determinará la versión adecuada automáticamente.");
        }

        if (customServerConfigs != null) {
            serverConfigs = new ArrayList<>(customServerConfigs);
        } else {
            serverConfigs = buildDefaultServerConfigs();
        }
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public boolean isHeadless() {
        return headless;
    }

    protected static String[] getPopupExtensionCandidates() {
        return POPUP_EXTENSION_CANDIDATES.clone();
    }

    protected static String[] getStreamtapePackagedCandidates() {
        return STREAMTAPE_PACKAGED_CANDIDATES.clone();
    }

    protected static String[] getStreamtapeUnpackedCandidates() {
        return STREAMTAPE_UNPACKED_CANDIDATES.clone();
    }

    private String[] buildAddonList(boolean includeAdblock, boolean includeStreamtape) {
        LinkedHashSet<String> addons = new LinkedHashSet<>();
        addons.add(POPUP_EXTENSION);
        addons.addAll(Arrays.asList(getPopupExtensionCandidates()));

        if (includeAdblock) {
            addons.add(ADBLOCK_PATH);
        }

        if (includeStreamtape) {
            addons.add(STREAMTAPE_EXTENSION);
            addons.addAll(Arrays.asList(getStreamtapePackagedCandidates()));
            addons.addAll(Arrays.asList(getStreamtapeUnpackedCandidates()));
        }

        return addons.toArray(new String[0]);
    }

    /**
     * Builds the default list of server configurations.
     */
    protected List<ServerConfig> buildDefaultServerConfigs() {
        List<ServerConfig> configs = new ArrayList<>();

        // Configure servers based on requirements
        // powvideo.org (ID: 1)
        configs.add(new ServerConfig(
                1,
                "powvideo.org",
                "powvideo.org",
                true,
                buildAddonList(true, false)
        ));

        // streamplay.to (ID: 21)
        configs.add(new ServerConfig(
                21,
                "streamplay.to",
                "streamplay.to",
                true,
                buildAddonList(true, false)
        ));

        // streamtape.com (ID: 497)
        configs.add(new ServerConfig(
                497,
                "streamtape.com",
                "streamtape.com",
                true,
                buildAddonList(false, true)
        ));

        // mixdrop.bz (ID: 15)
        configs.add(new ServerConfig(
                15,
                "mixdrop.bz",
                "mixdrop.bz",
                true,
                buildAddonList(false, false)
        ));

        // vidmoly.me (ID: 3)
        configs.add(new ServerConfig(
                3,
                "vidmoly.me",
                "vidmoly.to",
                true,
                buildAddonList(true, false)
        ));

        // Default configuration for other servers
        configs.add(new ServerConfig(
                -1,
                "default",
                "",
                true,
                buildAddonList(true, false)
        ));

        return configs;
    }

    /**
     * Streams a video from the specified URL.
     *
     * @param url The URL to stream
     * @param serverId The server ID (optional, can be -1 if unknown)
     */
    public void streamVideo(String url, int serverId) {
        try {
            System.out.println("Starting video stream for: " + url + " (Server ID: " + serverId + ")");

            // Find the appropriate server configuration
            ServerConfig config = findServerConfig(url, serverId);
            if (config == null) {
                throw new IllegalStateException("No server configuration available for the requested stream");
            }
            System.out.println("Using server configuration: " + config.getName());

            // Ensure any previous driver session for this manager is closed before starting a new one
            closeDriver();

            // Configure Chrome options
            ChromeOptions options = setupChromeOptions(config);

            // Initialize the WebDriver with our options
            driver = new ChromeDriver(options);

            onDriverCreated(driver, config);

            // Keep the browser in the background while extensions are installed
            if (!headless) {
                minimizeBrowserWindow();
            }

            // Set timeouts
            driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);
            driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);

            // Wait for extensions to load in the background and close any extension tabs
            handleExtensionTabs(EXTENSION_INITIALIZATION_DELAY_MS);

            // Allow subclasses to adjust the URL when required by a specific server
            String preparedUrl = prepareUrlForStreaming(url, config);
            if (preparedUrl == null || preparedUrl.isBlank()) {
                preparedUrl = url;
            }

            if (preparedUrl == null || preparedUrl.isBlank()) {
                throw new IllegalArgumentException("No valid URL provided for streaming");
            }

            // Navigate to the URL while keeping the browser in the background
            beforeNavigateTo(driver, preparedUrl, config);
            driver.get(preparedUrl);
            afterNavigateTo(driver, preparedUrl, config);

            // Give the page a brief moment to stabilise before showing it to the user
            Thread.sleep(POST_NAVIGATION_DELAY_MS);

            // Bring the browser window to the foreground for the user
            if (!headless) {
                bringBrowserToForeground();
            }

            System.out.println("Stream started successfully" + (headless ? " (headless mode)" : ""));
            if (!headless) {
                System.out.println("Press ESC to close the browser");
            }

            // Start monitoring for ESC key
            startEscMonitor();

            // Add shutdown hook to ensure Chrome is closed when JVM exits
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeDriver));

        } catch (Exception e) {
            System.err.println("Error streaming video: " + e.getMessage());
            e.printStackTrace();
            closeDriver();
        }
    }

    /**
     * Finds the appropriate server configuration based on URL and server ID.
     *
     * @param url The URL to stream
     * @param serverId The server ID
     * @return The server configuration to use
     */
    private ServerConfig findServerConfig(String url, int serverId) {
        ServerConfig defaultConfig = null;

        // First try to match by server ID
        for (ServerConfig config : serverConfigs) {
            if (config.getId() == -1) {
                defaultConfig = config;
            }

            if (config.getId() == serverId) {
                // Double check URL pattern for extra validation
                if (config.matchesUrl(url)) {
                    return config;
                }
            }
        }

        // If no match by ID or URL doesn't match the ID's pattern, try matching by URL
        for (ServerConfig config : serverConfigs) {
            if (config.getId() != -1 && config.matchesUrl(url)) {
                return config;
            }
        }

        // If no match found, return default configuration if available, otherwise fallback to the first config
        if (defaultConfig != null) {
            return defaultConfig;
        }

        return serverConfigs.isEmpty() ? null : serverConfigs.get(0);
    }

    /**
     * Sets up Chrome options based on server configuration.
     *
     * @param config The server configuration
     * @return Configured ChromeOptions
     */
    protected ChromeOptions setupChromeOptions(ServerConfig config) {
        ChromeOptions options = new ChromeOptions();

        // Set binary location to our custom Chrome
        String packagedBinary = getAbsolutePath(CHROME_PATH);
        String resolvedBinary = ChromeExecutableLocator.resolveChromeBinary(packagedBinary);
        if (resolvedBinary != null) {
            options.setBinary(resolvedBinary);
            System.out.println("Using Chrome binary: " + resolvedBinary);
        } else {
            System.out.println("No se encontró un binario de Chrome personalizado. Se utilizará el navegador predeterminado del sistema.");
        }

        // Use a dedicated user data directory per server to avoid interfering with the main browser
        File userDataDir = ensureUserDataDir(config);
        options.addArguments("--user-data-dir=" + userDataDir.getAbsolutePath());
        options.addArguments("--profile-directory=Default");
        if (headless) {
            options.addArguments("--headless=new");
            options.addArguments("--disable-gpu");
            options.addArguments("--mute-audio");
            options.addArguments("--window-size=1920,1080");
        } else {
            options.addArguments("--start-minimized");
        }

        // Basic configuration to avoid issues
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-setuid-sandbox");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-features=site-per-process");
        options.addArguments("--disable-features=IsolateOrigins,site-per-process");

        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--disable-notifications");
        options.addArguments("--autoplay-policy=no-user-gesture-required");

        // Configuration to avoid extension installation popups
        Map<String, Object> prefs = new HashMap<>();
        prefs.put("extensions.ui.developer_mode", false);
        prefs.put("extensions.install.showPrompt", false);
        prefs.put("extensions.install.showDialog", false);
        prefs.put("extensions.install.silent", true);
        prefs.put("profile.default_content_setting_values.popups", 2);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        options.setExperimentalOption("prefs", prefs);

        // Hide that it's automated
        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation", "enable-logging"));
        options.setExperimentalOption("useAutomationExtension", false);

        // Add extensions based on server configuration
        Set<String> packagedExtensions = new LinkedHashSet<>();
        Set<String> unpackedExtensions = new LinkedHashSet<>();

        for (String addonPath : config.getAddons()) {
            if (addonPath == null || addonPath.isEmpty()) {
                continue;
            }

            File addon = resolveAddonFile(addonPath);
            if (addon != null && addon.exists()) {
                File canonicalAddon = toCanonicalFile(addon);
                String canonicalPath = canonicalAddon.getAbsolutePath();

                if (canonicalAddon.isDirectory()) {
                    if (!unpackedExtensions.add(canonicalPath)) {
                        System.out.println("Skipping duplicate unpacked extension: " + canonicalPath);
                    }
                } else {
                    if (!packagedExtensions.add(canonicalPath)) {
                        System.out.println("Skipping duplicate extension: " + canonicalPath);
                    }
                }
            } else {
                System.out.println("Warning: Extension not found at: " + addonPath);
            }
        }

        for (String extensionPath : packagedExtensions) {
            options.addExtensions(new File(extensionPath));
            System.out.println("Added extension: " + extensionPath);
        }

        if (!unpackedExtensions.isEmpty()) {
            options.addArguments("--load-extension=" + String.join(",", unpackedExtensions));
            for (String extensionPath : unpackedExtensions) {
                System.out.println("Added unpacked extension: " + extensionPath);
            }
        }

        if (packagedExtensions.isEmpty() && unpackedExtensions.isEmpty()) {
            System.out.println("No extensions were loaded for this configuration.");
        }

        return options;
    }

    /**
     * Handles extension tabs that may open during startup.
     */
    private void handleExtensionTabs(long initialDelayMs) throws InterruptedException {
        if (initialDelayMs > 0) {
            Thread.sleep(initialDelayMs);
        }

        // Close any extension tabs that may have opened
        String originalHandle = driver.getWindowHandle();
        for (String handle : driver.getWindowHandles()) {
            if (!handle.equals(originalHandle)) {
                driver.switchTo().window(handle);
                driver.close();
            }
        }
        driver.switchTo().window(originalHandle);
    }

    private void minimizeBrowserWindow() {
        if (driver == null) {
            return;
        }
        try {
            driver.manage().window().minimize();
        } catch (UnsupportedCommandException e) {
            try {
                driver.manage().window().setPosition(new Point(-2000, 0));
            } catch (WebDriverException ignored) {
                // Ignore if we cannot reposition the window
            }
        } catch (WebDriverException e) {
            try {
                driver.manage().window().setPosition(new Point(-2000, 0));
            } catch (WebDriverException ignored) {
                // Ignore if we cannot reposition the window
            }
        }
    }

    private void bringBrowserToForeground() {
        if (driver == null) {
            return;
        }

        try {
            driver.manage().window().setPosition(new Point(0, 0));
        } catch (WebDriverException ignored) {
            // Ignore if positioning is not supported
        }

        try {
            driver.manage().window().maximize();
        } catch (WebDriverException ignored) {
            // Ignore if maximizing is not supported
        }

        try {
            driver.switchTo().window(driver.getWindowHandle());
        } catch (WebDriverException ignored) {
            // Ignore if focusing the window fails
        }

        try {
            if (driver instanceof JavascriptExecutor) {
                JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;
                jsExecutor.executeScript("window.focus();");
            }
        } catch (Exception ignored) {
            // Ignore if focusing via script fails
        }
    }

    /**
     * Starts a thread to monitor for ESC key press.
     */
    private void startEscMonitor() {
        running = true;
        escMonitorThread = new Thread(() -> {
            try {
                // Use JavascriptExecutor to inject a script that will monitor for ESC key
                if (driver instanceof JavascriptExecutor) {
                    JavascriptExecutor jsExecutor = (JavascriptExecutor) driver;

                    // Add a key event listener to the document
                    jsExecutor.executeScript(
                            "window.escPressed = false;" +
                                    "document.addEventListener('keydown', function(e) {" +
                                    "  if (e.key === 'Escape' || e.keyCode === 27) {" +
                                    "    window.escPressed = true;" +
                                    "  }" +
                                    "});"
                    );

                    // Check periodically if ESC was pressed
                    while (running && driver != null) {
                        try {
                            Boolean escPressed = (Boolean) jsExecutor.executeScript("return window.escPressed === true;");
                            if (escPressed != null && escPressed) {
                                System.out.println("ESC key detected, closing browser...");
                                closeDriver();
                                break;
                            }
                        } catch (Exception e) {
                            // Browser might be closed already
                            break;
                        }
                        Thread.sleep(500);
                    }
                }
            } catch (Exception e) {
                System.err.println("Error in ESC monitor: " + e.getMessage());
            }
        });
        escMonitorThread.setDaemon(true);
        escMonitorThread.start();
    }

    /**
     * Closes the WebDriver and ensures all Chrome processes are terminated.
     */
    public void closeDriver() {
        running = false;
        if (driver != null) {
            try {
                System.out.println("Closing Chrome browser...");

                // First try to close gracefully
                driver.close();
                driver.quit();

                // Give it a moment to close
                Thread.sleep(500);

                System.out.println("Chrome browser closed successfully");
            } catch (Exception e) {
                System.err.println("Error closing WebDriver: " + e.getMessage());
            } finally {
                driver = null;
            }
        }
    }

    /**
     * Gets the absolute path from a relative path.
     *
     * @param relativePath The relative path
     * @return The absolute path
     */
    private static String buildRelativePath(String first, String... more) {
        return Paths.get(first, more).toString();
    }

    private String getAbsolutePath(String relativePath) {
        Path path = Paths.get(relativePath);
        if (!path.isAbsolute()) {
            path = Paths.get(System.getProperty("user.dir")).resolve(path).normalize();
        }
        return path.toAbsolutePath().toString();
    }

    /**
     * Returns {@code true} if this manager has a configuration for the provided server ID.
     */
    public boolean handlesServer(int serverId) {
        return serverConfigs.stream().anyMatch(config -> config.getId() != -1 && config.getId() == serverId);
    }

    /**
     * Returns {@code true} if this manager has a configuration that matches the URL.
     */
    public boolean handlesUrl(String url) {
        return serverConfigs.stream().anyMatch(config -> config.getId() != -1 && config.matchesUrl(url));
    }

    /**
     * Gives subclasses a chance to tweak the URL before it is opened in the browser.
     *
     * <p>The base implementation simply returns the original value. Subclasses can override
     * this method to apply provider-specific normalisation without having to reimplement the
     * whole streaming logic.</p>
     */
    protected String prepareUrlForStreaming(String url, ServerConfig config) {
        return url;
    }

    /**
     * Hook that allows subclasses to customise the freshly created driver instance before any
     * navigation occurs. Subclasses can use this to register scripts, override the user agent or
     * perform other provider specific tweaks that must run prior to loading the target page.
     */
    protected void onDriverCreated(WebDriver driver, ServerConfig config) {
        // Default implementation does nothing.
    }

    /**
     * Hook that allows subclasses to adjust the driver or page just before navigation happens.
     * Implementations should avoid long running operations here to prevent delaying the stream
     * start unnecessarily.
     */
    protected void beforeNavigateTo(WebDriver driver, String url, ServerConfig config) {
        // Default implementation does nothing.
    }

    /**
     * Hook that allows subclasses to run logic immediately after a successful navigation. This is
     * useful to simulate small human-like delays or perform page specific preparation without
     * having to reimplement the entire streaming workflow.
     */
    protected void afterNavigateTo(WebDriver driver, String url, ServerConfig config) {
        // Default implementation does nothing.
    }

    private File ensureUserDataDir(ServerConfig config) {
        String name = config.getName() == null ? "default" : config.getName();
        String sanitized = name.replaceAll("[^a-zA-Z0-9-_]", "_");
        File profileDir = new File("ChromeProfiles" + File.separator + sanitized);
        if (!profileDir.exists() && !profileDir.mkdirs()) {
            System.err.println("Unable to create profile directory: " + profileDir.getAbsolutePath());
        }
        return profileDir;
    }

    private File toCanonicalFile(File addon) {
        try {
            return addon.getCanonicalFile();
        } catch (IOException e) {
            return addon.getAbsoluteFile();
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

        return null;
    }
}
