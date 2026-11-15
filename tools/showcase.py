"""Builds the README footage from a ./gradlew runShowcase capture.

The showcase run saves one screenshot per game tick and a timeline of when each
frame was taken and when each line started. This lays the frames out at their
real timing, mixes the spoken clips in where they started, and writes:

  docs/media/showcase.mp4   with sound
  docs/media/showcase.gif   silent, for the README

Needs ffmpeg. Run from the repo root:

  JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew runShowcase
  python3 -I tools/showcase.py
"""

import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CAPTURE = ROOT / "build" / "run" / "showcase" / "showcase"
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "talkingvillagers"
OUT = ROOT / "docs" / "media"
GIF_WIDTH = 960
GIF_FPS = 10


def read_timeline():
    frames, lines = [], []
    for row in (CAPTURE / "timeline.txt").read_text().splitlines():
        kind, seconds, value = row.split(" ", 2)
        (frames if kind == "frame" else lines).append((float(seconds), value))
    return frames, lines


def clip_path(event, sounds):
    """talkingvillagers:female.greet.23 -> .../sounds/female/greet_23.ogg"""
    name = sounds[event.split(":", 1)[1]]["sounds"][0]["name"]
    return ASSETS / "sounds" / (name.split(":", 1)[1] + ".ogg")


def write_concat(frames, path):
    """An ffmpeg concat list that shows each frame until the next one was taken."""
    with open(path, "w") as out:
        for (start, name), (end, _) in zip(frames, frames[1:] + [(frames[-1][0] + 0.5, None)]):
            out.write(f"file '{CAPTURE / name}'\nduration {end - start:.3f}\n")
        # The concat demuxer ignores the last duration unless the file repeats.
        out.write(f"file '{CAPTURE / frames[-1][1]}'\n")


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *args], check=True)


def main():
    if not (CAPTURE / "timeline.txt").exists():
        sys.exit(f"No capture in {CAPTURE}; run ./gradlew runShowcase first")
    frames, lines = read_timeline()
    # Frame times start when recording does; line times are on the same clock.
    origin = frames[0][0]
    frames = [(t - origin, name) for t, name in frames]
    sounds = json.loads((ASSETS / "sounds.json").read_text())
    OUT.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory() as tmp:
        concat = Path(tmp) / "frames.txt"
        write_concat(frames, concat)
        inputs = ["-f", "concat", "-safe", "0", "-i", str(concat)]
        mix = []
        for i, (start, event) in enumerate(lines, start=1):
            inputs += ["-i", str(clip_path(event, sounds))]
            delay = round((start - origin) * 1000)
            mix.append(f"[{i}:a]adelay={delay}:all=1[a{i}]")
        labels = "".join(f"[a{i}]" for i in range(1, len(lines) + 1))
        graph = ";".join(mix + [
            f"{labels}amix=inputs={len(lines)}:normalize=0[a]",
            "[0:v]fps=30,format=yuv420p[v]",
        ])
        ffmpeg(*inputs, "-filter_complex", graph, "-map", "[v]", "-map", "[a]",
               "-c:v", "libx264", "-preset", "slow", "-crf", "22",
               "-c:a", "aac", "-b:a", "128k", "-movflags", "+faststart",
               str(OUT / "showcase.mp4"))

        ffmpeg("-f", "concat", "-safe", "0", "-i", str(concat), "-vf",
               f"fps={GIF_FPS},scale={GIF_WIDTH}:-1:flags=lanczos,split[a][b];"
               "[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
               "-loop", "0", str(OUT / "showcase.gif"))

    for name in ("showcase.mp4", "showcase.gif"):
        size = (OUT / name).stat().st_size / 1e6
        print(f"{OUT / name}  {size:.1f} MB")


if __name__ == "__main__":
    main()
