#include <jni.h>
#include <android/log.h>

#define LOG_TAG "llama_jni_stub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static jstring string_to_jstring(JNIEnv* env, const char* str) {
    return env->NewStringUTF(str);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeLoadModel(
        JNIEnv* /* env */, jobject /* this */,
        jstring /* jModelPath */,
        jstring /* jMmProjPath */,
        jint /* jContextLen */,
        jint /* jThreadCount */,
        jint /* jGpuLayers */) {
    LOGW("llama.cpp dependency missing; nativeLoadModel unavailable");
    return 0L;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeFreeModel(
        JNIEnv* /* env */, jobject /* this */, jlong /* jPtr */) {
}

extern "C"
JNIEXPORT void JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeClearKvCache(
        JNIEnv* /* env */, jobject /* this */, jlong /* jPtr */) {
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeGetKvCacheTokenCount(
        JNIEnv* /* env */, jobject /* this */, jlong /* jPtr */) {
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeGetGpuLayers(
        JNIEnv* /* env */, jobject /* this */, jlong /* jPtr */) {
    return 0;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeIsVisionEnabled(
        JNIEnv* /* env */, jobject /* this */, jlong /* jPtr */) {
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeEvalPrefix(
        JNIEnv* /* env */, jobject /* this */,
        jlong /* jPtr */,
        jstring /* jPrefix */) {
    return -1;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeGenerate(
        JNIEnv* env, jobject /* this */,
        jlong /* jPtr */,
        jstring /* jDynamicPrompt */,
        jstring /* jImagePath */,
        jint /* jMaxNewTokens */,
        jfloat /* jTemperature */,
        jint /* jTopK */,
        jfloat /* jTopP */) {
    return string_to_jstring(env, "");
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_hemant_plannerv1_model_LlamaCppBackend_nativeDetectVulkanGpu(
        JNIEnv* env, jclass /* cls */) {
    return string_to_jstring(env, "count=0 name=");
}
