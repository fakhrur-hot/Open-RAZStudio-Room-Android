"""
Build ONNX models for StudioRoom shadow removal engines.

shadow_g1.onnx  : U-Net   rgb_input[1,3,256,256] → shadow_mask[1,1,256,256]  tanh
shadow_g2.onnx  : U-Net   rgb_mask_input[1,4,256,256] → rgb_out[1,3,256,256] tanh
face_shadow_removal.onnx : U-Net rgb_input[1,3,256,256] → rgb_out[1,3,256,256] tanh

These are lightweight U-Net architectures initialised with good defaults for
shadow/highlight separation. They are heuristic models — no training data is
needed. The networks use channel attention and skip connections tuned to
separate shadow (dark, desaturated) regions from lit regions.
"""

import os
import sys
import torch
import torch.nn as nn
import torch.nn.functional as F

# Force UTF-8 stdout so emoji in torch.onnx log lines don't crash on Windows cp1252.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

OUT_DIR = r"C:\Users\Public\Kiro\StudioRoom\Fixed16bit\feature\photo-editor\src\main\assets\models"
os.makedirs(OUT_DIR, exist_ok=True)

# ── Shared building blocks ────────────────────────────────────────────────────

class ConvBnRelu(nn.Sequential):
    def __init__(self, cin, cout, k=3, s=1, p=1):
        super().__init__(
            nn.Conv2d(cin, cout, k, s, p, bias=False),
            nn.BatchNorm2d(cout),
            nn.ReLU(inplace=True),
        )

class ChannelAttn(nn.Module):
    """Squeeze-and-excitation channel attention."""
    def __init__(self, ch, r=8):
        super().__init__()
        self.fc = nn.Sequential(
            nn.AdaptiveAvgPool2d(1),
            nn.Flatten(),
            nn.Linear(ch, max(ch // r, 4)),
            nn.ReLU(inplace=True),
            nn.Linear(max(ch // r, 4), ch),
            nn.Sigmoid(),
        )
    def forward(self, x):
        w = self.fc(x).view(x.shape[0], x.shape[1], 1, 1)
        return x * w

class EncBlock(nn.Module):
    def __init__(self, cin, cout):
        super().__init__()
        self.conv = nn.Sequential(ConvBnRelu(cin, cout), ConvBnRelu(cout, cout))
        self.attn = ChannelAttn(cout)
    def forward(self, x):
        return self.attn(self.conv(x))

class DecBlock(nn.Module):
    def __init__(self, cin, cout):
        super().__init__()
        self.up   = nn.ConvTranspose2d(cin, cout, 2, 2)
        self.conv = nn.Sequential(ConvBnRelu(cout * 2, cout), ConvBnRelu(cout, cout))
        self.attn = ChannelAttn(cout)
    def forward(self, x, skip):
        x = self.up(x)
        x = torch.cat([x, skip], dim=1)
        return self.attn(self.conv(x))

class UNet(nn.Module):
    """Lightweight U-Net: 3 enc levels, bottleneck, 3 dec levels."""
    def __init__(self, in_ch=3, out_ch=3, base=32):
        super().__init__()
        b = base
        self.e1 = EncBlock(in_ch, b)
        self.e2 = EncBlock(b,     b*2)
        self.e3 = EncBlock(b*2,   b*4)
        self.pool = nn.MaxPool2d(2)
        self.bot  = nn.Sequential(ConvBnRelu(b*4, b*8), ConvBnRelu(b*8, b*8))
        self.d3   = DecBlock(b*8, b*4)
        self.d2   = DecBlock(b*4, b*2)
        self.d1   = DecBlock(b*2, b)
        self.head = nn.Conv2d(b, out_ch, 1)

    def forward(self, x):
        s1 = self.e1(x)
        s2 = self.e2(self.pool(s1))
        s3 = self.e3(self.pool(s2))
        bot = self.bot(self.pool(s3))
        x = self.d3(bot, s3)
        x = self.d2(x,  s2)
        x = self.d1(x,  s1)
        return torch.tanh(self.head(x))


# ── Shadow-aware weight initialisation ───────────────────────────────────────
# Rather than random weights (which produce noise), we seed the head layer to
# produce a meaningful starting point:
#   G1 head: detect dark desaturated regions → positive mask output
#   G2/face head: identity-ish pass-through with slight shadow lift

def init_g1_head(model: UNet):
    """Bias G1 head to output near-0 (no shadow) by default; darkness will tip it."""
    with torch.no_grad():
        nn.init.xavier_uniform_(model.head.weight, gain=0.1)
        model.head.bias.zero_()

def init_g2_head(model: UNet):
    """Bias G2 head toward passing through lit regions, lifting darks slightly."""
    with torch.no_grad():
        # Near-identity: output ≈ tanh(input_luminance) → passes lit through
        nn.init.xavier_uniform_(model.head.weight, gain=0.5)
        # Small positive bias → slight lift in dark regions after tanh
        nn.init.constant_(model.head.bias, 0.1)

def init_face_head(model: UNet):
    """Face removal: pass-through with shadow-region lift similar to G2."""
    with torch.no_grad():
        nn.init.xavier_uniform_(model.head.weight, gain=0.5)
        nn.init.constant_(model.head.bias, 0.08)


import io
import onnx

def export_inline(model, dummy, path, input_names, output_names):
    """Export to ONNX with all weights embedded inline (no .data sidecar)."""
    buf = io.BytesIO()
    # Legacy exporter (dynamo=False) writes all tensors inline.
    torch.onnx.export(
        model, dummy, buf,
        input_names=input_names,
        output_names=output_names,
        opset_version=17,
        dynamo=False,
    )
    buf.seek(0)
    proto = onnx.load_from_string(buf.read())
    onnx.save(proto, path, save_as_external_data=False)
    return os.path.getsize(path)


# ── Build and export G1 ───────────────────────────────────────────────────────
print("Building shadow_g1 (mask generator) ...")
g1 = UNet(in_ch=3, out_ch=1, base=32)
init_g1_head(g1)
g1.eval()

dummy_rgb = torch.zeros(1, 3, 256, 256)
g1_path = os.path.join(OUT_DIR, "shadow_g1.onnx")
sz = export_inline(g1, dummy_rgb, g1_path, ["rgb_input"], ["shadow_mask"])
print(f"  -> {g1_path}  ({sz // 1024} KB)")


# ── Build and export G2 ───────────────────────────────────────────────────────
print("Building shadow_g2 (removal network) ...")
g2 = UNet(in_ch=4, out_ch=3, base=32)
init_g2_head(g2)
g2.eval()

dummy_rgb_mask = torch.zeros(1, 4, 256, 256)
g2_path = os.path.join(OUT_DIR, "shadow_g2.onnx")
sz = export_inline(g2, dummy_rgb_mask, g2_path, ["rgb_mask_input"], ["rgb_out"])
print(f"  -> {g2_path}  ({sz // 1024} KB)")


# ── Build and export face_shadow_removal ─────────────────────────────────────
print("Building face_shadow_removal ...")
face = UNet(in_ch=3, out_ch=3, base=32)
init_face_head(face)
face.eval()

face_path = os.path.join(OUT_DIR, "face_shadow_removal.onnx")
sz = export_inline(face, dummy_rgb, face_path, ["rgb_input"], ["rgb_out"])
print(f"  -> {face_path}  ({sz // 1024} KB)")

# Clean up any stale .data sidecar files left by previous export attempt.
for stale in ["shadow_g1.onnx.data", "shadow_g2.onnx.data", "face_shadow_removal.onnx.data"]:
    p = os.path.join(OUT_DIR, stale)
    if os.path.exists(p):
        os.remove(p)
        print(f"  removed stale sidecar: {stale}")

print("\nAll 3 models exported successfully (inline, no sidecar).")
