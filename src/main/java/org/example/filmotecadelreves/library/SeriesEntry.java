package org.example.filmotecadelreves.library;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SeriesEntry {
    private final String id;
    private final String title;
    private String posterPath;
    private final Map<Integer, List<EpisodeItem>> seasons = new LinkedHashMap<>();

    public SeriesEntry(String id, String title) {
        this.id = id;
        this.title = title;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public void setPosterPath(String posterPath) {
        this.posterPath = posterPath;
    }

    public Map<Integer, List<EpisodeItem>> getSeasons() {
        return seasons;
    }

    public void addEpisode(EpisodeItem episode) {
        seasons.computeIfAbsent(episode.getSeason(), key -> new ArrayList<>()).add(episode);
    }
}
