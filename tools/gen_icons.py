#!/usr/bin/env python3
"""
canmonitor.png -> Android launcher + splash varliklari.
Yeniden calistirmak icin:  python3 tools/gen_icons.py <kaynak.png>
"""
import os
import sys
from PIL import Image, ImageDraw

SRC = sys.argv[1] if len(sys.argv) > 1 else "/mnt/user-data/uploads/canmonitor.png"
RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

# adaptive icon arka plan rengi (tema ile ayni koyu lacivert)
BG = (6, 20, 40, 255)

DENSITIES = {           # klasor: olcek carpani (mdpi = 1)
    "mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4
}


def load_art():
    """Kaynagi yukle, seffaf kenarlari kirp, kare yap."""
    im = Image.open(SRC).convert("RGBA")
    bbox = im.getchannel("A").getbbox()
    if bbox:
        im = im.crop(bbox)
    side = max(im.size)
    sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2), im)
    return sq


def rounded(im, radius_ratio=0.22):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [0, 0, im.width - 1, im.height - 1], int(im.width * radius_ratio), fill=255
    )
    out = im.copy()
    out.putalpha(mask)
    return out


def circular(im):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, im.width - 1, im.height - 1], fill=255)
    out = im.copy()
    out.putalpha(mask)
    return out


def save(im, folder, name):
    d = os.path.join(RES, folder)
    os.makedirs(d, exist_ok=True)
    im.save(os.path.join(d, name), "PNG", optimize=True)


def main():
    art = load_art()

    for dens, mul in DENSITIES.items():
        # --- eski usul (API 25 ve alti) 48dp ikonlar
        legacy = int(48 * mul)
        base = art.resize((legacy, legacy), Image.LANCZOS)
        save(rounded(base), f"mipmap-{dens}", "ic_launcher.png")
        save(circular(base), f"mipmap-{dens}", "ic_launcher_round.png")

        # --- adaptive icon on katmani: 108dp tuval, icerik %70
        canvas = int(108 * mul)
        inner = int(canvas * 0.70)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        small = art.resize((inner, inner), Image.LANCZOS)
        off = (canvas - inner) // 2
        fg.paste(small, (off, off), small)
        save(fg, f"mipmap-{dens}", "ic_launcher_foreground.png")

        # --- splash ikonu: 288dp tuval, gorunen alan ic 192dp (2/3)
        sc = int(288 * mul)
        si = int(192 * mul)
        sp = Image.new("RGBA", (sc, sc), (0, 0, 0, 0))
        s = art.resize((si, si), Image.LANCZOS)
        o = (sc - si) // 2
        sp.paste(s, (o, o), s)
        save(sp, f"drawable-{dens}", "splash_icon.png")

    # Play Store 512x512 (repoda dursun, APK'ya girmez)
    store = Image.new("RGBA", (512, 512), BG)
    a = art.resize((512, 512), Image.LANCZOS)
    store.paste(a, (0, 0), a)
    out = os.path.join(os.path.dirname(__file__), "..", "playstore_icon_512.png")
    store.convert("RGB").save(out, "PNG", optimize=True)

    print("tamam")


if __name__ == "__main__":
    main()
