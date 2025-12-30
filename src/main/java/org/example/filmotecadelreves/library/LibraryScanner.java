package org.example.filmotecadelreves.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        return scanLibrary(libraryEntry, null).catalog();
    }

    public LibraryScanResult scanLibrary(LibraryEntry libraryEntry, LibraryCatalog existingCatalog) throws IOException {
        LibraryCatalog catalog = new LibraryCatalog();
        List<MediaItem> missingMovies = new ArrayList<>();
        List<EpisodeItem> missingEpisodes = new ArrayList<>();

        if (existingCatalog != null) {
            catalog.getMovies().addAll(existingCatalog.getMovies());
            catalog.getSeries().addAll(existingCatalog.getSeries());
        }

        Map<String, MediaItem> existingMoviesByPath = catalog.getMovies().stream()
                .filter(item -> item.getFilePath() != null)
                .collect(Collectors.toMap(MediaItem::getFilePath, item -> item, (first, second) -> first));

        Map<String, SeriesEntry> seriesByTitle = catalog.getSeries().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getTitle().toLowerCase(Locale.ROOT),
                        entry -> entry,
                        (first, second) -> first
                ));

        Map<String, EpisodeItem> existingEpisodesByPath = catalog.getSeries().stream()
                .flatMap(series -> series.getSeasons().values().stream().flatMap(List::stream))
                .filter(episode -> episode.getFilePath() != null)
                .collect(Collectors.toMap(EpisodeItem::getFilePath, episode -> episode, (first, second) -> first));

        Set<String> scannedMoviePaths = new HashSet<>();
        Set<String> scannedEpisodePaths = new HashSet<>();

        Path rootPath = Path.of(libraryEntry.getRootPath());
        if (!Files.exists(rootPath)) {
            return new LibraryScanResult(catalog, missingMovies, missingEpisodes);
        }

        try (Stream<Path> stream = Files.walk(rootPath)) {
            List<Path> videoFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(this::isVideoFile)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());

            if (libraryEntry.getType() == LibraryEntry.LibraryType.MOVIES) {
                for (Path file : videoFiles) {
                    String filePath = file.toString();
                    scannedMoviePaths.add(filePath);
                    if (!existingMoviesByPath.containsKey(filePath)) {
                        String title = cleanTitle(file.getFileName().toString());
                        String id = UUID.randomUUID().toString();
                        MediaItem item = new MediaItem(id, filePath, title);
                        catalog.getMovies().add(item);
                    }
                }
            } else {
                for (Path file : videoFiles) {
                    String seriesName = getSeriesName(rootPath, file);
                    String seriesKey = seriesName.toLowerCase(Locale.ROOT);
                    SeriesEntry seriesEntry = seriesByTitle.computeIfAbsent(seriesKey, key -> {
                        SeriesEntry newEntry = new SeriesEntry(UUID.randomUUID().toString(), seriesName);
                        catalog.getSeries().add(newEntry);
                        return newEntry;
                    });

                    String filePath = file.toString();
                    scannedEpisodePaths.add(filePath);
                    if (!existingEpisodesByPath.containsKey(filePath)) {
                        String fileName = file.getFileName().toString();
                        SeasonEpisode seasonEpisode = parseSeasonEpisode(fileName, file);
                        EpisodeItem episode = new EpisodeItem(
                                UUID.randomUUID().toString(),
                                filePath,
                                cleanTitle(fileName),
                                seasonEpisode.season(),
                                seasonEpisode.episode()
                        );
                        seriesEntry.addEpisode(episode);
                    }
                }
            }
        }

        for (MediaItem item : catalog.getMovies()) {
            if (item.getFilePath() != null && !scannedMoviePaths.contains(item.getFilePath())) {
                missingMovies.add(item);
            }
        }

        for (EpisodeItem episode : existingEpisodesByPath.values()) {
            if (episode.getFilePath() != null && !scannedEpisodePaths.contains(episode.getFilePath())) {
                missingEpisodes.add(episode);
            }
        }

        return new LibraryScanResult(catalog, missingMovies, missingEpisodes);
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
