"""Test photos for the cloud end-to-end test.

expect/  — put into the simulator's Photos library; the backup must contain them byte for byte.
restore/ — restored from the PC into the simulator.
"""
import os
import random
import sys

from PIL import Image, ImageDraw

out = sys.argv[1]
random.seed(7)
for folder, count, label in (("expect", 3, "iPhone"), ("restore", 2, "PC")):
    d = os.path.join(out, folder)
    os.makedirs(d, exist_ok=True)
    for i in range(1, count + 1):
        img = Image.new("RGB", (1200, 900), tuple(random.randint(30, 220) for _ in range(3)))
        draw = ImageDraw.Draw(img)
        for _ in range(40):
            x, y = random.randint(0, 1200), random.randint(0, 900)
            draw.ellipse([x, y, x + random.randint(30, 300), y + random.randint(30, 300)], fill=tuple(random.randint(0, 255) for _ in range(3)))
        draw.text((40, 40), f"SwitchFlow CI {label} {i}", fill=(255, 255, 255))
        img.save(os.path.join(d, f"SwitchFlowCI_{label}_{i}.jpg"), quality=90)
print("fixtures written to", out)
