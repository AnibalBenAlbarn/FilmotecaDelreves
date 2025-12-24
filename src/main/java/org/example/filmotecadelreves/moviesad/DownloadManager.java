package org.example.filmotecadelreves.moviesad;


import org.example.filmotecadelreves.DirectDownloader;
import org.example.filmotecadelreves.ManualDownloadCapable;
import org.example.filmotecadelreves.UI.AjustesUI;
import org.example.filmotecadelreves.UI.DescargasUI;
import org.example.filmotecadelreves.downloaders.MixdropDownloader;
import org.example.filmotecadelreves.downloaders.SeleniumPowvideo;
import org.example.filmotecadelreves.downloaders.SeleniumStreamplay;
import org.example.filmotecadelreves.downloaders.StreamtapeDownloader;
import org.example.filmotecadelreves.util.UrlNormalizer;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Gestiona las descargas para diferentes servidores
 */
public class DownloadManager {
    private static final Map<String, DirectDownloader> downloaders = new HashMap<>();

    // Inicializar los downloaders
    static {
        downloaders.put("streamtape", new StreamtapeDownloader());
        downloaders.put("powvideo", new SeleniumPowvideo());
        downloaders.put("streamplay", new SeleniumStreamplay());
        downloaders.put("mixdrop", new MixdropDownloader());
    }

    /**
     * Obtiene el downloader apropiado para un servidor
     * @param server El nombre del servidor
     * @return El downloader para el servidor, o null si no se encuentra
     */
    public static DirectDownloader getDownloaderForServer(String server) {
        if (server == null || server.isEmpty()) {
            return null;
        }

        // Extraer el nombre base del servidor (eliminar cualquier numeración como "Streamtape 1")
        String baseServer = server.split(" ")[0].toLowerCase();

        // Comprobar cada downloader soportado
        for (Map.Entry<String, DirectDownloader> entry : downloaders.entrySet()) {
            if (baseServer.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        return null;
    }

    public static void updateStreamplayHeadless(boolean runHeadless) {
        DirectDownloader downloader = downloaders.get("streamplay");
        if (downloader instanceof SeleniumStreamplay) {
            ((SeleniumStreamplay) downloader).setRunHeadless(runHeadless);
        }
    }

    public static void updatePowvideoHeadless(boolean runHeadless) {
        DirectDownloader powvideoDownloader = downloaders.get("powvideo");
        if (powvideoDownloader instanceof SeleniumPowvideo) {
            ((SeleniumPowvideo) powvideoDownloader).setRunHeadless(runHeadless);
        }
    }

    /**
     * Inicia una descarga para una película
     * @param item El elemento de la cesta de descargas
     * @param ajustesUI La interfaz de ajustes para obtener rutas de destino
     * @param descargasUI La interfaz de descargas para añadir la descarga
     * @return true si la descarga se inició correctamente, false en caso contrario
     */
    public static boolean startMovieDownload(DownloadBasketItem item, AjustesUI ajustesUI, DescargasUI descargasUI) {
        return startMovieDownloadInternal(item, ajustesUI, descargasUI, false);
    }

    public static boolean startManualMovieDownload(DownloadBasketItem item, AjustesUI ajustesUI, DescargasUI descargasUI) {
        return startMovieDownloadInternal(item, ajustesUI, descargasUI, true);
    }

    /**
     * Inicia una descarga para un episodio
     * @param item El elemento de la cesta de descargas
     * @param ajustesUI La interfaz de ajustes para obtener rutas de destino
     * @param descargasUI La interfaz de descargas para añadir la descarga
     * @return true si la descarga se inició correctamente, false en caso contrario
     */
    public static boolean startEpisodeDownload(DownloadBasketItem item, AjustesUI ajustesUI, DescargasUI descargasUI) {
        return startEpisodeDownloadInternal(item, ajustesUI, descargasUI, false);
    }

    public static boolean startManualEpisodeDownload(DownloadBasketItem item, AjustesUI ajustesUI, DescargasUI descargasUI) {
        return startEpisodeDownloadInternal(item, ajustesUI, descargasUI, true);
    }

    private static boolean startMovieDownloadInternal(DownloadBasketItem item,
                                                      AjustesUI ajustesUI,
                                                      DescargasUI descargasUI,
                                                      boolean manualMode) {
        try {
            DirectDownloader downloader = getDownloaderForServer(item.getServer());
            if (downloader == null) {
                System.err.println("No hay un downloader disponible para el servidor: " + item.getServer());
                return false;
            }

            if (manualMode && !(downloader instanceof ManualDownloadCapable)) {
                System.err.println("El downloader para el servidor " + item.getServer() + " no soporta modo manual.");
                return false;
            }

            String normalizedLink = UrlNormalizer.normalizeMediaUrl(item.getLink());
            String serverLower = item.getServer().toLowerCase();

            boolean isPowOrStreamplay = serverLower.contains("powvideo") || serverLower.contains("streamplay");
            if (!manualMode && isPowOrStreamplay && DownloadLimitManager.isPowvideoStreamplayLimitReached()) {
                System.err.println("Límite de descargas alcanzado para PowVideo/StreamPlay");

                String fileName = item.getName() + ".mp4";
                DescargasUI.DirectDownload directDownload = new DescargasUI.DirectDownload(
                        fileName,
                        0,
                        "Waiting",
                        item.getServer(),
                        normalizedLink,
                        "",
                        downloader
                );
                descargasUI.addDirectDownload(directDownload);
                return false;
            }

            String baseDestination = ajustesUI.getDirectMovieDestination();
            String movieName = item.getName();
            String sanitizedMovieFolderName = sanitizePathComponent(movieName);

            String movieFolder = baseDestination + File.separator + sanitizedMovieFolderName;
            File destDir = new File(movieFolder);
            if (!destDir.exists() && !destDir.mkdirs()) {
                System.err.println("No se pudo crear el directorio de destino: " + movieFolder);
                return false;
            }

            String fileName = movieName + ".mp4";
            DescargasUI.DirectDownload directDownload = new DescargasUI.DirectDownload(
                    fileName,
                    0,
                    "Waiting",
                    item.getServer(),
                    normalizedLink,
                    movieFolder,
                    downloader
            );

            Runnable startAction;
            if (manualMode) {
                ManualDownloadCapable manualDownloader = (ManualDownloadCapable) downloader;
                startAction = () -> manualDownloader.downloadManual(normalizedLink, movieFolder, directDownload);
            } else {
                startAction = () -> downloader.download(normalizedLink, movieFolder, directDownload);
            }

            descargasUI.enqueueDirectDownload(directDownload, startAction);

            if (isPowOrStreamplay) {
                DownloadLimitManager.incrementPowvideoStreamplayCount();
                descargasUI.refreshDownloadCounter();
            }

            return true;
        } catch (Exception e) {
            System.err.println("Error al iniciar la descarga de la película: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private static boolean startEpisodeDownloadInternal(DownloadBasketItem item,
                                                         AjustesUI ajustesUI,
                                                         DescargasUI descargasUI,
                                                         boolean manualMode) {
        try {
            DirectDownloader downloader = getDownloaderForServer(item.getServer());
            if (downloader == null) {
                System.err.println("No hay un downloader disponible para el servidor: " + item.getServer());
                return false;
            }

            if (manualMode && !(downloader instanceof ManualDownloadCapable)) {
                System.err.println("El downloader para el servidor " + item.getServer() + " no soporta modo manual.");
                return false;
            }

            String normalizedLink = UrlNormalizer.normalizeMediaUrl(item.getLink());
            String serverLower = item.getServer().toLowerCase();

            boolean isPowOrStreamplay = serverLower.contains("powvideo") || serverLower.contains("streamplay");
            if (!manualMode && isPowOrStreamplay && DownloadLimitManager.isPowvideoStreamplayLimitReached()) {
                System.err.println("Límite de descargas alcanzado para PowVideo/StreamPlay");

                String fileName = item.getName() + ".mp4";
                DescargasUI.DirectDownload directDownload = new DescargasUI.DirectDownload(
                        fileName,
                        0,
                        "Waiting",
                        item.getServer(),
                        normalizedLink,
                        "",
                        downloader
                );
                descargasUI.addDirectDownload(directDownload);
                return false;
            }

            String baseDestination = ajustesUI.getDirectSeriesDestination();
            String sanitizedSeriesFolder = sanitizePathComponent(item.getSeriesName());
            String sanitizedSeasonFolder = sanitizePathComponent("Season " + item.getSeasonNumber());

            String destinationPath = baseDestination + File.separator + sanitizedSeriesFolder + File.separator + sanitizedSeasonFolder;
            File destDir = new File(destinationPath);
            if (!destDir.exists() && !destDir.mkdirs()) {
                System.err.println("No se pudo crear el directorio de destino: " + destinationPath);
                return false;
            }

            String fileName = item.getName() + ".mp4";
            DescargasUI.DirectDownload directDownload = new DescargasUI.DirectDownload(
                    fileName,
                    0,
                    "Waiting",
                    item.getServer(),
                    normalizedLink,
                    destinationPath,
                    downloader
            );

            Runnable startAction;
            if (manualMode) {
                ManualDownloadCapable manualDownloader = (ManualDownloadCapable) downloader;
                startAction = () -> manualDownloader.downloadManual(normalizedLink, destinationPath, directDownload);
            } else {
                startAction = () -> downloader.download(normalizedLink, destinationPath, directDownload);
            }

            descargasUI.enqueueDirectDownload(directDownload, startAction);

            if (isPowOrStreamplay) {
                DownloadLimitManager.incrementPowvideoStreamplayCount();
                descargasUI.refreshDownloadCounter();
            }

            return true;
        } catch (Exception e) {
            System.err.println("Error al iniciar la descarga del episodio: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Limpia un componente de ruta para que sea válido en sistemas de archivos como Windows.
     * Reemplaza caracteres no permitidos y elimina puntos o espacios finales.
     *
     * @param name El nombre a sanitizar
     * @return Un nombre seguro para usar como carpeta o archivo
     */
    private static String sanitizePathComponent(String name) {
        if (name == null) {
            return "unknown";
        }

        String sanitized = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        sanitized = sanitized.replaceAll("\\s+", " ").trim();
        sanitized = sanitized.replaceAll("[\\. ]+$", "");

        if (sanitized.isEmpty()) {
            sanitized = "unknown";
        }

        return sanitized;
    }
}
