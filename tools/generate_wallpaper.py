"""Generuje alternatywną tapetę Czystego Launchera: głęboki kosmos z mgławicami, gwiazdami
i horyzontem planety z cienką poświatą atmosfery. Obraz jest w całości generowany,
więc można go swobodnie rozpowszechniać razem z projektem.

Użycie:  python tools/generate_wallpaper.py [katalog_wyjściowy] [ziarno]
Domyślnie zapisuje app/personal/res/raw (nadpisuje tapetę w Twoich buildach, poza repozytorium).
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

W, H = 1920, 1080
out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "app/personal/res/raw"
seed = int(sys.argv[2]) if len(sys.argv) > 2 else 7
rng = np.random.default_rng(seed)

yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
u, v = xx / W, yy / H


def fractal_noise(octaves=6, base=4, persistence=0.55):
    """Szum fraktalny: suma powiększonych losowych siatek o rosnącej rozdzielczości."""
    acc = np.zeros((H, W), np.float32)
    amp, total = 1.0, 0.0
    for i in range(octaves):
        cells = base * 2 ** i
        grid = rng.random((cells * 9 // 16 + 2, cells + 2)).astype(np.float32)
        layer = np.asarray(Image.fromarray(grid).resize((W, H), Image.BICUBIC), np.float32)
        acc += amp * layer
        total += amp
        amp *= persistence
    acc /= total
    return (acc - acc.min()) / (acc.max() - acc.min())


# Tło: granat u góry przechodzący w czerń.
top = np.array([14, 18, 52], np.float32)
bottom = np.array([3, 4, 12], np.float32)
img = top * (1 - v[..., None]) ** 1.2 + bottom * (1 - (1 - v[..., None]) ** 1.2)

# Mgławice: dwie warstwy szumu w kolorze fioletu/różu i turkusu, ograniczone miękką maską pasa.
band = np.exp(-((v - 0.36 - 0.16 * np.sin(u * 3.1 + 0.6)) ** 2) / 0.05)
n1 = fractal_noise() ** 1.8 * band
n2 = fractal_noise(base=3) ** 2.2 * np.exp(-((u - 0.74) ** 2 + (v - 0.30) ** 2) / 0.07)
n3 = fractal_noise(base=5) ** 2.4 * np.exp(-((u - 0.22) ** 2 + (v - 0.42) ** 2) / 0.05)
img += n1[..., None] * np.array([170, 60, 185], np.float32)
img += n2[..., None] * np.array([40, 150, 185], np.float32)
img += n3[..., None] * np.array([210, 80, 120], np.float32)
img += (n1 * n2)[..., None] * np.array([160, 120, 190], np.float32)
# Drobny „pył” w mgławicy, żeby nie była gładka jak mgła.
dust = fractal_noise(octaves=3, base=96, persistence=0.6) ** 6 * (n1 + n2 + n3)
img += dust[..., None] * np.array([220, 200, 255], np.float32) * 1.4

# Gwiazdy: dużo drobnych pojedynczych punktów i kilkanaście jasnych z miękką poświatą.
stars = np.zeros((H, W), np.float32)
count = 2200
sx, sy = rng.integers(0, W, count), rng.integers(0, H, count)
stars[sy, sx] = 0.25 + 0.75 * rng.random(count) ** 3
soft = np.asarray(Image.fromarray((stars * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)), np.float32) / 255
star_light = np.clip(stars + soft * 1.2, 0, 1)
for _ in range(18):
    px, py = rng.uniform(0, W), rng.uniform(0, H * 0.85)
    size = rng.uniform(1.2, 2.6)
    d2 = (xx - px) ** 2 + (yy - py) ** 2
    star_light += np.exp(-d2 / (2 * size ** 2)) + 0.18 * np.exp(-d2 / (2 * (size * 6) ** 2))
star_light = np.clip(star_light, 0, 1)
img = img * (1 - star_light[..., None]) + np.array([240, 242, 255], np.float32) * star_light[..., None]

# Planeta: płaski łuk pod dolnym rzędem przycisków, ciemna strona + cienka poświata atmosfery.
cx, cy, r = W * 0.5, 990 + 4800, 4800
d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
inside = d < r
rim = np.exp(-((d - r) / 9.0) ** 2)
halo = np.exp(-np.clip(d - r, 0, None) / 60.0) * (d >= r)
surface = 0.06 + 0.10 * fractal_noise(octaves=5, base=6)
lit = np.clip((cy - yy) / r, 0, 1) ** 3
planet = np.stack([surface * 40 + lit * 40, surface * 50 + lit * 60, surface * 80 + lit * 90], -1)
img = np.where(inside[..., None], planet, img)
img += (rim[..., None] * np.array([120, 200, 255], np.float32)) * 0.9
img += (halo[..., None] * np.array([40, 90, 160], np.float32)) * 0.6

out = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
out_dir.mkdir(parents=True, exist_ok=True)
tmp = out_dir / "_generated.png"
out.save(tmp)

# Dalsza obróbka (przyciemnienie pod zegarem i podpisami, wersja rozmyta) taka sama jak dla własnego zdjęcia.
sys.argv = ["prepare_wallpaper.py", str(tmp), str(out_dir)]
exec((Path(__file__).parent / "prepare_wallpaper.py").read_text(encoding="utf-8"))
tmp.unlink()
