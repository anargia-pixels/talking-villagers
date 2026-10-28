package dev.doctorthe113.talkingvillagers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which voices and line categories exist. Read once from the generated lines.json;
 * the audio itself is only touched when a line plays.
 */
public record Lines(List<String> adultVoices, List<String> childVoices, Map<String, Integer> counts) {
	public static final Lines EMPTY = new Lines(List.of(), List.of(), Map.of());

	public static Lines load(List<String> disabledVoices) {
		try (InputStream in = Lines.class.getResourceAsStream("/assets/talkingvillagers/lines.json")) {
			if (in == null) {
				TalkingVillagersClient.LOGGER.error("lines.json is missing from the mod jar; villagers will stay quiet");
				return EMPTY;
			}
			JsonObject json = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
			Map<String, Integer> counts = new HashMap<>();
			json.getAsJsonObject("categories").entrySet().forEach(e -> counts.put(e.getKey(), e.getValue().getAsInt()));
			return new Lines(voices(json, "adult_voices", disabledVoices), voices(json, "child_voices", disabledVoices), Map.copyOf(counts));
		} catch (Exception e) {
			TalkingVillagersClient.LOGGER.error("Failed to read lines.json; villagers will stay quiet", e);
			return EMPTY;
		}
	}

	private static List<String> voices(JsonObject json, String key, List<String> disabled) {
		List<String> all = json.getAsJsonArray(key).asList().stream().map(e -> e.getAsString()).toList();
		List<String> enabled = all.stream().filter(v -> !disabled.contains(v)).toList();
		// Disabling every voice would leave nothing to say; ignore the setting then.
		return enabled.isEmpty() ? all : enabled;
	}

	public int count(String category) {
		return counts.getOrDefault(category, 0);
	}
}
