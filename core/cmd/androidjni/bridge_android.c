#include <jni.h>
#include <stdlib.h>
#include "_cgo_export.h"

// Tokens and handles are deliberately short ASCII strings. Check UTF-16 length
// before JNI allocates a modified-UTF-8 copy of an accidentally supplied value.
static const char *token(JNIEnv *env, jstring s) {
    if (s == NULL || (*env)->GetStringLength(env, s) > 64) return NULL;
    return (*env)->GetStringUTFChars(env, s, NULL);
}

JNIEXPORT jstring JNICALL
Java_io_github_voicetrail_NativeCore_start(JNIEnv *env, jclass cls, jstring kind) {
    (void)cls;
    const char *name = token(env, kind);
    if (name == NULL) return NULL;
    char *id = VTStart((char *)name);
    (*env)->ReleaseStringUTFChars(env, kind, name);
    if (id == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, id);
    if (result == NULL) VTClose(id);
    free(id);
    return result;
}

typedef int (*token_operation)(char *, char *);

static int with_pair(JNIEnv *env, jstring id, jstring value, token_operation fn) {
    const char *handle = token(env, id);
    if (handle == NULL) return 0;
    const char *key = token(env, value);
    int result = 0;
    if (key != NULL) {
        result = fn((char *)handle, (char *)key);
        (*env)->ReleaseStringUTFChars(env, value, key);
    }
    (*env)->ReleaseStringUTFChars(env, id, handle);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_io_github_voicetrail_NativeCore_event(JNIEnv *env, jclass cls, jstring id, jstring kind) {
    (void)cls;
    return with_pair(env, id, kind, VTEvent) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_io_github_voicetrail_NativeCore_dispatch(JNIEnv *env, jclass cls, jstring id, jstring name) {
    (void)cls;
    return with_pair(env, id, name, VTDispatch);
}

JNIEXPORT void JNICALL
Java_io_github_voicetrail_NativeCore_close(JNIEnv *env, jclass cls, jstring id) {
    (void)cls;
    const char *handle = token(env, id);
    if (handle != NULL) {
        VTClose((char *)handle);
        (*env)->ReleaseStringUTFChars(env, id, handle);
    }
}
