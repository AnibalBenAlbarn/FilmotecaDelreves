package org.example.filmotecadelreves.downloaders.streams;

import java.util.List;

/**
 * Fallback handler used when no specific server matches. It simply reuses the
 * legacy behaviour and is never selected explicitly by {@link #supports}.
 */
public class FallbackStreamServerHandler extends AbstractStreamServerHandler {

    public FallbackStreamServerHandler(String name, List<String> extensionPaths) {
        super(-1, name, List.of(), extensionPaths);
    }

    @Override
    public boolean supports(String url, int serverId) {
        return false;
    }
}
