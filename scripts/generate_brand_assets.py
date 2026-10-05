#!/usr/bin/env python3
"""Generate checked-in openIME brand PNG assets.

Requires Pillow:
    python -m pip install Pillow
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "images"
# One brand, as in app/src/main/res: the app accent, ink, and a white tile.
BLUE = "#1668D0"
INK = "#15171C"
TILE = "#FFFFFF"
TILE_EDGE = "#E2E6ED"
PAGE = "#F4F6FA"
SUPERSAMPLE = 4


def draw_mark(image: Image.Image, box: tuple[int, int, int, int]) -> None:
    """The IME mark on its white tile, drawn from the 108-unit grid of
    ic_launcher_foreground.xml: the I is a text cursor in blue, ME in ink."""
    x0, y0, x1, y1 = box
    size = x1 - x0
    big = Image.new("RGBA", (size * SUPERSAMPLE, size * SUPERSAMPLE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(big)
    unit = size * SUPERSAMPLE / 108.0

    def p(x: float, y: float) -> tuple[float, float]:
        return x * unit, y * unit

    draw.rounded_rectangle([*p(2, 2), *p(106, 106)], radius=26 * unit, fill=TILE, outline=TILE_EDGE, width=max(1, round(1.5 * unit)))
    # I: stem and two serifs with 1.5-unit corners.
    draw.rectangle([*p(31, 41), *p(36, 67)], fill=BLUE)
    draw.rounded_rectangle([*p(27, 41), *p(40, 45)], radius=1.5 * unit, fill=BLUE)
    draw.rounded_rectangle([*p(27, 63), *p(40, 67)], radius=1.5 * unit, fill=BLUE)
    # M and E.
    draw.polygon([p(*xy) for xy in [(44, 67), (44, 41), (50, 41), (54, 52), (58, 41), (64, 41), (64, 67), (58, 67),
                                     (58, 53), (55.5, 60), (52.5, 60), (50, 53), (50, 67)]], fill=INK)
    draw.polygon([p(*xy) for xy in [(68, 41), (82, 41), (82, 47), (74, 47), (74, 51), (80, 51), (80, 57), (74, 57),
                                     (74, 61), (82, 61), (82, 67), (68, 67)]], fill=INK)
    mark = big.resize((size, size), Image.LANCZOS)
    image.paste(mark, (x0, y0), mark)


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
    draw_mark(image, (0, 0, 288, 288))
    image.save(OUTPUT / "openime-brand.png", optimize=True)


def generate_social_preview() -> None:
    image = Image.new("RGB", (1280, 640), PAGE)
    draw_mark(image, (520, 105, 760, 345))

    draw = ImageDraw.Draw(image)
    label = "openIME"
    font = load_font(92)
    bounds = draw.textbbox((0, 0), label, font=font)
    width = bounds[2] - bounds[0]
    draw.text(((1280 - width) / 2, 400), label, font=font, fill=INK)

    image.save(OUTPUT / "social-preview.png", optimize=True)


def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    generate_brand_png()
    generate_social_preview()
    print(OUTPUT / "openime-brand.png")
    print(OUTPUT / "social-preview.png")


if __name__ == "__main__":
    main()
