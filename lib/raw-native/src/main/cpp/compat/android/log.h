/*
 * android/log.h — desktop shim (Windows / Linux / macOS)
 * ======================================================
 * Lets the batch-path sources compile off-Android with ZERO edits.
 *
 * Every batch-critical file does exactly this:
 *
 *     #include <android/log.h>
 *     #define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
 *
 * ...so rather than patching six files (and creating permanent merge friction
 * with the Android build), this header supplies the same two symbols. It is
 * placed on the include path ONLY for the desktop `razbatch` target — the
 * Android build never sees it and keeps using the real NDK header.
 *
 * Output goes to stderr so stdout stays clean for machine-readable results.
 */

#ifndef RAZ_COMPAT_ANDROID_LOG_H
#define RAZ_COMPAT_ANDROID_LOG_H

#ifdef __ANDROID__
#error "compat/android/log.h must not be used for Android builds — the NDK provides the real header."
#endif

#include <cstdarg>
#include <cstdio>

typedef enum android_LogPriority {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT,
} android_LogPriority;

/* Set to 0 by the CLI's --quiet to silence per-file pipeline chatter. */
#ifdef __cplusplus
extern "C" {
#endif

extern int raz_log_enabled;

static inline const char* raz_log_prio(int prio) {
    switch (prio) {
        case ANDROID_LOG_VERBOSE: return "V";
        case ANDROID_LOG_DEBUG:   return "D";
        case ANDROID_LOG_INFO:    return "I";
        case ANDROID_LOG_WARN:    return "W";
        case ANDROID_LOG_ERROR:   return "E";
        case ANDROID_LOG_FATAL:   return "F";
        default:                  return "?";
    }
}

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    /* Errors always surface; everything else honours --quiet. */
    if (!raz_log_enabled && prio < ANDROID_LOG_ERROR) return 0;
    FILE* out = stderr;
    fprintf(out, "%s/%s: ", raz_log_prio(prio), tag ? tag : "?");
    va_list ap;
    va_start(ap, fmt);
    const int n = vfprintf(out, fmt, ap);
    va_end(ap);
    fputc('\n', out);
    return n;
}

static inline int __android_log_write(int prio, const char* tag, const char* text) {
    return __android_log_print(prio, tag, "%s", text ? text : "");
}

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif  /* RAZ_COMPAT_ANDROID_LOG_H */
