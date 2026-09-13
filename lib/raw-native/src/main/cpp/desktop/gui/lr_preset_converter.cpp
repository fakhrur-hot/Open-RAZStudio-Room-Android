#include "lr_preset_converter.h"
#include <regex>
#include <cctype>
#include <algorithm>
#include <cstdio>
#include <sstream>

namespace razgui {

// ─────────────────────────────────────────────────────────────────────────
// Format detection
// ─────────────────────────────────────────────────────────────────────────

LrPresetConverter::SourceFormat LrPresetConverter::detectFormat(const std::string& text) {
    std::string t = text;
    // Trim leading whitespace
    t.erase(0, t.find_first_not_of(" \t\n\r"));

    if (t.find("<?xml") == 0) return SourceFormat::XMP;
    if (t.find("<x:xmpmeta") != std::string::npos) return SourceFormat::XMP;
    if (t.find("xmlns:crs=") != std::string::npos) return SourceFormat::XMP;
    if (t.find("crs:Exposure2012") != std::string::npos) return SourceFormat::XMP;
    if (t.find("crs:Contrast2012") != std::string::npos) return SourceFormat::XMP;
    if (t.find("crs:ToneCurvePV2012") != std::string::npos) return SourceFormat::XMP;

    if (t.find("s = {") != std::string::npos) return SourceFormat::LRTEMPLATE;
    if (t.find("settings = {") != std::string::npos) return SourceFormat::LRTEMPLATE;
    if (t.find("Exposure2012") != std::string::npos && t.find("Contrast2012") != std::string::npos)
        return SourceFormat::LRTEMPLATE;

    return SourceFormat::UNKNOWN;
}

// ─────────────────────────────────────────────────────────────────────────
// XMP parser
// ─────────────────────────────────────────────────────────────────────────

LrPresetConverter::LrSettings LrPresetConverter::parseXml(const std::string& text) {
    LrSettings settings;
    std::regex attrRegex("(?:crs:)?([A-Za-z0-9_]+)=\"([^\"]*)\"");
    std::smatch m;
    std::string::const_iterator searchStart(text.cbegin());
    while (std::regex_search(searchStart, text.cend(), m, attrRegex)) {
        std::string key = m[1].str();
        std::string val = m[2].str();
        if (settings.raw.find(key) == settings.raw.end()) {
            settings.raw[key] = val;
        }
        searchStart = m.suffix().first;
    }
    return settings;
}

std::vector<std::pair<double, double>> LrPresetConverter::parseToneCurveXml(
    const std::string& text, const std::string& key) {
    std::vector<std::pair<double, double>> pts;
    // Find <ToneCurvePV2012>...<rdf:Seq>...</rdf:Seq>...</ToneCurvePV2012>
    std::string searchKey = "<" + key + ">";
    size_t keyStart = text.find(searchKey);
    if (keyStart == std::string::npos) return pts;
    size_t keyEnd = text.find("</" + key + ">", keyStart);
    if (keyEnd == std::string::npos) return pts;

    std::string block = text.substr(keyStart, keyEnd - keyStart);
    size_t seqStart = block.find("<rdf:Seq>");
    size_t seqEnd = block.find("</rdf:Seq>");
    if (seqStart == std::string::npos || seqEnd == std::string::npos) return pts;

    std::string seqBlock = block.substr(seqStart + 9, seqEnd - seqStart - 9);
    std::regex liRegex("<rdf:li>(.*?)</rdf:li>");
    std::smatch m;
    std::string::const_iterator searchStart(seqBlock.cbegin());
    while (std::regex_search(searchStart, seqBlock.cend(), m, liRegex)) {
        std::string content = m[1].str();
        std::istringstream iss(content);
        double x, y;
        char comma;
        if (iss >> x >> comma >> y) {
            pts.push_back({x, y});
        }
        searchStart = m.suffix().first;
    }
    return pts;
}

// ─────────────────────────────────────────────────────────────────────────
// LRTEMPLATE (Lua) parser
// ─────────────────────────────────────────────────────────────────────────

LrPresetConverter::LrSettings LrPresetConverter::parseLua(const std::string& text) {
    LrSettings settings;
    // Find settings = { ... }
    size_t idx = text.find("settings");
    if (idx == std::string::npos) return settings;
    size_t braceStart = text.find('{', idx);
    if (braceStart == std::string::npos) return settings;

    int depth = 0;
    size_t i = braceStart;
    size_t braceEnd = std::string::npos;
    while (i < text.length()) {
        if (text[i] == '{') depth++;
        else if (text[i] == '}') {
            depth--;
            if (depth == 0) { braceEnd = i; break; }
        }
        i++;
    }
    if (braceEnd == std::string::npos) return settings;

    std::string settingsBlock = text.substr(braceStart + 1, braceEnd - braceStart - 1);
    // Strip nested tables
    std::string flat;
    depth = 0;
    for (char c : settingsBlock) {
        if (c == '{') {
            while (depth > 0 && !settingsBlock.empty()) {
                if (settingsBlock[0] == '{') depth++;
                if (settingsBlock[0] == '}') depth--;
                settingsBlock = settingsBlock.substr(1);
            }
            flat += ' ';
        } else {
            flat += c;
        }
    }

    std::regex kvRegex(
        "([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[+-]?\\d+\\.?\\d*|true|false)"
    );
    std::smatch m;
    std::string::const_iterator searchStart(flat.cbegin());
    while (std::regex_search(searchStart, flat.cend(), m, kvRegex)) {
        std::string key = m[1].str();
        std::string val = m[2].str();
        if (settings.raw.find(key) == settings.raw.end()) {
            settings.raw[key] = val;
        }
        searchStart = m.suffix().first;
    }
    return settings;
}

std::vector<std::pair<double, double>> LrPresetConverter::parseToneCurveLua(
    const std::string& text, const std::string& key) {
    std::vector<std::pair<double, double>> pts;
    std::string searchKey = key + " =";
    size_t idx = text.find(searchKey);
    if (idx == std::string::npos) return pts;

    size_t braceStart = text.find('{', idx);
    size_t braceEnd = text.find('}', braceStart);
    if (braceStart == std::string::npos || braceEnd == std::string::npos) return pts;

    std::string numsStr = text.substr(braceStart + 1, braceEnd - braceStart - 1);
    std::istringstream iss(numsStr);
    double val;
    std::vector<double> nums;
    char comma;
    while (iss >> val) {
        nums.push_back(val);
        iss >> comma;  // skip comma
    }

    for (size_t i = 0; i + 1 < nums.size(); i += 2) {
        pts.push_back({nums[i], nums[i + 1]});
    }
    return pts;
}

// ─────────────────────────────────────────────────────────────────────────
// LrSettings impl
// ─────────────────────────────────────────────────────────────────────────

double LrPresetConverter::LrSettings::num(const std::string& key, double defVal) const {
    auto it = raw.find(key);
    if (it == raw.end()) return defVal;
    std::string val = it->second;
    // Remove quotes
    if (!val.empty() && val[0] == '"') val = val.substr(1);
    if (!val.empty() && val.back() == '"') val = val.substr(0, val.length() - 1);
    // Remove leading +
    if (!val.empty() && val[0] == '+') val = val.substr(1);
    try {
        return std::stod(val);
    } catch (...) {
        return defVal;
    }
}

std::string LrPresetConverter::LrSettings::str(const std::string& key, const std::string& defVal) const {
    auto it = raw.find(key);
    if (it == raw.end()) return defVal;
    std::string val = it->second;
    // Remove quotes
    if (!val.empty() && val[0] == '"') val = val.substr(1);
    if (!val.empty() && val.back() == '"') val = val.substr(0, val.length() - 1);
    return val;
}

bool LrPresetConverter::LrSettings::has(const std::string& key) const {
    auto it = raw.find(key);
    return it != raw.end() && !it->second.empty();
}

// ─────────────────────────────────────────────────────────────────────────
// Color space conversions
// ─────────────────────────────────────────────────────────────────────────

double LrPresetConverter::srgbToLinear(double c) {
    c = std::max(0.0, std::min(1.0, c));
    return (c <= 0.04045) ? (c / 12.92) : std::pow((c + 0.055) / 1.055, 2.4);
}

double LrPresetConverter::linearToSrgb(double c) {
    c = std::max(0.0, std::min(1.0, c));
    return (c <= 0.0031308) ? (c * 12.92) : (1.055 * std::pow(c, 1.0 / 2.4) - 0.055);
}

LrPresetConverter::Hsl LrPresetConverter::rgbToHsl(double r, double g, double b) {
    double maxc = std::max({r, g, b});
    double minc = std::min({r, g, b});
    double l = (maxc + minc) / 2.0;
    double d = maxc - minc;
    if (d == 0.0) return {0.0, 0.0, l};
    double s = d / (1.0 - std::abs(2 * l - 1) + 1e-8);
    double h = 0.0;
    if (maxc == r) h = std::fmod((g - b) / (d + 1e-8), 6.0);
    else if (maxc == g) h = (b - r) / (d + 1e-8) + 2;
    else h = (r - g) / (d + 1e-8) + 4;
    h = std::fmod(h * 60.0, 360.0);
    if (h < 0) h += 360;
    s = std::max(0.0, std::min(1.0, s));
    return {h, s, l};
}

void LrPresetConverter::hslToRgb(double h, double s, double l, double& r, double& g, double& b) {
    double c = (1.0 - std::abs(2 * l - 1)) * s;
    double hp = h / 60.0;
    double x = c * (1.0 - std::abs(std::fmod(hp, 2.0) - 1.0));
    double r1 = 0, g1 = 0, b1 = 0;
    if (hp < 1) { r1 = c; g1 = x; b1 = 0; }
    else if (hp < 2) { r1 = x; g1 = c; b1 = 0; }
    else if (hp < 3) { r1 = 0; g1 = c; b1 = x; }
    else if (hp < 4) { r1 = 0; g1 = x; b1 = c; }
    else if (hp < 5) { r1 = x; g1 = 0; b1 = c; }
    else { r1 = c; g1 = 0; b1 = x; }
    double m = l - c / 2.0;
    r = std::max(0.0, std::min(1.0, r1 + m));
    g = std::max(0.0, std::min(1.0, g1 + m));
    b = std::max(0.0, std::min(1.0, b1 + m));
}

int LrPresetConverter::hueBin(double hueDeg) {
    double h = std::fmod(hueDeg, 360.0);
    if (h < 0) h += 360;
    if (h < 15.0 || h >= 345.0) return 0;   // Red
    if (h < 45.0) return 1;                  // Orange
    if (h < 70.0) return 2;                  // Yellow
    if (h < 160.0) return 3;                 // Green
    if (h < 200.0) return 4;                 // Aqua
    if (h < 260.0) return 5;                 // Blue
    if (h < 310.0) return 6;                 // Purple
    return 7;                                 // Magenta
}

// ─────────────────────────────────────────────────────────────────────────
// Curve interpolation
// ─────────────────────────────────────────────────────────────────────────

double LrPresetConverter::interpolateCurve(
    const std::vector<std::pair<double, double>>& pts, double c) {
    if (pts.empty()) return c;
    std::vector<double> xs, ys;
    for (const auto& p : pts) {
        xs.push_back(p.first / 255.0);
        ys.push_back(p.second / 255.0);
    }
    if (c <= xs.front()) return ys.front();
    if (c >= xs.back()) return ys.back();
    for (size_t i = 0; i + 1 < xs.size(); i++) {
        if (c >= xs[i] && c <= xs[i + 1]) {
            double t = (c - xs[i]) / (xs[i + 1] - xs[i] + 1e-12);
            return ys[i] + t * (ys[i + 1] - ys[i]);
        }
    }
    return c;
}

// ─────────────────────────────────────────────────────────────────────────
// Pipeline implementation
// ─────────────────────────────────────────────────────────────────────────

LrPresetConverter::Pipeline::Pipeline(
    const LrSettings& settings,
    const std::vector<std::pair<double, double>>& tc,
    const std::vector<std::pair<double, double>>& tcR,
    const std::vector<std::pair<double, double>>& tcG,
    const std::vector<std::pair<double, double>>& tcB)
    : toneCurve(tc), toneCurveR(tcR), toneCurveG(tcG), toneCurveB(tcB) {

    exposure = settings.num("Exposure2012");
    contrast = settings.num("Contrast2012") / 100.0;
    highlights = settings.num("Highlights2012") / 100.0;
    shadows = settings.num("Shadows2012") / 100.0;
    whites = settings.num("Whites2012") / 100.0;
    blacks = settings.num("Blacks2012") / 100.0;
    saturation = settings.num("Saturation") / 100.0;
    vibrance = settings.num("Vibrance") / 100.0;

    const char* hslNames[] = {"Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Purple", "Magenta"};
    for (int i = 0; i < 6; i++) {
        char buf[64];
        std::snprintf(buf, 64, "HueAdjustment%s", hslNames[i]);
        hslHue[i] = settings.num(buf) / 100.0;
        std::snprintf(buf, 64, "SaturationAdjustment%s", hslNames[i]);
        hslSat[i] = settings.num(buf) / 100.0;
        std::snprintf(buf, 64, "LuminanceAdjustment%s", hslNames[i]);
        hslLum[i] = settings.num(buf) / 100.0;
    }
    hasHsl = false;
    for (int i = 0; i < 6; i++) {
        if (hslHue[i] != 0 || hslSat[i] != 0 || hslLum[i] != 0) { hasHsl = true; break; }
    }

    splitHiHue = settings.num("SplitToningHighlightHue");
    splitHiSat = settings.num("SplitToningHighlightSaturation") / 100.0;
    splitShHue = settings.num("SplitToningShadowHue");
    splitShSat = settings.num("SplitToningShadowSaturation") / 100.0;
    splitBalance = settings.num("SplitToningBalance") / 100.0;
    hasSplitToning = (splitHiSat != 0.0 || splitShSat != 0.0);

    calShadowTint = settings.num("CameraCalibrationShadowTint") / 100.0;
    calRedHue = settings.num("CameraCalibrationRedPrimaryHue") / 100.0;
    calRedSat = settings.num("CameraCalibrationRedPrimarySat") / 100.0;
    calGreenHue = settings.num("CameraCalibrationGreenPrimaryHue") / 100.0;
    calGreenSat = settings.num("CameraCalibrationGreenPrimarySat") / 100.0;
    calBlueHue = settings.num("CameraCalibrationBluePrimaryHue") / 100.0;
    calBlueSat = settings.num("CameraCalibrationBluePrimarySat") / 100.0;
    hasCalibration = (calShadowTint != 0 || calRedHue != 0 || calRedSat != 0 ||
                      calGreenHue != 0 || calGreenSat != 0 || calBlueHue != 0 || calBlueSat != 0);

    paramShadows = settings.num("ParametricShadows") / 100.0;
    paramDarks = settings.num("ParametricDarks") / 100.0;
    paramLights = settings.num("ParametricLights") / 100.0;
    paramHighlights = settings.num("ParametricHighlights") / 100.0;
    paramShadowSplit = settings.num("ParametricShadowSplit") / 100.0;
    paramMidtoneSplit = settings.num("ParametricMidtoneSplit") / 100.0;
    paramHighlightSplit = settings.num("ParametricHighlightSplit") / 100.0;
    hasParametric = (paramShadows != 0 || paramDarks != 0 || paramLights != 0 || paramHighlights != 0);
}

void LrPresetConverter::Pipeline::applyBasicTone(
    double rIn, double gIn, double bIn, double& r, double& g, double& b) const {
    r = linearToSrgb(srgbToLinear(rIn) * std::pow(2.0, exposure));
    g = linearToSrgb(srgbToLinear(gIn) * std::pow(2.0, exposure));
    b = linearToSrgb(srgbToLinear(bIn) * std::pow(2.0, exposure));

    auto contrastFn = [this](double c) { return std::max(0.0, std::min(1.0, (c - 0.5) * (1 + contrast) + 0.5)); };
    r = contrastFn(r);
    g = contrastFn(g);
    b = contrastFn(b);

    auto toneRegion = [this](double c) {
        double wHi = std::max(0.0, std::min(1.0, (c - 0.5) * 2.0));
        double wSh = std::max(0.0, std::min(1.0, (0.5 - c) * 2.0));
        double wWh = std::max(0.0, std::min(1.0, (c - 0.75) * 4.0));
        double wBl = std::max(0.0, std::min(1.0, (0.25 - c) * 4.0));
        double cc = c + highlights * 0.25 * wHi + shadows * 0.25 * wSh +
                    whites * 0.2 * wWh + blacks * 0.2 * wBl;
        return std::max(0.0, std::min(1.0, cc));
    };
    r = toneRegion(r);
    g = toneRegion(g);
    b = toneRegion(b);
}

double LrPresetConverter::Pipeline::applyParametricCurve(double c) const {
    if (!hasParametric) return c;
    double x = (c - 0.5) * 2.0;
    auto smoothStepUp = [](double x, double e0, double e1) {
        if (x <= e0) return 0.0;
        if (x >= e1) return 1.0;
        double t = (x - e0) / (e1 - e0);
        return t * t * (3.0 - 2.0 * t);
    };
    auto smoothBell = [smoothStepUp](double x, double e0, double e1) {
        double center = (e0 + e1) / 2.0;
        double half = (e1 - e0) / 2.0;
        if (half <= 0) return 0.0;
        double t = (x - center) / half;
        t = std::max(-1.0, std::min(1.0, t));
        return std::max(0.0, std::cos(t * 3.14159265 / 2.0));
    };

    double shadowEnd = paramShadowSplit * 2.0 - 1.0;
    double darkEnd = paramMidtoneSplit * 2.0 - 1.0;
    double highlightStart = paramHighlightSplit * 2.0 - 1.0;

    double wSh = 1.0 - smoothStepUp(x, -1.0, shadowEnd);
    double wDk = smoothBell(x, shadowEnd, darkEnd);
    double wLt = smoothBell(x, darkEnd, highlightStart);
    double wHi = smoothStepUp(x, highlightStart, 1.0);

    double delta = paramShadows * wSh + paramDarks * wDk + paramLights * wLt + paramHighlights * wHi;
    return std::max(0.0, std::min(1.0, c + delta * 0.25));
}

double LrPresetConverter::Pipeline::applyToneCurve(double c) const {
    double p = applyParametricCurve(c);
    return toneCurve.empty() ? p : interpolateCurve(toneCurve, p);
}

void LrPresetConverter::Pipeline::applyVibranceSaturation(double& r, double& g, double& b) const {
    Hsl hsl = rgbToHsl(r, g, b);
    double satMult = 1.0 + saturation + vibrance * (1.0 - std::sqrt(hsl.s));
    double s2 = std::max(0.0, std::min(1.0, hsl.s * satMult));
    hslToRgb(hsl.h, s2, hsl.l, r, g, b);
}

void LrPresetConverter::Pipeline::applyHsl(double& r, double& g, double& b) const {
    if (!hasHsl) return;
    Hsl hsl = rgbToHsl(r, g, b);
    int bin = hueBin(hsl.h);
    double newH = std::fmod(hsl.h + hslHue[bin] * 30.0, 360.0);
    double newS = std::max(0.0, std::min(1.0, hsl.s * (1.0 + hslSat[bin])));
    double newL = std::max(0.0, std::min(1.0, hsl.l * (1.0 + hslLum[bin] * 0.5)));
    hslToRgb(newH, newS, newL, r, g, b);
}

void LrPresetConverter::Pipeline::applySplitToning(double& r, double& g, double& b) const {
    if (!hasSplitToning) return;
    double luma = 0.299 * r + 0.587 * g + 0.114 * b;
    double crossover = 0.5 + splitBalance * 0.25;
    auto smoothStepUp = [](double x, double e0, double e1) {
        if (x <= e0) return 0.0;
        if (x >= e1) return 1.0;
        double t = (x - e0) / (e1 - e0);
        return t * t * (3.0 - 2.0 * t);
    };
    double highlightWeight = smoothStepUp(luma, crossover - 0.15, crossover + 0.15);
    double shadowWeight = 1.0 - highlightWeight;

    auto tintColor = [this](double hue, double strength) -> std::tuple<double, double, double> {
        if (strength <= 0.0) return {0, 0, 0};
        double tr, tg, tb;
        hslToRgb(hue, 1.0, 0.5, tr, tg, tb);
        return {tr * strength, tg * strength, tb * strength};
    };

    auto [hiR, hiG, hiB] = tintColor(splitHiHue, splitHiSat * highlightWeight);
    auto [shR, shG, shB] = tintColor(splitShHue, splitShSat * shadowWeight);

    r = std::max(0.0, std::min(1.0, r + hiR + shR));
    g = std::max(0.0, std::min(1.0, g + hiG + shG));
    b = std::max(0.0, std::min(1.0, b + hiB + shB));
}

void LrPresetConverter::Pipeline::applyCalibration(double& r, double& g, double& b) const {
    if (!hasCalibration) return;
    double luma = 0.299 * r + 0.587 * g + 0.114 * b;
    double shadowWeight = 1.0 - luma;
    double tintR = 1.0 + calShadowTint * 0.2 * shadowWeight;
    double tintG = 1.0 - calShadowTint * 0.3 * shadowWeight;
    double tintB = 1.0 + calShadowTint * 0.15 * shadowWeight;

    Hsl hsl = rgbToHsl(r * tintR, g * tintG, b * tintB);
    int bin = hueBin(hsl.h);
    double hueShift = 0, satShift = 0;
    if (bin == 0) { hueShift = calRedHue; satShift = calRedSat; }
    else if (bin == 3) { hueShift = calGreenHue; satShift = calGreenSat; }
    else if (bin == 5) { hueShift = calBlueHue; satShift = calBlueSat; }

    double newH = std::fmod(hsl.h + hueShift * 30.0, 360.0);
    double newS = std::max(0.0, std::min(1.0, hsl.s * (1.0 + satShift)));
    hslToRgb(newH, newS, hsl.l, r, g, b);
}

void LrPresetConverter::Pipeline::process(
    double rIn, double gIn, double bIn, double& rOut, double& gOut, double& bOut) const {
    applyBasicTone(rIn, gIn, bIn, rOut, gOut, bOut);
    rOut = applyToneCurve(rOut);
    gOut = applyToneCurve(gOut);
    bOut = applyToneCurve(bOut);
    if (!toneCurveR.empty()) rOut = interpolateCurve(toneCurveR, rOut);
    if (!toneCurveG.empty()) gOut = interpolateCurve(toneCurveG, gOut);
    if (!toneCurveB.empty()) bOut = interpolateCurve(toneCurveB, bOut);
    applyVibranceSaturation(rOut, gOut, bOut);
    applyHsl(rOut, gOut, bOut);
    applySplitToning(rOut, gOut, bOut);
    applyCalibration(rOut, gOut, bOut);
}

// ─────────────────────────────────────────────────────────────────────────
// Main conversion entry point
// ─────────────────────────────────────────────────────────────────────────

std::string LrPresetConverter::convertToCubeString(const std::string& presetText, int cubeSize) {
    try {
        SourceFormat fmt = detectFormat(presetText);
        if (fmt == SourceFormat::UNKNOWN) return "";

        LrSettings settings = (fmt == SourceFormat::XMP) ? parseXml(presetText) : parseLua(presetText);

        std::vector<std::pair<double, double>> curve, curveR, curveG, curveB;
        if (fmt == SourceFormat::XMP) {
            curve = parseToneCurveXml(presetText, "crs:ToneCurvePV2012");
            curveR = parseToneCurveXml(presetText, "crs:ToneCurvePV2012Red");
            curveG = parseToneCurveXml(presetText, "crs:ToneCurvePV2012Green");
            curveB = parseToneCurveXml(presetText, "crs:ToneCurvePV2012Blue");
        } else {
            curve = parseToneCurveLua(presetText, "ToneCurvePV2012");
            curveR = parseToneCurveLua(presetText, "ToneCurvePV2012Red");
            curveG = parseToneCurveLua(presetText, "ToneCurvePV2012Green");
            curveB = parseToneCurveLua(presetText, "ToneCurvePV2012Blue");
        }

        Pipeline pipe(settings, curve, curveR, curveG, curveB);

        std::string result;
        result += "TITLE \"Converted Lightroom Preset\"\n";
        result += "LUT_3D_SIZE " + std::to_string(cubeSize) + "\n";
        result += "DOMAIN_MIN 0.0 0.0 0.0\n";
        result += "DOMAIN_MAX 1.0 1.0 1.0\n";

        double step = 1.0 / (cubeSize - 1);
        for (int bi = 0; bi < cubeSize; bi++) {
            double bVal = bi * step;
            for (int gi = 0; gi < cubeSize; gi++) {
                double gVal = gi * step;
                for (int ri = 0; ri < cubeSize; ri++) {
                    double rVal = ri * step;
                    double r, g, b;
                    pipe.process(rVal, gVal, bVal, r, g, b);
                    char buf[64];
                    std::snprintf(buf, 64, "%.6f %.6f %.6f\n", r, g, b);
                    result += buf;
                }
            }
        }
        return result;
    } catch (...) {
        return "";
    }
}

} // namespace razgui
