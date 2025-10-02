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

    private static final String STREAMTAPE_EXTENSION_WINDOWS =
            "C\\\\Users\\\\Anibal\\\\IdeaProjects\\\\FilmotecaDelreves\\\\Extension\\\\StreamtapeDownloader.crx";
    private static final String STREAMTAPE_EXTENSION_RELATIVE = "Extension/StreamtapeDownloader.crx";
    private static final String[] STREAMTAPE_EXTENSION_UNPACKED_CANDIDATES = {
            "Extension/streamtape-extension-master",
            "Extension/StreamtapeDownloader"
    };

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

        boolean hasUnpacked = false;
        for (String candidate : STREAMTAPE_EXTENSION_UNPACKED_CANDIDATES) {
            File unpackedDir = new File(candidate);
            if (unpackedDir.isDirectory()) {
                addons.add(candidate);
                hasUnpacked = true;
            }
        }

        if (hasUnpacked) {
            addIfExists(addons, STREAMTAPE_EXTENSION_WINDOWS);
        } else {
            boolean packagedFound = addIfExists(addons, STREAMTAPE_EXTENSION_RELATIVE)
                    | addIfExists(addons, STREAMTAPE_EXTENSION_WINDOWS);

            if (!packagedFound) {
                // Keep the relative path as a fallback to show a clear warning when missing.
                addons.add(STREAMTAPE_EXTENSION_RELATIVE);
            }
        }

        if (addons.isEmpty()) {
            addons.add(STREAMTAPE_EXTENSION_RELATIVE);
        }

        return addons;
    }

    private static boolean addIfExists(List<String> addons, String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }

        File candidate = new File(path);
        if (candidate.exists()) {
            addons.add(path);
            return true;
        }

        return false;
    }
}
