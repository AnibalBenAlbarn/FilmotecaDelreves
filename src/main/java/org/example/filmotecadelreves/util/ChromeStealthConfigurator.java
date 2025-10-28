package org.example.filmotecadelreves.util;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Helper class that configures {@link ChromeOptions} instances so they resemble a regular
 * Chrome session opened by a human. Several streaming providers block automation frameworks
 * such as Selenium. By applying a persistent profile, disabling automation flags and masking
 * the {@code navigator.webdriver} property we can interact with those sites more reliably.
 */
public final class ChromeStealthConfigurator {

    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.199 Safari/537.36";

    private ChromeStealthConfigurator() {
    }

    /**
     * Applies a set of arguments and preferences so the resulting browser looks closer to a
     * regular Chrome instance.
     *
     * @param options     chrome options to modify
     * @param profileName profile directory name inside {@code ChromeProfiles}
     * @param headless    whether the browser will run in headless mode
     */
    public static void applyHumanLikeDefaults(ChromeOptions options, String profileName, boolean headless) {
        if (options == null) {
            throw new IllegalArgumentException("ChromeOptions cannot be null");
        }

        Path profilePath = ensureProfileDirectory(profileName);
        options.addArguments("--user-data-dir=" + profilePath.toAbsolutePath());
        options.addArguments("--profile-directory=Default");

        List<String> arguments = new ArrayList<>();
        arguments.add("--no-sandbox");
        arguments.add("--disable-dev-shm-usage");
        arguments.add("--window-size=1920,1080");
        arguments.add("--disable-blink-features=AutomationControlled");
        arguments.add("--disable-popup-blocking");
        arguments.add("--disable-notifications");
        arguments.add("--disable-default-apps");
        arguments.add("--disable-features=IsolateOrigins,site-per-process");
        arguments.add("--lang=es-ES");
        arguments.add("--remote-allow-origins=*");
        arguments.add("--autoplay-policy=no-user-gesture-required");
        arguments.add("--mute-audio");
        arguments.add("--user-agent=" + DEFAULT_USER_AGENT);

        if (headless) {
            arguments.add("--headless=new");
        } else {
            arguments.add("--start-maximized");
        }

        for (String argument : arguments) {
            options.addArguments(argument);
        }

        Map<String, Object> prefs = new HashMap<>();
        prefs.put("credentials_enable_service", false);
        prefs.put("profile.password_manager_enabled", false);
        prefs.put("profile.default_content_setting_values.notifications", 2);
        prefs.put("profile.default_content_setting_values.popups", 2);
        prefs.put("profile.default_content_setting_values.automatic_downloads", 1);
        options.setExperimentalOption("prefs", prefs);

        options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation", "enable-logging"));
        options.setExperimentalOption("useAutomationExtension", false);
    }

    /**
     * Masks the {@code navigator.webdriver} flag for the provided driver instance.
     *
     * @param driver active Selenium driver
     */
    public static void maskAutomation(WebDriver driver) {
        if (driver instanceof JavascriptExecutor) {
            try {
                ((JavascriptExecutor) driver).executeScript(
                        "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");
            } catch (Exception e) {
                System.err.println("Unable to mask navigator.webdriver flag: " + e.getMessage());
            }
        }
    }

    private static Path ensureProfileDirectory(String profileName) {
        String sanitized = (profileName == null || profileName.isBlank())
                ? "default"
                : profileName.replaceAll("[^a-zA-Z0-9-_]", "_");

        Path profilePath = Paths.get("ChromeProfiles", sanitized);
        try {
            Files.createDirectories(profilePath);
        } catch (IOException e) {
            System.err.println("Unable to create Chrome profile directory " + profilePath + ": " + e.getMessage());
        }
        return profilePath;
    }
}

