package org.example.filmotecadelreves.downloaders.streams;

import java.util.List;

/**
 * Generic handler that keeps the legacy behaviour (sending F11) for servers
 * that do not require special treatment.
 */
public class DefaultStreamServerHandler extends AbstractStreamServerHandler {

    public DefaultStreamServerHandler(int serverId, String name, List<String> hostPatterns, List<String> extensionPaths) {
        super(serverId, name, hostPatterns, extensionPaths);
    }

    public DefaultStreamServerHandler(String name, List<String> hostPatterns, List<String> extensionPaths) {
        this(-1, name, hostPatterns, extensionPaths);
    }

    @Override
    public boolean supports(String url, int serverId) {
        if (getName() == null || getName().isBlank()) {
            return false;
        }
        return super.supports(url, serverId);
    }
}
