package org.example.filmotecadelreves.downloaders;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base class that adds a collection of "stealth" tweaks to Selenium driven Chrome sessions in
 * order to resemble regular user initiated browsing. The adjustments performed here are focused on
 * bypassing simple bot detections commonly used by free streaming providers.
 */
public abstract class StealthVideosStreamerManager extends VideosStreamerManager {

    private static final String DEFAULT_ACCEPT_LANGUAGE = "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7";
    private static final String DEFAULT_PLATFORM = "Win32";
    private static final String STEALTH_SCRIPT =
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});" +
            "window.navigator.chrome = { runtime: {} };" +
            "Object.defineProperty(navigator, 'languages', {get: () => ['es-ES', 'es', 'en']});" +
            "Object.defineProperty(navigator, 'plugins', {get: () => [1, 2, 3, 4, 5]});" +
            "Object.defineProperty(navigator, 'platform', {get: () => 'Win32'});";

    protected StealthVideosStreamerManager(List<ServerConfig> customServerConfigs) {
        super(customServerConfigs);
    }

    @Override
    protected ChromeOptions setupChromeOptions(ServerConfig config) {
        ChromeOptions options = super.setupChromeOptions(config);

        // These switches help mimic a user launched Chrome instance.
        options.addArguments("--disable-blink-features=AutomationControlled");
        options.addArguments("--disable-infobars");
        options.addArguments("--lang=es-ES");
        options.addArguments("--no-first-run");
        options.addArguments("--no-default-browser-check");
        options.addArguments("--window-size=1365,768");

        return options;
    }

    @Override
    protected void onDriverCreated(WebDriver driver, ServerConfig config) {
        super.onDriverCreated(driver, config);

        if (!(driver instanceof ChromeDriver)) {
            return;
        }

        ChromeDriver chromeDriver = (ChromeDriver) driver;

        try {
            chromeDriver.executeCdpCommand("Network.enable", Collections.emptyMap());
        } catch (Exception e) {
            System.err.println("Unable to enable Chrome DevTools Network domain: " + e.getMessage());
        }

        Map<String, Object> uaParams = new HashMap<>();
        uaParams.put("userAgent", getUserAgent(config));
        uaParams.put("platform", DEFAULT_PLATFORM);
        uaParams.put("acceptLanguage", DEFAULT_ACCEPT_LANGUAGE);
        try {
            chromeDriver.executeCdpCommand("Network.setUserAgentOverride", uaParams);
        } catch (Exception e) {
            System.err.println("Unable to override user agent: " + e.getMessage());
        }

        Map<String, Object> scriptParams = new HashMap<>();
        scriptParams.put("source", STEALTH_SCRIPT);
        try {
            chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", scriptParams);
        } catch (Exception e) {
            System.err.println("Unable to register stealth script: " + e.getMessage());
        }
    }

    @Override
    protected void afterNavigateTo(WebDriver driver, String url, ServerConfig config) {
        super.afterNavigateTo(driver, url, config);

        try {
            Thread.sleep(800L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    protected String prepareUrlForStreaming(String url, ServerConfig config) {
        String prepared = super.prepareUrlForStreaming(url, config);
        if (prepared == null) {
            return null;
        }

        String trimmed = prepared.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        if (trimmed.startsWith("//")) {
            trimmed = "https:" + trimmed;
        }

        if (trimmed.startsWith("http://")) {
            trimmed = "https://" + trimmed.substring("http://".length());
        }

        if (!trimmed.startsWith("http")) {
            trimmed = "https://" + trimmed;
        }

        return trimmed;
    }

    /**
     * Returns the user agent string to report to the remote server. Subclasses may override this to
     * match the specific flavour of Chrome that works best for their provider.
     */
    protected String getUserAgent(ServerConfig config) {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.6099.225 Safari/537.36";
    }
}
