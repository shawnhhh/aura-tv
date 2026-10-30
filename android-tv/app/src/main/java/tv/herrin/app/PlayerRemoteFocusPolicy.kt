package tv.herrin.app

/**
 * TV transport must remain available while Media3 is buffering.
 *
 * Rendering the first decoded video frame and constructing a remote focus
 * target are separate operations. A missing/slow stream must not make the
 * remote DOWN/CENTER action wait on the video decoder indefinitely.
 */
internal object PlayerRemoteFocusPolicy {
    enum class Destination { NONE, TRANSPORT, RETRY }

    fun destination(
        live: Boolean,
        requested: Boolean,
        controlsVisible: Boolean,
        errorVisible: Boolean
    ): Destination = when {
        live || !requested -> Destination.NONE
        errorVisible -> Destination.RETRY
        controlsVisible -> Destination.TRANSPORT
        else -> Destination.NONE
    }

    fun showTransport(live: Boolean, controlsVisible: Boolean, errorVisible: Boolean): Boolean =
        !live && controlsVisible && !errorVisible
}
