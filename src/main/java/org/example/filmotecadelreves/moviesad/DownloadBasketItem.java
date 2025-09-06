package org.example.filmotecainvertida.moviesad;


/**
 * Representa un elemento en la cesta de descargas
 */
public class DownloadBasketItem {
    private final int id;
    private final String name;
    private final String type; // "movie" o "episode"
    private String quality;
    private String link;
    private String server;
    private final String seriesName; // Solo para episodios
    private final int seasonNumber; // Solo para episodios
    private final int episodeNumber; // Solo para episodios

    /**
     * Constructor para un elemento de la cesta de descargas
     * @param id ID del elemento (película o episodio)
     * @param name Nombre del elemento
     * @param type Tipo ("movie" o "episode")
     * @param quality Calidad del video
     * @param link Enlace de descarga
     * @param server Servidor de origen
     * @param seriesName Nombre de la serie (solo para episodios)
     * @param seasonNumber Número de temporada (solo para episodios)
     * @param episodeNumber Número de episodio (solo para episodios)
     */
    public DownloadBasketItem(int id, String name, String type, String quality, String link, String server,
                              String seriesName, int seasonNumber, int episodeNumber) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.quality = quality;
        this.link = link;
        this.server = server;
        this.seriesName = seriesName;
        this.seasonNumber = seasonNumber;
        this.episodeNumber = episodeNumber;
    }

    /**
     * Obtiene el ID del elemento
     * @return ID del elemento
     */
    public int getId() {
        return id;
    }

    /**
     * Obtiene el nombre del elemento
     * @return Nombre del elemento
     */
    public String getName() {
        return name;
    }

    /**
     * Obtiene el tipo del elemento
     * @return Tipo del elemento ("movie" o "episode")
     */
    public String getType() {
        return type;
    }

    /**
     * Obtiene la calidad del video
     * @return Calidad del video
     */
    public String getQuality() {
        return quality;
    }

    /**
     * Establece la calidad del video
     * @param quality Nueva calidad
     */
    public void setQuality(String quality) {
        this.quality = quality;
    }

    /**
     * Obtiene el enlace de descarga
     * @return Enlace de descarga
     */
    public String getLink() {
        return link;
    }

    /**
     * Establece el enlace de descarga
     * @param link Nuevo enlace
     */
    public void setLink(String link) {
        this.link = link;
    }

    /**
     * Obtiene el servidor de origen
     * @return Servidor de origen
     */
    public String getServer() {
        return server;
    }

    /**
     * Establece el servidor de origen
     * @param server Nuevo servidor
     */
    public void setServer(String server) {
        this.server = server;
    }

    /**
     * Obtiene el nombre de la serie (solo para episodios)
     * @return Nombre de la serie
     */
    public String getSeriesName() {
        return seriesName;
    }

    /**
     * Obtiene el número de temporada (solo para episodios)
     * @return Número de temporada
     */
    public int getSeasonNumber() {
        return seasonNumber;
    }

    /**
     * Obtiene el número de episodio (solo para episodios)
     * @return Número de episodio
     */
    public int getEpisodeNumber() {
        return episodeNumber;
    }

    /**
     * Obtiene el ID del episodio (solo para episodios)
     * @return ID del episodio
     */
    public int getEpisodeId() {
        return id;
    }
}