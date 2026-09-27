#!/usr/bin/env python3
"""Render Murmur's app and status icons from a single simple waveform mark."""

from pathlib import Path
import re

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1] / "src-tauri"
ICONS = ROOT / "icons"
RESOURCES = ROOT / "resources"
INK = (24, 54, 49, 255)
MINT = (140, 232, 197, 255)
WHITE = (246, 252, 249, 255)
RED = (246, 101, 95, 255)
CYAN = (115, 205, 229, 255)


def waveform(draw: ImageDraw.ImageDraw, box: tuple[int, int, int, int], color: tuple[int, ...]) -> None:
    left, top, right, bottom = box
    width, height = right - left, bottom - top
    center = (top + bottom) // 2
    heights = (0.35, 0.68, 1.0, 0.48, 0.24)
    stroke = max(2, round(width * 0.085))
    radius = stroke // 2
    for index, portion in enumerate(heights):
        x = left + round(width * (0.11 + 0.195 * index))
        half = round(height * portion * 0.5)
        draw.rounded_rectangle(
            (x - radius, center - half, x + radius, center + half),
            radius=radius,
            fill=color,
        )


def app_icon(size: int, *, round_mask: bool = False) -> Image.Image:
    scale = 4
    width = size * scale
    image = Image.new("RGBA", (width, width), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    inset = round(width * 0.035)
    radius = width // 2 if round_mask else round(width * 0.23)
    draw.rounded_rectangle(
        (inset, inset, width - inset, width - inset),
        radius=radius,
        fill=INK,
    )
    waveform(
        draw,
        (round(width * 0.19), round(width * 0.20), round(width * 0.81), round(width * 0.80)),
        MINT,
    )
    return image.resize((size, size), Image.Resampling.LANCZOS)


def status_icon(state: str, *, dark: bool = False, colored: bool = False, warning: bool = False) -> Image.Image:
    image = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    if colored:
        draw.rounded_rectangle((18, 18, 238, 238), radius=54, fill=INK)
        color = RED if state == "recording" else CYAN if state == "transcribing" else MINT
    else:
        color = INK if dark else WHITE
    waveform(draw, (32, 35, 224, 221), color)
    if warning:
        draw.ellipse((180, 168, 250, 238), fill=(239, 171, 64, 255))
    return image.resize((64, 64), Image.Resampling.LANCZOS)


def save_icon(path: Path, size: int, *, round_mask: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    app_icon(size, round_mask=round_mask).save(path)


def main() -> None:
    for path in ICONS.glob("*.png"):
        if path.name.startswith("Square"):
            match = re.match(r"Square(\d+)x\d+Logo", path.stem)
            size = int(match.group(1)) if match else 256
        elif path.name == "StoreLogo.png":
            size = 50
        elif path.name == "128x128@2x.png":
            size = 256
        elif path.stem in {"icon", "logo"}:
            size = 1024
        else:
            size = int(path.stem.split("x", 1)[0])
        save_icon(path, size)

    for path in (ICONS / "ios").glob("*.png"):
        match = re.match(r"AppIcon-([\d.]+)(?:x[\d.]+)?(?:@(\d)x)?", path.stem)
        if not match:
            raise ValueError(f"Unknown iOS icon size: {path}")
        size = round(float(match.group(1)) * int(match.group(2) or 1))
        save_icon(path, size)

    android_sizes = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for path in (ICONS / "android").glob("mipmap-*/*.png"):
        density = path.parent.name.removeprefix("mipmap-")
        size = android_sizes[density]
        save_icon(path, size, round_mask=path.stem.endswith("round"))

    app_icon(256).save(ICONS / "icon.ico", format="ICO", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    app_icon(1024).save(ICONS / "icon.icns", format="ICNS")

    variants = {
        "tray_idle.png": ("idle", False, False, False),
        "tray_idle_dark.png": ("idle", True, False, False),
        "tray_recording.png": ("recording", False, False, False),
        "tray_recording_dark.png": ("recording", True, False, False),
        "tray_transcribing.png": ("transcribing", False, False, False),
        "tray_transcribing_dark.png": ("transcribing", True, False, False),
        "tray_idle_warning.png": ("idle", False, False, True),
        "tray_idle_warning_dark.png": ("idle", True, False, True),
        "murmur.png": ("idle", False, True, False),
        "recording.png": ("recording", False, True, False),
        "transcribing.png": ("transcribing", False, True, False),
    }
    for name, options in variants.items():
        state, dark, colored, warning = options
        status_icon(state, dark=dark, colored=colored, warning=warning).save(RESOURCES / name)

    (RESOURCES / "handy.png").unlink(missing_ok=True)
    (RESOURCES / "handy_warning.png").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
