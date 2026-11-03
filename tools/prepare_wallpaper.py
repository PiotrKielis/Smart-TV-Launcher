"""Przygotowuje tapetę launchera z dowolnego obrazu 16:9: wersja ostra 1920x1080 i rozmyta 480x270,
z przyciemnieniem góry (pod zegarem) i dołu (pod podpisami) wypalonym w obraz,
żeby nic nie liczyć w trakcie działania.

Użycie:  python tools/prepare_wallpaper.py zdjecie.jpg app/src/main/res/raw
"""
import sys
from pathlib import Path
from PIL import Image, ImageFilter

src, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)

img = Image.open(src).convert("RGB").resize((1920, 1080), Image.LANCZOS)


def shade(w, h, top, bottom, base):
    """Maska jasności: base w środku, ciemniej przy górze (top) i dole (bottom)."""
    col = []
    for y in range(h):
        t = y / (h - 1)
        k = base
        if t < 0.42:  # góra: pod datą i zegarem
            k *= top + (1 - top) * (t / 0.42) ** 1.4
        if t > 0.70:  # dół: pod szybkimi ustawieniami
            k *= 1 - (1 - bottom) * ((t - 0.70) / 0.30) ** 1.2
        col.append(int(255 * k))
    m = Image.new("L", (1, h))
    m.putdata(col)
    return m.resize((w, h))


black = Image.new("RGB", img.size)
sharp = Image.composite(img, black, shade(1920, 1080, top=0.62, bottom=0.62, base=0.92))
sharp.save(out_dir / "bg.jpg", quality=86, optimize=True, progressive=False)

small = img.resize((480, 270), Image.LANCZOS).filter(ImageFilter.GaussianBlur(9))
blur = Image.composite(small, Image.new("RGB", small.size), shade(480, 270, top=0.55, bottom=0.75, base=0.62))
blur.save(out_dir / "bg_blur.jpg", quality=90, optimize=True)

for f in ("bg.jpg", "bg_blur.jpg"):
    print(f, (out_dir / f).stat().st_size)
