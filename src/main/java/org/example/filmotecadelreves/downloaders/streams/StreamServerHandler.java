package org.example.filmotecadelreves.downloaders.streams;

import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;

/**
 * Strategy interface that encapsulates the logic required to stream
 * a video from a concrete server.
 */
public interface StreamServerHandler {

    /**
     * @return human readable name for logging purposes.
     */
    String getName();

    /**
     * Determines whether this handler should be used for the provided
     * combination of URL and server id.
     *
     * @param url      url that will be streamed.
     * @param serverId identifier provided by the caller (can be -1 when unknown).
     * @return {@code true} if the handler supports the given data.
     */
    boolean supports(String url, int serverId);

    /**
     * Gives the handler a chance to customise the {@link ChromeOptions}
     * before the {@link org.openqa.selenium.WebDriver} is created. Typical
     * usages are installing extensions or tweaking command line arguments.
     */
    void configure(ChromeOptions options);

    /**
     * Hook executed after the {@link org.openqa.selenium.WebDriver} has been
     * created but before any navigation occurs.
     */
    default void onDriverCreated(WebDriver driver) {
        // Default implementation does nothing.
    }

    /**
     * Hook executed after the target url has been loaded. Implementations can
     * interact with the page (e.g. pressing buttons) at this stage.
     */
    default void afterPageLoad(WebDriver driver) {
        // Default implementation does nothing.
    }

    /**
     * Whether the manager should use the browser fullscreen shortcut
     * (F11). Streamers that rely on an in-player fullscreen button can
     * opt-out by returning {@code false}.
     */
    default boolean useBrowserFullscreen() {
        return true;
    }
}
