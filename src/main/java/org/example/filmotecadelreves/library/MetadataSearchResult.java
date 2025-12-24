package org.example.filmotecadelreves.library;

public class MetadataSearchResult {
    private final String provider;
    private final String type;
    private final long id;
    private final String title;
    private final Integer year;
    private final String overview;
    private final String posterUrl;

    public MetadataSearchResult(String provider, String type, long id, String title, Integer year, String overview, String posterUrl) {
        this.provider = provider;
        this.type = type;
        this.id = id;
        this.title = title;
        this.year = year;
        this.overview = overview;
        this.posterUrl = posterUrl;
    }

    public String getProvider() {
        return provider;
    }

    public String getType() {
        return type;
    }

    public long getId() {
        return id;
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

    @Override
    public String toString() {
        String yearText = year == null ? "—" : String.valueOf(year);
        return title + " (" + yearText + ")";
    }
}
