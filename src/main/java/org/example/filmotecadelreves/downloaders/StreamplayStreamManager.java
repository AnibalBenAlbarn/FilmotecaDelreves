package org.example.filmotecadelreves.downloaders;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Stream manager specialised in handling Streamplay streams with a browser configuration that
 * emulates real user behaviour to avoid anti-bot protections.
 */
public class StreamplayStreamManager extends StealthVideosStreamerManager {

    public StreamplayStreamManager() {
        super(createConfigs());
    }

    private static List<ServerConfig> createConfigs() {
        LinkedHashSet<String> addons = new LinkedHashSet<>();

        Collections.addAll(addons, getPopupExtensionCandidates());
        addons.add(POPUP_EXTENSION_RELATIVE);
        addons.add(ADBLOCK_PATH);

        return Collections.singletonList(new ServerConfig(
                21,
                "streamplay.to",
                "streamplay.to",
                true,
                addons.toArray(new String[0])
        ));
    }

    @Override
    protected String getUserAgent(ServerConfig config) {
        // Keep Streamplay on a slightly older stable build to avoid unexpected layout changes.
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/119.0.6045.200 Safari/537.36";
    }
}
