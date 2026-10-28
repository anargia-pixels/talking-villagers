"""Generates the villager voice lines and the mod's sound assets.

Reads dialogue/*.txt, synthesizes each line with Gemini TTS through OpenRouter,
derives pitch variants with ffmpeg, and writes:

  src/main/resources/assets/talkingvillagers/sounds/<voice>/<category>_<n>.ogg
  src/main/resources/assets/talkingvillagers/sounds.json
  src/main/resources/assets/talkingvillagers/lang/en_us.json   (subtitles)
  src/main/resources/assets/talkingvillagers/lines.json        (index the mod reads)

Synthesized clips are cached under tools/tts-cache, keyed by text, speaker and
delivery, so a rerun only pays for lines that changed. Gemini bills audio at a
flat 25 output tokens per second, so spend is tracked from clip length and the
run stops before --max-tokens. Needs OPENROUTER_API_KEY (or a .env file in
the repo root) and ffmpeg built with rubberband. Run from the repo root:

  python3 -I tools/generate.py --samples   # one line per candidate speaker
  python3 -I tools/generate.py             # everything
"""

import argparse
import hashlib
import json
import os
import random
import re
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DIALOGUE = ROOT / "dialogue"
CACHE = ROOT / "tools" / "tts-cache"
SAMPLES = ROOT / "tools" / "tts-samples"
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "talkingvillagers"
MANIFEST = CACHE / "manifest.json"
SPEND = CACHE / "spend.json"

API_URL = "https://openrouter.ai/api/v1/audio/speech"
MODEL = "google/gemini-3.8-flash-lite-tts"
PCM_RATE = 24000  # Gemini returns 16-bit mono PCM at 24 kHz
TOKENS_PER_SECOND = 25

# Gemini speakers that get synthesized, with a persona for the delivery.
# Every voice below derives from one of these.
BASES = {
    "achird": ("Achird", "A friendly, ordinary village man."),
    "sulafat": ("Sulafat", "A warm, ordinary village woman."),
    "algenib": ("Algenib", "A gravelly, grumpy old village man."),
    "gacrux": ("Gacrux", "A mature, no-nonsense village woman."),
    "puck": ("Puck", "An excitable little boy."),
    "leda": ("Leda", "A cheerful little girl."),
}

# voice id -> (base, pitch shift in semitones). Shifting also moves formants,
# so negative shifts sound like a bigger speaker, positive like a smaller one.
ADULT_VOICES = {
    "male": ("achird", 0.0),
    "female": ("sulafat", 0.0),
    "deep": ("algenib", -3.0),
    "low": ("gacrux", -2.5),
    "high_male": ("achird", 2.5),
    "high_female": ("sulafat", 2.5),
    "gruff": ("algenib", 0.0),
    "lady": ("gacrux", 0.0),
}
CHILD_VOICES = {
    "boy": ("puck", 3.0),
    "girl": ("leda", 3.0),
}
CHILD_PREFIX = "baby_"

# How lines in a category are delivered; the first matching prefix wins.
MOODS = [
    ("baby_hurt", "Crying out in pain, whiny."),
    ("hurt", "Sudden sharp pain, yelping."),
    ("death", "Weak and dying, fading out."),
    ("flee_", "Panicked, shouting in fear while running."),
    ("ambient_danger", "Panicked, shouting in fear."),
    ("ambient_thunder", "Startled and nervous."),
    ("ambient_night", "Quiet, sleepy, a little uneasy."),
    ("greet_night", "Quiet and sleepy."),
    ("ambient_snow", "Shivering from the cold."),
    ("greet_snow", "Shivering from the cold."),
    ("celebrate", "Cheering, overjoyed."),
    ("greet_hero", "Admiring and grateful."),
    ("cured", "Relieved and grateful."),
    ("no", "Annoyed and dismissive."),
    ("trade_yes", "Pleased and cheerful."),
    ("greet_emerald", "Greedy and delighted."),
    ("work_", "Focused, muttering to yourself."),
    ("ambient_indoors", "Homely, a little protective of the house."),
    ("baby_", "Playful and giggly."),
    ("", "Casual and natural."),
]
PACE = "Brisk, natural pace; no pauses before or after."

SAMPLE_RATE = 22050
VORBIS_QUALITY = "0"

SAMPLE_SPEAKERS = [
    # men
    "Achird", "Puck", "Umbriel", "Zubenelgenubi", "Algenib", "Charon", "Orus", "Sadachbia",
    # women
    "Sulafat", "Aoede", "Despina", "Gacrux", "Vindemiatrix", "Leda", "Kore", "Achernar",
]
SAMPLE_LINE = "Oh! Hello there, traveler. Welcome to our little village."


def parse_dialogue():
    """Returns {category: [line, ...]} in file order."""
    categories = {}
    for path in sorted(DIALOGUE.glob("*.txt")):
        current = None
        for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            header = re.fullmatch(r"\[([a-z_]+)\]", line)
            if header:
                current = header.group(1)
                if current in categories:
                    sys.exit(f"{path.name}:{number}: duplicate section [{current}]")
                categories[current] = []
            elif current is None:
                sys.exit(f"{path.name}:{number}: line outside a [section]")
            else:
                categories[current].append(line)
    return categories


def mood(category):
    return next(text for prefix, text in MOODS if category.startswith(prefix))


def instructions(base, category):
    return f"{BASES[base][1]} {mood(category)} {PACE}"


def clip_hash(*parts):
    return hashlib.sha1("\0".join(parts).encode("utf-8")).hexdigest()[:16]


def api_key():
    key = os.environ.get("OPENROUTER_API_KEY")
    env = ROOT / ".env"
    if not key and env.exists():
        for line in env.read_text().splitlines():
            if line.startswith("OPENROUTER_API_KEY="):
                key = line.split("=", 1)[1].strip()
    if not key:
        sys.exit("Set OPENROUTER_API_KEY or put it in .env")
    return key


class Budget:
    """Tracks output tokens across runs; refuses new requests past the cap."""

    def __init__(self, cap):
        self.cap = cap
        self.lock = threading.Lock()
        self.spent = json.loads(SPEND.read_text())["tokens"] if SPEND.exists() else 0
        self.reserved = 0

    def reserve(self, estimate):
        with self.lock:
            if self.spent + self.reserved + estimate > self.cap:
                return False
            self.reserved += estimate
            return True

    def settle(self, estimate, actual):
        with self.lock:
            self.reserved -= estimate
            self.spent += actual
            SPEND.write_text(json.dumps({"tokens": self.spent}))


def estimate_tokens(text):
    # About 2.5 words per second when spoken briskly, plus slack for breaths.
    return int((len(text.split()) / 2.5 + 1.0) * TOKENS_PER_SECOND)


def synthesize(key, speaker, text, direction, out):
    body = json.dumps({
        "model": MODEL,
        "input": text,
        "voice": speaker,
        "instructions": direction,
        "response_format": "pcm",
    }).encode("utf-8")
    attempt = 0
    while True:
        request = urllib.request.Request(API_URL, body, {
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        })
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                pcm = response.read()
            if len(pcm) < PCM_RATE // 10:
                raise ValueError(f"suspiciously short audio ({len(pcm)} bytes)")
            break
        except urllib.error.HTTPError as e:
            detail = e.read()[:300].decode("utf-8", "replace")
            if e.code == 402 and "in_flight" in detail:
                # OpenRouter reserves worst-case cost for each in-flight request against
                # the key's limit. This just means "too many at once": wait for a slot.
                # It doesn't count as a failure, so the worker count can run high.
                time.sleep(random.uniform(0.5, 2.0))
                continue
            # Real lack of credit or a bad request won't fix itself.
            if e.code in (400, 401, 402, 403):
                raise RuntimeError(f"HTTP {e.code}: {detail}") from e
            error = f"HTTP {e.code}: {detail}"
        except (urllib.error.URLError, TimeoutError, ValueError) as e:
            error = str(e)
        attempt += 1
        if attempt == 6:
            raise RuntimeError(f"giving up on {text!r}: {error}")
        time.sleep(2 ** attempt)
    tmp = out.with_suffix(".tmp.wav")
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(PCM_RATE)
        w.writeframes(pcm)
    os.replace(tmp, out)
    return round(len(pcm) / 2 / PCM_RATE * TOKENS_PER_SECOND)


def encode(src, semitones, out):
    filters = [
        # Trim lead-in/out silence so lines start right on the trigger.
        "silenceremove=start_periods=1:start_threshold=-50dB",
        "areverse",
        "silenceremove=start_periods=1:start_threshold=-50dB",
        "areverse",
    ]
    if semitones:
        filters.append(f"rubberband=pitch={2 ** (semitones / 12):.5f}")
    filters.append("loudnorm=I=-18:TP=-1.5:LRA=11")
    tmp = out.with_suffix(".tmp.ogg")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(src), "-af", ",".join(filters),
         "-ac", "1", "-ar", str(SAMPLE_RATE), "-c:a", "libvorbis", "-q:a", VORBIS_QUALITY,
         "-map_metadata", "-1", str(tmp)],
        check=True,
    )
    os.replace(tmp, out)


def run_synthesis(key, budget, jobs, workers):
    """jobs: list of (speaker, text, instructions, wav). Returns False if the budget ran out."""
    jobs = [j for j in jobs if not j[3].exists()]
    if not jobs:
        return True
    print(f"synthesizing {len(jobs)} clips (spent so far: {budget.spent} tokens, cap {budget.cap})", flush=True)
    done = 0
    skipped = 0
    lock = threading.Lock()

    def work(job):
        nonlocal done, skipped
        speaker, text, direction, wav = job
        estimate = estimate_tokens(text)
        if not budget.reserve(estimate):
            with lock:
                skipped += 1
            return
        actual = 0
        try:
            actual = synthesize(key, speaker, text, direction, wav)
        finally:
            budget.settle(estimate, actual)
        with lock:
            done += 1
            if done % 250 == 0:
                print(f"  synthesized {done}/{len(jobs)}, {budget.spent} tokens spent", flush=True)

    with ThreadPoolExecutor(workers) as pool:
        for future in [pool.submit(work, job) for job in jobs]:
            future.result()
    print(f"  synthesized {done}/{len(jobs)}, {budget.spent} tokens spent", flush=True)
    if skipped:
        print(f"budget cap reached: {skipped} clips not synthesized", flush=True)
    return skipped == 0


def samples(key, budget, workers):
    SAMPLES.mkdir(parents=True, exist_ok=True)
    direction = f"A friendly, ordinary villager. {mood('greet')} {PACE}"
    jobs = []
    for speaker in SAMPLE_SPEAKERS:
        wav = CACHE / "samples" / f"{clip_hash(speaker, SAMPLE_LINE, direction)}.wav"
        wav.parent.mkdir(parents=True, exist_ok=True)
        jobs.append((speaker, SAMPLE_LINE, direction, wav))
    run_synthesis(key, budget, jobs, workers)
    for speaker, _, _, wav in jobs:
        encode(wav, 0, SAMPLES / f"{speaker}.ogg")
    print(f"samples written to {SAMPLES}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, default=32, help="parallel API requests")
    parser.add_argument("--max-tokens", type=int, default=900_000, help="output token cap across all runs")
    parser.add_argument("--samples", action="store_true", help="only synthesize one line per candidate speaker")
    parser.add_argument("--only", help="comma-separated categories to generate (for previews)")
    parser.add_argument("--dry-run", action="store_true", help="print the token estimate and stop")
    args = parser.parse_args()

    CACHE.mkdir(parents=True, exist_ok=True)
    key = api_key()
    budget = Budget(args.max_tokens)
    if args.samples:
        samples(key, budget, args.workers)
        return

    categories = parse_dialogue()
    only = set(args.only.split(",")) if args.only else None
    for name, lines in categories.items():
        print(f"{name:24} {len(lines)}")
    print(f"{'total':24} {sum(len(v) for v in categories.values())}")

    # Work out every (voice, line) pair and which base clips it needs.
    entries = []  # (category, index, text, voice, base, semitones, wav)
    synth = {}
    for category, lines in categories.items():
        if only and category not in only:
            continue
        voices = CHILD_VOICES if category.startswith(CHILD_PREFIX) else ADULT_VOICES
        for index, text in enumerate(lines, 1):
            for voice, (base, semitones) in voices.items():
                direction = instructions(base, category)
                speaker = BASES[base][0]
                wav = CACHE / base / f"{clip_hash(speaker, text, direction)}.wav"
                entries.append((category, index, text, voice, base, semitones, wav))
                synth[wav] = (speaker, text, direction, wav)
    for base in BASES:
        (CACHE / base).mkdir(parents=True, exist_ok=True)

    missing = [j for j in synth.values() if not j[3].exists()]
    estimate = sum(estimate_tokens(j[1]) for j in missing)
    print(f"{len(missing)} clips to synthesize, about {estimate} output tokens "
          f"(${estimate * 6 / 1e6:.2f}); {budget.spent} spent so far")
    if args.dry_run:
        return
    if not run_synthesis(key, budget, list(synth.values()), args.workers):
        sys.exit("stopped at the token cap; nothing was written to the mod")

    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    to_encode = []
    for category, index, text, voice, base, semitones, wav in entries:
        key_ = f"{voice}/{category}_{index}"
        out = ASSETS / "sounds" / f"{key_}.ogg"
        stamp = f"{wav.stem}:{semitones}:{SAMPLE_RATE}:{VORBIS_QUALITY}"
        if out.exists() and manifest.get(key_) == stamp:
            continue
        out.parent.mkdir(parents=True, exist_ok=True)
        to_encode.append((wav, semitones, out, key_, stamp))
    if to_encode:
        print(f"encoding {len(to_encode)} clips...", flush=True)
        with ThreadPoolExecutor(os.cpu_count()) as pool:
            for done, _ in enumerate(pool.map(lambda j: encode(*j[:3]), to_encode), 1):
                if done % 2000 == 0 or done == len(to_encode):
                    print(f"  encoded {done}/{len(to_encode)}", flush=True)
        for *_, key_, stamp in to_encode:
            manifest[key_] = stamp
        MANIFEST.write_text(json.dumps(manifest, sort_keys=True))

    if only:
        return

    # Drop audio for lines that no longer exist.
    expected = {f"{e[3]}/{e[0]}_{e[1]}" for e in entries}
    for ogg in (ASSETS / "sounds").glob("*/*.ogg"):
        if f"{ogg.parent.name}/{ogg.stem}" not in expected:
            ogg.unlink()
            manifest.pop(f"{ogg.parent.name}/{ogg.stem}", None)
    MANIFEST.write_text(json.dumps(manifest, sort_keys=True))

    sounds, lang = {}, {}
    for category, index, text, voice, *_ in entries:
        subtitle = f"subtitles.talkingvillagers.{category}.{index}"
        lang[subtitle] = f"“{text}”"
        # stream: decoded audio isn't kept in the sound buffer cache, so memory stays flat.
        sounds[f"{voice}.{category}.{index}"] = {
            "subtitle": subtitle,
            "sounds": [{"name": f"talkingvillagers:{voice}/{category}_{index}", "stream": True}],
        }
    (ASSETS / "lang").mkdir(parents=True, exist_ok=True)
    (ASSETS / "sounds.json").write_text(json.dumps(sounds, separators=(",", ":")))
    (ASSETS / "lang" / "en_us.json").write_text(json.dumps(lang, indent=1, ensure_ascii=False) + "\n")
    index = {
        "adult_voices": list(ADULT_VOICES),
        "child_voices": list(CHILD_VOICES),
        "categories": {name: len(lines) for name, lines in categories.items()},
    }
    (ASSETS / "lines.json").write_text(json.dumps(index, indent=1) + "\n")
    print(f"done; {budget.spent} output tokens spent in total")


if __name__ == "__main__":
    main()
