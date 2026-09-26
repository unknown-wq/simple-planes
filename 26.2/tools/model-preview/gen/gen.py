"""Procedural fighter_metal.png (128x128) + texOffs for the fighter metal/exhaust models.

Each named cube gets its own box-UV net packed into the atlas and painted per face.
Face rects for texOffs (u,v) and box (w,h,d), as verified from the real baked ModelPart:
  top    (u+d,      v,   w, d)      bottom (u+d+w,   v,   w, d)
  west   (u,        v+d, d, h)      front  (u+d,     v+d, w, h)   (front = faces the nose)
  east   (u+d+w,    v+d, d, h)      rear   (u+2d+w,  v+d, w, h)
"""
import json, random, re, sys
from PIL import Image

REPO = '/home/user/simple-planes/26.2/src/main'
OUT_PNG = REPO + '/resources/assets/simpleplanes/textures/plane_upgrades/fighter_metal.png'
MODELS = REPO + '/java/xyz/przemyk/simpleplanes/client/render/models/'
S = 128
rnd = random.Random(26_2)

# name: (w, h, d, material)
CUBES = {
    'canopy_main':  (14, 5, 28, 'glass'),
    'canopy_mid':   (12, 3, 25, 'glass'),
    'canopy_top':   (8, 3, 18, 'glass'),
    'canopy_front': (10, 3, 5, 'glass'),
    'intake':       (4, 8, 28, 'intake'),
    'missile':      (2, 2, 22, 'missile'),
    'nose_mid':     (8, 6, 8, 'metal'),
    'nose_tip':     (4, 4, 6, 'metal'),
    'pitot':        (1, 1, 6, 'strut'),
    'dash':         (12, 3, 3, 'dash'),
    'nose_strut':   (2, 6, 2, 'strut'),
    'main_strut':   (2, 7, 2, 'strut'),
    'nose_wheel':   (2, 5, 5, 'tire'),
    'main_wheel':   (3, 6, 6, 'tire'),
    'petal_h':      (10, 2, 8, 'nozzle'),
    'petal_v':      (2, 6, 8, 'nozzle'),
    'turbine':      (6, 6, 1, 'turbine'),
    'flame_outer':  (5, 5, 6, 'flame'),
    'flame_core':   (3, 3, 11, 'core'),
}

def faces(u, v, w, h, d):
    return {'top': (u+d, v, w, d), 'bottom': (u+d+w, v, w, d), 'west': (u, v+d, d, h),
            'front': (u+d, v+d, w, h), 'east': (u+d+w, v+d, d, h), 'rear': (u+2*d+w, v+d, w, h)}

# ---- first-fit packing, largest nets first (1px gutter) ----
order = sorted(CUBES, key=lambda n: -((2*CUBES[n][2] + 2*CUBES[n][0]) * (CUBES[n][2] + CUBES[n][1])))
occ = [[False]*S for _ in range(S)]
pos = {}
for n in order:
    w, h, d, _ = CUBES[n]
    nw, nh = 2*d + 2*w, d + h
    done = False
    for y in range(0, S - nh + 1):
        for x in range(0, S - nw + 1):
            if all(not occ[yy][xx] for yy in range(y, min(S, y+nh+1)) for xx in range(x, min(S, x+nw+1))):
                pos[n] = (x, y)
                for yy in range(y, y+nh):
                    for xx in range(x, x+nw): occ[yy][xx] = True
                done = True; break
        if done: break
    assert done, f'atlas full at {n}'

img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
px = img.load()

def clamp(c): return tuple(max(0, min(255, int(v))) for v in c)
def jitter(c, a): k = rnd.randint(-a, a); return clamp((c[0]+k, c[1]+k, c[2]+k)) + (255,)

def fill(rect, fn):
    x0, y0, w, h = rect
    for j in range(h):
        for i in range(w):
            px[x0+i, y0+j] = fn(i, j, w, h)

def metal(base=(74, 76, 80), a=7, edge=-16):
    blot = {}
    def f(i, j, w, h):
        k = (i // 2, j // 2)
        if k not in blot: blot[k] = rnd.randint(-a, a)
        c = tuple(b + blot[k] + rnd.randint(-2, 2) for b in base)
        if i in (0, w-1) or j in (0, h-1): c = tuple(v + edge for v in c)
        return clamp(c) + (255,)
    return f

LIGHT_EDGE, DARK_FRAME, GLINT, GLINT2 = (178, 214, 228), (52, 56, 62), (232, 246, 252), (200, 232, 244)

def glass(edges='', dark='', glints=True):
    """Tinted canopy glass, opaque: vertical tint gradient, a diagonal reflection streak, frame edges."""
    def f(i, j, w, h):
        side = {'t': j == 0, 'b': j == h-1, 'l': i == 0, 'r': i == w-1}
        if any(side[k] for k in dark): return jitter(DARK_FRAME, 3)
        if any(side[k] for k in edges): return jitter(LIGHT_EDGE, 3)
        t = j / max(1, h - 1)
        base = tuple(a + (b - a) * t for a, b in zip((120, 170, 196), (70, 104, 128)))
        if glints:
            k = (i + j) % 9
            if k == 0: return jitter(GLINT, 2)
            if k == 1: return jitter(GLINT2, 2)
        return jitter(base, 3)
    return f

# tier -> ((own min x, own rear z), (covering tier x0, x1, z0, z1)), model pixels. Top-face texel (i, j):
# i grows with +x from the box's min x, j grows towards the nose from the box's rear (max z) edge.
COVERED = {
    'canopy_main': ((-7, 0), (-6, 6, -26, -1)),
    'canopy_mid':  ((-6, -1), (-4, 4, -21, -3)),
}

def clear(i, j, w, h): return (0, 0, 0, 0)

def solid(c, a=4):
    return lambda i, j, w, h: jitter(c, a)

def paint(n):
    w, h, d, mat = CUBES[n]
    u, v = pos[n]
    F = faces(u, v, w, h, d)
    if mat == 'glass':
        # side faces: light upper edge, dark sill line; front: dark windscreen bow; top: light outline
        fill(F['west'], glass(edges='t', dark='b'))
        fill(F['east'], glass(edges='t', dark='b'))
        fill(F['front'], glass(dark='tblr'))
        fill(F['rear'], glass(edges='t', dark='b'))
        fill(F['top'], glass(edges='tblr', glints=(n == 'canopy_top')))
        fill(F['bottom'], clear)
        if n == 'canopy_front':
            fill(F['rear'], clear)                            # buried in canopy_main's front
        if n in COVERED:
            # Part of this tier's top face lies under the next tier: invisible from outside, but it is the
            # one face of the canopy that points at the pilot's eye if the seat sits a little higher than
            # specified. Cut it out so it can never block the first-person view.
            (x0, zrear), (cx0, cx1, cz0, cz1) = COVERED[n]
            tx, ty, tw, th = F['top']
            for i in range(cx0 - x0, cx1 - x0):
                for j in range(zrear - cz1, zrear - cz0):
                    px[tx + i, ty + j] = (0, 0, 0, 0)
    elif mat == 'intake':
        for k in F: fill(F[k], metal())
        def hole(i, j, W, H):
            if i in (0, W-1) or j in (0, H-1): return jitter((150, 152, 156), 4)
            depth = min(i, W-1-i, j, H-1-j)
            g = 14 + 6*depth
            return clamp((g, g, g+2)) + (255,)
        fill(F['front'], hole)
    elif mat == 'metal':
        for k in F: fill(F[k], metal())
    elif mat == 'strut':
        for k in F: fill(F[k], metal((150, 152, 156), 5, -24))
    elif mat == 'missile':
        for k in F: fill(F[k], metal((206, 208, 210), 4, -30))
        fill(F['front'], solid((40, 42, 46), 3))
        fill(F['rear'], solid((60, 60, 62), 3))
        # red warhead band on the four long faces, 2px behind the seeker (nose = low z of the net rows/cols)
        for k in ('top', 'bottom'):
            x0, y0, fw, fh = F[k]
            for i in range(fw): px[x0+i, y0+fh-3] = (180, 40, 36, 255)
        for k in ('west', 'east'):
            x0, y0, fw, fh = F[k]
            col = x0 + fw - 3 if k == 'west' else x0 + 2
            for j in range(fh): px[col, y0+j] = (180, 40, 36, 255)
    elif mat == 'seat':
        for k in F: fill(F[k], metal((58, 58, 62), 5))
        cush = (150, 52, 40)
        if n == 'seat_back': fill(F['front'], metal(cush, 6, -30))
        if n == 'seat_cushion': fill(F['top'], metal(cush, 6, -30))
    elif mat == 'dash':
        for k in F: fill(F[k], metal((40, 42, 46), 3))
        x0, y0, fw, fh = F['rear']
        for i in range(1, fw-1, 3):
            px[x0+i, y0+1] = (90, 220, 120, 255) if (i // 3) % 2 == 0 else (240, 180, 60, 255)
    elif mat == 'tire':
        for k in F: fill(F[k], metal((30, 30, 33), 3, -6))
        for k in ('west', 'east'):
            x0, y0, fw, fh = F[k]
            cx, cy = fw // 2, fh // 2
            for j in range(fh):
                for i in range(fw):
                    if abs(i - (fw-1)/2) <= 0.6 + (fw % 2 == 0) * 0.5 and abs(j - (fh-1)/2) <= 0.6 + (fh % 2 == 0) * 0.5:
                        px[x0+i, y0+j] = (150, 152, 156, 255)
    elif mat == 'nozzle':
        for k in F: fill(F[k], metal((96, 88, 84), 8, -18))
        # heat-tint streaks along the length
        for k in ('top', 'bottom', 'west', 'east'):
            x0, y0, fw, fh = F[k]
            for t in range(max(fw, fh)):
                if rnd.random() < 0.35:
                    i, j = rnd.randrange(fw), rnd.randrange(fh)
                    px[x0+i, y0+j] = (132, 104, 78, 255)
        fill(F['rear'], metal((150, 146, 140), 4, -10))
    elif mat == 'turbine':
        for k in F: fill(F[k], solid((34, 30, 30), 2))
        def fan(i, j, W, H):
            r = max(abs(i - (W-1)/2), abs(j - (H-1)/2))
            if r < 1: return (255, 170, 60, 255)
            if r < 2: return (200, 80, 30, 255)
            return (70, 36, 28, 255)
        fill(F['rear'], fan)
        fill(F['front'], fan)
    elif mat == 'flame':
        def fl(i, j, W, H):
            return clamp((255, 110 + rnd.randint(0, 50), 30 + rnd.randint(0, 20))) + (255,)
        for k in F: fill(F[k], fl)
    elif mat == 'core':
        def fc(i, j, W, H):
            return clamp((255, 225 + rnd.randint(0, 25), 150 + rnd.randint(0, 60))) + (255,)
        for k in F: fill(F[k], fc)

for n in CUBES: paint(n)
img.save(OUT_PNG)
json.dump({n: pos[n] for n in CUBES}, open('texoffs.json', 'w'), indent=1)

for cls in ('FighterMetalModel', 'FighterExhaustModel'):
    src = open(cls + '.java.tmpl').read()
    def rep(m):
        u, v = pos[m.group(1)]; return f'{u}, {v}'
    out = re.sub(r'@(\w+)@', rep, src)
    open(MODELS + cls + '.java', 'w').write(out)
print('packed:', {n: pos[n] for n in CUBES})
