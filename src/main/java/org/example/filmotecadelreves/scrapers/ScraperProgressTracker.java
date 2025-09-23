package org.example.filmotecadelreves.scrapers;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;

/**
 * Simple state holder that keeps track of the last processed page for the
 * different scrapers used by the application. The values are exposed as
 * {@link IntegerProperty} instances so the UI can react automatically to
 * changes and persist them in the configuration file when the application
 * closes.
 */
public class ScraperProgressTracker {

    private final IntegerProperty directMoviesLastPage = new SimpleIntegerProperty(-1);
    private final IntegerProperty directSeriesLastPage = new SimpleIntegerProperty(-1);
    private final IntegerProperty torrentMoviesLastPage = new SimpleIntegerProperty(-1);
    private final IntegerProperty torrentSeriesLastPage = new SimpleIntegerProperty(-1);

    /**
     * Resets every stored value to the default "unknown" state.
     */
    public void reset() {
        setDirectMoviesLastPage(-1);
        setDirectSeriesLastPage(-1);
        setTorrentMoviesLastPage(-1);
        setTorrentSeriesLastPage(-1);
    }

    public IntegerProperty directMoviesLastPageProperty() {
        return directMoviesLastPage;
    }

    public int getDirectMoviesLastPage() {
        return directMoviesLastPage.get();
    }

    public void setDirectMoviesLastPage(int page) {
        directMoviesLastPage.set(page);
    }

    public IntegerProperty directSeriesLastPageProperty() {
        return directSeriesLastPage;
    }

    public int getDirectSeriesLastPage() {
        return directSeriesLastPage.get();
    }

    public void setDirectSeriesLastPage(int page) {
        directSeriesLastPage.set(page);
    }

    public IntegerProperty torrentMoviesLastPageProperty() {
        return torrentMoviesLastPage;
    }

    public int getTorrentMoviesLastPage() {
        return torrentMoviesLastPage.get();
    }

    public void setTorrentMoviesLastPage(int page) {
        torrentMoviesLastPage.set(page);
    }

    public IntegerProperty torrentSeriesLastPageProperty() {
        return torrentSeriesLastPage;
    }

    public int getTorrentSeriesLastPage() {
        return torrentSeriesLastPage.get();
    }

    public void setTorrentSeriesLastPage(int page) {
        torrentSeriesLastPage.set(page);
    }
}

