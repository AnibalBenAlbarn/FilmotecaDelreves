package org.example.filmotecadelreves.library;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LibraryCatalogStore {
    private static final String CATALOG_FILE = "catalog.json";

    public LibraryCatalog loadCatalog(Path libraryDir) {
        LibraryCatalog catalog = new LibraryCatalog();
        Path file = libraryDir.resolve(CATALOG_FILE);
        if (!Files.exists(file)) {
            return catalog;
        }
        try (FileReader reader = new FileReader(file.toFile(), StandardCharsets.UTF_8)) {
            JSONParser parser = new JSONParser();
            JSONObject root = (JSONObject) parser.parse(reader);
            JSONArray movies = (JSONArray) root.getOrDefault("movies", new JSONArray());
            for (Object obj : movies) {
                JSONObject item = (JSONObject) obj;
                MediaItem media = new MediaItem(
                        String.valueOf(item.get("id")),
                        String.valueOf(item.get("filePath")),
                        String.valueOf(item.get("title"))
                );
                media.setYear(parseInt(item.get("year")));
                media.setDirector((String) item.get("director"));
                media.setOverview((String) item.get("overview"));
                media.setPosterPath((String) item.get("posterPath"));
                JSONArray genres = (JSONArray) item.getOrDefault("genres", new JSONArray());
                List<String> genreList = new ArrayList<>();
                for (Object g : genres) {
                    genreList.add(String.valueOf(g));
                }
                media.setGenres(genreList);
                catalog.getMovies().add(media);
            }

            JSONArray series = (JSONArray) root.getOrDefault("series", new JSONArray());
            for (Object obj : series) {
                JSONObject item = (JSONObject) obj;
                SeriesEntry entry = new SeriesEntry(
                        String.valueOf(item.get("id")),
                        String.valueOf(item.get("title"))
                );
                entry.setPosterPath((String) item.get("posterPath"));
                JSONArray seasons = (JSONArray) item.getOrDefault("seasons", new JSONArray());
                for (Object seasonObj : seasons) {
                    JSONObject seasonJson = (JSONObject) seasonObj;
                    int seasonNumber = parseInt(seasonJson.get("season"), 1);
                    JSONArray episodes = (JSONArray) seasonJson.getOrDefault("episodes", new JSONArray());
                    for (Object epObj : episodes) {
                        JSONObject ep = (JSONObject) epObj;
                        EpisodeItem episodeItem = new EpisodeItem(
                                String.valueOf(ep.get("id")),
                                String.valueOf(ep.get("filePath")),
                                String.valueOf(ep.get("title")),
                                seasonNumber,
                                parseInt(ep.get("episode"), 0)
                        );
                        entry.addEpisode(episodeItem);
                    }
                }
                catalog.getSeries().add(entry);
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
        return catalog;
    }

    public void saveCatalog(Path libraryDir, LibraryCatalog catalog) {
        JSONObject root = new JSONObject();
        JSONArray movies = new JSONArray();
        for (MediaItem item : catalog.getMovies()) {
            JSONObject media = new JSONObject();
            media.put("id", item.getId());
            media.put("filePath", item.getFilePath());
            media.put("title", item.getTitle());
            media.put("year", item.getYear());
            media.put("director", item.getDirector());
            media.put("overview", item.getOverview());
            media.put("posterPath", item.getPosterPath());
            JSONArray genres = new JSONArray();
            genres.addAll(item.getGenres());
            media.put("genres", genres);
            movies.add(media);
        }
        root.put("movies", movies);

        JSONArray seriesArray = new JSONArray();
        for (SeriesEntry entry : catalog.getSeries()) {
            JSONObject series = new JSONObject();
            series.put("id", entry.getId());
            series.put("title", entry.getTitle());
            series.put("posterPath", entry.getPosterPath());
            JSONArray seasons = new JSONArray();
            for (Map.Entry<Integer, List<EpisodeItem>> seasonEntry : entry.getSeasons().entrySet()) {
                JSONObject seasonJson = new JSONObject();
                seasonJson.put("season", seasonEntry.getKey());
                JSONArray episodes = new JSONArray();
                for (EpisodeItem episode : seasonEntry.getValue()) {
                    JSONObject episodeJson = new JSONObject();
                    episodeJson.put("id", episode.getId());
                    episodeJson.put("filePath", episode.getFilePath());
                    episodeJson.put("title", episode.getTitle());
                    episodeJson.put("episode", episode.getEpisode());
                    episodes.add(episodeJson);
                }
                seasonJson.put("episodes", episodes);
                seasons.add(seasonJson);
            }
            series.put("seasons", seasons);
            seriesArray.add(series);
        }
        root.put("series", seriesArray);

        try {
            Files.createDirectories(libraryDir);
            try (FileWriter writer = new FileWriter(libraryDir.resolve(CATALOG_FILE).toFile(), StandardCharsets.UTF_8)) {
                writer.write(root.toJSONString());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Integer parseInt(Object value) {
        return parseInt(value, null);
    }

    private Integer parseInt(Object value, Integer fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
