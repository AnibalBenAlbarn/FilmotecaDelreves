package org.example.filmotecadelreves.downloaders;

import java.io.File;
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

        boolean hasUnpacked = false;
        for (String candidate : getStreamtapeUnpackedCandidates()) {
            File unpackedDir = new File(candidate);
            if (unpackedDir.isDirectory()) {
                String absolutePath = unpackedDir.getAbsolutePath();
                if (!addons.contains(absolutePath)) {
                    addons.add(absolutePath);
                }
                hasUnpacked = true;
            }
        }

        if (hasUnpacked) {
            for (String candidate : getStreamtapePackagedCandidates()) {
                addIfExists(addons, candidate);
            }
        } else {
            boolean packagedFound = false;
            for (String candidate : getStreamtapePackagedCandidates()) {
                packagedFound = addIfExists(addons, candidate) | packagedFound;
            }

            if (!packagedFound) {
                // Keep the relative path as a fallback to show a clear warning when missing.
                addIfMissing(addons, STREAMTAPE_EXTENSION_RELATIVE);
            }
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
