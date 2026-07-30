#!/usr/bin/env python3
"""Generate the checked-in 16x16 Noto Emoji sprite and CLDC lookup table."""
from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
NOTO_VERSION = "v2.047"
NOTO_SOURCE = (
    "https://github.com/googlefonts/noto-emoji/releases/tag/"
    + NOTO_VERSION
)
REACTIONS = "👍❤🤣😱😢🙏🔥👎🎉🤔😍🤯"

# Single-codepoint subset: common Unicode frequency leaders plus the fixed
# reaction palette. Sequences, flags and skin variants deliberately fall back.
EMOJI = (
    "😂❤😍🤣😊🙏💕😭😘👍😅👏😁🔥🥰💔💖💙🎉☺✨😢💗😆🤔💪😀🥳😎👌"
    "🤩😔😡🥺😳💜🙌🤗💚👀😋😱💛😉💯😞😄😃😬🤭😐🤨😇🤦🤷🙄😏😌"
    "😴🤤😷🤒🤕🤢🤮🤧🥵🥶🥴😵🤯🤠🥸😈👿👻💀☠👽🤖💩😺😸😹😻"
    "😼😽🙀😿😾👋🤚🖐✋🖖🤏✌🤞🤟🤘🤙👈👉👆👇☝👎✊👊🤛🤜"
    "🤝👐🤲🤳💅👂👃🧠🫀🫁👣👶👧🧒👦👩🧑👨👵🧓👴🌹🌸🌞⭐"
    "🌈☀☁⚡❄☕🍕🍰🎂🍻🥂⚽🏀🏆🎵🎶📷📱💡🎁🚀✅❌⚠"
)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", required=True, type=Path)
    parser.add_argument("--font-size", type=int, default=109,
                        help="NotoColorEmoji bitmap strike size")
    args = parser.parse_args()

    cps: list[int] = []
    # The send palette is a hard requirement even if frequency ordering moves.
    for char in REACTIONS + EMOJI:
        if ord(char) == 0xFE0F:
            continue
        cp = ord(char)
        if cp not in cps:
            cps.append(cp)
    cps = cps[:150]

    columns = 16
    rows = (len(cps) + columns - 1) // columns
    sheet = Image.new("RGBA", (columns * 16, rows * 16), (0, 0, 0, 0))
    font = ImageFont.truetype(str(args.font), args.font_size)
    for cell, cp in enumerate(cps):
        tile = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
        draw = ImageDraw.Draw(tile)
        draw.text((64, 64), chr(cp), font=font, anchor="mm",
                  embedded_color=True, stroke_width=0)
        tile = tile.resize((16, 16), Image.Resampling.LANCZOS)
        x = (cell % columns) * 16
        y = (cell // columns) * 16
        sheet.alpha_composite(tile, (x, y))

    # Indexed transparency compresses far better than a 32-bit sheet in a JAR.
    indexed = sheet.quantize(colors=96, method=Image.Quantize.FASTOCTREE)
    out_png = ROOT / "res" / "emoji.png"
    out_png.parent.mkdir(parents=True, exist_ok=True)
    indexed.save(out_png, optimize=True)

    ordered = sorted((cp, cell) for cell, cp in enumerate(cps))
    java = ROOT / "generated" / "tg" / "ui" / "EmojiData.java"
    java.parent.mkdir(parents=True, exist_ok=True)
    lines = [
        "package tg.ui;",
        "",
        "/** GENERATED from Noto Emoji " + NOTO_VERSION
        + " by tools/generate-emoji.py; do not edit. */",
        "final class EmojiData",
        "{",
        "    static final int[] CODEPOINTS = {",
    ]
    for i in range(0, len(ordered), 8):
        lines.append("        " + ", ".join("0x%x" % cp for cp, _ in ordered[i:i+8]) + ",")
    lines += ["    };", "    static final short[] CELLS = {"]
    for i in range(0, len(ordered), 16):
        lines.append("        " + ", ".join(str(cell) for _, cell in ordered[i:i+16]) + ",")
    lines += [
        "    };",
        "    private EmojiData() { }",
        "}",
        "",
    ]
    java.write_text("\n".join(lines), encoding="utf-8")
    print("emoji: %d glyphs, %dx%d, %d bytes" %
          (len(cps), sheet.width, sheet.height, out_png.stat().st_size))


if __name__ == "__main__":
    main()
