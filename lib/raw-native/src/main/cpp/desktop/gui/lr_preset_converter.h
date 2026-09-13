#pragma once
#include <string>
#include <vector>
#include <map>
#include <cmath>

namespace razgui {

/**
 * Converts Lightroom .xmp (XML) or .lrtemplate (Lua) presets to 33-point cube LUTs.
 * Supports: exposure, contrast, highlights, shadows, whites, blacks, vibrance,
 * saturation, tone curves (master + RGB), parametric curves, HSL per-color,
 * split toning, and camera calibration.
 */
class LrPresetConverter {
public:
    /**
     * Parse an XMP or LRTEMPLATE file and convert to a cube LUT string.
     * Returns empty string on failure.
     */
    static std::string convertToCubeString(const std::string& presetText, int cubeSize = 33);

private:
    enum class SourceFormat { LRTEMPLATE, XMP, UNKNOWN };

    struct LrSettings {
        std::map<std::string, std::string> raw;

        double num(const std::string& key, double defVal = 0.0) const;
        std::string str(const std::string& key, const std::string& defVal = "") const;
        bool has(const std::string& key) const;
    };

    static SourceFormat detectFormat(const std::string& text);
    static LrSettings parseXml(const std::string& text);
    static LrSettings parseLua(const std::string& text);
    static std::vector<std::pair<double, double>> parseToneCurveXml(
        const std::string& text, const std::string& key = "crs:ToneCurvePV2012");
    static std::vector<std::pair<double, double>> parseToneCurveLua(
        const std::string& text, const std::string& key = "ToneCurvePV2012");

    // Color space conversions
    static double srgbToLinear(double c);
    static double linearToSrgb(double c);

    struct Hsl { double h, s, l; };
    static Hsl rgbToHsl(double r, double g, double b);
    static void hslToRgb(double h, double s, double l, double& r, double& g, double& b);
    static int hueBin(double hueDeg);

    // Interpolate tone curve
    static double interpolateCurve(const std::vector<std::pair<double, double>>& pts, double c);

    // Main processing pipeline
    class Pipeline {
    public:
        Pipeline(const LrSettings& settings,
                 const std::vector<std::pair<double, double>>& toneCurve,
                 const std::vector<std::pair<double, double>>& toneCurveR,
                 const std::vector<std::pair<double, double>>& toneCurveG,
                 const std::vector<std::pair<double, double>>& toneCurveB);

        void process(double rIn, double gIn, double bIn, double& rOut, double& gOut, double& bOut) const;

    private:
        double exposure, contrast, highlights, shadows, whites, blacks;
        double saturation, vibrance;
        double hslHue[6], hslSat[6], hslLum[6];
        bool hasHsl;
        double splitHiHue, splitHiSat, splitShHue, splitShSat, splitBalance;
        bool hasSplitToning;
        double calShadowTint, calRedHue, calRedSat, calGreenHue, calGreenSat, calBlueHue, calBlueSat;
        bool hasCalibration;
        double paramShadows, paramDarks, paramLights, paramHighlights;
        double paramShadowSplit, paramMidtoneSplit, paramHighlightSplit;
        bool hasParametric;
        std::vector<std::pair<double, double>> toneCurve, toneCurveR, toneCurveG, toneCurveB;

        void applyBasicTone(double rIn, double gIn, double bIn, double& r, double& g, double& b) const;
        double applyParametricCurve(double c) const;
        double applyToneCurve(double c) const;
        void applyVibranceSaturation(double& r, double& g, double& b) const;
        void applyHsl(double& r, double& g, double& b) const;
        void applySplitToning(double& r, double& g, double& b) const;
        void applyCalibration(double& r, double& g, double& b) const;
    };
};

} // namespace razgui
