package org.example.filmotecadelreves.library;

import java.util.Objects;

public class LibraryEntry {
    public enum LibraryType {
        MOVIES,
        SERIES
    }

    private final String id;
    private String name;
    private LibraryType type;
    private String rootPath;
    private String scraperProvider;
    private String scraperApiKey;

    public LibraryEntry(String id, String name, LibraryType type, String rootPath) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.rootPath = rootPath;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LibraryType getType() {
        return type;
    }

    public void setType(LibraryType type) {
        this.type = type;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public String getScraperProvider() {
        return scraperProvider;
    }

    public void setScraperProvider(String scraperProvider) {
        this.scraperProvider = scraperProvider;
    }

    public String getScraperApiKey() {
        return scraperApiKey;
    }

    public void setScraperApiKey(String scraperApiKey) {
        this.scraperApiKey = scraperApiKey;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LibraryEntry that)) {
            return false;
        }
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
