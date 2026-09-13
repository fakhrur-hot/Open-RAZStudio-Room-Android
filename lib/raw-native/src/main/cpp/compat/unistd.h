/*
 * unistd.h — minimal Windows shim
 * ================================
 * v3/tiff_mmap_io.cpp uses ::open/::close/fstat on a POSIX fd. MSVC provides
 * those as underscore-prefixed CRT functions in <io.h>/<fcntl.h>; this header
 * just supplies the POSIX spellings so the file compiles UNMODIFIED.
 */

#ifndef RAZ_COMPAT_UNISTD_H
#define RAZ_COMPAT_UNISTD_H

#if !defined(_WIN32)
#error "compat/unistd.h is a Windows shim; POSIX platforms have the real header."
#endif

#include <io.h>
#include <fcntl.h>
#include <process.h>
#include <stdint.h>

/* MSVC has no ssize_t. */
#if !defined(_SSIZE_T_DEFINED)
#define _SSIZE_T_DEFINED
typedef intptr_t ssize_t;
#endif

/*
 * Deliberately NO open/close/read/write wrappers here.
 *
 * MSVC's <io.h> already declares the POSIX spellings (as deprecated aliases of
 * _open/_close/...), so adding our own overloads makes every call site
 * ambiguous:  "error: call to 'open' is ambiguous"  (tiff_mmap_io.cpp:277).
 * Including <io.h> and <fcntl.h> above is sufficient.
 *
 * CRT text-mode translation is not a concern for the one consumer here:
 * tiff_mmap_io.cpp only uses the fd to obtain the OS handle via
 * _get_osfhandle() for the file mapping, which bypasses CRT translation
 * entirely. Nothing reads bytes through the fd itself.
 */

#endif  /* RAZ_COMPAT_UNISTD_H */
