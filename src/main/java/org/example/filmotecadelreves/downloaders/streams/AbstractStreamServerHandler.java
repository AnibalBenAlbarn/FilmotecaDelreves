package org.example.filmotecadelreves.downloaders.streams;

import org.openqa.selenium.chrome.ChromeOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base implementation that handles url/serverId matching and extension
 * installation ordering.
 */
public abstract class AbstractStreamServerHandler implements StreamServerHandler {

    private final int serverId;
    private final String name;
    private final List<String> hostPatterns;
    private final List<String> extensionPaths;

    protected AbstractStreamServerHandler(int serverId, String name, List<String> hostPatterns, List<String> extensionPaths) {
        this.serverId = serverId;
        this.name = name;
        this.hostPatterns = hostPatterns == null ? Collections.emptyList() : new ArrayList<>(hostPatterns);
        this.extensionPaths = extensionPaths == null ? Collections.emptyList() : new ArrayList<>(extensionPaths);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean supports(String url, int requestedServerId) {
        if (serverId == requestedServerId && serverId != -1) {
            return hostPatterns.isEmpty() || matchesUrl(url);
        }

        if (matchesUrl(url)) {
            return true;
        }

        return false;
    }

    @Override
    public void configure(ChromeOptions options) {
        for (String extensionPath : extensionPaths) {
            addExtension(options, extensionPath);
        }
    }

    protected boolean matchesUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        for (String pattern : hostPatterns) {
            if (pattern != null && !pattern.isBlank() && url.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    protected void addExtension(ChromeOptions options, String path) {
        if (path == null || path.isBlank()) {
            return;
        }

        String normalizedPath = path.replace("\\", File.separator);
        File extension = new File(normalizedPath);

        if (!extension.exists()) {
            int driveSeparator = normalizedPath.indexOf(':');
            if (driveSeparator > -1 && driveSeparator + 1 < normalizedPath.length()) {
                String withoutDrive = normalizedPath.substring(driveSeparator + 1);
                while (withoutDrive.startsWith(File.separator)) {
                    withoutDrive = withoutDrive.substring(1);
                }
                extension = new File(withoutDrive);
            }
        }

        if (extension.exists()) {
            options.addExtensions(extension);
            System.out.println("Added extension: " + extension.getAbsolutePath());
        } else {
            System.out.println("Warning: Extension not found at: " + path);
        }
    }
}
