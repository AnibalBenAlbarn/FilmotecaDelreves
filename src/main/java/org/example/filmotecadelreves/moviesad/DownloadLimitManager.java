package org.example.filmotecainvertida.moviesad;


import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestiona los límites de descargas para diferentes servidores
 */
public class DownloadLimitManager {
    // Contadores para los diferentes servidores
    private static final AtomicInteger powvideoStreamplayCount = new AtomicInteger(0);
    private static final AtomicInteger streamtapeCount = new AtomicInteger(0);
    private static final AtomicInteger mixdropCount = new AtomicInteger(0);

    // Límites para cada tipo de servidor
    private static final int POWVIDEO_STREAMPLAY_LIMIT = 10; // Actualizado a 10 descargas por día
    private static final int STREAMTAPE_LIMIT = 5;
    private static final int MIXDROP_LIMIT = 3;

    // Tiempo de reinicio para el contador de PowVideo/StreamPlay
    private static LocalDateTime powvideoStreamplayResetTime = null;

    /**
     * Verifica si se ha alcanzado el límite para Powvideo/Streamplay
     * @return true si se ha alcanzado el límite, false en caso contrario
     */
    public static boolean isPowvideoStreamplayLimitReached() {
        // Verificar si es necesario reiniciar el contador
        checkAndResetPowvideoStreamplayCounter();
        return powvideoStreamplayCount.get() >= POWVIDEO_STREAMPLAY_LIMIT;
    }

    /**
     * Verifica si se ha alcanzado el límite para Streamtape
     * @return true si se ha alcanzado el límite, false en caso contrario
     */
    public static boolean isStreamtapeLimitReached() {
        return streamtapeCount.get() >= STREAMTAPE_LIMIT;
    }

    /**
     * Verifica si se ha alcanzado el límite para Mixdrop
     * @return true si se ha alcanzado el límite, false en caso contrario
     */
    public static boolean isMixdropLimitReached() {
        return mixdropCount.get() >= MIXDROP_LIMIT;
    }

    /**
     * Incrementa el contador de descargas para Powvideo/Streamplay
     * @return el nuevo valor del contador
     */
    public static int incrementPowvideoStreamplayCount() {
        // Si es la primera descarga, establecer el tiempo de reinicio
        if (powvideoStreamplayCount.get() == 0) {
            powvideoStreamplayResetTime = LocalDateTime.now().plusHours(24);
        }
        return powvideoStreamplayCount.incrementAndGet();
    }

    /**
     * Incrementa el contador de descargas para Streamtape
     * @return el nuevo valor del contador
     */
    public static int incrementStreamtapeCount() {
        return streamtapeCount.incrementAndGet();
    }

    /**
     * Incrementa el contador de descargas para Mixdrop
     * @return el nuevo valor del contador
     */
    public static int incrementMixdropCount() {
        return mixdropCount.incrementAndGet();
    }

    /**
     * Decrementa el contador de descargas para Powvideo/Streamplay
     * @return el nuevo valor del contador
     */
    public static int decrementPowvideoStreamplayCount() {
        return powvideoStreamplayCount.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * Decrementa el contador de descargas para Streamtape
     * @return el nuevo valor del contador
     */
    public static int decrementStreamtapeCount() {
        return streamtapeCount.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * Decrementa el contador de descargas para Mixdrop
     * @return el nuevo valor del contador
     */
    public static int decrementMixdropCount() {
        return mixdropCount.updateAndGet(v -> Math.max(0, v - 1));
    }

    /**
     * Reinicia todos los contadores
     */
    public static void resetAllCounters() {
        powvideoStreamplayCount.set(0);
        streamtapeCount.set(0);
        mixdropCount.set(0);
        powvideoStreamplayResetTime = null;
    }

    /**
     * Obtiene el contador actual para Powvideo/Streamplay
     * @return el valor actual del contador
     */
    public static int getPowvideoStreamplayCount() {
        checkAndResetPowvideoStreamplayCounter();
        return powvideoStreamplayCount.get();
    }

    /**
     * Obtiene el contador actual para Streamtape
     * @return el valor actual del contador
     */
    public static int getStreamtapeCount() {
        return streamtapeCount.get();
    }

    /**
     * Obtiene el contador actual para Mixdrop
     * @return el valor actual del contador
     */
    public static int getMixdropCount() {
        return mixdropCount.get();
    }

    /**
     * Verifica si es necesario reiniciar el contador de PowVideo/StreamPlay
     * y lo reinicia si ha pasado el tiempo establecido (24 horas)
     */
    private static void checkAndResetPowvideoStreamplayCounter() {
        if (powvideoStreamplayResetTime != null && LocalDateTime.now().isAfter(powvideoStreamplayResetTime)) {
            powvideoStreamplayCount.set(0);
            powvideoStreamplayResetTime = null;
        }
    }

    /**
     * Obtiene el tiempo restante hasta que se reinicie el contador de PowVideo/StreamPlay
     * @return Tiempo restante en segundos, o 0 si no hay tiempo de reinicio establecido
     */
    public static long getRemainingTimeUntilReset() {
        if (powvideoStreamplayResetTime == null) {
            return 0;
        }
        return LocalDateTime.now().until(powvideoStreamplayResetTime, ChronoUnit.SECONDS);
    }

    /**
     * Formatea el tiempo restante en formato HH:MM:SS
     * @return String con el formato de tiempo restante
     */
    public static String getFormattedRemainingTime() {
        long seconds = getRemainingTimeUntilReset();
        if (seconds <= 0) {
            return "00:00:00";
        }

        long hours = seconds / 3600;
        seconds %= 3600;
        long minutes = seconds / 60;
        seconds %= 60;

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /**
     * Obtiene el límite de descargas para PowVideo/StreamPlay
     * @return el límite configurado
     */
    public static int getPowvideoStreamplayLimit() {
        return POWVIDEO_STREAMPLAY_LIMIT;
    }
}