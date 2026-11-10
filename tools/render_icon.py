#!/usr/bin/env python3
"""Render the original display/backlight mark as a 256px PNG (stdlib only)."""
from pathlib import Path
import math
import struct
import zlib

SIZE = 256
SCALE = 4
BACKGROUND = (15, 23, 42)
FRAME = (148, 163, 184)
SCREEN = (30, 41, 59)
LIGHT = (56, 189, 248)

def rounded(x, y, left, top, right, bottom, radius):
    cx = min(max(x, left + radius), right - radius)
    cy = min(max(y, top + radius), bottom - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius ** 2

def pixel(x, y):
    color = BACKGROUND
    if rounded(x, y, 42, 28, 214, 228, 24):
        color = FRAME
    if rounded(x, y, 48, 34, 208, 222, 18):
        color = SCREEN
    # Split brightness symbol: illuminated left half and dark right half.
    distance = math.hypot(x - 128, y - 122)
    if distance <= 32:
        color = LIGHT if x <= 128 else BACKGROUND
    if 28 <= distance <= 33:
        color = LIGHT
    for angle in range(0, 360, 45):
        a = math.radians(angle)
        dx, dy = x - 128, y - 122
        along = dx * math.cos(a) + dy * math.sin(a)
        across = abs(-dx * math.sin(a) + dy * math.cos(a))
        if 45 <= along <= 57 and across <= 3:
            color = LIGHT
    if rounded(x, y, 112, 202, 144, 207, 2.5):
        color = FRAME
    return color

def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))

rows = bytearray()
for y in range(SIZE):
    rows.append(0)
    for x in range(SIZE):
        samples = [pixel(x + (sx + .5) / SCALE, y + (sy + .5) / SCALE)
                   for sy in range(SCALE) for sx in range(SCALE)]
        rows.extend(round(sum(p[c] for p in samples) / len(samples)) for c in range(3))
png = b'\x89PNG\r\n\x1a\n'
png += chunk(b'IHDR', struct.pack('>IIBBBBB', SIZE, SIZE, 8, 2, 0, 0, 0))
png += chunk(b'IDAT', zlib.compress(rows, 9)) + chunk(b'IEND', b'')
output = Path(__file__).resolve().parents[1] / 'assets/icon.png'
output.write_bytes(png)
print(output)
