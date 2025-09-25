package org.example.filmotecadelreves.downloaders;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Thread-safe ring buffer that stores the last {@code maxEntries} log events
 * for a torrent.  The downloader can append entries from background threads
 * while the JavaFX UI reads snapshots to show the activity log to the user.
 */
final class TorrentLogBook implements Serializable {

    private final int maxEntries;
    private final Deque<TorrentLogEntry> entries;

    TorrentLogBook(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.entries = new ArrayDeque<>(this.maxEntries);
    }

    synchronized void add(TorrentLogEntry entry) {
        if (entry == null) {
            return;
        }
        entries.addLast(entry);
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }
    }

    synchronized List<TorrentLogEntry> snapshot() {
        return new ArrayList<>(entries);
    }

    synchronized void clear() {
        entries.clear();
    }
}

