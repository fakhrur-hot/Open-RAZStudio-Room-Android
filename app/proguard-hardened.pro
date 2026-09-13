# ── HARDENED build type — extra R8 rules on top of proguard-rules.pro ─────────
# Goal: raise the reverse-engineering cost of the Kotlin/Java layer (the native
# .so is separately hardened in CMakeLists via hidden visibility + strip). Only
# applied to the `hardened` build variant, never to the normal release, so the
# normal release stays easy to symbolicate/debug.

# 1. Strip ALL logging. The pipeline logs narrate control flow and real parameter
#    values ("[GL-ADJ] SMART_COLOR enable=1 wbMin=…", "segmentation OK in … ms",
#    EXIF make/model/iso). assumenosideeffects lets R8 delete these calls AND the
#    argument-building code, so none of it survives in bytecode.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
    public static boolean isLoggable(...);
}

# 2. Flatten + aggressively repackage the remaining Kotlin/Java so the class/
#    package structure no longer maps to the source layout, and R8 can inline
#    and merge across access boundaries (harder to chart the JNI bridge + the
#    macro→ShaderParams mapping that surrounds the native calls).
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5

# NOTE: -overloadaggressively is deliberately NOT used — it can break reflection
# and Compose/Hilt/serialization paths. If a specific flow breaks in the hardened
# build, add a targeted -keep here rather than relaxing everything.
