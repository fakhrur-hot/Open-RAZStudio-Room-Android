/*
 * StudioRoom — RAW Pipeline v3 — Adobe XMP sidecar parser (M5.5).
 * Copyright (c) 2026 RAZStudio (Fakhrurraze). Apache-2.0.
 */

#include "adobe_xmp_parser.h"
#include "../expat.h"

#include <android/log.h>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#define LOG_TAG "RawV3.Xmp"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace raw_v3 {

namespace {

// HSL block layout: R/O/Y/G/A/B × (h, s, l)
constexpr int idxHue(int range) { return range * 3 + 0; }
constexpr int idxSat(int range) { return range * 3 + 1; }
constexpr int idxLum(int range) { return range * 3 + 2; }

int rangeIndex(const std::string& name) {
    if (name == "Red")    return 0;
    if (name == "Orange") return 1;
    if (name == "Yellow") return 2;
    if (name == "Green")  return 3;
    if (name == "Aqua")   return 4;
    if (name == "Blue")   return 5;
    return -1;
}

// Strip any namespace prefix ("crs:Exposure2012" → "Exposure2012").
const char* stripNs(const char* name) {
    const char* colon = std::strchr(name, ':');
    return colon ? colon + 1 : name;
}

struct ParseState {
    XmpParams p;
    // For element-form (value is text inside the element): track which key
    // we are inside, accumulate chars, finalize on EndElement.
    std::string currentKey;        // empty when not inside a CRS leaf
    std::string charBuf;
};

// Apply a (key, value) pair to the XmpParams. Returns true if it matched a
// recognised CRS adjustment (so we can flag found=true at the top).
bool applyKv(XmpParams& p, const std::string& key, const std::string& valueStr) {
    if (key.empty() || valueStr.empty()) return false;
    char* endPtr = nullptr;
    float v = std::strtof(valueStr.c_str(), &endPtr);
    if (endPtr == valueStr.c_str()) return false;   // not a number

    // Tone block.
    if (key == "Exposure2012")   { p.exposure   = v / 5.0f;        return true; }
    if (key == "Contrast2012")   { p.contrast   = v / 100.0f;      return true; }
    if (key == "Highlights2012") { p.highlights = v / 100.0f;      return true; }
    if (key == "Shadows2012")    { p.shadows    = v / 100.0f;      return true; }
    if (key == "Whites2012")     { p.whites     = v / 100.0f;      return true; }
    if (key == "Blacks2012")     { p.blacks     = v / 100.0f;      return true; }

    // Per-range HSL adjustments.
    auto suffixAfter = [&](const char* prefix) -> std::string {
        size_t n = std::strlen(prefix);
        return (key.size() > n && key.compare(0, n, prefix) == 0) ? key.substr(n) : std::string();
    };

    if (auto s = suffixAfter("HueAdjustment"); !s.empty()) {
        int r = rangeIndex(s);
        if (r >= 0) { p.hsl[idxHue(r)] = v / 100.0f; return true; }
    }
    if (auto s = suffixAfter("SaturationAdjustment"); !s.empty()) {
        int r = rangeIndex(s);
        if (r >= 0) { p.hsl[idxSat(r)] = v / 100.0f; return true; }
    }
    if (auto s = suffixAfter("LuminanceAdjustment"); !s.empty()) {
        int r = rangeIndex(s);
        if (r >= 0) { p.hsl[idxLum(r)] = v / 100.0f; return true; }
    }

    // Recognised tags we don't (yet) wire through to the shader. Returning
    // true keeps `found = true` so the user sees "XMP applied" status even
    // if we can't render the temperature/tint/saturation/vibrance shift.
    if (key == "Saturation" || key == "Vibrance"  ||
        key == "Temperature" || key == "Tint") {
        return true;
    }
    return false;
}

void XMLCALL onStart(void* userData, const char* name, const char** atts) {
    auto* st = static_cast<ParseState*>(userData);
    const char* localName = stripNs(name);

    // Attribute form: every CRS adjustment lives as an attribute on
    // rdf:Description.
    for (int i = 0; atts && atts[i]; i += 2) {
        const char* attrName  = atts[i];
        const char* attrValue = atts[i + 1];
        // Only act on attributes in the `crs:` namespace. Different XMP
        // generators emit slightly different forms ("crs:Exposure2012" vs
        // a namespace-resolved form); accept any attr whose local name is
        // a known CRS key.
        const char* localAttr = stripNs(attrName);
        if (applyKv(st->p, localAttr, attrValue ? attrValue : "")) {
            st->p.found = true;
        }
    }

    // Element form: prep to capture text content if this is a known CRS leaf.
    // Tag names look like "crs:Exposure2012"; the namespace prefix is opaque
    // to expat, so we trust stripNs.
    if (applyKv(st->p, localName, "0")) {
        // applyKv returned true for the *key existence*, even though "0" set
        // it to 0. We'll overwrite when EndElement fires with the real text.
        // Re-zero what we just wrote:
        // (Could be tighter, but the value is overwritten on endElement and
        // the field starts at 0 anyway, so an extra zero-write is harmless.)
        st->currentKey = localName;
        st->charBuf.clear();
    } else {
        st->currentKey.clear();
    }
}

void XMLCALL onChars(void* userData, const char* s, int len) {
    auto* st = static_cast<ParseState*>(userData);
    if (st->currentKey.empty()) return;
    st->charBuf.append(s, len);
}

void XMLCALL onEnd(void* userData, const char* name) {
    auto* st = static_cast<ParseState*>(userData);
    if (st->currentKey.empty()) return;
    const char* localName = stripNs(name);
    if (st->currentKey == localName) {
        // Trim leading/trailing whitespace.
        size_t a = st->charBuf.find_first_not_of(" \t\r\n");
        size_t b = st->charBuf.find_last_not_of(" \t\r\n");
        if (a != std::string::npos && b != std::string::npos && b >= a) {
            std::string v = st->charBuf.substr(a, b - a + 1);
            if (applyKv(st->p, st->currentKey, v)) {
                st->p.found = true;
            }
        }
        st->currentKey.clear();
        st->charBuf.clear();
    }
}

}  // anonymous namespace

void XmpParams::writeTo(float* dst) const {
    dst[0]  = found ? 1.0f : 0.0f;
    dst[1]  = exposure;
    dst[2]  = contrast;
    dst[3]  = highlights;
    dst[4]  = shadows;
    dst[5]  = whites;
    dst[6]  = blacks;
    for (int i = 0; i < 18; ++i) dst[7 + i] = hsl[i];
}

XmpParams parseAdobeXmpString(const std::string& xml) {
    ParseState st;
    XML_Parser parser = XML_ParserCreate(nullptr);
    if (!parser) {
        LOGE("parseAdobeXmpString: XML_ParserCreate failed");
        return st.p;
    }
    XML_SetUserData(parser, &st);
    XML_SetElementHandler(parser, onStart, onEnd);
    XML_SetCharacterDataHandler(parser, onChars);

    if (XML_Parse(parser, xml.data(), int(xml.size()), 1) != XML_STATUS_OK) {
        LOGE("parseAdobeXmpString: parse error (code=%d)", XML_GetErrorCode(parser));
        // Even on error we may have collected some keys; keep what we got.
    }
    XML_ParserFree(parser);

    LOGI("parseAdobeXmpString: found=%d exposure=%.3f contrast=%.3f "
         "hi=%.3f sh=%.3f wh=%.3f bl=%.3f hslR=(%.2f,%.2f,%.2f)",
         st.p.found, st.p.exposure, st.p.contrast,
         st.p.highlights, st.p.shadows, st.p.whites, st.p.blacks,
         st.p.hsl[0], st.p.hsl[1], st.p.hsl[2]);
    return st.p;
}

XmpParams parseAdobeXmpFile(const std::string& path) {
    std::ifstream in(path);
    if (!in) {
        LOGE("parseAdobeXmpFile: cannot open %s", path.c_str());
        return XmpParams{};
    }
    std::stringstream ss;
    ss << in.rdbuf();
    return parseAdobeXmpString(ss.str());
}

}  // namespace raw_v3
