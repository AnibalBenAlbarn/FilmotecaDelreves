package org.example.filmotecadelreves.downloaders;

import java.util.HashMap;
import java.util.Map;

/**
 * Class to handle video streaming in full screen mode using Selenium.
 * Opens Chrome browser in full screen mode with the specified URL.
 * Closes when ESC key is pressed.
 */
public class VideoStream {
    private final VideosStreamerManager defaultStreamerManager;
    private final Map<Integer, VideosStreamerManager> serverManagers;
    private volatile boolean headless;

    /**
     * Initializes the VideoStream with Selenium WebDriver
     */
    public VideoStream() {
        this.defaultStreamerManager = new VideosStreamerManager();
        this.defaultStreamerManager.setHeadless(headless);
        this.serverManagers = new HashMap<>();
        registerManager(1, new PowvideoStreamManager());
        registerManager(21, new StreamplayStreamManager());
        registerManager(497, new StreamtapeStreamManager());
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
        defaultStreamerManager.setHeadless(headless);
        for (VideosStreamerManager manager : serverManagers.values()) {
            if (manager != null) {
                manager.setHeadless(headless);
            }
        }
    }

    /**
     * Opens a URL in Chrome in full screen mode
     * @param url The URL to stream
     */
    public void stream(String url) {
        VideosStreamerManager manager = selectManager(url, null);
        int serverId = resolveServerId(manager, url);
        manager.streamVideo(url, serverId);
    }

    /**
     * Opens a URL in Chrome in full screen mode with specific server ID
     * @param url The URL to stream
     * @param serverId The server ID
     */
    public void stream(String url, int serverId) {
        VideosStreamerManager manager = selectManager(url, serverId);
        manager.streamVideo(url, serverId);
    }

    private void registerManager(int serverId, VideosStreamerManager manager) {
        if (manager != null) {
            manager.setHeadless(headless);
            serverManagers.put(serverId, manager);
        }
    }

    private VideosStreamerManager selectManager(String url, Integer serverId) {
        if (serverId != null) {
            VideosStreamerManager manager = serverManagers.get(serverId);
            if (manager != null) {
                return manager;
            }
        }

        if (url != null) {
            for (Map.Entry<Integer, VideosStreamerManager> entry : serverManagers.entrySet()) {
                VideosStreamerManager manager = entry.getValue();
                if (manager != null && manager.handlesUrl(url)) {
                    return manager;
                }
            }
        }

        return defaultStreamerManager;
    }

    private int resolveServerId(VideosStreamerManager manager, String url) {
        if (manager == null || manager == defaultStreamerManager) {
            return -1;
        }

        for (Map.Entry<Integer, VideosStreamerManager> entry : serverManagers.entrySet()) {
            if (entry.getValue() == manager) {
                return entry.getKey();
            }
        }

        if (url != null && manager.handlesUrl(url)) {
            for (Map.Entry<Integer, VideosStreamerManager> entry : serverManagers.entrySet()) {
                VideosStreamerManager candidate = entry.getValue();
                if (candidate != null && candidate.handlesUrl(url)) {
                    return entry.getKey();
                }
            }
        }

        return -1;
    }
}