"""Draws the mod icon: a villager head with a speech bubble.

  uv run --with pillow python -I tools/icon.py src/main/resources/assets/talkingvillagers/icon.png
"""
import sys
from PIL import Image

N = 32
img = Image.new("RGBA", (N, N), (0, 0, 0, 0))
px = img.load()

def rect(x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = c

def hexc(h, a=255):
    h = h.lstrip("#"); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)

OUT = hexc("2b1d16")
SKIN = hexc("bd8b72"); SKIN_D = hexc("a3735c"); SKIN_L = hexc("cf9f86")
HAIR = hexc("6b4433"); BROW = hexc("3b271d")
NOSE = hexc("a86f57"); NOSE_D = hexc("8c5945")
EYE_W = hexc("f2efe9"); EYE_G = hexc("2e8b3e")
MOUTH = hexc("5e3a2b")
ROBE = hexc("6e4b30"); ROBE_D = hexc("553823")
WHITE = hexc("ffffff"); BUB_S = hexc("d9dde3")

# --- villager head (x 1..18, y 11..31) ---
hx0, hy0, hx1, hy1 = 2, 12, 17, 29
rect(hx0 - 1, hy0 - 1, hx1 + 1, hy1, OUT)          # outline
rect(hx0, hy0, hx1, hy1 - 1, SKIN)
rect(hx0, hy0, hx1, hy0 + 2, HAIR)                 # top of head
rect(hx0, hy0 + 3, hx1, hy0 + 3, SKIN_D)
rect(hx1 - 1, hy0 + 3, hx1, hy1 - 1, SKIN_D)       # side shade
rect(hx0, hy0 + 4, hx0, hy1 - 1, SKIN_L)           # left light
rect(hx0 + 1, hy0 + 6, hx1 - 2, hy0 + 6, BROW)     # unibrow
# eyes
rect(hx0 + 2, hy0 + 7, hx0 + 4, hy0 + 8, EYE_W); rect(hx0 + 3, hy0 + 7, hx0 + 4, hy0 + 8, EYE_G)
rect(hx1 - 5, hy0 + 7, hx1 - 3, hy0 + 8, EYE_W); rect(hx1 - 5, hy0 + 7, hx1 - 4, hy0 + 8, EYE_G)
# the famous nose
nx = (hx0 + hx1) // 2
rect(nx - 1, hy0 + 7, nx + 1, hy0 + 14, NOSE)
rect(nx - 2, hy0 + 11, nx + 2, hy0 + 15, NOSE)
rect(nx + 1, hy0 + 8, nx + 2, hy0 + 15, NOSE_D)
rect(nx - 2, hy0 + 15, nx + 2, hy0 + 15, NOSE_D)
# open mouth (talking!)
rect(hx0 + 3, hy0 + 14, nx - 3, hy0 + 15, MOUTH)
rect(nx + 3, hy0 + 14, hx1 - 3, hy0 + 15, MOUTH)
# robe collar
rect(hx0 - 1, hy1 - 1, hx1 + 1, 31, OUT)
rect(hx0, hy1, hx1, 31, ROBE)
rect(hx0, hy1, hx1, hy1, ROBE_D)

# --- speech bubble (top right) ---
bx0, by0, bx1, by1 = 15, 1, 31, 11
rect(bx0 + 1, by0, bx1 - 1, by1, OUT)
rect(bx0, by0 + 1, bx1, by1 - 1, OUT)
rect(bx0 + 1, by0 + 1, bx1 - 1, by1 - 1, WHITE)
rect(bx0 + 1, by1 - 1, bx1 - 1, by1 - 1, BUB_S)
# tail, pointing down-left at the villager
px[21, by1] = BUB_S; px[22, by1] = BUB_S
px[20, 12] = OUT; px[21, 12] = BUB_S; px[22, 12] = OUT
px[20, 13] = OUT; px[21, 13] = OUT
# "..." in the bubble
for dx in (0, 4, 8):
    rect(bx0 + 4 + dx, by0 + 4, bx0 + 5 + dx, by0 + 5, OUT)

scale = int(sys.argv[2]) if len(sys.argv) > 2 else 4
img.resize((N * scale, N * scale), Image.NEAREST).save(sys.argv[1])
