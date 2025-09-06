package org.example.filmotecadelreves;

import org.example.filmotecadelreves.ui.DescargasUI;

/**
 * Interfaz para los descargadores directos de diferentes servidores
 */
public interface DirectDownloader {
    /**
     * Inicia la descarga de un archivo desde una URL
     * @param url URL del archivo a descargar
     * @param destinationPath Ruta de destino donde se guardará el archivo
     * @param directDownload Objeto que representa la descarga
     */
    void download(String url, String destinationPath, DescargasUI.DirectDownload directDownload);

    /**
     * Pausa una descarga en progreso
     * @param download Descarga a pausar
     */
    void pauseDownload(DescargasUI.DirectDownload download);

    /**
     * Reanuda una descarga pausada
     * @param download Descarga a reanudar
     */
    void resumeDownload(DescargasUI.DirectDownload download);

    /**
     * Cancela una descarga
     * @param download Descarga a cancelar
     */
    void cancelDownload(DescargasUI.DirectDownload download);

    /**
     * Verifica si una URL está disponible para descarga
     * @param url URL a verificar
     * @return true si la URL está disponible, false en caso contrario
     */
    boolean isAvailable(String url);
}