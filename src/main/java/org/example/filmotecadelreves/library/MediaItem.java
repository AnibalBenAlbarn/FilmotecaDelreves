package org.example.filmotecadelreves.library;

import java.util.ArrayList;
import java.util.List;

public class MediaItem {
    private final String id;
    private final String filePath;
    private final String title;
    private Integer year;
    private String director;
    private List<String> genres = new ArrayList<>();
    private String overview;
    private String posterPath;

    public MediaItem(String id, String filePath, String title) {
        this.id = id;
        this.filePath = filePath;
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getTitle() {
        return title;
    }

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public String getDirector() {
        return director;
    }

    public void setDirector(String director) {
        this.director = director;
    }

    public List<String> getGenres() {
        return genres;
    }

    public void setGenres(List<String> genres) {
        this.genres = genres == null ? new ArrayList<>() : new ArrayList<>(genres);
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public void setPosterPath(String posterPath) {
        this.posterPath = posterPath;
    }
}
