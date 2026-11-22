"""Split the WAAAGH! banner into: background+jaw base, and 7 symbol masks.

Output (work/):
  labels.png   - uint8 label map: 0 bg, 1 jaw, 10..16 symbols W A A A G H !
  seg_vis.png  - colored overlay for manual check
"""
import os
import numpy as np, cv2

os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
os.makedirs('work', exist_ok=True)

im = cv2.imread('source/waaagh_banner.png')
h, w = im.shape[:2]
hsv = cv2.cvtColor(im, cv2.COLOR_BGR2HSV_FULL).astype(int)
H, S, V = hsv[..., 0] * 360 // 256, hsv[..., 1], hsv[..., 2]

paint = ((S > 140) & (V > 110) & ((H < 150) | (H > 330))).astype(np.uint8)
paint = cv2.morphologyEx(paint, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
n, lab, st, cen = cv2.connectedComponentsWithStats(paint, 8)

# Assign each paint component (>=300 px) to a symbol by its centroid: the
# nearest of these hand-checked anchors (one big paint area per symbol).
ANCHORS = {0: (283, 348), 1: (564, 370), 2: (816, 344), 3: (1036, 361),
           4: (1234, 341), 5: (1458, 377), 6: (1637, 305)}
DOT = (1591, 550)  # '!' dot

# Jaw markers (hand-placed polygons, safely inside the jaw and teeth)
JAW_UPPER = [(275, 520), (320, 590), (440, 650), (600, 690), (720, 690), (900, 690),
             (1000, 670), (1130, 650), (1250, 620), (1320, 600)]
JAW_LOWER = [(1320, 640), (1250, 700), (1150, 760), (1050, 800), (900, 820), (800, 830),
             (700, 800), (560, 750), (450, 720), (380, 680), (300, 620), (270, 560)]
JAW_BAND = JAW_UPPER + JAW_LOWER
JAW_ARM = [(274, 505), (292, 498), (335, 580), (315, 600), (282, 560)]
JAW_RUST_H = [(1320, 586), (1366, 576), (1374, 606), (1326, 614)]
TEETH = [JAW_ARM, JAW_RUST_H,
    [(500, 645), (575, 515), (565, 645)],
    [(620, 655), (660, 625), (665, 655)],
    [(760, 675), (820, 485), (880, 675)],
    [(1030, 635), (1050, 525), (1110, 635)],
    [(1255, 608), (1280, 548), (1305, 608)],
]
# rust components that the paint test catches on the jaw/teeth
JAW_EXTRA_COMPS_AT = [(835, 539), (563, 574), (1513, 589)]

markers = np.zeros((h, w), np.int32)
markers[V < 10] = 1                      # background
jawm = np.zeros((h, w), np.uint8)
cv2.fillPoly(jawm, [np.array(JAW_BAND, np.int32)], 1)
for t in TEETH:
    cv2.fillPoly(jawm, [np.array(t, np.int32)], 1)
markers[(jawm > 0) & (V >= 10)] = 2      # jaw

ero = np.ones((3, 3), np.uint8)
for i in range(1, n):
    if st[i, 4] < 300:
        continue
    c = cen[i]
    if any(abs(c[0] - x) < 6 and abs(c[1] - y) < 6 for x, y in JAW_EXTRA_COMPS_AT):
        continue
    if c[1] > 680:
        continue  # rust on the lower jaw
    if abs(c[0] - DOT[0]) < 8 and abs(c[1] - DOT[1]) < 8:
        sym = 6
    else:
        sym = min(ANCHORS, key=lambda k: (ANCHORS[k][0] - c[0]) ** 2 + (ANCHORS[k][1] - c[1]) ** 2)
    m = cv2.erode((lab == i).astype(np.uint8), ero)
    markers[(m > 0) & (markers == 0)] = 10 + sym

# hand-placed symbol markers for ambiguous dark bevels (checked on zoomed crops)
SYM_POLYS = {0: [[(254, 462), (261, 458), (267, 540), (258, 552)]],
             1: [[(610, 512), (676, 496), (674, 512), (620, 548)]]}
for sym, polys in SYM_POLYS.items():
    for pl in polys:
        t = np.zeros((h, w), np.uint8)
        cv2.fillPoly(t, [np.array(pl, np.int32)], 1)
        markers[(t > 0) & (V >= 10)] = 10 + sym
ws = markers.copy()
cv2.watershed(cv2.GaussianBlur(im, (3, 3), 0), ws)
# watershed boundary pixels (-1) -> most common neighbouring label
bnd = ws == -1
if bnd.any():
    tmp = ws.copy()
    tmp[bnd] = 0
    for _ in range(3):
        dil = cv2.dilate(tmp.astype(np.uint8), np.ones((3, 3), np.uint8))
        fill = (tmp == 0) & bnd
        tmp[fill] = dil[fill]
    ws = tmp

labels = np.zeros((h, w), np.uint8)
labels[ws == 2] = 1
for s in range(7):
    labels[ws == 10 + s] = 10 + s
# keep only the main connected blob(s) per symbol; stray islands -> jaw/bg by neighbour
for s in range(7):
    m = (labels == 10 + s).astype(np.uint8)
    k, l2, st2, _ = cv2.connectedComponentsWithStats(m, 8)
    big = st2[1:, 4].max() if k > 1 else 0
    for j in range(1, k):
        if st2[j, 4] < max(400, big * 0.02):
            # dot of '!' is a big separate blob, protected by the size rule
            labels[l2 == j] = 0

# Non-black pixels the flood gave to the background (dark bevels, shadows):
# give each connected piece to the label it touches most; isolated sparks stay.
FG_T = 18
rest = ((labels == 0) & (V >= FG_T)).astype(np.uint8)
k, l3, st3, _ = cv2.connectedComponentsWithStats(rest, 8)
lab_dil = {}
for L in [1] + [10 + s for s in range(7)]:
    lab_dil[L] = cv2.dilate((labels == L).astype(np.uint8), np.ones((3, 3), np.uint8))
counts = np.zeros((k, 18), np.int64)
for L, d in lab_dil.items():
    counts[:, L] = np.bincount(l3[d > 0], minlength=k)
for j in range(1, k):
    c = counts[j]
    if c.sum() == 0:
        continue
    labels[l3 == j] = int(np.argmax(c))
cv2.imwrite('work/labels.png', labels)

pal = [(60, 220, 60), (60, 255, 200), (0, 255, 255), (0, 170, 255), (0, 90, 255), (60, 60, 255), (255, 0, 255)]
over = np.zeros_like(im)
over[labels == 1] = (160, 120, 60)
for s in range(7):
    over[labels == 10 + s] = pal[s]
vis = cv2.addWeighted(im, 0.5, over, 0.5, 0)
# outline boundaries
edges = cv2.Canny(labels * 15, 1, 1) > 0
vis[edges] = (255, 255, 255)
cv2.imwrite('work/seg_vis.png', vis)
for s in range(7):
    ys, xs = np.nonzero(labels == 10 + s)
    print('sym', s, 'px', len(ys), 'bbox', xs.min(), ys.min(), xs.max(), ys.max())
