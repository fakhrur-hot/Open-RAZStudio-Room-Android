/*
 * sys/mman.h — minimal Windows shim (read-only file mapping)
 * ===========================================================
 * v3/tiff_mmap_io.cpp memory-maps the Stage A BigTIFF for reading. Windows has
 * no mmap; this maps the POSIX calls onto CreateFileMapping/MapViewOfFile so
 * the file compiles UNMODIFIED, like the other compat/ shims.
 *
 * Exactly what tiff_mmap_io.cpp uses (verified, nothing more):
 *     mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0)
 *     munmap(ptr, size)
 *     MAP_FAILED
 * Write mappings, MAP_FIXED, msync, madvise and non-zero offsets are NOT
 * implemented — mmap() rejects them rather than pretending to succeed.
 */

#ifndef RAZ_COMPAT_SYS_MMAN_H
#define RAZ_COMPAT_SYS_MMAN_H

#if !defined(_WIN32)
#error "compat/sys/mman.h is a Windows shim; POSIX platforms have the real header."
#endif

#include <io.h>
#include <stdint.h>
#include <windows.h>

#define PROT_NONE   0x0
#define PROT_READ   0x1
#define PROT_WRITE  0x2
#define PROT_EXEC   0x4

#define MAP_SHARED  0x01
#define MAP_PRIVATE 0x02
#define MAP_FAILED  ((void*) -1)

/*
 * Read-only shared mapping of the whole file. `length` is honoured only as a
 * sanity bound: MapViewOfFile(0) maps to end-of-file, which is what the caller
 * wants (it passes the fstat size).
 */
static inline void* mmap(void* addr, size_t length, int prot, int flags,
                         int fd, long long offset) {
    (void) addr; (void) flags;
    /* Unsupported combinations fail loudly instead of silently misbehaving. */
    if (offset != 0)          return MAP_FAILED;
    if (prot & PROT_WRITE)    return MAP_FAILED;
    if (!(prot & PROT_READ))  return MAP_FAILED;
    if (length == 0)          return MAP_FAILED;

    const HANDLE hFile = (HANDLE) _get_osfhandle(fd);
    if (hFile == INVALID_HANDLE_VALUE) return MAP_FAILED;

    const HANDLE hMap = CreateFileMappingW(hFile, NULL, PAGE_READONLY, 0, 0, NULL);
    if (hMap == NULL) return MAP_FAILED;

    void* view = MapViewOfFile(hMap, FILE_MAP_READ, 0, 0, 0);
    /* The view keeps the section alive, so the handle can be dropped now —
       this is what lets munmap() need nothing but the pointer. */
    CloseHandle(hMap);

    return view ? view : MAP_FAILED;
}

static inline int munmap(void* addr, size_t length) {
    (void) length;
    if (!addr || addr == MAP_FAILED) return -1;
    return UnmapViewOfFile(addr) ? 0 : -1;
}

#endif  /* RAZ_COMPAT_SYS_MMAN_H */
