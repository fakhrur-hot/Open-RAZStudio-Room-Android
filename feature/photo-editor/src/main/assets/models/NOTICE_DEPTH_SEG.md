# Model notices (depth + subject)

## Depth-Anything-V2-Small
- Upstream: https://github.com/DepthAnything/Depth-Anything-V2
- Weights: https://huggingface.co/depth-anything/Depth-Anything-V2-Small
- ONNX export used here: fabio-sim/Depth-Anything-ONNX v2.0.0
  https://github.com/fabio-sim/Depth-Anything-ONNX/releases/tag/v2.0.0
  file: `depth_anything_v2_vits.onnx`
- License: Apache-2.0 (Small only). Do NOT ship Base/Large/Giant (CC-BY-NC-4.0).
- Residual counsel flag: Depth-Anything-V2 issues #81 / #320 (training-data inheritance).
  Public SPDX for Small remains Apache-2.0.

## BiRefNet Lite (swin_v1_t)
- Upstream: https://github.com/ZhengPeng7/BiRefNet
- HF: https://huggingface.co/ZhengPeng7/BiRefNet_lite
- Official ONNX: BiRefNet-general-bb_swin_v1_tiny-epoch_232.onnx (v1 release)
- App asset name: `birefnet_lite.onnx`
- License: MIT (code + Lite weights). Do NOT ship Swin-L / BRIA RMBG-2.0 (non-commercial).

Place `depth_anything_v2_vits.onnx` next to the other models in this folder.
Loaders no-op when the file is absent.
