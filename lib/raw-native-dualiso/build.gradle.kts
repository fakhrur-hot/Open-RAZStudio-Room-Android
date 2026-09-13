/*
 * StudioRoom Magic-Lantern dual-ISO blend module
 * Copyright (C) 2013 Magic Lantern Team (original cr2hdr.c)
 * Copyright (C) 2026 RAZStudio (Fakhrurraze)  — Android port
 *
 * This module is licensed under the GNU General Public License v2 because
 * it is a direct port of cr2hdr.c from the Magic Lantern project.
 * See LICENSE-GPL2 in this module's root.
 *
 * The rest of the StudioRoom Room app remains under the Apache License 2.0;
 * this module is intentionally separate so the GPL-2 obligations do not
 * propagate to the main app's source tree. The JNI boundary is the
 * "system library" boundary for license purposes — calls from Apache
 * code into this module run through `System.loadLibrary` like any other
 * native dependency.
 */

plugins {
    alias(libs.plugins.image.toolbox.library)
}

android {
    namespace = "com.raz.razstudio.lib.dualiso"

    defaultConfig {
        ndk {
            // Match sibling raw-native module — arm64 only.
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
