/*
 * pthread.h stub — desktop parity harness ONLY.
 *
 * gles_renderer.h declares a pthread_mutex_t member and two inline
 * lock/unlock helpers. The parity target parses that header (for the
 * ShaderParams struct + GradingInputs) but never instantiates GlesRenderer,
 * so these definitions exist purely to satisfy the compiler; they are never
 * executed. Do NOT use this stub for any target that actually runs
 * GlesRenderer code.
 */
#pragma once

typedef struct raz_parity_fake_mutex { void* opaque; } pthread_mutex_t;
typedef struct raz_parity_fake_mutexattr { int unused; } pthread_mutexattr_t;

static inline int pthread_mutex_init(pthread_mutex_t* m, const pthread_mutexattr_t* a) {
    (void)m; (void)a; return 0;
}
static inline int pthread_mutex_destroy(pthread_mutex_t* m) { (void)m; return 0; }
static inline int pthread_mutex_lock(pthread_mutex_t* m)    { (void)m; return 0; }
static inline int pthread_mutex_unlock(pthread_mutex_t* m)  { (void)m; return 0; }
