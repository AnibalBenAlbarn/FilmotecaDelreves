package org.example.filmotecadelreves.moviesad;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadPersistenceManagerTest {

    private static Path tempDirectory;

    @BeforeAll
    static void configureDatabasePath() throws IOException {
        tempDirectory = Files.createTempDirectory("downloads-db-test");
        Path databaseFile = tempDirectory.resolve("download_state.db");
        System.setProperty("filmoteca.download.db.path", databaseFile.toString());
        DownloadPersistenceManager.resetForTests();
    }

    @AfterAll
    static void cleanupDatabase() throws IOException {
        try {
            Files.walk(tempDirectory)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } finally {
            System.clearProperty("filmoteca.download.db.path");
            DownloadPersistenceManager.resetForTests();
        }
    }

    @BeforeEach
    void cleanTables() {
        DownloadPersistenceManager manager = DownloadPersistenceManager.getInstance();
        manager.clearAllData();
    }

    @Test
    void directDownloadsArePersistedAndUpdated() {
        DownloadPersistenceManager manager = DownloadPersistenceManager.getInstance();

        manager.upsertDirectDownload(
                "direct-1",
                "Película",
                "https://servidor/archivo.mp4",
                "PowVideo",
                "/tmp/archivo.mp4",
                "Descargando",
                0.5,
                2_048L,
                1_024L,
                512.0,
                120L,
                false
        );

        List<DownloadPersistenceManager.DirectDownloadRecord> records = manager.loadDirectDownloads();
        assertEquals(1, records.size());
        DownloadPersistenceManager.DirectDownloadRecord record = records.get(0);
        assertEquals("Película", record.getName());
        assertEquals("https://servidor/archivo.mp4", record.getUrl());
        assertEquals("PowVideo", record.getServer());
        assertFalse(record.isManuallyPaused());

        manager.upsertDirectDownload(
                "direct-1",
                "Película 2",
                "https://servidor/archivo_v2.mp4",
                "Streamtape",
                "/tmp/archivo_v2.mp4",
                "Completado",
                1.0,
                4_096L,
                4_096L,
                0.0,
                0L,
                true
        );

        records = manager.loadDirectDownloads();
        assertEquals(1, records.size());
        record = records.get(0);
        assertEquals("Película 2", record.getName());
        assertEquals("https://servidor/archivo_v2.mp4", record.getUrl());
        assertEquals("Completado", record.getStatus());
        assertTrue(record.isManuallyPaused());
        assertEquals(4_096L, record.getFileSize());
        assertEquals(4_096L, record.getDownloadedBytes());

        manager.deleteDirectDownload("direct-1");
        assertTrue(manager.loadDirectDownloads().isEmpty());
    }

    @Test
    void torrentDownloadsArePersistedAndDeleted() {
        DownloadPersistenceManager manager = DownloadPersistenceManager.getInstance();

        manager.upsertTorrent(
                "torrent-1",
                "magnet:?xt=urn:btih:INFOHASH",
                "/tmp/torrent",
                "Mi Torrent",
                "Descargando",
                0.25,
                8_192L,
                512,
                128,
                5,
                true,
                false,
                "INFOHASH"
        );

        List<DownloadPersistenceManager.TorrentDownloadRecord> records = manager.loadTorrentDownloads();
        assertEquals(1, records.size());
        DownloadPersistenceManager.TorrentDownloadRecord record = records.get(0);
        assertEquals("Mi Torrent", record.getName());
        assertEquals("magnet:?xt=urn:btih:INFOHASH", record.getSource());
        assertTrue(record.isSequential());
        assertFalse(record.isManuallyPaused());
        assertEquals("INFOHASH", record.getInfoHash());

        manager.deleteTorrent("torrent-1");
        assertTrue(manager.loadTorrentDownloads().isEmpty());
    }
}
