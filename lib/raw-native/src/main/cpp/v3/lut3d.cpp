#include "lut3d.h"
namespace raw_v3 {
CubeLut parseCubeFile(const std::string&) { return {}; }
#ifndef RAZ_NO_EGL
GLuint uploadCubeLutAsTexture3D(const CubeLut&) { return 0; }
#endif
}  // namespace raw_v3