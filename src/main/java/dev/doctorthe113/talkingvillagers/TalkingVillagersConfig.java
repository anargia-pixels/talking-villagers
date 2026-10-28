package dev.doctorthe113.talkingvillagers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** User settings, persisted to config/talkingvillagers.json. */
public class TalkingVillagersConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("talkingvillagers.json");

	private static TalkingVillagersConfig instance = new TalkingVillagersConfig();

	public boolean enabled = true;
	/** Volume of spoken lines, on top of the Voice/Speech slider. */
	public float volume = 1.0f;
	/** Chance that a villager's idle mumble becomes a spoken line instead. */
	public float ambientChance = 0.35f;
	/** Chance that right-clicking a villager makes it greet you. */
	public float greetChance = 0.85f;
	public float tradeChance = 0.5f;
	public float tradeAcceptChance = 0.6f;
	public float refuseChance = 0.7f;
	public float workChance = 0.3f;
	public float celebrateChance = 0.5f;
	/** Most villagers talking at the same time. Hurt, death and danger lines ignore this. */
	public int maxSpeakers = 3;
	/** Show what villagers say as floating text above their heads. */
	public boolean showSpeechText = true;
	/** Farthest a villager can be, in blocks, for its speech text to show. */
	public int speechTextDistance = 16;
	/** Silence a villager's vanilla mumbles while it's talking. */
	public boolean muteVanillaWhileSpeaking = true;
	/** Voice ids never assigned to villagers, e.g. ["deep", "high_female"]. */
	public List<String> disabledVoices = new ArrayList<>();

	public static TalkingVillagersConfig get() {
		return instance;
	}

	public static void load() {
		if (Files.exists(PATH)) {
			try (Reader reader = Files.newBufferedReader(PATH)) {
				TalkingVillagersConfig loaded = GSON.fromJson(reader, TalkingVillagersConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (Exception e) {
				TalkingVillagersClient.LOGGER.warn("Failed to read {}, using defaults", PATH, e);
			}
		}
		if (instance.disabledVoices == null) {
			instance.disabledVoices = new ArrayList<>();
		}
		instance.maxSpeakers = Math.max(1, instance.maxSpeakers);
		instance.speechTextDistance = Math.clamp(instance.speechTextDistance, 4, 64);
		// Write back so new options show up in existing files.
		save();
	}

	public static void save() {
		try (Writer writer = Files.newBufferedWriter(PATH)) {
			GSON.toJson(instance, writer);
		} catch (IOException e) {
			TalkingVillagersClient.LOGGER.warn("Failed to write {}", PATH, e);
		}
	}
}
