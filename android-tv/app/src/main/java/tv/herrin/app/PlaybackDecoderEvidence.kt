package tv.herrin.app

import android.os.Build
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/** Actual renderer observations. Never retain exception messages, URLs or format labels. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class PlaybackDecoderEvidence : AnalyticsListener {
    private var videoDecoder: String? = null
    private var audioDecoder: String? = null
    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) { videoDecoder = decoderName }
    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String,
        initializedTimestampMs: Long, initializationDurationMs: Long) { audioDecoder = decoderName }
    private fun safe(value: String?) = value?.takeIf { it.length <= 120 && it.matches(Regex("[a-zA-Z0-9._ /,+-]+")) } ?: "unknown"
    fun report(player: ExoPlayer, error: PlaybackException): String {
        val video = player.videoFormat
        val audio = player.audioFormat
        val chain = PlaybackFailureHints.causes(error)
        val init = chain.filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>().firstOrNull()
        val phase = when (error.errorCode) {
            4002 -> "DECODER_QUERY_FAILED"
            4004 -> "FORMAT_EXCEEDS_CAPABILITIES"
            4005 -> "FORMAT_UNSUPPORTED"
            4001 -> "DECODER_INITIALIZATION_FAILED"
            4003 -> "DECODER_RUNTIME_FAILED"
            5001, 5002 -> "AUDIO_OUTPUT_FAILED"
            3001, 3002, 3003, 3004 -> "MEDIA_PARSING_FAILED"
            else -> "OTHER_MEDIA3_FAILURE"
        }
        // RFC 6381 codec string carries signalled profile/level; absent means unknown.
        return "phase=$phase code=${error.errorCode} sdk=${Build.VERSION.SDK_INT} model=${safe(Build.MODEL)}\n" +
            "container=${safe(video?.containerMimeType)} video=${safe(video?.sampleMimeType)} codec_profile_level=${safe(video?.codecs)}\n" +
            "size=${video?.width ?: -1}x${video?.height ?: -1} fps=${video?.frameRate ?: -1f}\n" +
            "audio=${safe(audio?.sampleMimeType)} audio_codec=${safe(audio?.codecs)}\n" +
            "video_decoder=${safe(init?.codecInfo?.name ?: videoDecoder)} audio_decoder=${safe(audioDecoder)}\n" +
            "cause=" + when {
                init != null -> "DecoderInitializationException"
                chain.any { it is android.media.MediaCodec.CodecException } -> "MediaCodec.CodecException"
                chain.any { it is androidx.media3.common.ParserException } -> "ParserException"
                else -> "UNCLASSIFIED"
            }
    }
}
