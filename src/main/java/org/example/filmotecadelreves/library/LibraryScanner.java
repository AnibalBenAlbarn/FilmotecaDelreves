package org.example.filmotecadelreves.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LibraryScanner {
    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "mpeg", "mpg", "m4v"
    );
    private static final Pattern SEASON_EPISODE_PATTERN = Pattern.compile("(?i)S(\\d{1,2})E(\\d{1,2})");

    public LibraryCatalog scanLibrary(LibraryEntry libraryEntry) throws IOException {
        LibraryCatalog catalog = new LibraryCatalog();
        Path rootPath = Path.of(libraryEntry.getRootPath());
        if (!Files.exists(rootPath)) {
            return catalog;
        }
        try (Stream<Path> stream = Files.walk(rootPath)) {
            List<Path> videoFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isVideoFile)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
            if (libraryEntry.getType() == LibraryEntry.LibraryType.MOVIES) {
                for (Path file : videoFiles) {
                    String title = cleanTitle(file.getFileName().toString());
                    String id = UUID.randomUUID().toString();
                    MediaItem item = new MediaItem(id, file.toString(), title);
                    catalog.getMovies().add(item);
                }
            } else {
                for (Path file : videoFiles) {
                    String seriesName = getSeriesName(rootPath, file);
                    SeriesEntry seriesEntry = catalog.getSeries().stream()
                            .filter(series -> series.getTitle().equalsIgnoreCase(seriesName))
                            .findFirst()
                            .orElseGet(() -> {
                                SeriesEntry newEntry = new SeriesEntry(UUID.randomUUID().toString(), seriesName);
                                catalog.getSeries().add(newEntry);
                                return newEntry;
                            });
                    String fileName = file.getFileName().toString();
                    SeasonEpisode seasonEpisode = parseSeasonEpisode(fileName, file);
                    EpisodeItem episode = new EpisodeItem(
                            UUID.randomUUID().toString(),
                            file.toString(),
                            cleanTitle(fileName),
                            seasonEpisode.season(),
                            seasonEpisode.episode()
                    );
                    seriesEntry.addEpisode(episode);
                }
            }
        }
        return catalog;
    }

    private boolean isVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int index = fileName.lastIndexOf('.');
        if (index <= 0) {
            return false;
        }
        String extension = fileName.substring(index + 1);
        return VIDEO_EXTENSIONS.contains(extension);
    }

    private String cleanTitle(String fileName) {
        int index = fileName.lastIndexOf('.');
        String base = index > 0 ? fileName.substring(0, index) : fileName;
        String cleaned = base.replaceAll("[._]+", " ");
        cleaned = cleaned.replaceAll("[\\[\\(].*?[\\]\\)]", " ");
        cleaned = cleaned.replaceAll("(?i)\\b(\\d{3,4}p|x264|x265|h264|h265|hevc|aac|ac3|dts|hdr|hdrip|bdrip|brrip|blu\\s?ray|web\\s?dl|webrip|dvdrip|dvdscr|cam|ts|telesync|remux|subs?|multi|spanish|castellano|latino|espanol|dual|proper|repack|extended|unrated|limited|sample)\\b", " ");
        String withoutYear = cleaned.replaceAll("(?i)\\b\\d{4}\\b", " ");
        cleaned = withoutYear.replaceAll("[\\-]+", " ");
        cleaned = cleaned.replaceAll("\\s{2,}", " ").trim();
        if (cleaned.isBlank()) {
            cleaned = base.replaceAll("[._]+", " ").replaceAll("\\s{2,}", " ").trim();
        }
        return cleaned;
    }

    private String getSeriesName(Path rootPath, Path file) {
        Path relative = rootPath.relativize(file);
        if (relative.getNameCount() > 1) {
            return relative.getName(0).toString();
        }
        return cleanTitle(file.getFileName().toString());
    }

    private SeasonEpisode parseSeasonEpisode(String fileName, Path file) {
        Matcher matcher = SEASON_EPISODE_PATTERN.matcher(fileName);
        if (matcher.find()) {
            int season = Integer.parseInt(matcher.group(1));
            int episode = Integer.parseInt(matcher.group(2));
            return new SeasonEpisode(season, episode);
        }
        OptionalInt seasonFromFolder = extractSeasonFromFolders(file);
        int season = seasonFromFolder.orElse(1);
        return new SeasonEpisode(season, 0);
    }

    private OptionalInt extractSeasonFromFolders(Path file) {
        Set<Integer> detected = new HashSet<>();
        for (Path part : file) {
            String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.contains("season") || name.startsWith("s")) {
                String digits = name.replaceAll("[^0-9]", "");
                if (!digits.isBlank()) {
                    try {
                        detected.add(Integer.parseInt(digits));
                    } catch (NumberFormatException ignored) {
                        // ignore
                    }
                }
            }
        }
        return detected.stream().mapToInt(Integer::intValue).sorted().findFirst();
    }

    private record SeasonEpisode(int season, int episode) {
    }
}
