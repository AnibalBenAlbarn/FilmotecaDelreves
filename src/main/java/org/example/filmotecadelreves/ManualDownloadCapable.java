package org.example.filmotecadelreves;

import org.example.filmotecadelreves.UI.DescargasUI;

/**
 * Defines additional behaviour for downloaders that support a manual download
 * workflow. Manual downloads rely on user interaction in a visible browser
 * window before the automated transfer can begin.
 */
public interface ManualDownloadCapable {
    /**
     * Starts a manual download flow. Implementations should open a dedicated
     * browser window, allow the user to complete any required interactions and
     * monitor the page until the media file can be downloaded automatically.
     *
     * @param url             the original media URL to download
     * @param destinationPath target directory for the downloaded file
     * @param directDownload  descriptor representing the current download
     */
    void downloadManual(String url, String destinationPath, DescargasUI.DirectDownload directDownload);
}
