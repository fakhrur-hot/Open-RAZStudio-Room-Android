#!/usr/bin/env python3
"""
RAW-domain HDR highlight recovery — fingerprint training pipeline.

Usage:
    # Step 1: extract 64x64 fingerprint patches from RAW files
    python train_raw_hdr_unet.py extract \
        --raw-dir /path/to/raw_files \
        --patch-dir /tmp/fingerprints

    # Step 2: train on fingerprint patches
    python train_raw_hdr_unet.py train \
        --patch-dir /tmp/fingerprints \
        --epochs 200 \
        --out raw_hdr_recovery.pth

    # Step 3: export to Android asset (~350KB)
    python train_raw_hdr_unet.py export \
        --checkpoint raw_hdr_recovery.pth \
        --out ../feature/photo-editor/src/main/assets/models/raw_hdr_recovery.bin

Strategy: Synthetic Distillation on 64x64 Bayer fingerprint patches.
  - Only patches with >=10% clipped pixels are kept.
  - Synthetic clipping at 60-92% of white level creates (degraded, GT) pairs.
  - Highlight-focused loss weights clipped pixels 8x over unclipped ones.
  - Output: ~350KB .bin, <100K params, <50ms per megapixel on-device.

Architecture: pixel-unshuffled depthwise-separable U-Net (<100K params)
    Input  : 4-ch RGGB [0,1] at 32x32  (pixel-unshuffled from 64x64 Bayer)
    enc0   : DWSep(4->16)  + BN + ReLU          32x32
    enc1   : DWSep(16->32) + BN + ReLU + Pool2  16x16
    enc2   : DWSep(32->64) + BN + ReLU + Pool2   8x8
    bot    : DWSep(64->64) + BN + ReLU           8x8
    dec2   : Upsample*2 + cat + DWSep(96->32)   16x16
    dec1   : Upsample*2 + cat + DWSep(48->16)   32x32
    head   : Conv1x1(16->4) + Sigmoid
"""

import argparse
import random
import struct
import sys
import io
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import Dataset, DataLoader


# ── Model ─────────────────────────────────────────────────────────────────────

class DWSepBlock(nn.Module):
    def __init__(self, c_in, c_out):
        super().__init__()
        self.dw = nn.Conv2d(c_in, c_in,  3, padding=1, groups=c_in, bias=True)
        self.pw = nn.Conv2d(c_in, c_out, 1,                         bias=True)
        self.bn = nn.BatchNorm2d(c_out)

    def forward(self, x):
        return F.relu(self.bn(self.pw(self.dw(x))), inplace=True)


class RawHdrUNet(nn.Module):
    def __init__(self):
        super().__init__()
        self.enc0 = DWSepBlock(4,       16)
        self.enc1 = DWSepBlock(16,      32)
        self.enc2 = DWSepBlock(32,      64)
        self.bot  = DWSepBlock(64,      64)
        self.dec2 = DWSepBlock(64 + 32, 32)
        self.dec1 = DWSepBlock(32 + 16, 16)
        self.head = nn.Conv2d(16, 4, 1)

    def forward(self, x):
        e0 = self.enc0(x)
        e1 = self.enc1(F.max_pool2d(e0, 2))
        e2 = self.enc2(F.max_pool2d(e1, 2))
        b  = self.bot(e2)
        d2 = self.dec2(torch.cat([F.interpolate(b,  scale_factor=2, mode='bilinear', align_corners=False), e1], 1))
        d1 = self.dec1(torch.cat([F.interpolate(d2, scale_factor=2, mode='bilinear', align_corners=False), e0], 1))
        return torch.sigmoid(self.head(d1))


# ── Fingerprint extraction ────────────────────────────────────────────────────

def extract(args):
    try:
        import rawpy
    except ImportError:
        sys.exit("pip install rawpy")

    PATCH_SIZE   = 64
    CLIP_FRAC    = 0.92
    DENSITY_MIN  = 0.10
    MAX_PATCHES  = 60

    patch_dir = Path(args.patch_dir)
    patch_dir.mkdir(parents=True, exist_ok=True)

    raw_files = []
    for ext in ('*.dng','*.DNG','*.nef','*.NEF','*.cr2','*.CR2','*.arw','*.ARW'):
        raw_files.extend(sorted(Path(args.raw_dir).glob(ext)))
    print(f"Found {len(raw_files)} RAW files")

    total = 0
    for rp in raw_files:
        try:
            with rawpy.imread(str(rp)) as raw:
                bayer = raw.raw_image.copy()
                white = raw.white_level
                black = int(np.median(raw.black_level_per_channel))
        except Exception as e:
            print(f"  SKIP {rp.name}: {e}"); continue

        norm = np.clip((bayer.astype(np.float32) - black) / max(white - black, 1), 0, 1)
        H, W = norm.shape
        saved = 0
        for y in range(0, (H // PATCH_SIZE) * PATCH_SIZE - PATCH_SIZE + 1, PATCH_SIZE):
            for x in range(0, (W // PATCH_SIZE) * PATCH_SIZE - PATCH_SIZE + 1, PATCH_SIZE):
                if saved >= MAX_PATCHES:
                    break
                patch = norm[y:y+PATCH_SIZE, x:x+PATCH_SIZE]
                if float((patch > CLIP_FRAC).mean()) < DENSITY_MIN:
                    continue
                np.save(str(patch_dir / f"{rp.stem}_y{y:04d}_x{x:04d}.npy"),
                        patch.astype(np.float32))
                saved += 1
                total += 1
            if saved >= MAX_PATCHES:
                break
        if saved:
            print(f"  {rp.name}: {saved} patches")

    size_mb = total * PATCH_SIZE * PATCH_SIZE * 4 / 1e6
    print(f"\nTotal fingerprint patches: {total}  ({size_mb:.1f} MB)")


# ── Dataset ───────────────────────────────────────────────────────────────────

class FingerprintDataset(Dataset):
    CLIP_LO = 0.60
    CLIP_HI = 0.92

    def __init__(self, paths, augment=True):
        self.paths   = paths
        self.augment = augment

    def __len__(self):
        return len(self.paths)

    @staticmethod
    def _unshuffle(patch):
        """[64,64] Bayer -> [4,32,32] RGGB."""
        return np.stack([
            patch[0::2, 0::2],
            patch[0::2, 1::2],
            patch[1::2, 0::2],
            patch[1::2, 1::2],
        ], axis=0).astype(np.float32)

    def __getitem__(self, idx):
        gt = np.load(str(self.paths[idx]))       # [64,64] float32
        if self.augment:
            if random.random() > .5: gt = gt[:,  ::-1].copy()
            if random.random() > .5: gt = gt[::-1,  :].copy()
        gt  = torch.from_numpy(self._unshuffle(gt))  # [4,32,32]
        lvl = random.uniform(self.CLIP_LO, self.CLIP_HI)
        return torch.clamp(gt, max=lvl), gt


# ── Training ──────────────────────────────────────────────────────────────────

def train(args):
    device = torch.device('cuda' if torch.cuda.is_available() else 'cpu')
    print(f"Device: {device}")

    patches = sorted(Path(args.patch_dir).glob('*.npy'))
    if not patches:
        sys.exit(f"No .npy patches in {args.patch_dir} — run 'extract' first.")
    random.shuffle(patches)
    n_val  = max(20, len(patches) // 10)
    ds_val = FingerprintDataset(patches[:n_val],  augment=False)
    ds_trn = FingerprintDataset(patches[n_val:],  augment=True)
    dl_trn = DataLoader(ds_trn, batch_size=64, shuffle=True,  num_workers=4, pin_memory=True)
    dl_val = DataLoader(ds_val, batch_size=64, shuffle=False, num_workers=2, pin_memory=True)
    print(f"Train: {len(ds_trn)}  Val: {len(ds_val)}  batches/epoch: {len(dl_trn)}")

    model = RawHdrUNet().to(device)
    n_params = sum(p.numel() for p in model.parameters())
    print(f"Parameters: {n_params:,}")

    opt   = torch.optim.AdamW(model.parameters(), lr=3e-3, weight_decay=1e-4)
    sched = torch.optim.lr_scheduler.OneCycleLR(
        opt, max_lr=3e-3,
        steps_per_epoch=len(dl_trn), epochs=args.epochs,
        pct_start=0.05, div_factor=25, final_div_factor=1000)

    CLIP_LEVEL = 0.60
    best_val   = float('inf')

    for epoch in range(1, args.epochs + 1):
        model.train()
        tl = 0.
        for inp, gt in dl_trn:
            inp, gt = inp.to(device), gt.to(device)
            pred  = model(inp)
            mask  = (inp >= CLIP_LEVEL).float()
            loss  = 8. * F.l1_loss(pred * mask,       gt * mask) \
                  + 1. * F.l1_loss(pred * (1 - mask), gt * (1 - mask))
            opt.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 0.5)
            opt.step()
            sched.step()
            tl += loss.item()

        model.eval()
        vl = 0.
        with torch.no_grad():
            for inp, gt in dl_val:
                inp, gt = inp.to(device), gt.to(device)
                pred = model(inp)
                mask = (inp >= CLIP_LEVEL).float()
                vl  += (8. * F.l1_loss(pred * mask,       gt * mask) +
                        1. * F.l1_loss(pred * (1 - mask), gt * (1 - mask))).item()

        tl /= len(dl_trn)
        vl /= len(dl_val)
        if epoch % 25 == 0 or epoch <= 3:
            print(f"Epoch {epoch:3d}/{args.epochs}  train={tl:.5f}  val={vl:.5f}")
        if vl < best_val:
            best_val = vl
            torch.save(model.state_dict(), args.out)

    print(f"\nBest val loss: {best_val:.5f}  ->  {args.out}")


# ── Export ────────────────────────────────────────────────────────────────────
# Binary format (little-endian):
#   char[4]  "RAZ1"
#   uint32   version = 1
#   uint32   num_tensors
#   For each tensor:
#     uint32   name_len
#     char[]   name
#     uint32   rank
#     uint32[] shape  (rank values)
#     float32[]data   (product(shape) floats)
#
# BatchNorm is fused into affine scale+bias — no BN ops at inference time.

def export(args):
    model = RawHdrUNet()
    model.load_state_dict(torch.load(args.checkpoint, map_location='cpu'))
    model.eval()
    sd = model.state_dict()

    tensors = {}
    shapes  = {}

    def fuse_block(prefix):
        dw_w  = sd[f'{prefix}.dw.weight']   # [C, 1, 3, 3]
        dw_b  = sd[f'{prefix}.dw.bias']
        pw_w  = sd[f'{prefix}.pw.weight']   # [C_out, C_in, 1, 1]
        pw_b  = sd[f'{prefix}.pw.bias']
        C     = dw_w.shape[0]
        k     = dw_w.shape[2]
        C_out = pw_w.shape[0]

        gamma = sd[f'{prefix}.bn.weight'].numpy()
        beta  = sd[f'{prefix}.bn.bias'].numpy()
        mu    = sd[f'{prefix}.bn.running_mean'].numpy()
        var   = sd[f'{prefix}.bn.running_var'].numpy()
        scale = gamma / np.sqrt(var + 1e-5)
        bias  = beta  - mu * scale

        tensors[f'{prefix}.dw.weight'] = dw_w.reshape(C, k, k).numpy()
        tensors[f'{prefix}.dw.bias']   = dw_b.numpy()
        tensors[f'{prefix}.pw.weight'] = pw_w.reshape(C_out, C).numpy()
        tensors[f'{prefix}.pw.bias']   = pw_b.numpy()
        tensors[f'{prefix}.bn.scale']  = scale
        tensors[f'{prefix}.bn.bias']   = bias

        shapes[f'{prefix}.dw.weight'] = [C, k, k]
        shapes[f'{prefix}.dw.bias']   = [C]
        shapes[f'{prefix}.pw.weight'] = [C_out, C]
        shapes[f'{prefix}.pw.bias']   = [C_out]
        shapes[f'{prefix}.bn.scale']  = [C_out]
        shapes[f'{prefix}.bn.bias']   = [C_out]

    for blk in ['enc0', 'enc1', 'enc2', 'bot', 'dec2', 'dec1']:
        fuse_block(blk)

    tensors['head.weight'] = sd['head.weight'].reshape(4, 16).numpy()
    tensors['head.bias']   = sd['head.bias'].numpy()
    shapes['head.weight']  = [4, 16]
    shapes['head.bias']    = [4]

    buf = io.BytesIO()
    buf.write(b'RAZ1')
    buf.write(struct.pack('<II', 1, len(tensors)))
    for name, data in tensors.items():
        nb = name.encode('utf-8')
        sh = shapes[name]
        fl = data.astype(np.float32).flatten()
        buf.write(struct.pack('<I', len(nb))); buf.write(nb)
        buf.write(struct.pack('<I', len(sh)))
        for d in sh: buf.write(struct.pack('<I', d))
        buf.write(fl.tobytes())

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(buf.getvalue())
    total_params = sum(int(np.prod(shapes[k])) for k in shapes)
    print(f"Exported {len(tensors)} tensors  ({total_params:,} params)  ->  {out_path}")
    print(f"File size: {len(buf.getvalue())/1024:.1f} KB  (target < 500 KB)")


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    ap  = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)

    ex = sub.add_parser('extract', help='Extract 64x64 fingerprint patches from RAW files')
    ex.add_argument('--raw-dir',   required=True, help='Directory of .dng/.nef/.cr2/.arw files')
    ex.add_argument('--patch-dir', required=True, help='Output directory for .npy patches')

    tr = sub.add_parser('train', help='Train U-Net on fingerprint patches')
    tr.add_argument('--patch-dir', required=True)
    tr.add_argument('--epochs',    type=int, default=200)
    tr.add_argument('--out',       default='raw_hdr_recovery.pth')

    ex2 = sub.add_parser('export', help='Export trained weights to .bin Android asset')
    ex2.add_argument('--checkpoint', required=True)
    ex2.add_argument('--out', default=str(
        Path(__file__).parent.parent /
        'feature/photo-editor/src/main/assets/models/raw_hdr_recovery.bin'))

    args = ap.parse_args()
    {'extract': extract, 'train': train, 'export': export}[args.cmd](args)


if __name__ == '__main__':
    main()
