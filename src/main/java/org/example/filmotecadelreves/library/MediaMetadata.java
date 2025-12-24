package org.example.filmotecadelreves.library;

import java.util.ArrayList;
import java.util.List;

public class MediaMetadata {
    private final String provider;
    private final String type;
    private final String title;
    private final Integer year;
    private final String overview;
    private final String posterUrl;
    private final String director;
    private final List<String> genres;

    public MediaMetadata(String provider, String type, String title, Integer year, String overview,
                         String posterUrl, String director, List<String> genres) {
        this.provider = provider;
        this.type = type;
        this.title = title;
        this.year = year;
        this.overview = overview;
        this.posterUrl = posterUrl;
        this.director = director;
        this.genres = genres == null ? new ArrayList<>() : new ArrayList<>(genres);
    }

    public String getProvider() {
        return provider;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public Integer getYear() {
        return year;
    }

    public String getOverview() {
        return overview;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public String getDirector() {
        return director;
    }

    public List<String> getGenres() {
        return genres;
    }
}
