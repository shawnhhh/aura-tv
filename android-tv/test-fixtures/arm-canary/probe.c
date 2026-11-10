/* Original JNI arithmetic only. No provider code, credentials or media. */
#include <jni.h>
#ifdef __aarch64__
JNIEXPORT jint JNICALL Java_tv_herrin_app_HerrinArmCanary_arm64(JNIEnv *env, jobject self) { return 183; }
#else
JNIEXPORT jint JNICALL Java_tv_herrin_app_HerrinArmCanary_arm32(JNIEnv *env, jobject self) { return 40; }
#endif
