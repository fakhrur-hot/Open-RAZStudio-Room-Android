# AI models (partially builder-supplied)

Open RAZStudio Room ships **only the small, app-internal model/parameter files**
in this folder. Every large neural network and any third-party model is **not
bundled** — you must obtain your own copy from the original source before
building if you want the corresponding feature.

The model loaders are all guarded: when a model file is absent, that one AI
feature silently disables and the app still builds, installs, and runs offline.
Nothing crashes.

## Bundled in this repo (small, app-internal)

| File | Purpose |
|---|---|
| `ae_tone_model.tflite`, `ae_scaler_mean.npy`, `ae_scaler_scale.npy` | Auto-exposure tone regressor + feature scalers |
| `raw_hdr_recovery.bin`, `raw_shadow_recovery.bin` | RAW highlight/shadow recovery coefficient tables |

## NOT bundled — fetch your own copy from the original source

Place the file in this folder with the exact filename shown. Feature disables
if absent.

| Expected filename | Feature | Where to get it (original source) |
|---|---|---|
| `birefnet_lite.onnx` | Subject/background segmentation | BiRefNet — https://github.com/ZhengPeng7/BiRefNet (export to ONNX, e.g. via rembg) |
| `u2net.onnx` | Saliency segmentation, Heal/blemish base | U^2-Net — https://github.com/xuebinqin/U-2-Net |
| `deeplabv3p_human.onnx` | Human parsing mask | DeepLabV3+ human-parsing model of your choice |
| `segformer_cityscapes_remap_fp16.onnx` | Landscape/sky/terrain masking | SegFormer-B1 Cityscapes — smp-hub/segformer-b1-1024x1024-city-160k, remapped to 4 classes + fp16 |
| `mobile_sam_encoder.onnx`, `mobile_sam_decoder.onnx` | SAM edge refinement | MobileSAM — https://github.com/ChaoningZhang/MobileSAM (standard ONNX export) |
| `razgan.onnx` | Deep inpaint for Heal/Erase | MI-GAN — https://github.com/Picsart-AI-Research/MI-GAN (MIT), converted to ONNX |
| `Lightweight-Face-Detection.onnx` | Face-aware processing | Qualcomm "Lightweight-Face-Detection" ONNX |
| `dncnn_gray_s15.tflite` | AI denoise | DnCNN (grayscale, sigma 15) TFLite export |
| `zero_dce.onnx` | Low-light probe | Zero-DCE — https://github.com/Li-Chongyi/Zero-DCE |

Each model is distributed under its own upstream license. Review and comply
with each license before adding or redistributing any model here. These files
are intentionally listed in `.gitignore` so they are never committed.
