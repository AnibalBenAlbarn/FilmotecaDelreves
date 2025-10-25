package org.example.filmotecadelreves.downloaders;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Base class that adds a collection of "stealth" tweaks to Selenium driven Chrome sessions in
 * order to resemble regular user initiated browsing. The adjustments performed here are focused on
 * bypassing simple bot detections commonly used by free streaming providers.
 */
public abstract class StealthVideosStreamerManager extends VideosStreamerManager {

    private static final String DEFAULT_ACCEPT_LANGUAGE = "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7";
    private static final String DEFAULT_PLATFORM = "Win32";
    private static final String STEALTH_SCRIPT = String.join("",
            "(() => {",
            "const navigatorProto = Object.getPrototypeOf(navigator);",
            "Object.defineProperty(navigatorProto, 'webdriver', {get: () => undefined});",
            "if (!window.chrome) { window.chrome = { runtime: {} }; }",
            "Object.defineProperty(navigator, 'languages', {get: () => ['es-ES', 'es', 'en-US', 'en']});",
            "Object.defineProperty(navigator, 'platform', {get: () => 'Win32'});",
            "Object.defineProperty(navigator, 'hardwareConcurrency', {get: () => 8});",
            "Object.defineProperty(navigator, 'deviceMemory', {get: () => 8});",
            "Object.defineProperty(navigator, 'maxTouchPoints', {get: () => 0});",
            "const originalPlugins = navigator.plugins;",
            "Object.defineProperty(navigator, 'plugins', {get: () => originalPlugins || [{ name: 'Chrome PDF Viewer' }, { name: 'Chromium PDF Viewer' }, { name: 'Microsoft Edge PDF Viewer' }]});",
            "const originalMimeTypes = navigator.mimeTypes;",
            "Object.defineProperty(navigator, 'mimeTypes', {get: () => originalMimeTypes || [{ type: 'application/pdf' }, { type: 'text/pdf' }]});",
            "const originalQuery = window.navigator.permissions && window.navigator.permissions.query;",
            "if (originalQuery) {",
            "  window.navigator.permissions.query = (parameters) => parameters && parameters.name === 'notifications' ? Promise.resolve({ state: Notification.permission }) : originalQuery(parameters);",
            "}",
            "const getParameter = WebGLRenderingContext.prototype.getParameter;",
            "WebGLRenderingContext.prototype.getParameter = function(parameter) {",
            "  const webglParams = {",
            "    37445: 'Intel Inc.',",
            "    37446: 'Intel(R) UHD Graphics 630'",
            "  };",
            "  return webglParams[parameter] || getParameter.apply(this, arguments);",
            "};",
            "HTMLCanvasElement.prototype.toDataURL = (() => {",
            "  const original = HTMLCanvasElement.prototype.toDataURL;",
            "  return function() {",
            "    const context = this.getContext('2d');",
            "    if (context) {",
            "      const shift = 0.00001 * (this.width + this.height);",
            "      try {",
            "        const { width, height } = this;",
            "        if (width && height) {",
            "          const imageData = context.getImageData(0, 0, width, height);",
            "          for (let i = 0; i < imageData.data.length; i += 4) {",
            "            imageData.data[i] = imageData.data[i] + shift;",
            "          }",
            "          context.putImageData(imageData, 0, 0);",
            "        }",
            "      } catch (err) {",
            "        // Ignore security errors",
            "      }",
            "    }",
            "    return original.apply(this, arguments);",
            "  };",
            "})();",
            "const makePropertyWritable = (object, property) => {",
            "  const descriptor = Object.getOwnPropertyDescriptor(object, property);",
            "  if (descriptor && descriptor.configurable) {",
            "    Object.defineProperty(object, property, { ...descriptor, writable: true });",
            "  }",
            "};",
            "makePropertyWritable(Notification, 'permission');",
            "const screenDescriptor = Object.getOwnPropertyDescriptor(window, 'screen');",
            "if (screenDescriptor && screenDescriptor.configurable) {",
            "  Object.defineProperty(window, 'screen', {",
            "    ...screenDescriptor,",
            "    value: Object.assign({}, screenDescriptor.value, {",
            "      availHeight: window.innerHeight,",
            "      availWidth: window.innerWidth,",
            "      colorDepth: 24",
            "    })",
            "  });",
            "}",
            "})();");

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

        enableNetworkDomain(chromeDriver);
        overrideUserAgent(chromeDriver, config);
        injectStealthScript(chromeDriver);
        applyLocaleTweaks(chromeDriver);
    }

    @Override
    protected void afterNavigateTo(WebDriver driver, String url, ServerConfig config) {
        super.afterNavigateTo(driver, url, config);

        try {
            Thread.sleep(randomPauseDuration());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (driver instanceof JavascriptExecutor) {
            try {
                ((JavascriptExecutor) driver).executeScript("window.focus(); document.body.dispatchEvent(new Event('mousemove'));");
            } catch (Exception e) {
                System.err.println("Unable to simulate focus event: " + e.getMessage());
            }
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

    private void enableNetworkDomain(ChromeDriver chromeDriver) {
        try {
            chromeDriver.executeCdpCommand("Network.enable", Collections.emptyMap());
        } catch (Exception e) {
            System.err.println("Unable to enable Chrome DevTools Network domain: " + e.getMessage());
        }
    }

    private void overrideUserAgent(ChromeDriver chromeDriver, ServerConfig config) {
        Map<String, Object> uaParams = new HashMap<>();
        String userAgent = getUserAgent(config);
        uaParams.put("userAgent", userAgent);
        uaParams.put("platform", DEFAULT_PLATFORM);
        uaParams.put("acceptLanguage", DEFAULT_ACCEPT_LANGUAGE);

        Map<String, Object> metadata = buildUserAgentMetadata(userAgent);
        if (!metadata.isEmpty()) {
            uaParams.put("userAgentMetadata", metadata);
        }

        try {
            chromeDriver.executeCdpCommand("Network.setUserAgentOverride", uaParams);
        } catch (Exception e) {
            System.err.println("Unable to override user agent: " + e.getMessage());
        }

        Map<String, Object> headers = new HashMap<>();
        headers.put("Accept-Language", DEFAULT_ACCEPT_LANGUAGE);
        try {
            chromeDriver.executeCdpCommand("Network.setExtraHTTPHeaders", Collections.singletonMap("headers", headers));
        } catch (Exception e) {
            System.err.println("Unable to set extra HTTP headers: " + e.getMessage());
        }
    }

    private Map<String, Object> buildUserAgentMetadata(String userAgent) {
        if (userAgent == null || userAgent.isEmpty()) {
            return Collections.emptyMap();
        }

        int chromeIndex = userAgent.indexOf("Chrome/");
        if (chromeIndex < 0) {
            return Collections.emptyMap();
        }

        int versionEnd = userAgent.indexOf(' ', chromeIndex);
        if (versionEnd < 0) {
            versionEnd = userAgent.length();
        }

        String version = userAgent.substring(chromeIndex + "Chrome/".length(), versionEnd);
        String major = version;
        int dot = version.indexOf('.');
        if (dot > 0) {
            major = version.substring(0, dot);
        }

        List<Map<String, Object>> brands = new ArrayList<>();
        brands.add(createBrand("Not.A/Brand", "8"));
        brands.add(createBrand("Chromium", major));
        brands.add(createBrand("Google Chrome", major));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("brands", brands);
        metadata.put("fullVersion", version);
        metadata.put("platform", "Windows");
        metadata.put("platformVersion", "10.0.0");
        metadata.put("architecture", "x86");
        metadata.put("model", "");
        metadata.put("bitness", "64");
        metadata.put("mobile", false);

        return metadata;
    }

    private Map<String, Object> createBrand(String brand, String version) {
        Map<String, Object> brandMap = new HashMap<>();
        brandMap.put("brand", brand);
        brandMap.put("version", version);
        return brandMap;
    }

    private void injectStealthScript(ChromeDriver chromeDriver) {
        Map<String, Object> scriptParams = new HashMap<>();
        scriptParams.put("source", STEALTH_SCRIPT);
        try {
            chromeDriver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", scriptParams);
        } catch (Exception e) {
            System.err.println("Unable to register stealth script: " + e.getMessage());
        }
    }

    private void applyLocaleTweaks(ChromeDriver chromeDriver) {
        Map<String, Object> timezoneParams = new HashMap<>();
        timezoneParams.put("timezoneId", "Europe/Madrid");
        try {
            chromeDriver.executeCdpCommand("Emulation.setTimezoneOverride", timezoneParams);
        } catch (Exception e) {
            System.err.println("Unable to override timezone: " + e.getMessage());
        }

        Map<String, Object> localeParams = new HashMap<>();
        localeParams.put("locale", "es-ES");
        try {
            chromeDriver.executeCdpCommand("Emulation.setLocaleOverride", localeParams);
        } catch (Exception e) {
            System.err.println("Unable to override locale: " + e.getMessage());
        }
    }

    private long randomPauseDuration() {
        int base = 900;
        int spread = 800;
        return base + ThreadLocalRandom.current().nextInt(spread);
    }
}
