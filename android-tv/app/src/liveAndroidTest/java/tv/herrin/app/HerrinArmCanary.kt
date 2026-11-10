package tv.herrin.app

/** Implemented only by the original, optional test JNI canary. */
internal object HerrinArmCanary {
    external fun arm64(): Int
    external fun arm32(): Int
}
