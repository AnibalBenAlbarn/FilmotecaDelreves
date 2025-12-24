package org.example.filmotecadelreves.library;

import java.util.ArrayList;
import java.util.List;

public class LibraryCatalog {
    private final List<MediaItem> movies = new ArrayList<>();
    private final List<SeriesEntry> series = new ArrayList<>();

    public List<MediaItem> getMovies() {
        return movies;
    }

    public List<SeriesEntry> getSeries() {
        return series;
    }
}
