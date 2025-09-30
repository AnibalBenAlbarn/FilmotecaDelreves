package org.example.filmotecadelreves.downloaders;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import org.example.filmotecadelreves.downloaders.streams.DefaultStreamServerHandler;
import org.example.filmotecadelreves.downloaders.streams.FallbackStreamServerHandler;
import org.example.filmotecadelreves.downloaders.streams.StreamServerHandler;
import org.example.filmotecadelreves.downloaders.streams.StreamtapeStreamHandler;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Manager class for streaming videos from different servers.
 * Handles server-specific configurations and browser setup.
 */
public class VideosStreamerManager {
    private WebDriver driver;
    private static final String CHROME_DRIVER_PATH = "ChromeDriver/chromedriver.exe";
    private static final String CHROME_PATH = "Chrome Test/chrome.exe";

    // Addon paths
    private static final String POPUP_BLOCKER_PATH = "lib/PopUp Strict.crx";
    private static final String ADBLOCK_PATH = "lib/adblock2.crx";
    private static final String STREAMTAPE_ADDON_PATH = "lib/Streamtape.crx";
    private static final String STREAMTAPE_DOWNLOADER_EXTENSION_PATH =
            "C:/Users/Anibal/IdeaProjects/FilmotecaDelreves/Extension/StreamtapeDownloader.crx";

    private static final String TEST_PROFILE_DIR = "ChromeProfile";

    // Thread to monitor for ESC key
    private Thread escMonitorThread;
    private boolean running = false;

    private final List<StreamServerHandler> handlers;
    private final StreamServerHandler fallbackHandler;

    /**
     * Initializes the VideosStreamerManager with server configurations.
     */
    public VideosStreamerManager() {
        // Set the path to the ChromeDriver
        System.setProperty("webdriver.chrome.driver", getAbsolutePath(CHROME_DRIVER_PATH));

        handlers = new ArrayList<>();

        handlers.add(new DefaultStreamServerHandler(1, "PowVideo",
                List.of("powvideo.org"),
                List.of(POPUP_BLOCKER_PATH, ADBLOCK_PATH)));

        handlers.add(new DefaultStreamServerHandler(21, "StreamPlay",
                List.of("streamplay.to"),
                List.of(POPUP_BLOCKER_PATH, ADBLOCK_PATH)));

        handlers.add(new StreamtapeStreamHandler(497, "Streamtape",
                List.of("streamtape.com", "streamtape.net"),
                List.of(
                        POPUP_BLOCKER_PATH,
                        STREAMTAPE_DOWNLOADER_EXTENSION_PATH,
                        STREAMTAPE_ADDON_PATH
                )));

        handlers.add(new DefaultStreamServerHandler(15, "Mixdrop",
                List.of("mixdrop.bz"),
                List.of(POPUP_BLOCKER_PATH, STREAMTAPE_ADDON_PATH)));

        handlers.add(new DefaultStreamServerHandler(3, "Vidmoly",
                List.of("vidmoly.me", "vidmoly.to"),
                List.of(POPUP_BLOCKER_PATH, ADBLOCK_PATH)));

        fallbackHandler = new FallbackStreamServerHandler("Default",
                List.of(POPUP_BLOCKER_PATH, ADBLOCK_PATH));
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

            StreamServerHandler handler = findHandler(url, serverId);
            System.out.println("Using server handler: " + handler.getName());

            ChromeOptions options = setupChromeOptions(handler);

            // Initialize the WebDriver with our options
            driver = new ChromeDriver(options);

            // Set timeouts
            driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);
            driver.manage().timeouts().implicitlyWait(10, TimeUnit.SECONDS);

            // Wait for extensions to load and close any extension tabs
            handleExtensionTabs();

            handler.onDriverCreated(driver);

            // Navigate to the URL
            driver.get(url);

            // Wait for the page to load
            Thread.sleep(2000);

            handler.afterPageLoad(driver);

            if (handler.useBrowserFullscreen()) {
                Thread.sleep(2000);
                new Actions(driver).sendKeys(Keys.F11).perform();
                System.out.println("Entered fullscreen mode");
            }

            System.out.println("Stream started successfully");
            System.out.println("Press ESC to close the browser");

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
    private StreamServerHandler findHandler(String url, int serverId) {
        for (StreamServerHandler handler : handlers) {
            try {
                if (handler.supports(url, serverId)) {
                    return handler;
                }
            } catch (Exception e) {
                System.err.println("Error checking handler " + handler.getName() + ": " + e.getMessage());
            }
        }
        return fallbackHandler;
    }

    /**
     * Sets up Chrome options based on server configuration.
     *
     * @param config The server configuration
     * @return Configured ChromeOptions
     */
    private ChromeOptions setupChromeOptions(StreamServerHandler handler) {
        ChromeOptions options = new ChromeOptions();

        // Set binary location to our custom Chrome
        options.setBinary(getAbsolutePath(CHROME_PATH));

        // Basic configuration to avoid issues
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-setuid-sandbox");
        options.addArguments("--disable-popup-blocking");
        options.addArguments("--disable-features=site-per-process");
        options.addArguments("--disable-features=IsolateOrigins,site-per-process");

        options.addArguments("--disable-dev-shm-usage");
        options.addArguments("--start-maximized");
        options.addArguments("--disable-notifications");
        options.addArguments("--autoplay-policy=no-user-gesture-required");

        // Use dedicated profile to avoid interfering with the user's main Chrome
        File profileDir = new File(TEST_PROFILE_DIR);
        if (!profileDir.exists() && !profileDir.mkdirs()) {
            System.err.println("Unable to create profile directory: " + profileDir.getAbsolutePath());
        }
        options.addArguments("--user-data-dir=" + profileDir.getAbsolutePath());

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

        handler.configure(options);

        return options;
    }

    /**
     * Handles extension tabs that may open during startup.
     */
    private void handleExtensionTabs() throws InterruptedException {
        // Wait for extensions to load (blank page)
        Thread.sleep(2000);

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
    private String getAbsolutePath(String relativePath) {
        File file = new File(relativePath);
        return file.getAbsolutePath();
    }
}