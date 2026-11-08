//go:build android || jni

// SPDX-License-Identifier: GPL-3.0-only

#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "_cgo_export.h"

static void throw_error(JNIEnv *env, const char *type, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass exception = (*env)->FindClass(env, type);
    if (exception != NULL) {
        (*env)->ThrowNew(env, exception, message);
        (*env)->DeleteLocalRef(env, exception);
    }
}

static void free_text(char *text) {
    if (text == NULL) return;
    size_t length = strlen(text);
    volatile char *bytes = (volatile char *) text;
    while (length != 0) bytes[--length] = 0;
    free(text);
}

// JNI's GetStringUTFChars uses modified UTF-8, including CESU-8 surrogate
// pairs. Decode UTF-16 explicitly so valid non-BMP profile comments round-trip
// as ordinary UTF-8. Reject embedded NUL and unpaired surrogates, never truncate.
static char *read_utf8(JNIEnv *env, jstring value, size_t max_bytes) {
    if (value == NULL || (size_t) (*env)->GetStringLength(env, value) > max_bytes) {
        throw_error(env, "java/lang/IllegalArgumentException", "Invalid or oversized WireGuard input.");
        return NULL;
    }
    jsize length = (*env)->GetStringLength(env, value);
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) return NULL;
    char *result = (char *) malloc(max_bytes + 1);
    if (result == NULL) {
        (*env)->ReleaseStringChars(env, value, chars);
        throw_error(env, "java/lang/OutOfMemoryError", "Could not allocate WireGuard input.");
        return NULL;
    }
    size_t offset = 0;
    int valid = 1;
    for (jsize i = 0; i < length; ++i) {
        uint32_t code = chars[i];
        if (code >= 0xd800 && code <= 0xdbff) {
            if (++i >= length || chars[i] < 0xdc00 || chars[i] > 0xdfff) {
                valid = 0;
                break;
            }
            code = 0x10000 + ((code - 0xd800) << 10) + (chars[i] - 0xdc00);
        } else if (code == 0 || (code >= 0xdc00 && code <= 0xdfff)) {
            valid = 0;
            break;
        }
        size_t count = code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
        if (offset + count > max_bytes) {
            valid = 0;
            break;
        }
        if (count == 1) {
            result[offset++] = (char) code;
        } else {
            const unsigned char prefix[] = {0, 0, 0xc0, 0xe0, 0xf0};
            result[offset++] = (char) (prefix[count] | (code >> (6 * (count - 1))));
            for (size_t remaining = count - 1; remaining != 0; --remaining) {
                result[offset++] = (char) (0x80 | ((code >> (6 * (remaining - 1))) & 0x3f));
            }
        }
    }
    result[offset] = '\0';
    (*env)->ReleaseStringChars(env, value, chars);
    if (!valid) {
        free_text(result);
        throw_error(env, "java/lang/IllegalArgumentException", "Invalid or oversized WireGuard input.");
        return NULL;
    }
    return result;
}

JNIEXPORT jint JNICALL Java_app_morphe_extension_shared_wireguard_NativeTunnel_start(
        JNIEnv *env, jclass klass, jstring config, jstring username, jstring password, jint port) {
    (void) klass;
    if (port < 0 || port > 65535) {
        throw_error(env, "java/lang/IllegalArgumentException", "Invalid local proxy port.");
        return -1;
    }
    char *conf = read_utf8(env, config, 65536);
    if (conf == NULL) return -1;
    char *user = read_utf8(env, username, 128);
    if (user == NULL) {
        free_text(conf);
        return -1;
    }
    char *pass = read_utf8(env, password, 256);
    if (pass == NULL) {
        free_text(user);
        free_text(conf);
        return -1;
    }
    int result = morphe_wg_start(conf, user, pass, (int)port);
    free_text(pass);
    free_text(user);
    free_text(conf);
    if (result == -1) {
        throw_error(env, "java/lang/IllegalArgumentException", "Invalid or unsupported WireGuard configuration.");
    } else if (result == -2) {
        throw_error(env, "java/lang/IllegalStateException", "WireGuard proxy is already running.");
    } else if (result <= 0) {
        throw_error(env, "java/lang/IllegalStateException", "Could not start the local WireGuard proxy.");
    }
    return (jint) result;
}

JNIEXPORT void JNICALL Java_app_morphe_extension_shared_wireguard_NativeTunnel_stop(JNIEnv *env, jclass klass) {
    (void) env;
    (void) klass;
    morphe_wg_stop();
}

JNIEXPORT void JNICALL Java_app_morphe_extension_shared_wireguard_NativeTunnel_validate(JNIEnv *env, jclass klass, jstring config) {
    (void) klass;
    char *conf = read_utf8(env, config, 65536);
    if (conf == NULL) return;
    char *error = morphe_wg_validate(conf);
    free_text(conf);
    if (error != NULL) {
        throw_error(env, "app/morphe/extension/shared/wireguard/NativeTunnel$InvalidProfileException", error);
        free(error);
    }
}
