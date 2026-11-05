/* SPDX-License-Identifier: GPL-3.0-only
 * HERRIN host adaptation of FongMi/TV 305dff2 authorized Spider lifecycle.
 * 2026-11-05: carry source/film/episode authority across Binder recovery.
 */
package tv.herrin.app

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SpiderFilmOrigin(val site: String, val runtime: String, val proof: String) {
    override fun toString() = "SpiderFilmOrigin([redacted])"
}
data class SpiderRouteProof(val value: String) {
    override fun toString() = "SpiderRouteProof([redacted])"
}

/** Proofs bind route data, never titles/artwork enriched by TMDB. No plugin sees the key. */
internal class SpiderFilmAuthority(private val site: String, private val runtime: String,
        private val secret: String) {
    fun sign(film: Film): Film = film.copy(
        spiderOrigin = SpiderFilmOrigin(site, runtime, proof(film)),
        playbackEpisodes = film.playbackEpisodes.map { episode -> episode.copy(lines = episode.lines.map {
            it.copy(spiderProof = SpiderRouteProof(proof(film, episode.number, it)))
        }) })

    fun accepts(film: Film): Boolean {
        val origin = film.spiderOrigin ?: return false
        if (film.spiderVodId.isNullOrBlank() || origin.site != site || origin.runtime != runtime) return false
        return equal(origin.proof, proof(film)) && film.playbackEpisodes.all { episode ->
            episode.lines.all { equal(it.spiderProof?.value.orEmpty(), proof(film, episode.number, it)) }
        }
    }

    fun requireFilm(film: Film) = require(accepts(film)) {
        "Spider 影片、选集或授权来源已变化，请重新查找片源"
    }

    fun mediaId(film: Film, episode: Int, lineId: String): String {
        val line = film.playbackEpisodes.first { it.number == episode }.lines.first { it.id == lineId }
        return "spider/" + proof(film, episode, line)
    }

    private fun equal(a: String, b: String) = MessageDigest.isEqual(
        a.toByteArray(Charsets.US_ASCII), b.toByteArray(Charsets.US_ASCII))

    private fun proof(film: Film, episode: Int? = null, line: PlaybackLine? = null): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            mac.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
            mac.update(bytes)
        }
        field(site); field(runtime); field(film.id); field(film.spiderVodId.orEmpty())
        field(if (line == null) "film" else "line")
        if (line != null) { field(episode.toString()); field(line.id); field(line.title); field(line.uri) }
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
