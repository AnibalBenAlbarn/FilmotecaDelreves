package org.example.filmotecadelreves.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utility class responsible for normalizing media URLs obtained from the database.
 * Some providers (e.g. PowVideo, StreamPlay) expose embedded URLs that need to be converted
 * to their canonical form before being used for streaming or downloading.
 */
public final class UrlNormalizer {
    private static final Pattern SIZE_SUFFIX = Pattern.compile("-(\\d+)x(\\d+)(?=\\.html$)", Pattern.CASE_INSENSITIVE);

    private UrlNormalizer() {
        // Utility class
    }

    /**
     * Normalizes URLs from providers that rely on embedded URLs (PowVideo, StreamPlay, etc.).
     *
     * @param url Raw URL retrieved from the database or UI selections.
     * @return A normalized URL without embed prefixes or size suffixes when applicable.
     */
    public static String normalizeMediaUrl(String url) {
        if (url == null) {
            return null;
        }

        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!lower.contains("powvideo") && !lower.contains("streamplay")) {
            return trimmed;
        }

        String query = "";
        int queryIndex = trimmed.indexOf('?');
        if (queryIndex >= 0) {
            query = trimmed.substring(queryIndex);
            trimmed = trimmed.substring(0, queryIndex);
        }

        String normalized = trimmed
                .replaceFirst("(?i)/embed-", "/")
                .replaceFirst("(?i)/iframe-", "/");

        normalized = SIZE_SUFFIX.matcher(normalized).replaceFirst("");
        normalized = normalized.replaceFirst("(?i)\\.html$", "");

        if (normalized.isEmpty()) {
            return url;
        }

        return normalized + query;
    }
}
