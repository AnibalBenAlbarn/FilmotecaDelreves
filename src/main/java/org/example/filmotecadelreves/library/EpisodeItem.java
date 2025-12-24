package org.example.filmotecadelreves.library;

public class EpisodeItem {
    private final String id;
    private final String filePath;
    private final String title;
    private final int season;
    private final int episode;

    public EpisodeItem(String id, String filePath, String title, int season, int episode) {
        this.id = id;
        this.filePath = filePath;
        this.title = title;
        this.season = season;
        this.episode = episode;
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

    public int getSeason() {
        return season;
    }

    public int getEpisode() {
        return episode;
    }
}
