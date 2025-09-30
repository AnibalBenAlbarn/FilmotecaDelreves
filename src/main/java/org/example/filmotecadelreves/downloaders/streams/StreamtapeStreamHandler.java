package org.example.filmotecadelreves.downloaders.streams;

import org.openqa.selenium.*;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Streamer dedicated to Streamtape. It installs the required extensions in a
 * deterministic order and presses the fullscreen button inside the video
 * player instead of relying on F11.
 */
public class StreamtapeStreamHandler extends AbstractStreamServerHandler {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(15);

    public StreamtapeStreamHandler(int serverId, String name, List<String> hostPatterns, List<String> extensionPaths) {
        super(serverId, name, hostPatterns, extensionPaths);
    }

    @Override
    public boolean useBrowserFullscreen() {
        return false;
    }

    @Override
    public void afterPageLoad(WebDriver driver) {
        try {
            if (!switchToStreamtapeFrame(driver)) {
                System.out.println("Streamtape: unable to locate player iframe");
                return;
            }

            WebDriverWait wait = new WebDriverWait(driver, WAIT_TIMEOUT);
            WebElement fullscreenButton = locateFullscreenButton(wait);

            if (fullscreenButton != null) {
                clickElement(driver, fullscreenButton);
                System.out.println("Streamtape fullscreen enabled using in-player control");
            } else {
                System.out.println("Streamtape: fullscreen button not found");
            }
        } catch (Exception e) {
            System.err.println("Streamtape: error while enabling fullscreen - " + e.getMessage());
        } finally {
            try {
                driver.switchTo().defaultContent();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean switchToStreamtapeFrame(WebDriver driver) {
        List<WebElement> frames = driver.findElements(By.tagName("iframe"));
        for (WebElement frame : frames) {
            String src = frame.getAttribute("src");
            if (src != null && src.contains("streamtape")) {
                driver.switchTo().frame(frame);
                return true;
            }
        }
        return false;
    }

    private WebElement locateFullscreenButton(WebDriverWait wait) {
        List<By> selectors = Arrays.asList(
                By.cssSelector("button[data-plyr='fullscreen']"),
                By.cssSelector("button[aria-label*='Full']"),
                By.cssSelector("button[title*='Full']"),
                By.cssSelector(".plyr__controls button[aria-label*='Full']"),
                By.cssSelector(".vjs-fullscreen-control"),
                By.cssSelector("button[class*='fullscreen']"),
                By.cssSelector("div[id^='player'] button")
        );

        for (By selector : selectors) {
            try {
                return wait.until(ExpectedConditions.elementToBeClickable(selector));
            } catch (TimeoutException ignored) {
            }
        }
        return null;
    }

    private void clickElement(WebDriver driver, WebElement element) {
        try {
            element.click();
        } catch (ElementClickInterceptedException | WebDriverException e) {
            try {
                new Actions(driver).moveToElement(element).click().perform();
            } catch (WebDriverException ignored) {
                if (driver instanceof JavascriptExecutor js) {
                    js.executeScript("arguments[0].click();", element);
                }
            }
        }
    }
}
