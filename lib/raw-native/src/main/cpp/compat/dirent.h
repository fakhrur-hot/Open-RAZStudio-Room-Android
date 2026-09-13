/*
 * dirent.h — minimal Windows shim
 * ================================
 * lensfun_android.cpp scans the lensfun DB directory with the POSIX API, which
 * MSVC/clang-cl do not provide. This supplies just enough of it that the file
 * compiles UNMODIFIED. Placed on the include path for the desktop target only.
 *
 * Exactly what lensfun_android.cpp uses (verified, nothing more):
 *     DIR* dir = opendir(dbDir);
 *     struct dirent* ent;  while ((ent = readdir(dir))) { ent->d_name ... }
 *     closedir(dir);
 * No d_type, no rewinddir, no seekdir — deliberately not implemented rather
 * than half-implemented.
 *
 * Sizing note: _finddata_t::name is char[260], so d_name MUST be at least that
 * big. Declaring it _MAX_FNAME (256) would let a long filename overflow, and
 * strcpy_s ABORTS the process on overflow rather than truncating.
 */

#ifndef RAZ_COMPAT_DIRENT_H
#define RAZ_COMPAT_DIRENT_H

#if !defined(_WIN32)
#error "compat/dirent.h is a Windows shim; POSIX platforms have the real header."
#endif

#include <io.h>
#include <cstring>
#include <cstdlib>
#include <string>

struct dirent {
    char d_name[_MAX_PATH];   /* >= sizeof(_finddata_t::name) */
};

typedef struct DIR {
    intptr_t            handle;   /* -1 once exhausted */
    struct _finddata_t  info;
    struct dirent       entry;
    bool                pending;  /* _findfirst already produced a result */
} DIR;

inline DIR* opendir(const char* path) {
    if (!path || !*path) return nullptr;
    DIR* d = new (std::nothrow) DIR();
    if (!d) return nullptr;

    std::string pattern(path);
    const char last = pattern.empty() ? '\0' : pattern.back();
    if (last != '\\' && last != '/') pattern += '\\';
    pattern += '*';

    d->handle = _findfirst(pattern.c_str(), &d->info);
    if (d->handle == -1) {          /* missing dir, or genuinely empty */
        delete d;
        return nullptr;
    }
    d->pending = true;
    return d;
}

inline struct dirent* readdir(DIR* d) {
    if (!d || d->handle == -1) return nullptr;
    if (d->pending) {
        d->pending = false;         /* consume the _findfirst hit */
    } else if (_findnext(d->handle, &d->info) != 0) {
        return nullptr;
    }
    /* strncpy + explicit NUL: never aborts, unlike strcpy_s on overflow. */
    std::strncpy(d->entry.d_name, d->info.name, sizeof(d->entry.d_name) - 1);
    d->entry.d_name[sizeof(d->entry.d_name) - 1] = '\0';
    return &d->entry;
}

inline int closedir(DIR* d) {
    if (!d) return -1;
    if (d->handle != -1) _findclose(d->handle);
    delete d;
    return 0;
}

#endif  /* RAZ_COMPAT_DIRENT_H */
