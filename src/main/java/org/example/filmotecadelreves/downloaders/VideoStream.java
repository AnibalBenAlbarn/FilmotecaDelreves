package org.example.filmotecadelreves.downloaders;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.interactions.Actions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;

import java.io.File;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Class to handle video streaming in full screen mode using Selenium.
 * Opens Chrome browser in full screen mode with the specified URL.
 * Closes when ESC key is pressed.
 */
public class VideoStream {
    private VideosStreamerManager streamerManager;

    /**
     * Initializes the VideoStream with Selenium WebDriver
     */
    public VideoStream() {
        this.streamerManager = new VideosStreamerManager();
    }

    /**
     * Opens a URL in Chrome in full screen mode
     * @param url The URL to stream
     */
    public void stream(String url) {
        // Use the new VideosStreamerManager with default server ID (-1)
        // The manager will detect the server type from the URL
        streamerManager.streamVideo(url, -1);
    }

    /**
     * Opens a URL in Chrome in full screen mode with specific server ID
     * @param url The URL to stream
     * @param serverId The server ID
     */
    public void stream(String url, int serverId) {
        streamerManager.streamVideo(url, serverId);
    }
}