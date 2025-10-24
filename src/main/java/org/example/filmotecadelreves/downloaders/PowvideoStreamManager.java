package org.example.filmotecadelreves.downloaders;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Stream manager that applies additional stealth tweaks required by powvideo streams.
 */
public class PowvideoStreamManager extends StealthVideosStreamerManager {

    public PowvideoStreamManager() {
        super(createConfigs());
    }

    private static List<ServerConfig> createConfigs() {
        LinkedHashSet<String> addons = new LinkedHashSet<>();

        Collections.addAll(addons, getPopupExtensionCandidates());
        addons.add(POPUP_EXTENSION_RELATIVE);
        addons.add(ADBLOCK_PATH);

        return Collections.singletonList(new ServerConfig(
                1,
                "powvideo.org",
                "powvideo.org",
                true,
                addons.toArray(new String[0])
        ));
    }

    @Override
    protected String getUserAgent(ServerConfig config) {
        // Slightly newer build helps powvideo avoid flagging the browser as outdated.
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/121.0.6167.140 Safari/537.36";
    }
}
