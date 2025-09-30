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

    private static final String STREAMTAPE_EXTENSION_WINDOWS =
            "C:\\Users\\Anibal\\IdeaProjects\\FilmotecaDelreves\\Extension\\StreamtapeDownloader.crx";
    private static final String STREAMTAPE_EXTENSION_RELATIVE = "Extension/StreamtapeDownloader.crx";

    public StreamtapeStreamManager() {
        super(createConfigs());
    }

    private static List<ServerConfig> createConfigs() {
        List<ServerConfig> configs = new ArrayList<>();

        List<String> addons = new ArrayList<>();
        addons.add(POPUP_BLOCKER_PATH);
        addons.add(STREAMTAPE_EXTENSION_WINDOWS);
        addons.add(STREAMTAPE_EXTENSION_RELATIVE);

        configs.add(new ServerConfig(
                497,
                "streamtape.com",
                "streamtape.com",
                true,
                addons.toArray(new String[0])
        ));

        return configs;
    }
}
