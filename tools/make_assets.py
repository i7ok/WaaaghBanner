"""Build runtime assets from source/waaagh_banner.png + work/labels.png (run segment.py first).

Canvas: W x H (2:1), banner content fitted with a margin for the shake.
Outputs:
  app/src/main/res/drawable-nodpi/banner_base.png  - black bg + jaw (+sparks), letters removed
  app/src/main/assets/banner/letters_orig.png       - RGBA letter layer, source colours
  app/src/main/assets/banner/letters_metal.png      - RGBA, dull dark steel
  app/src/main/assets/banner/letters_red.png        - RGBA, paint turned red
  app/src/main/assets/banner/symbols.png            - L8: symbol index 0..6, 255 = none
  app/src/main/assets/banner/meta.json              - canvas size, per-symbol top/bottom
  work/preview_*.png                                - reference composites (Python)
"""
import json, os, sys
import numpy as np, cv2

W, H = 600, 300
MARGIN = 14          # px on the canvas, > shake amplitude
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
os.chdir(ROOT)

src = cv2.imread('source/waaagh_banner.png').astype(np.float32) / 255.0      # BGR
labels = cv2.imread('work/labels.png', 0)
hh, ww = labels.shape
V = src.max(2)

# ---- content bbox and placement -------------------------------------------
fg = (V * 255 >= 18) | (labels > 0)
ys, xs = np.nonzero(fg)
x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
scale = min((W - 2 * MARGIN) / (x1 - x0), (H - 2 * MARGIN) / (y1 - y0))
ox = (W - (x1 - x0) * scale) / 2 - x0 * scale
oy = (H - (y1 - y0) * scale) / 2 - y0 * scale
M = np.float32([[scale, 0, ox], [0, scale, oy]])
print('content', (x0, y0, x1, y1), 'scale %.4f' % scale)


def place(img, interp=cv2.INTER_AREA):
    # downscale with area filter, then translate with sub-pixel offset
    small = cv2.resize(img, None, fx=scale, fy=scale, interpolation=interp)
    T = np.float32([[1, 0, ox], [0, 1, oy]])
    return cv2.warpAffine(small, T, (W, H), flags=cv2.INTER_LINEAR, borderValue=0)


letters = labels >= 10
jaw = labels == 1

# ---- base: remove letters, inpaint jaw that hid behind them ----------------
base = src.copy()
base[letters] = 0
near_jaw = cv2.dilate(jaw.astype(np.uint8), np.ones((3, 3), np.uint8), iterations=28) > 0
hole = (letters & near_jaw).astype(np.uint8)
base8 = (base * 255).astype(np.uint8)
base8 = cv2.inpaint(base8, hole, 9, cv2.INPAINT_TELEA)
# fade the inpainted area to black away from the jaw so it doesn't look like a smear
d = cv2.distanceTransform((~jaw).astype(np.uint8), cv2.DIST_L2, 5)
fade = np.clip(1.0 - (d - 6) / 22.0, 0, 1)[..., None]
inp = base8.astype(np.float32) / 255.0
base = np.where(hole[..., None] > 0, inp * fade, base)
base_c = place(base)

# ---- downscale letter layer with soft alpha --------------------------------
alpha_src = letters.astype(np.float32)


def place_layer(col):
    pm = place(col * alpha_src[..., None])
    a = place(alpha_src)
    rgb = np.where(a[..., None] > 1e-4, pm / np.maximum(a[..., None], 1e-4), 0)
    return np.clip(rgb, 0, 1), np.clip(a, 0, 1)


orig_c, a_c = place_layer(src)

# symbol index map: per-pixel argmax of coverage
cov = np.stack([place((labels == 10 + s).astype(np.float32)) for s in range(7)], 0)
sym = np.argmax(cov, 0).astype(np.uint8)
sym[cov.max(0) < 1e-3] = 255

meta = {'width': W, 'height': H, 'scale': float(scale), 'offsetX': float(ox), 'offsetY': float(oy),
        'symbols': []}
names = ['W', 'A1', 'A2', 'A3', 'G', 'H', '!']
for s in range(7):
    yy, _ = np.nonzero(labels == 10 + s)
    top = yy.min() * scale + oy
    bot = (yy.max() + 1) * scale + oy
    meta['symbols'].append({'name': names[s], 'top': round(float(top), 3), 'bottom': round(float(bot), 3)})
print(json.dumps(meta['symbols']))

out_assets = 'app/src/main/assets/banner'
out_res = 'app/src/main/res/drawable-nodpi'
os.makedirs(out_assets, exist_ok=True)
os.makedirs(out_res, exist_ok=True)


def save_rgba(path, rgb, a):
    bgra = np.dstack([rgb, a[..., None]])
    cv2.imwrite(path, np.clip(bgra * 255 + 0.5, 0, 255).astype(np.uint8))


cv2.imwrite(f'{out_res}/banner_base.png', np.clip(base_c * 255 + 0.5, 0, 255).astype(np.uint8))
save_rgba(f'{out_assets}/letters_orig.png', orig_c, a_c)
cv2.imwrite(f'{out_assets}/symbols.png', np.dstack([sym, sym, sym]))
with open(f'{out_assets}/meta.txt', 'w', newline='\n') as f:
    f.write(f"{W} {H}\n")
    for sm in meta['symbols']:
        f.write(f"{sm['name']} {sm['top']} {sm['bottom']}\n")

# ---- reference renderer (same math as BannerMath.kt) ------------------------
rgb = orig_c[..., ::-1]                      # RGB
lum = 0.2126 * rgb[..., 0] + 0.7152 * rgb[..., 1] + 0.0722 * rgb[..., 2]
steel = 0.04 + 0.32 * np.clip(lum, 0, 1) ** 0.9
metal_rgb = np.clip(np.stack([steel * 0.90, steel * 1.0, steel * 1.06], -1), 0, 1)
hsv = cv2.cvtColor(orig_c.astype(np.float32), cv2.COLOR_BGR2HSV)
hh, ss, vv = hsv[..., 0], hsv[..., 1], hsv[..., 2]
pw = np.clip((ss - 0.18) / 0.30, 0, 1) * np.clip((vv - 0.06) / 0.15, 0, 1)


def paint_at(t):
    h2 = np.where(hh <= 180, hh - t * (hh + 2), hh + t * (358 - hh)) % 360
    col = cv2.cvtColor(np.dstack([h2, ss, vv]).astype(np.float32), cv2.COLOR_HSV2RGB)
    return rgb * (1 - pw[..., None]) + col * pw[..., None]


def render(level, all_red=False, dx=0):
    red_t = 1.0 if all_red else float(np.clip((level - 70) / 30.0, 0, 1))
    paint = paint_at(red_t)
    rows = np.arange(H)[:, None] + 0.0
    cover = np.zeros((H, W), np.float32)
    for s in range(7):
        top, bot = meta['symbols'][s]['top'], meta['symbols'][s]['bottom']
        f = 1.0 if all_red else np.clip((level - 10 * s) / 10.0, 0, 1)
        yline = np.inf if f <= 0 else (-np.inf if f >= 1 else bot - f * (bot - top))
        c = np.clip(rows + 1 - yline, 0, 1)
        cover = np.where(sym == s, np.broadcast_to(c, (H, W)), cover)
    out = metal_rgb * (1 - cover[..., None]) + paint * cover[..., None]
    T = np.float32([[1, 0, dx], [0, 1, 0]])
    o = cv2.warpAffine(out[..., ::-1].copy(), T, (W, H))
    a = cv2.warpAffine(a_c, T, (W, H))
    return base_c * (1 - a[..., None]) + o * a[..., None]


os.makedirs('work', exist_ok=True)
for lv in [0, 5, 35, 65, 70, 85, 100]:
    cv2.imwrite(f'work/preview_{lv:03d}.png', (np.clip(render(lv), 0, 1) * 255).astype(np.uint8))
cv2.imwrite('work/preview_red_shake.png', (np.clip(render(30, True, 6), 0, 1) * 255).astype(np.uint8))
sheet = np.vstack([np.hstack([render(a), render(b)]) for a, b in [(0, 5), (35, 65), (70, 85), (100, 30)]])
cv2.imwrite('work/preview_sheet.png', (np.clip(sheet, 0, 1) * 255).astype(np.uint8))
cv2.imwrite(f'{out_res}/widget_preview.png', (np.clip(render(70), 0, 1) * 255).astype(np.uint8))
print('ok')
