package org.example.filmotecadelreves.library;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MetadataScraper {
    private static final Logger LOGGER = Logger.getLogger(MetadataScraper.class.getName());
    public enum Provider {
        TMDB,
        OMDB,
        TVMAZE,
        TRAKT,
        JUSTWATCH
    }

    private static final String TMDB_IMAGE_BASE = "https://image.tmdb.org/t/p/w500";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public MediaMetadata fetchMovie(String title, Provider provider, String apiKey) throws IOException, InterruptedException, ParseException {
        List<String> candidates = buildSearchCandidates(title);
        for (String candidate : candidates) {
            MediaMetadata metadata = switch (provider) {
                case TMDB -> fetchTmdbMovie(candidate, apiKey);
                case OMDB -> fetchOmdb(candidate, "movie", apiKey);
                case TVMAZE -> null;
                default -> null;
            };
            if (metadata != null) {
                return metadata;
            }
        }
        return null;
    }

    public MediaMetadata fetchSeries(String title, Provider provider, String apiKey) throws IOException, InterruptedException, ParseException {
        List<String> candidates = buildSearchCandidates(title);
        for (String candidate : candidates) {
            MediaMetadata metadata = switch (provider) {
                case TMDB -> fetchTmdbSeries(candidate, apiKey);
                case OMDB -> fetchOmdb(candidate, "series", apiKey);
                case TVMAZE -> fetchTvMaze(candidate);
                default -> null;
            };
            if (metadata != null) {
                return metadata;
            }
        }
        return null;
    }

    public void downloadImage(String url, Path target) throws IOException, InterruptedException {
        if (url == null || url.isBlank()) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "image/*")
                .GET()
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            Files.createDirectories(target.getParent());
            Files.write(target, response.body());
        }
    }

    private MediaMetadata fetchTmdbMovie(String title, String token) throws IOException, InterruptedException, ParseException {
        TmdbAuth auth = resolveTmdbAuth(token);
        String url = "https://api.themoviedb.org/3/search/movie?language=es-ES&query=" +
                URLEncoder.encode(title, StandardCharsets.UTF_8);
        JSONObject root = getJsonWithAuth(url, auth);
        JSONArray results = (JSONArray) root.get("results");
        if (results == null || results.isEmpty()) {
            return null;
        }
        JSONObject top = selectBestTmdbResult(results, title, "title");
        Long id = (Long) top.get("id");
        String movieTitle = (String) top.get("title");
        String releaseDate = (String) top.getOrDefault("release_date", "");
        Integer year = parseYear(releaseDate);
        String overview = (String) top.getOrDefault("overview", "");
        String posterPath = (String) top.get("poster_path");
        String posterUrl = posterPath == null ? null : TMDB_IMAGE_BASE + posterPath;

        List<String> genres = new ArrayList<>();
        String director = null;
        if (id != null) {
            JSONObject details = getJsonWithAuth("https://api.themoviedb.org/3/movie/" + id + "?language=es-ES&append_to_response=credits", auth);
            JSONArray genreArray = (JSONArray) details.get("genres");
            if (genreArray != null) {
                for (Object obj : genreArray) {
                    JSONObject genre = (JSONObject) obj;
                    genres.add(String.valueOf(genre.get("name")));
                }
            }
            JSONObject credits = (JSONObject) details.get("credits");
            if (credits != null) {
                JSONArray crew = (JSONArray) credits.get("crew");
                if (crew != null) {
                    for (Object obj : crew) {
                        JSONObject crewMember = (JSONObject) obj;
                        if ("Director".equalsIgnoreCase(String.valueOf(crewMember.get("job")))) {
                            director = String.valueOf(crewMember.get("name"));
                            break;
                        }
                    }
                }
            }
        }
        return new MediaMetadata("TMDB", "movie", movieTitle, year, overview, posterUrl, director, genres);
    }

    private MediaMetadata fetchTmdbSeries(String title, String token) throws IOException, InterruptedException, ParseException {
        TmdbAuth auth = resolveTmdbAuth(token);
        String url = "https://api.themoviedb.org/3/search/tv?language=es-ES&query=" +
                URLEncoder.encode(title, StandardCharsets.UTF_8);
        JSONObject root = getJsonWithAuth(url, auth);
        JSONArray results = (JSONArray) root.get("results");
        if (results == null || results.isEmpty()) {
            return null;
        }
        JSONObject top = selectBestTmdbResult(results, title, "name");
        Long id = (Long) top.get("id");
        String name = (String) top.get("name");
        String firstAir = (String) top.getOrDefault("first_air_date", "");
        Integer year = parseYear(firstAir);
        String overview = (String) top.getOrDefault("overview", "");
        String posterPath = (String) top.get("poster_path");
        String posterUrl = posterPath == null ? null : TMDB_IMAGE_BASE + posterPath;

        List<String> genres = new ArrayList<>();
        if (id != null) {
            JSONObject details = getJsonWithAuth("https://api.themoviedb.org/3/tv/" + id + "?language=es-ES", auth);
            JSONArray genreArray = (JSONArray) details.get("genres");
            if (genreArray != null) {
                for (Object obj : genreArray) {
                    JSONObject genre = (JSONObject) obj;
                    genres.add(String.valueOf(genre.get("name")));
                }
            }
        }
        return new MediaMetadata("TMDB", "tv", name, year, overview, posterUrl, null, genres);
    }

    private MediaMetadata fetchOmdb(String title, String type, String apiKey) throws IOException, InterruptedException, ParseException {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String url = "https://www.omdbapi.com/?apikey=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8) +
                "&t=" + URLEncoder.encode(title, StandardCharsets.UTF_8) +
                "&type=" + URLEncoder.encode(type, StandardCharsets.UTF_8) +
                "&plot=short&r=json";
        JSONObject root = getJson(url);
        if (!"True".equalsIgnoreCase(String.valueOf(root.get("Response")))) {
            return null;
        }
        String name = (String) root.get("Title");
        Integer year = parseYear(String.valueOf(root.get("Year")));
        String overview = (String) root.get("Plot");
        String poster = (String) root.get("Poster");
        String director = (String) root.get("Director");
        List<String> genres = new ArrayList<>();
        String genreText = (String) root.get("Genre");
        if (genreText != null) {
            for (String genre : genreText.split(",")) {
                genres.add(genre.trim());
            }
        }
        String posterUrl = "N/A".equalsIgnoreCase(poster) ? null : poster;
        return new MediaMetadata("OMDb", type, name, year, overview, posterUrl, director, genres);
    }

    private MediaMetadata fetchTvMaze(String title) throws IOException, InterruptedException, ParseException {
        String url = "https://api.tvmaze.com/singlesearch/shows?q=" + URLEncoder.encode(title, StandardCharsets.UTF_8);
        JSONObject root = getJson(url);
        String name = (String) root.get("name");
        String premiered = (String) root.getOrDefault("premiered", "");
        Integer year = parseYear(premiered);
        String summary = (String) root.get("summary");
        String overview = summary == null ? null : summary.replaceAll("<[^>]+>", "").trim();
        JSONObject image = (JSONObject) root.get("image");
        String posterUrl = null;
        if (image != null) {
            posterUrl = (String) image.get("medium");
        }
        return new MediaMetadata("TVmaze", "tv", name, year, overview, posterUrl, null, List.of());
    }

    public List<MetadataSearchResult> searchMovies(String title, Integer year, String director, String genre,
                                                   Provider provider, String apiKey)
            throws IOException, InterruptedException, ParseException {
        if (provider != Provider.TMDB) {
            return List.of();
        }
        return searchTmdb(title, year, director, genre, apiKey, "movie");
    }

    public List<MetadataSearchResult> searchSeries(String title, Integer year, String director, String genre,
                                                   Provider provider, String apiKey)
            throws IOException, InterruptedException, ParseException {
        if (provider != Provider.TMDB) {
            return List.of();
        }
        return searchTmdb(title, year, director, genre, apiKey, "tv");
    }

    public MediaMetadata fetchTmdbById(long id, String type, String token) throws IOException, InterruptedException, ParseException {
        TmdbAuth auth = resolveTmdbAuth(token);
        String detailsUrl = "https://api.themoviedb.org/3/" + ("tv".equalsIgnoreCase(type) ? "tv" : "movie") + "/" + id +
                "?language=es-ES&append_to_response=credits";
        JSONObject details = getJsonWithAuth(detailsUrl, auth);
        String titleKey = "tv".equalsIgnoreCase(type) ? "name" : "title";
        String dateKey = "tv".equalsIgnoreCase(type) ? "first_air_date" : "release_date";
        String title = (String) details.get(titleKey);
        String date = (String) details.getOrDefault(dateKey, "");
        Integer year = parseYear(date);
        String overview = (String) details.getOrDefault("overview", "");
        String posterPath = (String) details.get("poster_path");
        String posterUrl = posterPath == null ? null : TMDB_IMAGE_BASE + posterPath;

        List<String> genres = new ArrayList<>();
        JSONArray genreArray = (JSONArray) details.get("genres");
        if (genreArray != null) {
            for (Object obj : genreArray) {
                JSONObject genre = (JSONObject) obj;
                genres.add(String.valueOf(genre.get("name")));
            }
        }

        String director = null;
        JSONObject credits = (JSONObject) details.get("credits");
        if (credits != null && "movie".equalsIgnoreCase(type)) {
            JSONArray crew = (JSONArray) credits.get("crew");
            if (crew != null) {
                for (Object obj : crew) {
                    JSONObject crewMember = (JSONObject) obj;
                    if ("Director".equalsIgnoreCase(String.valueOf(crewMember.get("job")))) {
                        director = String.valueOf(crewMember.get("name"));
                        break;
                    }
                }
            }
        }
        return new MediaMetadata("TMDB", type, title, year, overview, posterUrl, director, genres);
    }

    private List<String> buildSearchCandidates(String title) {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        String trimmed = title.trim();
        List<String> candidates = new ArrayList<>();
        candidates.add(trimmed);
        String withoutYear = trimmed.replaceAll("(?i)\\b\\d{4}\\b", " ").replaceAll("\\s{2,}", " ").trim();
        if (!withoutYear.isBlank() && !withoutYear.equals(trimmed)) {
            candidates.add(withoutYear);
        }
        String normalized = normalizeText(trimmed);
        if (!normalized.equalsIgnoreCase(trimmed) && !normalized.isBlank()) {
            candidates.add(normalized);
        }
        String shortTitle = trimmed.replaceAll("(?i)\\b(1080p|720p|480p|x264|x265|h264|h265|bluray|bdrip|webrip|webdl|dvdrip)\\b", " ")
                .replaceAll("\\s{2,}", " ").trim();
        if (!shortTitle.isBlank() && !candidates.contains(shortTitle)) {
            candidates.add(shortTitle);
        }
        return candidates.stream().distinct().toList();
    }

    private String normalizeText(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        normalized = normalized.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ").trim();
        normalized = normalized.replaceAll("\\s{2,}", " ");
        return normalized;
    }

    private JSONObject selectBestTmdbResult(JSONArray results, String query, String titleKey) {
        String normalizedQuery = normalizeText(query).toLowerCase();
        JSONObject best = (JSONObject) results.get(0);
        int bestScore = -1;
        for (Object obj : results) {
            JSONObject candidate = (JSONObject) obj;
            String title = String.valueOf(candidate.getOrDefault(titleKey, ""));
            String normalizedTitle = normalizeText(title).toLowerCase();
            int score = scoreMatch(normalizedTitle, normalizedQuery);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private int scoreMatch(String candidate, String query) {
        if (candidate.equals(query)) {
            return 3;
        }
        if (candidate.startsWith(query) || query.startsWith(candidate)) {
            return 2;
        }
        if (candidate.contains(query) || query.contains(candidate)) {
            return 1;
        }
        return 0;
    }

    private List<MetadataSearchResult> searchTmdb(String title, Integer year, String director, String genre, String token, String type)
            throws IOException, InterruptedException, ParseException {
        if (title == null || title.isBlank()) {
            return List.of();
        }
        TmdbAuth auth = resolveTmdbAuth(token);
        String queryType = "tv".equalsIgnoreCase(type) ? "tv" : "movie";
        StringBuilder url = new StringBuilder("https://api.themoviedb.org/3/search/").append(queryType)
                .append("?language=es-ES&query=").append(URLEncoder.encode(title, StandardCharsets.UTF_8));
        if (year != null) {
            String yearParam = "tv".equalsIgnoreCase(type) ? "first_air_date_year" : "year";
            url.append("&").append(yearParam).append("=").append(year);
        }
        JSONObject root = getJsonWithAuth(url.toString(), auth);
        JSONArray results = (JSONArray) root.get("results");
        if (results == null) {
            return List.of();
        }
        List<MetadataSearchResult> output = new ArrayList<>();
        String normalizedDirector = director == null ? null : normalizeText(director).toLowerCase();
        String normalizedGenre = genre == null ? null : normalizeText(genre).toLowerCase();
        for (Object obj : results) {
            if (output.size() >= 15) {
                break;
            }
            JSONObject item = (JSONObject) obj;
            Long id = (Long) item.get("id");
            String titleKey = "tv".equalsIgnoreCase(type) ? "name" : "title";
            String dateKey = "tv".equalsIgnoreCase(type) ? "first_air_date" : "release_date";
            String itemTitle = String.valueOf(item.getOrDefault(titleKey, ""));
            Integer itemYear = parseYear(String.valueOf(item.getOrDefault(dateKey, "")));
            String overview = String.valueOf(item.getOrDefault("overview", ""));
            String posterPath = (String) item.get("poster_path");
            String posterUrl = posterPath == null ? null : TMDB_IMAGE_BASE + posterPath;
            if (id == null) {
                continue;
            }
            if (normalizedDirector != null || normalizedGenre != null) {
                JSONObject details = getJsonWithAuth("https://api.themoviedb.org/3/" + queryType + "/" + id +
                        "?language=es-ES&append_to_response=credits", auth);
                if (normalizedGenre != null && !normalizedGenre.isBlank()) {
                    JSONArray genres = (JSONArray) details.get("genres");
                    boolean matchesGenre = false;
                    if (genres != null) {
                        for (Object genreObj : genres) {
                            JSONObject genreJson = (JSONObject) genreObj;
                            String genreName = normalizeText(String.valueOf(genreJson.get("name"))).toLowerCase();
                            if (genreName.contains(normalizedGenre) || normalizedGenre.contains(genreName)) {
                                matchesGenre = true;
                                break;
                            }
                        }
                    }
                    if (!matchesGenre) {
                        continue;
                    }
                }
                if (normalizedDirector != null && !normalizedDirector.isBlank()) {
                    boolean matchesDirector = false;
                    if ("movie".equalsIgnoreCase(type)) {
                        JSONObject credits = (JSONObject) details.get("credits");
                        if (credits != null) {
                            JSONArray crew = (JSONArray) credits.get("crew");
                            if (crew != null) {
                                for (Object crewObj : crew) {
                                    JSONObject crewMember = (JSONObject) crewObj;
                                    if ("Director".equalsIgnoreCase(String.valueOf(crewMember.get("job")))) {
                                        String name = normalizeText(String.valueOf(crewMember.get("name"))).toLowerCase();
                                        matchesDirector = name.contains(normalizedDirector) || normalizedDirector.contains(name);
                                        break;
                                    }
                                }
                            }
                        }
                    } else {
                        JSONArray creators = (JSONArray) details.get("created_by");
                        if (creators != null) {
                            for (Object creatorObj : creators) {
                                JSONObject creator = (JSONObject) creatorObj;
                                String name = normalizeText(String.valueOf(creator.get("name"))).toLowerCase();
                                if (name.contains(normalizedDirector) || normalizedDirector.contains(name)) {
                                    matchesDirector = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!matchesDirector) {
                        continue;
                    }
                }
            }
            output.add(new MetadataSearchResult("TMDB", queryType, id, itemTitle, itemYear, overview, posterUrl));
        }
        return output;
    }

    private JSONObject getJsonWithAuth(String url, TmdbAuth auth) throws IOException, InterruptedException, ParseException {
        String resolvedUrl = buildTmdbUrl(url, auth);
        logTmdbRequest(resolvedUrl, auth);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(resolvedUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET();
        if (auth.type == TmdbTokenType.V4 && auth.token != null && !auth.token.isBlank()) {
            builder.header("Authorization", "Bearer " + auth.token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body();
            logTmdbError(resolvedUrl, auth, response.statusCode(), body);
            throw new IOException("HTTP " + response.statusCode() + " -> " + resolvedUrl + " body=" + body);
        }
        JSONParser parser = new JSONParser();
        return (JSONObject) parser.parse(response.body());
    }

    private JSONObject getJson(String url) throws IOException, InterruptedException, ParseException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " -> " + url);
        }
        JSONParser parser = new JSONParser();
        return (JSONObject) parser.parse(response.body());
    }

    private Integer parseYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 4) {
            try {
                return Integer.parseInt(trimmed.substring(0, 4));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private TmdbAuth resolveTmdbAuth(String rawToken) {
        String token = rawToken == null ? null : rawToken.trim();
        if (token == null || token.isBlank()) {
            return new TmdbAuth(null, TmdbTokenType.NONE);
        }
        if (looksLikeJwt(token)) {
            return new TmdbAuth(token, TmdbTokenType.V4);
        }
        if (token.matches("(?i)^[a-f0-9]{32}$")) {
            return new TmdbAuth(token, TmdbTokenType.V3);
        }
        LOGGER.warning("Token TMDB no reconocido. Se intentará como Bearer v4.");
        return new TmdbAuth(token, TmdbTokenType.V4);
    }

    private boolean looksLikeJwt(String token) {
        if (token.startsWith("eyJ")) {
            return true;
        }
        int dotCount = 0;
        for (char c : token.toCharArray()) {
            if (c == '.') {
                dotCount++;
            }
        }
        return dotCount >= 2 && token.length() > 40;
    }

    private String buildTmdbUrl(String url, TmdbAuth auth) {
        if (auth.type != TmdbTokenType.V3 || auth.token == null || auth.token.isBlank()) {
            return url;
        }
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + "api_key=" + URLEncoder.encode(auth.token, StandardCharsets.UTF_8);
    }

    private void logTmdbRequest(String url, TmdbAuth auth) {
        String tokenHint = maskToken(auth.token);
        LOGGER.info(() -> "TMDB request -> " + url + " auth=" + auth.type + " token=" + tokenHint);
    }

    private void logTmdbError(String url, TmdbAuth auth, int statusCode, String body) {
        String tokenHint = maskToken(auth.token);
        LOGGER.log(Level.WARNING, "TMDB error -> status={0} url={1} auth={2} token={3} body={4}",
                new Object[]{statusCode, url, auth.type, tokenHint, body});
    }

    private String maskToken(String token) {
        if (token == null || token.isBlank()) {
            return "(vacío)";
        }
        String trimmed = token.trim();
        int visible = Math.min(4, trimmed.length());
        String suffix = trimmed.substring(trimmed.length() - visible);
        return "***" + suffix;
    }

    private enum TmdbTokenType {
        NONE,
        V3,
        V4
    }

    private static class TmdbAuth {
        private final String token;
        private final TmdbTokenType type;

        private TmdbAuth(String token, TmdbTokenType type) {
            this.token = token;
            this.type = type;
        }
    }
}
