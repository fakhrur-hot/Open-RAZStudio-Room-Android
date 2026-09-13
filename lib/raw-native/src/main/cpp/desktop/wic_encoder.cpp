#include "wic_encoder.h"

#include <windows.h>
#include <wincodec.h>
#include <propvarutil.h>

#include <cmath>
#include <vector>

namespace raz {
namespace {

/* Minimal COM smart pointer — avoids leaking on every early return. */
template <class T>
struct Com {
    T* p = nullptr;
    ~Com() { if (p) p->Release(); }
    T** operator&() { return &p; }
    T* operator->() const { return p; }
    explicit operator bool() const { return p != nullptr; }
};

/*
 * WIC encodes a TIFF/EXIF RATIONAL as VT_UI8 with numerator in the low DWORD
 * and denominator in the high DWORD.
 */
ULONGLONG packRational(float v) {
    if (v <= 0.f) return 0;
    uint32_t num, den;
    if (v < 1.0f) {                      // keep 1/N shape for shutter speeds
        den = static_cast<uint32_t>(std::lround(1.0 / double(v)));
        if (den == 0) den = 1;
        num = 1;
    } else {
        num = static_cast<uint32_t>(std::lround(double(v) * 1000.0));
        den = 1000;
    }
    return ULONGLONG(num) | (ULONGLONG(den) << 32);
}

void setStr(IWICMetadataQueryWriter* w, const wchar_t* path, const std::string& v) {
    if (!w || v.empty()) return;
    const int n = MultiByteToWideChar(CP_UTF8, 0, v.c_str(), -1, nullptr, 0);
    if (n <= 0) return;
    // static_cast, not size_t(n): `vector<wchar_t> wide(size_t(n))` is the
    // most vexing parse — the compiler reads it as a function declaration.
    std::vector<wchar_t> wide(static_cast<size_t>(n));
    MultiByteToWideChar(CP_UTF8, 0, v.c_str(), -1, wide.data(), n);
    PROPVARIANT pv;
    PropVariantInit(&pv);
    pv.vt = VT_LPWSTR;
    pv.pwszVal = wide.data();
    w->SetMetadataByName(path, &pv);     // best-effort; container may refuse
    pv.pwszVal = nullptr;                // buffer is stack-owned, don't free
    PropVariantClear(&pv);
}

void setRational(IWICMetadataQueryWriter* w, const wchar_t* path, float v) {
    if (!w || v <= 0.f) return;
    PROPVARIANT pv; PropVariantInit(&pv);
    pv.vt = VT_UI8;
    pv.uhVal.QuadPart = packRational(v);
    w->SetMetadataByName(path, &pv);
    PropVariantClear(&pv);
}

void setU16(IWICMetadataQueryWriter* w, const wchar_t* path, int v) {
    if (!w || v <= 0) return;
    PROPVARIANT pv; PropVariantInit(&pv);
    pv.vt = VT_UI2;
    pv.uiVal = static_cast<USHORT>(v);
    w->SetMetadataByName(path, &pv);
    PropVariantClear(&pv);
}

}  // namespace

bool encodeWic(const std::string& path,
               const uint8_t* rgba, uint32_t width, uint32_t height, uint32_t stride,
               WicFormat format, int quality,
               const EncodeMeta& meta, std::string* err) {
    auto fail = [&](const char* m, HRESULT hr = S_OK) {
        if (err) {
            *err = m;
            if (hr != S_OK) *err += " (hr=0x" + std::to_string(static_cast<unsigned>(hr)) + ")";
        }
        return false;
    };
    if (!rgba || !width || !height) return fail("no pixels");

    // Widen the output path for WIC.
    const int wn = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, nullptr, 0);
    if (wn <= 0) return fail("bad output path");
    std::vector<wchar_t> wpath(static_cast<size_t>(wn));   // see note in setStr
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), -1, wpath.data(), wn);

    // COM may already be initialised on this thread by something else; treat
    // RPC_E_CHANGED_MODE / S_FALSE as "already up" and skip our uninit.
    const HRESULT hrInit = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    const bool weInitialised = SUCCEEDED(hrInit) && hrInit != S_FALSE;

    bool ok = false;
    {
        Com<IWICImagingFactory> factory;
        HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER,
                                      IID_PPV_ARGS(&factory));
        if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("WIC factory", hr); }

        Com<IWICStream> stream;
        hr = factory->CreateStream(&stream);
        if (SUCCEEDED(hr)) hr = stream->InitializeFromFilename(wpath.data(), GENERIC_WRITE);
        if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("open output", hr); }

        Com<IWICBitmapEncoder> encoder;
        const GUID container = (format == WicFormat::Jpeg)
                             ? GUID_ContainerFormatJpeg : GUID_ContainerFormatPng;
        hr = factory->CreateEncoder(container, nullptr, &encoder);
        if (SUCCEEDED(hr)) hr = encoder->Initialize(stream.p, WICBitmapEncoderNoCache);
        if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("create encoder", hr); }

        Com<IWICBitmapFrameEncode> frame;
        IPropertyBag2* bagRaw = nullptr;
        hr = encoder->CreateNewFrame(&frame, &bagRaw);
        Com<IPropertyBag2> bag; bag.p = bagRaw;
        if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("create frame", hr); }

        if (format == WicFormat::Jpeg && bag) {
            PROPBAG2 opt = {};
            opt.pstrName = const_cast<LPOLESTR>(L"ImageQuality");
            VARIANT v; VariantInit(&v);
            v.vt = VT_R4;
            v.fltVal = float(quality < 1 ? 1 : (quality > 100 ? 100 : quality)) / 100.0f;
            bag->Write(1, &opt, &v);
            VariantClear(&v);
        }

        hr = frame->Initialize(bag.p);
        if (SUCCEEDED(hr)) hr = frame->SetSize(width, height);
        if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("frame init", hr); }

        // 24bppBGR for both containers: the source is opaque, and BGR is the
        // format both the JPEG and PNG encoders accept without a converter.
        WICPixelFormatGUID pf = GUID_WICPixelFormat24bppBGR;
        hr = frame->SetPixelFormat(&pf);
        if (FAILED(hr) || pf != GUID_WICPixelFormat24bppBGR)
            { if (weInitialised) CoUninitialize(); return fail("pixel format", hr); }

        // ICC profile.
        if (meta.icc && meta.iccSize) {
            Com<IWICColorContext> cc;
            if (SUCCEEDED(factory->CreateColorContext(&cc)) &&
                SUCCEEDED(cc->InitializeFromMemory(meta.icc, UINT(meta.iccSize)))) {
                IWICColorContext* arr[1] = { cc.p };
                frame->SetColorContexts(1, arr);   // best-effort
            }
        }

        // EXIF. JPEG carries a full APP1/IFD; PNG's WIC support does not, so
        // PNG ends up with ICC only — reported honestly rather than silently.
        if (format == WicFormat::Jpeg) {
            Com<IWICMetadataQueryWriter> qw;
            if (SUCCEEDED(frame->GetMetadataQueryWriter(&qw)) && qw) {
                setStr(qw.p, L"/app1/ifd/{ushort=271}", meta.make);
                setStr(qw.p, L"/app1/ifd/{ushort=272}", meta.model);
                setStr(qw.p, L"/app1/ifd/{ushort=306}", meta.dateTime);
                setRational(qw.p, L"/app1/ifd/exif/{ushort=33434}", meta.exposureTime);
                setRational(qw.p, L"/app1/ifd/exif/{ushort=33437}", meta.fNumber);
                setU16(qw.p, L"/app1/ifd/exif/{ushort=34855}", meta.iso);
                setStr(qw.p, L"/app1/ifd/exif/{ushort=36867}", meta.dateTimeOriginal);
                setRational(qw.p, L"/app1/ifd/exif/{ushort=37386}", meta.focalLength);
                setStr(qw.p, L"/app1/ifd/exif/{ushort=42036}", meta.lensModel);
            }
        }

        // RGBA -> BGR, one row at a time.
        std::vector<uint8_t> row(size_t(width) * 3);
        for (uint32_t y = 0; y < height; ++y) {
            const uint8_t* src = rgba + size_t(y) * stride;
            for (uint32_t x = 0; x < width; ++x) {
                row[size_t(x) * 3 + 0] = src[size_t(x) * 4 + 2];   // B
                row[size_t(x) * 3 + 1] = src[size_t(x) * 4 + 1];   // G
                row[size_t(x) * 3 + 2] = src[size_t(x) * 4 + 0];   // R
            }
            hr = frame->WritePixels(1, UINT(width) * 3, UINT(row.size()), row.data());
            if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("write pixels", hr); }
        }

        hr = frame->Commit();
        if (SUCCEEDED(hr)) hr = encoder->Commit();
        if (FAILED(hr)) { if (weInitialised) CoUninitialize(); return fail("commit", hr); }
        ok = true;
    }
    if (weInitialised) CoUninitialize();
    return ok;
}

}  // namespace raz
