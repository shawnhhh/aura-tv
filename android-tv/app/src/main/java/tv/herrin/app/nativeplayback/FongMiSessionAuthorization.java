/* SPDX-License-Identifier: GPL-3.0-only
 * Isolated Herrin host bridge for the authorized FongMi playback session.
 */
package tv.herrin.app.nativeplayback;

/**
 * The global TMDB/NAS/cloud/TVBox source selector must authenticate a
 * Spider-generated localhost stream against the current user-approved site.
 * Never fall back to the removed SpiderProxyPlayback route classifier.
 */
public final class FongMiSessionAuthorization {
    private FongMiSessionAuthorization() {}

    public static boolean isApproved(String mediaUrl, String approvedSiteKey) {
        return approvedSiteKey != null && !approvedSiteKey.isBlank() &&
            NativeFongMiRoute.isNativeProxy(mediaUrl) &&
            NativeFongMiRoute.probe(mediaUrl, approvedSiteKey) ==
                NativeFongMiRoute.Health.READY;
    }
}
