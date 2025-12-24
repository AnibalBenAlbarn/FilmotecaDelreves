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
        return switch (provider) {
            case TMDB -> fetchTmdbMovie(title, apiKey);
            case OMDB -> fetchOmdb(title, "movie", apiKey);
            case TVMAZE -> null;
            default -> null;
        };
    }

    public MediaMetadata fetchSeries(String title, Provider provider, String apiKey) throws IOException, InterruptedException, ParseException {
        return switch (provider) {
            case TMDB -> fetchTmdbSeries(title, apiKey);
            case OMDB -> fetchOmdb(title, "series", apiKey);
            case TVMAZE -> fetchTvMaze(title);
            default -> null;
        };
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
        JSONObject top = (JSONObject) results.get(0);
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
        JSONObject top = (JSONObject) results.get(0);
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
