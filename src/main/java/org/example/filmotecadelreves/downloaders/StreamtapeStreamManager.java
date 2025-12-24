package org.example.filmotecadelreves.downloaders;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialized {@link VideosStreamerManager} for Streamtape streams.
 *
 * <p>This manager ensures that the custom Streamtape downloader extension is installed before
 * navigating to the video URL. The class keeps the browser instance isolated by relying on the
 * base manager behaviour, which now uses a dedicated user data directory instead of closing every
 * Chrome instance on the system.</p>
 */
public class StreamtapeStreamManager extends VideosStreamerManager {

    public StreamtapeStreamManager() {
        super(createConfigs());
    }

    @Override
    protected String prepareUrlForStreaming(String url, ServerConfig config) {
        String prepared = super.prepareUrlForStreaming(url, config);
        if (prepared == null) {
            return null;
        }

        boolean isStreamtapeConfig = false;
        if (config != null) {
            String pattern = config.getUrlPattern();
            if (pattern != null) {
                isStreamtapeConfig = pattern.toLowerCase().contains("streamtape");
            }
        }

        if (!isStreamtapeConfig && !prepared.toLowerCase().contains("streamtape")) {
            return prepared;
        }

        if (prepared.contains("/e/")) {
            return prepared.replace("/e/", "/v/");
        }

        return prepared;
    }

    private static List<ServerConfig> createConfigs() {
        List<ServerConfig> configs = new ArrayList<>();

        List<String> addons = buildStreamtapeAddons();

        configs.add(new ServerConfig(
                497,
                "streamtape.com",
                "streamtape.com",
                true,
                addons.toArray(new String[0])
        ));

        return configs;
    }

    private static List<String> buildStreamtapeAddons() {
        List<String> addons = new ArrayList<>();

        boolean popupFound = false;
        for (String candidate : getPopupExtensionCandidates()) {
            popupFound = addIfExists(addons, candidate) | popupFound;
        }
        if (!popupFound) {
            addIfMissing(addons, POPUP_EXTENSION_RELATIVE);
        }

        boolean packagedFound = false;
        for (String candidate : getStreamtapePackagedCandidates()) {
            packagedFound = addIfExists(addons, candidate) | packagedFound;
        }

        if (!packagedFound) {
            // Keep the relative path as a fallback to show a clear warning when missing.
            addIfMissing(addons, STREAMTAPE_EXTENSION_RELATIVE);
        }

        if (addons.isEmpty()) {
            addIfMissing(addons, STREAMTAPE_EXTENSION_RELATIVE);
        }

        return addons;
    }

    private static boolean addIfExists(List<String> addons, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        File candidate = new File(path);
        if (candidate.exists()) {
            if (!addons.contains(path)) {
                addons.add(path);
            }
            return true;
        }

        return false;
    }

    private static void addIfMissing(List<String> addons, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }

        if (!addons.contains(path)) {
            addons.add(path);
        }
    }
}
