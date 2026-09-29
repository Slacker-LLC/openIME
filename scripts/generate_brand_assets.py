#!/usr/bin/env python3
"""Generate checked-in openIME brand PNG assets.

Requires Pillow:
    python -m pip install Pillow
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "images"
ACCENT = "#6EC3F7"
DARK = "#1C1C1E"
TEXT = "#F2F2F7"


def draw_mark(image: Image.Image, box: tuple[int, int, int, int], fill, cutout) -> None:
    draw = ImageDraw.Draw(image)
    x0, y0, x1, y1 = box
    scale = (x1 - x0) / 48.0

    def point(x: float, y: float) -> tuple[float, float]:
        return x0 + x * scale, y0 + y * scale

    draw.rounded_rectangle(
        [*point(4, 4), *point(44, 44)],
        radius=11 * scale,
        fill=fill,
    )
    draw.polygon(
        [
            point(17, 11),
            point(31, 11),
            point(31, 14),
            point(26, 14),
            point(26, 34),
            point(31, 34),
            point(31, 37),
            point(17, 37),
            point(17, 34),
            point(22, 34),
            point(22, 14),
            point(17, 14),
        ],
        fill=cutout,
    )


def load_font(size: int) -> ImageFont.ImageFont:
    candidates = (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
    )
    for candidate in candidates:
        path = Path(candidate)
        if path.is_file():
            return ImageFont.truetype(str(path), size)
    raise RuntimeError("No supported bold font found; install DejaVu Sans or Liberation Sans")


def generate_brand_png() -> None:
    image = Image.new("RGBA", (288, 288), (0, 0, 0, 0))
    draw_mark(image, (0, 0, 288, 288), ACCENT, (0, 0, 0, 0))
    image.save(OUTPUT / "openime-brand.png", optimize=True)


def generate_social_preview() -> None:
    image = Image.new("RGB", (1280, 640), DARK)
    draw_mark(image, (520, 105, 760, 345), ACCENT, DARK)

    draw = ImageDraw.Draw(image)
    label = "openIME"
    font = load_font(92)
    bounds = draw.textbbox((0, 0), label, font=font)
    width = bounds[2] - bounds[0]
    draw.text(((1280 - width) / 2, 400), label, font=font, fill=TEXT)

    image.save(OUTPUT / "social-preview.png", optimize=True)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    generate_brand_png()
    generate_social_preview()
    print(OUTPUT / "openime-brand.png")
    print(OUTPUT / "social-preview.png")


if __name__ == "__main__":
    main()
