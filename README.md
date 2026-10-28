# Talking Villagers

<img src="src/main/resources/assets/talkingvillagers/icon.png" width="96" alt="Talking Villagers icon">

A client-side Fabric mod for Minecraft 26.3. It gives villagers voices. They greet you,
gossip, haggle, complain about the rain and snow, grumble at night, yell when zombies or
pillagers chase them, and say "ow" when you hit them.

- About 2,300 voiced lines, each in 8 adult voices (male, female, deep, low, high male,
  high female, gruff, lady) and 2 child voices for baby villagers.
- Each villager keeps one voice and a slight personal pitch for life, based on its UUID.
- The words float above the villager's head while it talks: nametag-style text with
  no background, stacked above the villager's name if it has one.
- Lines also show as subtitles when subtitles are on.
- Plays on the **Voice/Speech** volume slider.

## Install

Download the jar from [Releases](https://github.com/anargia-pixels/talking-villagers/releases)
and put it in your `mods` folder alongside [Fabric API](https://modrinth.com/mod/fabric-api).
It only needs to be installed on the client, and it works on servers that don't have it.

## How it stays lightweight

The mod never ticks and never scans for villagers. It hooks `SoundManager.play`:
when vanilla plays a villager sound (ambient mumble, hurt, death, trade, yes/no, work,
celebrate, or the zombie-villager cure), the mod finds that villager and, with some
probability, swaps the mumble for a spoken line. A right-click greeting uses Fabric's
`UseEntityCallback`. Any other sound costs one string comparison.

Context is checked only when a line is about to play:

| Context | How it's detected |
| --- | --- |
| Rain / snow / thunder | Level weather plus the biome's precipitation at the villager |
| Morning / evening / night | Overworld clock and `isDarkOutside()` |
| Zombie or pillager chasing | One entity query for monsters within 12 blocks of the villager |
| Player in the same house | Within 8 blocks, both under a low roof (heightmap), line of sight |
| Hero of the Village, emerald in hand | Player effect and main-hand item |
| Speech text above heads | A map lookup per visible villager per frame; drawn only while it speaks |
| Profession, baby | Villager data |

Danger always gets a reaction, so a villager running from a zombie speaks on its next sound
instead of at random.

Audio is streamed (`"stream": true`), so clips aren't kept in Minecraft's decoded
sound cache. Memory stays flat no matter how many lines you've heard.

## Resource pack compatibility

The mod adds only sounds, subtitles and a line index under its own `talkingvillagers`
namespace. It doesn't touch any textures, models, or vanilla sound files. Bare Bones,
Fresh Animations, EMF/ETF and the like keep working, and packs that replace villager
sounds still work too, because triggers are matched by sound event id, not by file.

## Configuration

With [Mod Menu](https://modrinth.com/mod/modmenu) installed, click the mod's config button
for a settings screen. Mod Menu is optional. Without it, edit `config/talkingvillagers.json`,
which is created on first launch:

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `volume` | `1.0` | On top of the Voice/Speech slider |
| `ambientChance` | `0.35` | Chance an idle mumble becomes a line |
| `greetChance` | `0.85` | Chance a right-click gets a greeting |
| `tradeChance`, `tradeAcceptChance`, `refuseChance`, `workChance`, `celebrateChance` | various | Same, for those sounds |
| `maxSpeakers` | `3` | Villagers talking at once (pain and danger ignore this) |
| `showSpeechText` | `true` | Text above heads while talking |
| `speechTextDistance` | `16` | Blocks within which that text shows |
| `muteVanillaWhileSpeaking` | `true` | Silence "hmm" sounds from a villager mid-sentence |
| `disabledVoices` | `[]` | Voice ids to never assign, e.g. `["deep"]` |

## Building

The encoded voice clips are committed, since they cost money to regenerate. They're generated with Gemini 3.8 Flash Lite TTS through
OpenRouter. You need an OpenRouter key in `OPENROUTER_API_KEY` or in a git-ignored `.env`
(`OPENROUTER_API_KEY=...`), and `ffmpeg` built with rubberband:

```sh
python3 -I tools/generate.py --samples   # one line per candidate speaker, into tools/tts-samples/
python3 -I tools/generate.py --dry-run   # how many clips and output tokens a run would cost
python3 -I tools/generate.py             # everything
```

The script synthesizes 4 adult Gemini speakers and 2 child speakers (about 9,000 clips).
Each category gets its own delivery direction: pained hurt lines, panicked zombie and
pillager lines, hushed night lines. The deep, low and high voices are pitch-shifted from
the same clips, not synthesized separately. Gemini bills 25 output tokens per second of
audio. A full run is roughly 650k tokens (about $4), and the script stops at `--max-tokens`
(default 900k, counted across runs in `tools/tts-cache/spend.json`). Clips are cached by
text, speaker and direction, so after editing `dialogue/*.txt` only the changed lines are
paid for again.

Then build with a JDK 25 or newer:

```sh
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew build
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew installToPrism   # copy into the 26.3 Prism instance
```

## Tests

```sh
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew test                # line-choice logic
JAVA_HOME=/usr/lib/jvm/java-27-openjdk ./gradlew runClientGameTest   # opens the game
```

The client game test (`src/gametest`) creates a superflat world and checks the hurt,
zombie, pillager, snow, greeting and indoor-greeting lines. For each, it confirms a
matching line plays with a real sound, and that the head text is shown. Screenshots go to
`build/run/clientGameTest/screenshots/`.

## Editing lines

`dialogue/*.txt` has one line per row, grouped under `[category]` headers. Line ids come
from their position, so add new lines at the end of a section. The categories the mod
picks from are in `Situation.java` and `Speech.java`. A missing category (for example a
modded profession) falls back to the generic one.
