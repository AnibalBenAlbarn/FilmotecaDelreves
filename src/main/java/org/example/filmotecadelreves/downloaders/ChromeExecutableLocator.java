package org.example.filmotecadelreves.downloaders;

import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Utility class that resolves the Chrome binary and driver paths used by Selenium.
 * <p>
 * The resolver looks for explicit overrides first (JVM property, environment variable
 * or config.json entry) and falls back to common installation locations. When no
 * override can be located, the packaged binary/driver paths provided by the callers
 * are returned.
 */
final class ChromeExecutableLocator {

    private static final String BROWSER_PROPERTY = "filmoteca.browserPath";
    private static final String DRIVER_PROPERTY = "filmoteca.chromeDriver";
    private static final String[] CONFIG_BROWSER_KEYS = {
            "userBrowserPath",
            "userChromePath",
            "chromeBinary",
            "chromePath",
            "browserPath"
    };
    private static final String[] CONFIG_DRIVER_KEYS = {
            "userChromeDriver",
            "chromeDriverPath",
            "webdriverPath"
    };
    private static final String[] ENV_BROWSER_KEYS = {
            "FILMOTECA_BROWSER_PATH",
            "CHROME_BINARY",
            "GOOGLE_CHROME_SHIM",
            "CHROME_PATH"
    };
    private static final String[] ENV_DRIVER_KEYS = {
            "FILMOTECA_CHROMEDRIVER_PATH",
            "CHROMEDRIVER",
            "CHROME_DRIVER_PATH"
    };

    private static final Object CONFIG_LOCK = new Object();
    private static volatile boolean configLoaded = false;
    private static volatile String configuredBrowserPath;
    private static volatile String configuredDriverPath;

    private static volatile String cachedBrowserPath;
    private static volatile String cachedDriverPath;

    private ChromeExecutableLocator() {
        // Utility class
    }

    /**
     * Resolves the Chrome binary path to use. When no override can be located the
     * provided packaged path is returned (even if it does not exist) so callers can
     * decide how to handle the fallback scenario.
     */
    static String resolveChromeBinary(String packagedBinary) {
        if (cachedBrowserPath != null) {
            return cachedBrowserPath;
        }

        List<String> candidates = new ArrayList<>();

        addIfPresent(candidates, System.getProperty(BROWSER_PROPERTY));
        addIfPresent(candidates, System.getProperty("webdriver.chrome.binary"));

        loadConfigOverrides();
        addIfPresent(candidates, configuredBrowserPath);

        for (String envKey : ENV_BROWSER_KEYS) {
            addIfPresent(candidates, System.getenv(envKey));
        }

        candidates.addAll(getDefaultBrowserCandidates());
        addIfPresent(candidates, packagedBinary);

        cachedBrowserPath = firstExistingExecutable(candidates);
        if (cachedBrowserPath == null && packagedBinary != null) {
            Path packagedPath = normalizeToPath(packagedBinary);
            if (packagedPath != null && Files.isRegularFile(packagedPath) && Files.isExecutable(packagedPath)) {
                cachedBrowserPath = packagedPath.toString();
            }
        }
        return cachedBrowserPath;
    }

    /**
     * Resolves the Chrome binary to the provided packaged path only.
     * <p>
     * This helper is intended for download flows that need the bundled Chrome
     * (to allow CRX installation) regardless of any user-installed browser.
     * If the packaged binary does not exist or is not executable, {@code null}
     * is returned so callers can decide how to proceed.
     */
    static String resolvePackagedChromeBinary(String packagedBinary) {
        Path packagedPath = normalizeToPath(packagedBinary);
        if (packagedPath != null && Files.isRegularFile(packagedPath) && Files.isExecutable(packagedPath)) {
            return packagedPath.toString();
        }
        return null;
    }

    /**
     * Resolves the ChromeDriver executable path. If no override exists and none of the provided
     * packaged drivers exist, {@code null} is returned so Selenium Manager can download a matching
     * version automatically.
     */
    static String resolveChromeDriver(String... packagedDrivers) {
        if (cachedDriverPath != null) {
            return cachedDriverPath;
        }

        List<String> candidates = new ArrayList<>();

        addIfPresent(candidates, System.getProperty(DRIVER_PROPERTY));
        addIfPresent(candidates, System.getProperty("webdriver.chrome.driver"));

        loadConfigOverrides();
        addIfPresent(candidates, configuredDriverPath);

        for (String envKey : ENV_DRIVER_KEYS) {
            addIfPresent(candidates, System.getenv(envKey));
        }

        if (packagedDrivers != null) {
            for (String packagedDriver : packagedDrivers) {
                addIfPresent(candidates, packagedDriver);
            }
        }

        cachedDriverPath = firstExistingExecutable(candidates);
        if (cachedDriverPath == null) {
            cachedDriverPath = null; // Explicit to emphasise the fallback behaviour
        }

        return cachedDriverPath;
    }

    private static void loadConfigOverrides() {
        if (configLoaded) {
            return;
        }

        synchronized (CONFIG_LOCK) {
            if (configLoaded) {
                return;
            }

            Path configPath = Paths.get("config.json");
            if (Files.isRegularFile(configPath)) {
                try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                    Object parsed = new JSONParser().parse(reader);
                    if (parsed instanceof JSONObject jsonObject) {
                        configuredBrowserPath = firstStringValue(jsonObject, CONFIG_BROWSER_KEYS);
                        configuredDriverPath = firstStringValue(jsonObject, CONFIG_DRIVER_KEYS);
                    }
                } catch (IOException | org.json.simple.parser.ParseException ignored) {
                    // Ignore malformed configuration entries silently; callers will fall back to defaults
                }
            }

            configLoaded = true;
        }
    }

    private static String firstStringValue(JSONObject jsonObject, String[] keys) {
        return Arrays.stream(keys)
                .map(jsonObject::get)
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .findFirst()
                .orElse(null);
    }

    private static List<String> getDefaultBrowserCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> defaults = new ArrayList<>();

        if (os.contains("win")) {
            String programFiles = System.getenv("PROGRAMFILES");
            String programFilesX86 = System.getenv("PROGRAMFILES(X86)");
            String localAppData = System.getenv("LOCALAPPDATA");

            defaults.add(join(programFiles, "Google", "Chrome", "Application", "chrome.exe"));
            defaults.add(join(programFilesX86, "Google", "Chrome", "Application", "chrome.exe"));
            defaults.add(join(localAppData, "Google", "Chrome", "Application", "chrome.exe"));
            defaults.add(join(localAppData, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
            defaults.add(join(programFiles, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"));
        } else if (os.contains("mac")) {
            defaults.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            defaults.add("/Applications/Brave Browser.app/Contents/MacOS/Brave Browser");
            defaults.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            defaults.add("/usr/bin/google-chrome");
            defaults.add("/usr/bin/chromium-browser");
            defaults.add("/usr/bin/chromium");
            defaults.add("/snap/bin/chromium");
            defaults.add("/usr/bin/brave-browser");
        }

        return defaults;
    }

    private static String firstExistingExecutable(List<String> candidates) {
        for (String candidate : candidates) {
            Path normalized = normalizeToPath(candidate);
            if (normalized != null && Files.isRegularFile(normalized) && Files.isExecutable(normalized)) {
                return normalized.toString();
            }
        }
        return null;
    }

    private static void addIfPresent(List<String> candidates, String value) {
        if (value != null) {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                candidates.add(trimmed);
            }
        }
    }

    private static String join(String first, String... more) {
        if (first == null || first.isBlank()) {
            return null;
        }
        return Paths.get(first, more).toString();
    }

    private static Path normalizeToPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        return Paths.get(path.trim()).toAbsolutePath().normalize();
    }

    private static String normalize(String path) {
        Path normalized = normalizeToPath(path);
        return normalized != null ? normalized.toString() : null;
    }
}
