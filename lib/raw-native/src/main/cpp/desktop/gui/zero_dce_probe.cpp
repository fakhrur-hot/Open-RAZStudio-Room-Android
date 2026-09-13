#include "zero_dce_probe.h"
#include <cstdio>
#include <cmath>
#include <algorithm>
#include <vector>
#include <fstream>

#ifdef HAVE_ONNXRUNTIME

#ifdef _MSC_VER
#pragma warning(push)
#pragma warning(disable: 4996)  // ONNX Runtime uses deprecated APIs on Windows
#endif

// ONNX Runtime C++ API
#include <onnxruntime_cxx_api.h>

#ifdef _MSC_VER
#pragma warning(pop)
#endif

namespace razgui {

class ZeroDceProbe::Impl {
public:
    Impl() : env_(nullptr), session_(nullptr), ready_(false) {}

    ~Impl() {
        session_.reset();
        env_.reset();
    }

    bool init(const std::string& modelPath) {
        try {
            // Create ORT environment
            env_ = std::make_unique<Ort::Env>(ORT_LOGGING_LEVEL_WARNING, "ZeroDceProbe");

            // Load model file
            std::ifstream file(modelPath, std::ios::binary);
            if (!file) {
                std::fprintf(stderr, "ZeroDceProbe: cannot open model file: %s\n", modelPath.c_str());
                return false;
            }
            file.seekg(0, std::ios::end);
            size_t modelSize = file.tellg();
            file.seekg(0, std::ios::beg);

            std::vector<uint8_t> modelBuffer(modelSize);
            file.read(reinterpret_cast<char*>(modelBuffer.data()), modelSize);
            file.close();

            // Create session options. CPU execution provider is the ORT
            // default — no explicit provider append needed. (DirectML can be
            // added here later for GPU acceleration; see AI_FEATURES_INTEGRATION.md.)
            Ort::SessionOptions sessionOptions;
            sessionOptions.SetIntraOpNumThreads(1);
            sessionOptions.SetInterOpNumThreads(1);
            sessionOptions.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);

            // Create session from memory
            session_ = std::make_unique<Ort::Session>(
                *env_,
                modelBuffer.data(),
                modelBuffer.size(),
                sessionOptions
            );

            ready_ = true;
            std::fprintf(stderr, "ZeroDceProbe: model loaded (%.1f MB)\n", modelSize / 1024.0f / 1024.0f);
            return true;

        } catch (const std::exception& e) {
            std::fprintf(stderr, "ZeroDceProbe::init failed: %s\n", e.what());
            return false;
        }
    }

    float runProbe(const uint8_t* rgba, int width, int height) const {
        if (!ready_ || !session_) return -1.f;

        try {
            constexpr int INPUT_SIZE = 256;

            // Downscale RGBA to 256×256 using simple box filter
            std::vector<float> input(3 * INPUT_SIZE * INPUT_SIZE);
            downscaleRgba(rgba, width, height, input.data(), INPUT_SIZE);

            // Create input tensor (NCHW format: 1 image, 3 channels, 256×256)
            std::vector<int64_t> inputShape = {1, 3, INPUT_SIZE, INPUT_SIZE};
            Ort::Value inputTensor = Ort::Value::CreateTensor<float>(
                Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault),
                input.data(), input.size(),
                inputShape.data(), inputShape.size()
            );

            // Get input/output names
            Ort::AllocatorWithDefaultOptions allocator;
            std::vector<const char*> inputNames{"input"};
            std::vector<const char*> outputNames{"output"};

            // Run inference
            auto outputs = session_->Run(
                Ort::RunOptions{nullptr},
                inputNames.data(), &inputTensor, 1,
                outputNames.data(), outputNames.size()
            );

            if (outputs.empty() || !outputs[0].IsTensor()) {
                std::fprintf(stderr, "ZeroDceProbe: invalid output\n");
                return -1.f;
            }

            // Extract output tensor: [1, 24, 256, 256]
            float* outputData = outputs[0].GetTensorMutableData<float>();
            int64_t outputSize = outputs[0].GetTensorTypeAndShapeInfo().GetElementCount();

            // Average the absolute values of all coefficients
            double sum = 0.0;
            for (int64_t i = 0; i < outputSize; ++i) {
                sum += std::abs(outputData[i]);
            }
            float score = static_cast<float>(sum / outputSize);

            // Clamp to [0, 1] and scale for interpretability
            // The model output ranges widely; we normalize to perceptual scale
            score = std::min(1.f, score / 0.5f);  // Normalize to roughly 0..1

            return score;

        } catch (const std::exception& e) {
            std::fprintf(stderr, "ZeroDceProbe::runProbe failed: %s\n", e.what());
            return -1.f;
        }
    }

private:
    std::unique_ptr<Ort::Env> env_;
    std::unique_ptr<Ort::Session> session_;
    bool ready_;

    static void downscaleRgba(
        const uint8_t* src, int srcW, int srcH,
        float* dst, int dstSize) {
        // Box filter downscale from srcW×srcH to dstSize×dstSize
        float scaleX = static_cast<float>(srcW) / dstSize;
        float scaleY = static_cast<float>(srcH) / dstSize;

        std::vector<float> r(dstSize * dstSize, 0);
        std::vector<float> g(dstSize * dstSize, 0);
        std::vector<float> b(dstSize * dstSize, 0);
        std::vector<int> count(dstSize * dstSize, 0);

        // Accumulate source pixels into destination bins
        for (int sy = 0; sy < srcH; ++sy) {
            int dy = static_cast<int>(sy / scaleY);
            if (dy >= dstSize) dy = dstSize - 1;

            for (int sx = 0; sx < srcW; ++sx) {
                int dx = static_cast<int>(sx / scaleX);
                if (dx >= dstSize) dx = dstSize - 1;

                int srcIdx = (sy * srcW + sx) * 4;
                int dstIdx = dy * dstSize + dx;

                r[dstIdx] += src[srcIdx + 0];
                g[dstIdx] += src[srcIdx + 1];
                b[dstIdx] += src[srcIdx + 2];
                count[dstIdx]++;
            }
        }

        // Average and convert to float [0, 1]
        for (int i = 0; i < dstSize * dstSize; ++i) {
            if (count[i] > 0) {
                r[i] /= (count[i] * 255.f);
                g[i] /= (count[i] * 255.f);
                b[i] /= (count[i] * 255.f);
            }
        }

        // Interleave into NCHW format: [R plane, G plane, B plane]
        int planeSize = dstSize * dstSize;
        std::copy(r.begin(), r.end(), dst);
        std::copy(g.begin(), g.end(), dst + planeSize);
        std::copy(b.begin(), b.end(), dst + 2 * planeSize);
    }
};

std::unique_ptr<ZeroDceProbe> ZeroDceProbe::create(const std::string& modelPath) {
    auto probe = std::make_unique<ZeroDceProbe>();
    probe->impl_ = std::make_unique<Impl>();
    if (probe->impl_->init(modelPath)) {
        probe->ready_ = true;
    }
    return probe;
}

ZeroDceProbe::~ZeroDceProbe() = default;

float ZeroDceProbe::probeAverageLift(const uint8_t* rgba, int width, int height) const {
    if (!ready_) return -1.f;
    return impl_->runProbe(rgba, width, height);
}

} // namespace razgui

#else  // !HAVE_ONNXRUNTIME ─────────────────────────────────────────────────
// Built without the ONNX Runtime SDK available (no ONNXRUNTIME_ROOT set at
// CMake configure time). AI Color Enhance / AI Expose degrade cleanly: the
// probe reports "not ready" and app_main.cpp disables their controls and
// shows a pointer to AI_FEATURES_INTEGRATION.md instead of crashing.

namespace razgui {

class ZeroDceProbe::Impl {
public:
    bool init(const std::string&) { return false; }
    float runProbe(const uint8_t*, int, int) const { return -1.f; }
};

std::unique_ptr<ZeroDceProbe> ZeroDceProbe::create(const std::string& modelPath) {
    auto probe = std::make_unique<ZeroDceProbe>();
    probe->impl_ = std::make_unique<Impl>();
    probe->ready_ = false;
    std::fprintf(stderr, "ZeroDceProbe: built without ONNX Runtime; AI features disabled "
                          "(model would have been %s)\n", modelPath.c_str());
    return probe;
}

ZeroDceProbe::~ZeroDceProbe() = default;

float ZeroDceProbe::probeAverageLift(const uint8_t*, int, int) const {
    return -1.f;
}

} // namespace razgui

#endif  // HAVE_ONNXRUNTIME
