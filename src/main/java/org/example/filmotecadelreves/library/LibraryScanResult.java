package org.example.filmotecadelreves.library;

import java.util.List;

public record LibraryScanResult(LibraryCatalog catalog,
                                List<MediaItem> missingMovies,
                                List<EpisodeItem> missingEpisodes) {
}
