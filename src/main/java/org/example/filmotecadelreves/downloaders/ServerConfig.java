package org.example.filmotecainvertida.downloaders;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration class for streaming servers.
 * Stores server information and addon requirements.
 */
public class ServerConfig {
    private final int id;
    private final String name;
    private final String urlPattern;
    private final List<String> addons;
    private final boolean useFullscreen;

    /**
     * Creates a new server configuration.
     *
     * @param id Server ID
     * @param name Server name
     * @param urlPattern URL pattern to match
     * @param useFullscreen Whether to use fullscreen mode
     * @param addons List of addon paths to use
     */
    public ServerConfig(int id, String name, String urlPattern, boolean useFullscreen, String... addons) {
        this.id = id;
        this.name = name;
        this.urlPattern = urlPattern;
        this.useFullscreen = useFullscreen;
        this.addons = Arrays.asList(addons);
    }

    public int getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public List<String> getAddons() {
        return addons;
    }

    public boolean useFullscreen() {
        return useFullscreen;
    }

    /**
     * Checks if a URL matches this server's pattern.
     *
     * @param url URL to check
     * @return true if the URL matches this server's pattern
     */
    public boolean matchesUrl(String url) {
        return url != null && url.toLowerCase().contains(urlPattern.toLowerCase());
    }
}