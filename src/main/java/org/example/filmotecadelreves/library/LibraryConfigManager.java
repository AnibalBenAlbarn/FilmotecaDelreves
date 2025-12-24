package org.example.filmotecadelreves.library;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class LibraryConfigManager {
    public static final String LIBRARIES_FILE = "bibliotecas.json";
    public static final String DATA_DIR = "biblioteca_data";

    public List<LibraryEntry> loadLibraries() {
        ensureLibrariesFileExists();
        List<LibraryEntry> libraries = new ArrayList<>();
        File file = new File(LIBRARIES_FILE);
        if (!file.exists()) {
            return libraries;
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            JSONParser parser = new JSONParser();
            JSONObject root = (JSONObject) parser.parse(reader);
            JSONArray list = (JSONArray) root.getOrDefault("libraries", new JSONArray());
            for (Object item : list) {
                JSONObject entry = (JSONObject) item;
                String id = String.valueOf(entry.get("id"));
                String name = String.valueOf(entry.get("name"));
                String type = String.valueOf(entry.get("type"));
                String rootPath = String.valueOf(entry.get("rootPath"));
                LibraryEntry libraryEntry = new LibraryEntry(id, name, LibraryEntry.LibraryType.valueOf(type), rootPath);
                JSONObject scraper = (JSONObject) entry.get("scraper");
                if (scraper != null) {
                    libraryEntry.setScraperProvider((String) scraper.get("provider"));
                    libraryEntry.setScraperApiKey(normalizeToken((String) scraper.get("apiKey")));
                }
                libraries.add(libraryEntry);
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        }
        return libraries;
    }

    public void saveLibraries(List<LibraryEntry> libraries) {
        JSONObject root = new JSONObject();
        JSONArray list = new JSONArray();
        for (LibraryEntry entry : libraries) {
            JSONObject object = new JSONObject();
            object.put("id", entry.getId());
            object.put("name", entry.getName());
            object.put("type", entry.getType().name());
            object.put("rootPath", entry.getRootPath());
            JSONObject scraper = new JSONObject();
            if (entry.getScraperProvider() != null) {
                scraper.put("provider", entry.getScraperProvider());
            }
            String normalizedToken = normalizeToken(entry.getScraperApiKey());
            if (normalizedToken != null) {
                scraper.put("apiKey", normalizedToken);
                String tokenType = detectTmdbTokenType(entry.getScraperProvider(), normalizedToken);
                if (tokenType != null) {
                    scraper.put("apiKeyType", tokenType);
                }
            }
            if (!scraper.isEmpty()) {
                object.put("scraper", scraper);
            }
            object.put("updatedAt", Instant.now().toString());
            list.add(object);
        }
        root.put("libraries", list);
        try (FileWriter writer = new FileWriter(LIBRARIES_FILE, StandardCharsets.UTF_8)) {
            writer.write(root.toJSONString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public LibraryEntry createLibrary(String name, LibraryEntry.LibraryType type, String rootPath) {
        String id = name.toLowerCase().replaceAll("[^a-z0-9]+", "-") + "-" + UUID.randomUUID();
        return new LibraryEntry(id, name, type, rootPath);
    }

    public Path getLibraryDataDir(LibraryEntry entry) {
        return Path.of(DATA_DIR, entry.getId());
    }

    public void ensureLibrariesFileExists() {
        File file = new File(LIBRARIES_FILE);
        if (file.exists()) {
            return;
        }
        JSONObject root = new JSONObject();
        root.put("libraries", new JSONArray());
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(root.toJSONString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void ensureDataDirExists() {
        try {
            Files.createDirectories(Path.of(DATA_DIR));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String detectTmdbTokenType(String provider, String token) {
        if (provider == null || token == null) {
            return null;
        }
        if (!"TMDB".equalsIgnoreCase(provider)) {
            return null;
        }
        if (token.startsWith("eyJ") || token.contains(".")) {
            return "v4";
        }
        if (token.matches("(?i)^[a-f0-9]{32}$")) {
            return "v3";
        }
        return "unknown";
    }
}
