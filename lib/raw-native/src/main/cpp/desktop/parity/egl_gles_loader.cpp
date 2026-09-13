/*
 * egl_gles_loader — Windows dynamic loader for ANGLE's libEGL.dll/libGLESv2.dll.
 *
 * The engine sources (offscreen_save_renderer.cpp, grading_uniforms.cpp) call
 * egl/gl symbols directly, exactly as they do on Android where the NDK
 * provides import libraries. On Windows there is no GLES SDK to link, so this
 * TU *defines* every symbol the parity harness needs and forwards each call
 * through a pointer resolved from ANGLE at runtime.
 *
 * ANGLE DLL search order (first hit wins):
 *   1. %RAZ_ANGLE_DIR%              — explicit override
 *   2. the harness exe's directory  — drop-in DLLs
 *   3. newest Microsoft Edge install (ships ANGLE on every Windows box)
 *   4. newest Google Chrome install
 *
 * All parity-target TUs compile with KHRONOS_STATIC so the Khronos headers
 * declare these functions without __declspec(dllimport) — otherwise defining
 * them here would be C2491.
 */

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/hardware_buffer.h>   // parity compat stub — AHardwareBuffer typedef

#include <cstdio>
#include <string>
#include <vector>

namespace {

HMODULE g_egl  = nullptr;
HMODULE g_gles = nullptr;
std::string g_loadedFrom;

// Highest-version-name subdirectory containing libEGL.dll under [base].
std::string newestWithAngle(const std::string& base) {
    WIN32_FIND_DATAA fd;
    std::string best;
    HANDLE h = FindFirstFileA((base + "\\*").c_str(), &fd);
    if (h == INVALID_HANDLE_VALUE) return best;
    do {
        if (!(fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) continue;
        std::string name = fd.cFileName;
        if (name == "." || name == "..") continue;
        std::string cand = base + "\\" + name;
        DWORD attrs = GetFileAttributesA((cand + "\\libEGL.dll").c_str());
        if (attrs == INVALID_FILE_ATTRIBUTES) continue;
        // Version dirs sort correctly enough lexicographically for same-width
        // components; ties don't matter — any ANGLE new enough works.
        if (name > best) best = name;
    } while (FindNextFileA(h, &fd));
    FindClose(h);
    return best.empty() ? best : base + "\\" + best;
}

bool tryLoadFrom(const std::string& dir) {
    if (dir.empty()) return false;
    // GLESv2 first: libEGL links against it, and loading by absolute path with
    // ALTERED_SEARCH_PATH lets its dependents resolve from the same directory.
    HMODULE gles = LoadLibraryExA((dir + "\\libGLESv2.dll").c_str(), nullptr,
                                  LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!gles) return false;
    HMODULE egl = LoadLibraryExA((dir + "\\libEGL.dll").c_str(), nullptr,
                                 LOAD_WITH_ALTERED_SEARCH_PATH);
    if (!egl) { FreeLibrary(gles); return false; }
    g_gles = gles;
    g_egl  = egl;
    g_loadedFrom = dir;
    return true;
}

std::string exeDir() {
    char buf[MAX_PATH];
    DWORD n = GetModuleFileNameA(nullptr, buf, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) return {};
    std::string p(buf, n);
    size_t slash = p.find_last_of('\\');
    return slash == std::string::npos ? std::string() : p.substr(0, slash);
}

void* razResolve(const char* name) {
    if (!g_gles) {
        std::fprintf(stderr, "[loader] FATAL: %s called before razparity_load_angle()\n", name);
        std::abort();
    }
    void* p = (void*) GetProcAddress(g_gles, name);
    if (!p) p = (void*) GetProcAddress(g_egl, name);
    if (!p) {
        // eglGetProcAddress covers extension entry points ANGLE doesn't export.
        auto egpa = (void* (EGLAPIENTRY*)(const char*))
            GetProcAddress(g_egl, "eglGetProcAddress");
        if (egpa) p = egpa(name);
    }
    if (!p) {
        std::fprintf(stderr, "[loader] FATAL: symbol %s not found in ANGLE DLLs\n", name);
        std::abort();
    }
    return p;
}

}  // namespace

extern "C" bool razparity_load_angle() {
    if (g_egl) return true;
    char envBuf[MAX_PATH];
    DWORD n = GetEnvironmentVariableA("RAZ_ANGLE_DIR", envBuf, MAX_PATH);
    if (n > 0 && n < MAX_PATH && tryLoadFrom(std::string(envBuf, n))) {}
    else if (tryLoadFrom(exeDir())) {}
    else if (tryLoadFrom(newestWithAngle("C:\\Program Files (x86)\\Microsoft\\Edge\\Application"))) {}
    else if (tryLoadFrom(newestWithAngle("C:\\Program Files\\Microsoft\\Edge\\Application"))) {}
    else if (tryLoadFrom(newestWithAngle("C:\\Program Files\\Google\\Chrome\\Application"))) {}
    else if (tryLoadFrom(newestWithAngle("C:\\Program Files (x86)\\Google\\Chrome\\Application"))) {}

    if (!g_egl) {
        std::fprintf(stderr,
            "[loader] ANGLE not found. Set RAZ_ANGLE_DIR to a directory containing\n"
            "         libEGL.dll + libGLESv2.dll, or place them beside the exe.\n");
        return false;
    }
    std::printf("[loader] ANGLE loaded from: %s\n", g_loadedFrom.c_str());
    return true;
}

// ── Symbol definitions ──────────────────────────────────────────────────────
// One lazily-resolved forwarder per entry point. `return fn args;` is valid
// for void returns too (returning a void expression).

#define RAZ_EGL(ret, name, PFN, params, args) \
    extern "C" ret EGLAPIENTRY name params { \
        static PFN fn = (PFN) razResolve(#name); \
        return fn args; \
    }

#define RAZ_GL(ret, name, PFN, params, args) \
    extern "C" ret GL_APIENTRY name params { \
        static PFN fn = (PFN) razResolve(#name); \
        return fn args; \
    }

RAZ_EGL(EGLDisplay, eglGetDisplay, PFNEGLGETDISPLAYPROC,
        (EGLNativeDisplayType d), (d))
RAZ_EGL(EGLBoolean, eglInitialize, PFNEGLINITIALIZEPROC,
        (EGLDisplay d, EGLint* maj, EGLint* min), (d, maj, min))
RAZ_EGL(EGLBoolean, eglChooseConfig, PFNEGLCHOOSECONFIGPROC,
        (EGLDisplay d, const EGLint* attribs, EGLConfig* cfgs, EGLint sz, EGLint* num),
        (d, attribs, cfgs, sz, num))
RAZ_EGL(EGLSurface, eglCreatePbufferSurface, PFNEGLCREATEPBUFFERSURFACEPROC,
        (EGLDisplay d, EGLConfig c, const EGLint* attribs), (d, c, attribs))
RAZ_EGL(EGLContext, eglCreateContext, PFNEGLCREATECONTEXTPROC,
        (EGLDisplay d, EGLConfig c, EGLContext share, const EGLint* attribs),
        (d, c, share, attribs))
RAZ_EGL(EGLBoolean, eglMakeCurrent, PFNEGLMAKECURRENTPROC,
        (EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext ctx),
        (d, draw, read, ctx))
RAZ_EGL(EGLBoolean, eglDestroySurface, PFNEGLDESTROYSURFACEPROC,
        (EGLDisplay d, EGLSurface s), (d, s))
RAZ_EGL(EGLBoolean, eglDestroyContext, PFNEGLDESTROYCONTEXTPROC,
        (EGLDisplay d, EGLContext c), (d, c))
RAZ_EGL(EGLBoolean, eglTerminate, PFNEGLTERMINATEPROC, (EGLDisplay d), (d))
RAZ_EGL(EGLint, eglGetError, PFNEGLGETERRORPROC, (void), ())
RAZ_EGL(const char*, eglQueryString, PFNEGLQUERYSTRINGPROC,
        (EGLDisplay d, EGLint name), (d, name))
RAZ_EGL(EGLBoolean, eglBindAPI, PFNEGLBINDAPIPROC, (EGLenum api), (api))
RAZ_EGL(EGLBoolean, eglSwapBuffers, PFNEGLSWAPBUFFERSPROC,
        (EGLDisplay d, EGLSurface s), (d, s))
RAZ_EGL(EGLBoolean, eglReleaseThread, PFNEGLRELEASETHREADPROC, (void), ())

extern "C" __eglMustCastToProperFunctionPointerType EGLAPIENTRY
eglGetProcAddress(const char* procname) {
    static PFNEGLGETPROCADDRESSPROC fn =
        (PFNEGLGETPROCADDRESSPROC) razResolve("eglGetProcAddress");
    return fn(procname);
}

RAZ_GL(void, glActiveTexture, PFNGLACTIVETEXTUREPROC, (GLenum t), (t))
RAZ_GL(void, glAttachShader, PFNGLATTACHSHADERPROC, (GLuint p, GLuint s), (p, s))
RAZ_GL(void, glBindAttribLocation, PFNGLBINDATTRIBLOCATIONPROC,
       (GLuint p, GLuint i, const GLchar* n), (p, i, n))
RAZ_GL(void, glBindBuffer, PFNGLBINDBUFFERPROC, (GLenum t, GLuint b), (t, b))
RAZ_GL(void, glBindFramebuffer, PFNGLBINDFRAMEBUFFERPROC, (GLenum t, GLuint f), (t, f))
RAZ_GL(void, glBindRenderbuffer, PFNGLBINDRENDERBUFFERPROC, (GLenum t, GLuint r), (t, r))
RAZ_GL(void, glBindTexture, PFNGLBINDTEXTUREPROC, (GLenum t, GLuint x), (t, x))
RAZ_GL(void, glBindVertexArray, PFNGLBINDVERTEXARRAYPROC, (GLuint a), (a))
RAZ_GL(void, glBlendFunc, PFNGLBLENDFUNCPROC, (GLenum s, GLenum d), (s, d))
RAZ_GL(void, glBufferData, PFNGLBUFFERDATAPROC,
       (GLenum t, GLsizeiptr sz, const void* d, GLenum u), (t, sz, d, u))
RAZ_GL(GLenum, glCheckFramebufferStatus, PFNGLCHECKFRAMEBUFFERSTATUSPROC,
       (GLenum t), (t))
RAZ_GL(void, glClear, PFNGLCLEARPROC, (GLbitfield m), (m))
RAZ_GL(void, glClearColor, PFNGLCLEARCOLORPROC,
       (GLfloat r, GLfloat g, GLfloat b, GLfloat a), (r, g, b, a))
RAZ_GL(void, glCompileShader, PFNGLCOMPILESHADERPROC, (GLuint s), (s))
RAZ_GL(GLuint, glCreateProgram, PFNGLCREATEPROGRAMPROC, (void), ())
RAZ_GL(GLuint, glCreateShader, PFNGLCREATESHADERPROC, (GLenum t), (t))
RAZ_GL(void, glDeleteBuffers, PFNGLDELETEBUFFERSPROC,
       (GLsizei n, const GLuint* b), (n, b))
RAZ_GL(void, glDeleteFramebuffers, PFNGLDELETEFRAMEBUFFERSPROC,
       (GLsizei n, const GLuint* f), (n, f))
RAZ_GL(void, glDeleteProgram, PFNGLDELETEPROGRAMPROC, (GLuint p), (p))
RAZ_GL(void, glDeleteRenderbuffers, PFNGLDELETERENDERBUFFERSPROC,
       (GLsizei n, const GLuint* r), (n, r))
RAZ_GL(void, glDeleteShader, PFNGLDELETESHADERPROC, (GLuint s), (s))
RAZ_GL(void, glDeleteTextures, PFNGLDELETETEXTURESPROC,
       (GLsizei n, const GLuint* t), (n, t))
RAZ_GL(void, glDeleteVertexArrays, PFNGLDELETEVERTEXARRAYSPROC,
       (GLsizei n, const GLuint* a), (n, a))
RAZ_GL(void, glDisable, PFNGLDISABLEPROC, (GLenum c), (c))
RAZ_GL(void, glDrawArrays, PFNGLDRAWARRAYSPROC,
       (GLenum m, GLint f, GLsizei c), (m, f, c))
RAZ_GL(void, glEnable, PFNGLENABLEPROC, (GLenum c), (c))
RAZ_GL(void, glEnableVertexAttribArray, PFNGLENABLEVERTEXATTRIBARRAYPROC,
       (GLuint i), (i))
RAZ_GL(void, glFinish, PFNGLFINISHPROC, (void), ())
RAZ_GL(void, glFlush, PFNGLFLUSHPROC, (void), ())
RAZ_GL(void, glFramebufferRenderbuffer, PFNGLFRAMEBUFFERRENDERBUFFERPROC,
       (GLenum t, GLenum a, GLenum rt, GLuint r), (t, a, rt, r))
RAZ_GL(void, glFramebufferTexture2D, PFNGLFRAMEBUFFERTEXTURE2DPROC,
       (GLenum t, GLenum a, GLenum tt, GLuint x, GLint l), (t, a, tt, x, l))
RAZ_GL(void, glGenBuffers, PFNGLGENBUFFERSPROC, (GLsizei n, GLuint* b), (n, b))
RAZ_GL(void, glGenFramebuffers, PFNGLGENFRAMEBUFFERSPROC,
       (GLsizei n, GLuint* f), (n, f))
RAZ_GL(void, glGenRenderbuffers, PFNGLGENRENDERBUFFERSPROC,
       (GLsizei n, GLuint* r), (n, r))
RAZ_GL(void, glGenTextures, PFNGLGENTEXTURESPROC, (GLsizei n, GLuint* t), (n, t))
RAZ_GL(void, glGenVertexArrays, PFNGLGENVERTEXARRAYSPROC,
       (GLsizei n, GLuint* a), (n, a))
RAZ_GL(GLenum, glGetError, PFNGLGETERRORPROC, (void), ())
RAZ_GL(void, glGetIntegerv, PFNGLGETINTEGERVPROC,
       (GLenum p, GLint* d), (p, d))
RAZ_GL(void, glGetProgramInfoLog, PFNGLGETPROGRAMINFOLOGPROC,
       (GLuint p, GLsizei sz, GLsizei* len, GLchar* log), (p, sz, len, log))
RAZ_GL(void, glGetProgramiv, PFNGLGETPROGRAMIVPROC,
       (GLuint p, GLenum n, GLint* d), (p, n, d))
RAZ_GL(void, glGetShaderInfoLog, PFNGLGETSHADERINFOLOGPROC,
       (GLuint s, GLsizei sz, GLsizei* len, GLchar* log), (s, sz, len, log))
RAZ_GL(void, glGetShaderiv, PFNGLGETSHADERIVPROC,
       (GLuint s, GLenum n, GLint* d), (s, n, d))
RAZ_GL(const GLubyte*, glGetString, PFNGLGETSTRINGPROC, (GLenum n), (n))
RAZ_GL(GLint, glGetUniformLocation, PFNGLGETUNIFORMLOCATIONPROC,
       (GLuint p, const GLchar* n), (p, n))
RAZ_GL(void, glInvalidateFramebuffer, PFNGLINVALIDATEFRAMEBUFFERPROC,
       (GLenum t, GLsizei n, const GLenum* a), (t, n, a))
RAZ_GL(void, glLinkProgram, PFNGLLINKPROGRAMPROC, (GLuint p), (p))
RAZ_GL(void, glPixelStorei, PFNGLPIXELSTOREIPROC, (GLenum p, GLint v), (p, v))
RAZ_GL(void, glReadPixels, PFNGLREADPIXELSPROC,
       (GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum t, void* d),
       (x, y, w, h, f, t, d))
RAZ_GL(void, glRenderbufferStorage, PFNGLRENDERBUFFERSTORAGEPROC,
       (GLenum t, GLenum f, GLsizei w, GLsizei h), (t, f, w, h))
RAZ_GL(void, glShaderSource, PFNGLSHADERSOURCEPROC,
       (GLuint s, GLsizei c, const GLchar* const* str, const GLint* len),
       (s, c, str, len))
RAZ_GL(void, glTexImage2D, PFNGLTEXIMAGE2DPROC,
       (GLenum t, GLint l, GLint i, GLsizei w, GLsizei h, GLint b, GLenum f,
        GLenum ty, const void* d),
       (t, l, i, w, h, b, f, ty, d))
RAZ_GL(void, glTexParameteri, PFNGLTEXPARAMETERIPROC,
       (GLenum t, GLenum p, GLint v), (t, p, v))
RAZ_GL(void, glTexStorage2D, PFNGLTEXSTORAGE2DPROC,
       (GLenum t, GLsizei l, GLenum f, GLsizei w, GLsizei h), (t, l, f, w, h))
RAZ_GL(void, glTexSubImage2D, PFNGLTEXSUBIMAGE2DPROC,
       (GLenum t, GLint l, GLint x, GLint y, GLsizei w, GLsizei h, GLenum f,
        GLenum ty, const void* d),
       (t, l, x, y, w, h, f, ty, d))
RAZ_GL(void, glUniform1f, PFNGLUNIFORM1FPROC, (GLint l, GLfloat v), (l, v))
RAZ_GL(void, glUniform1fv, PFNGLUNIFORM1FVPROC,
       (GLint l, GLsizei c, const GLfloat* v), (l, c, v))
RAZ_GL(void, glUniform1i, PFNGLUNIFORM1IPROC, (GLint l, GLint v), (l, v))
RAZ_GL(void, glUniform1iv, PFNGLUNIFORM1IVPROC,
       (GLint l, GLsizei c, const GLint* v), (l, c, v))
RAZ_GL(void, glUniform2f, PFNGLUNIFORM2FPROC,
       (GLint l, GLfloat x, GLfloat y), (l, x, y))
RAZ_GL(void, glUniform2fv, PFNGLUNIFORM2FVPROC,
       (GLint l, GLsizei c, const GLfloat* v), (l, c, v))
RAZ_GL(void, glUniform3f, PFNGLUNIFORM3FPROC,
       (GLint l, GLfloat x, GLfloat y, GLfloat z), (l, x, y, z))
RAZ_GL(void, glUniform3fv, PFNGLUNIFORM3FVPROC,
       (GLint l, GLsizei c, const GLfloat* v), (l, c, v))
RAZ_GL(void, glUniform4f, PFNGLUNIFORM4FPROC,
       (GLint l, GLfloat x, GLfloat y, GLfloat z, GLfloat w), (l, x, y, z, w))
RAZ_GL(void, glUniform4fv, PFNGLUNIFORM4FVPROC,
       (GLint l, GLsizei c, const GLfloat* v), (l, c, v))
RAZ_GL(void, glUniformMatrix4fv, PFNGLUNIFORMMATRIX4FVPROC,
       (GLint l, GLsizei c, GLboolean t, const GLfloat* v), (l, c, t, v))
RAZ_GL(void, glUseProgram, PFNGLUSEPROGRAMPROC, (GLuint p), (p))
RAZ_GL(void, glVertexAttribPointer, PFNGLVERTEXATTRIBPOINTERPROC,
       (GLuint i, GLint sz, GLenum t, GLboolean n, GLsizei s, const void* p),
       (i, sz, t, n, s, p))
RAZ_GL(void, glViewport, PFNGLVIEWPORTPROC,
       (GLint x, GLint y, GLsizei w, GLsizei h), (x, y, w, h))

// AHardwareBuffer stubs (declared in the parity compat header; never called —
// the AHB code paths live in gles_renderer.cpp, which this target excludes).
extern "C" void AHardwareBuffer_acquire(AHardwareBuffer*) { std::abort(); }
extern "C" void AHardwareBuffer_release(AHardwareBuffer*) { std::abort(); }
