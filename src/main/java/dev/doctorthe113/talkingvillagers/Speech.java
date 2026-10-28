package dev.doctorthe113.talkingvillagers;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Decides when villagers talk and plays the lines. All entry points are
 * event-driven (a vanilla villager sound or a right-click), so nothing runs per tick.
 */
public final class Speech {
	/** Ignore villager sounds further away than this; they'd be inaudible anyway. */
	private static final double MAX_DISTANCE_SQR = 24 * 24;
	private static final double THREAT_RADIUS = 12;
	private static final double INDOORS_DISTANCE_SQR = 8 * 8;
	/** A roof more than this far up is probably a cliff or cave, not a house. */
	private static final int MAX_ROOF_HEIGHT = 10;

	private static Lines lines = Lines.EMPTY;
	/** Entity id -> the line that villager is currently saying. Entries go stale harmlessly. */
	private static final Int2ObjectOpenHashMap<Line> speaking = new Int2ObjectOpenHashMap<>();
	/** Category -> last line index played, to avoid saying the same line twice in a row. */
	private static final Object2IntOpenHashMap<String> lastLine = new Object2IntOpenHashMap<>();

	/** What was last said and why; read by the client game tests. */
	static volatile String lastCategory;
	static volatile Identifier lastEvent;
	static volatile Situation lastSituation;

	private enum Trigger { AMBIENT, TRADE, YES, NO, HURT, DEATH, CELEBRATE, WORK, CURED }

	/** How much a line resists being cut off. */
	private enum Priority { CHATTER, GREETING, PAIN }

	/** @param text what's being said, for the speech text above the villager's head */
	private record Line(SoundInstance sound, Priority priority, Component text) {
	}

	private Speech() {
	}

	static void init(Lines loaded) {
		lines = loaded;
	}

	/** Turns the mod on or off. Off stops anything being said and lets go of it. */
	public static void setEnabled(boolean enabled) {
		TalkingVillagersConfig.get().enabled = enabled;
		TalkingVillagersConfig.save();
		if (!enabled) {
			reset();
		}
	}

	/**
	 * Stops all lines and forgets them. Each line holds its villager, so this also
	 * matters on leaving a world: otherwise the old world couldn't be freed.
	 */
	static void reset() {
		Minecraft mc = Minecraft.getInstance();
		for (Line line : speaking.values()) {
			mc.getSoundManager().stop(line.sound());
		}
		speaking.clear();
		speaking.trim();
		lastLine.clear();
		lastSituation = null;
	}

	/** Lines currently tracked; for the game tests. */
	static int trackedLines() {
		return speaking.size();
	}

	/**
	 * Called for every sound the client plays. Returns true to cancel the vanilla sound,
	 * either because a spoken line replaces it or the villager is mid-sentence.
	 */
	public static boolean onVanillaSound(SoundInstance sound) {
		if (!TalkingVillagersConfig.get().enabled) {
			return false;
		}
		Identifier id = sound.getIdentifier();
		if (!"minecraft".equals(id.getNamespace())) {
			return false;
		}
		Trigger trigger = trigger(id.getPath());
		if (trigger == null) {
			return false;
		}
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer player = mc.player;
		ClientLevel level = mc.level;
		if (player == null || level == null || lines.counts().isEmpty()
				|| player.distanceToSqr(sound.getX(), sound.getY(), sound.getZ()) > MAX_DISTANCE_SQR) {
			return false;
		}

		Villager villager = findVillager(level, sound.getX(), sound.getY(), sound.getZ(), trigger == Trigger.CURED ? 2.0 : 0.5);
		if (trigger == Trigger.CURED) {
			if (villager != null) {
				speak(villager, "cured", true, Priority.CHATTER);
			} else {
				// The cured villager may not have reached the client yet; speak from the spot.
				speakAt(sound.getX(), sound.getY(), sound.getZ(), "cured");
			}
			return false;
		}
		if (villager == null) {
			return false;
		}

		RandomGenerator random = ThreadLocalRandom.current();
		boolean busy = isSpeaking(villager);
		if (trigger == Trigger.HURT || trigger == Trigger.DEATH) {
			// A hit can play the hurt sound twice, and rapid hits shouldn't restart the "ow" each time.
			if (trigger == Trigger.HURT && busy && speaking.get(villager.getId()).priority() == Priority.PAIN) {
				return true;
			}
			// Pain interrupts whatever they were saying.
			Situation situation = situation(villager, player, level, threat(villager, level));
			String category = trigger == Trigger.DEATH
					? (situation.baby() ? "baby_hurt" : "death")
					: situation.hurt(random);
			speak(villager, category, true, Priority.PAIN);
			return true;
		}
		if (busy) {
			return config.muteVanillaWhileSpeaking;
		}

		String category;
		if (trigger == Trigger.AMBIENT) {
			// Danger always gets a reaction; ordinary chatter is rationed. Roll before
			// looking at anything else, since most mumbles stay mumbles.
			Situation.Threat threat = threat(villager, level);
			if (threat == Situation.Threat.NONE && !(roll(config.ambientChance) && hasRoom(config))) {
				return false;
			}
			Situation situation = situation(villager, player, level, threat);
			category = situation.ambient(random);
			speak(villager, category, situation.inDanger(), Priority.CHATTER);
			return true;
		}

		float chance = switch (trigger) {
			case TRADE -> config.tradeChance;
			case YES -> config.tradeAcceptChance;
			case NO -> config.refuseChance;
			case WORK -> config.workChance;
			default -> config.celebrateChance;
		};
		if (!roll(chance) || !hasRoom(config)) {
			return false;
		}
		if (villager.isBaby()) {
			category = "baby_ambient";
		} else {
			category = switch (trigger) {
				case TRADE -> "trade";
				case YES -> "trade_yes";
				case NO -> "no";
				case WORK -> "work_" + profession(villager);
				default -> "celebrate";
			};
		}
		return speak(villager, category, false, Priority.CHATTER);
	}

	/** Right-click greeting. */
	public static void onInteract(Villager villager) {
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		Minecraft mc = Minecraft.getInstance();
		if (!config.enabled || mc.player == null || mc.level == null || mc.player.isSpectator()
				|| !roll(config.greetChance)) {
			return;
		}
		// Being spoken to beats idle chatter, but don't restart a greeting or cut off pain.
		Line current = speaking.get(villager.getId());
		if (current != null && current.priority() != Priority.CHATTER
				&& mc.getSoundManager().isActive(current.sound())) {
			return;
		}
		Situation situation = situation(villager, mc.player, mc.level, threat(villager, mc.level));
		speak(villager, situation.greet(ThreadLocalRandom.current()), true, Priority.GREETING);
	}

	private static Trigger trigger(String path) {
		if (path.startsWith("entity.villager.")) {
			return switch (path.substring("entity.villager.".length())) {
				case "ambient" -> Trigger.AMBIENT;
				case "trade" -> Trigger.TRADE;
				case "yes" -> Trigger.YES;
				case "no" -> Trigger.NO;
				case "hurt" -> Trigger.HURT;
				case "death" -> Trigger.DEATH;
				case "celebrate" -> Trigger.CELEBRATE;
				case String s when s.startsWith("work_") -> Trigger.WORK;
				default -> null;
			};
		}
		return path.equals("entity.zombie_villager.converted") ? Trigger.CURED : null;
	}

	private static Situation situation(Villager villager, LocalPlayer player, ClientLevel level, Situation.Threat threat) {
		BlockPos pos = villager.blockPosition();
		Situation.Weather weather = Situation.Weather.CLEAR;
		if (level.isThundering()) {
			weather = Situation.Weather.THUNDER;
		} else if (level.isRaining()) {
			Biome.Precipitation precipitation = level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
			weather = switch (precipitation) {
				case SNOW -> Situation.Weather.SNOW;
				case RAIN -> Situation.Weather.RAIN;
				case NONE -> Situation.Weather.CLEAR;
			};
		}
		long time = Math.floorMod(level.getOverworldClockTime(), 24000L);
		Situation.TimeOfDay timeOfDay;
		if (level.isDarkOutside()) {
			timeOfDay = Situation.TimeOfDay.NIGHT;
		} else if (time >= 23000 || time < 2500) {
			timeOfDay = Situation.TimeOfDay.MORNING;
		} else if (time >= 11000) {
			timeOfDay = Situation.TimeOfDay.EVENING;
		} else {
			timeOfDay = Situation.TimeOfDay.DAY;
		}
		return lastSituation = new Situation(
				villager.isBaby(),
				profession(villager),
				threat,
				indoorsTogether(villager, player, level),
				weather,
				timeOfDay,
				player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE),
				player.getMainHandItem().getItem() == Items.EMERALD);
	}

	private static String profession(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
				.map(key -> key.identifier().getPath())
				.orElse("none");
	}

	private static Situation.Threat threat(Villager villager, ClientLevel level) {
		AABB area = villager.getBoundingBox().inflate(THREAT_RADIUS);
		Situation.Threat threat = Situation.Threat.NONE;
		for (Monster monster : level.getEntitiesOfClass(Monster.class, area, Monster::isAlive)) {
			if (monster instanceof Raider) {
				return Situation.Threat.RAIDER;
			}
			if (monster instanceof Zombie && !(monster instanceof ZombifiedPiglin)) {
				threat = Situation.Threat.ZOMBIE;
			} else if (threat == Situation.Threat.NONE && !(monster instanceof ZombifiedPiglin)) {
				threat = Situation.Threat.MONSTER;
			}
		}
		return threat;
	}

	/**
	 * Close by, both under a low roof, and able to see each other: good enough
	 * for "in the same house" without knowing where houses are.
	 */
	private static boolean indoorsTogether(Villager villager, LocalPlayer player, ClientLevel level) {
		return villager.distanceToSqr(player) <= INDOORS_DISTANCE_SQR
				&& Math.abs(villager.getY() - player.getY()) <= 3
				&& underRoof(level, villager.blockPosition())
				&& underRoof(level, player.blockPosition())
				&& villager.hasLineOfSight(player);
	}

	private static boolean underRoof(ClientLevel level, BlockPos pos) {
		// The heightmap is the first open block above the highest solid one, so any
		// roof over the head puts it at least two above the feet.
		int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
		return top > pos.getY() + 1 && top <= pos.getY() + MAX_ROOF_HEIGHT;
	}

	private static Villager findVillager(ClientLevel level, double x, double y, double z, double radius) {
		AABB area = new AABB(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
		List<Villager> found = level.getEntitiesOfClass(Villager.class, area, Villager::isAlive);
		Villager best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Villager villager : found) {
			double distance = villager.distanceToSqr(x, y, z);
			if (distance < bestDistance) {
				best = villager;
				bestDistance = distance;
			}
		}
		// Dying villagers are no longer "alive", but their death sound still comes from them.
		if (best == null) {
			for (Villager villager : level.getEntitiesOfClass(Villager.class, area)) {
				return villager;
			}
		}
		return best;
	}

	private static boolean isSpeaking(Villager villager) {
		Line line = speaking.get(villager.getId());
		return line != null && Minecraft.getInstance().getSoundManager().isActive(line.sound());
	}

	private static boolean hasRoom(TalkingVillagersConfig config) {
		SoundManager sounds = Minecraft.getInstance().getSoundManager();
		int active = 0;
		for (Line line : speaking.values()) {
			if (sounds.isActive(line.sound())) {
				active++;
			}
		}
		return active < config.maxSpeakers;
	}

	private static boolean roll(float chance) {
		return chance >= 1f || ThreadLocalRandom.current().nextFloat() < chance;
	}

	/** Plays a line from the villager. Returns true if something was played. */
	private static boolean speak(Villager villager, String category, boolean interrupt, Priority priority) {
		String fallback = category.startsWith("baby_") ? "baby_ambient" : category.split("_")[0];
		if (lines.count(category) == 0) {
			// Modded professions and the like fall back to the generic lines.
			category = lines.count(fallback) > 0 ? fallback : "ambient";
		}
		VoiceChoice voice = voice(villager.getUUID(), villager.isBaby());
		if (voice == null || lines.count(category) == 0) {
			return false;
		}
		SoundManager sounds = Minecraft.getInstance().getSoundManager();
		Line previous = speaking.get(villager.getId());
		if (previous != null) {
			if (!interrupt && sounds.isActive(previous.sound())) {
				return false;
			}
			sounds.stop(previous.sound());
		}
		int index = pickLine(category);
		SoundInstance line = new EntityBoundSoundInstance(event(voice.id(), category, index), SoundSource.VOICE,
				TalkingVillagersConfig.get().volume, voice.pitch(), villager, ThreadLocalRandom.current().nextLong());
		pruneFinished(sounds);
		speaking.put(villager.getId(), new Line(line, priority, text(category, index)));
		sounds.play(line);
		lastCategory = category;
		lastEvent = line.getIdentifier();
		return true;
	}

	private static void speakAt(double x, double y, double z, String category) {
		VoiceChoice voice = voice(UUID.randomUUID(), false);
		if (voice == null || lines.count(category) == 0) {
			return;
		}
		Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(event(voice.id(), category, pickLine(category)), SoundSource.VOICE,
				TalkingVillagersConfig.get().volume, voice.pitch(), RandomSource.create(), x, y, z));
	}

	private static int pickLine(String category) {
		int count = lines.count(category);
		int line = 1 + ThreadLocalRandom.current().nextInt(count);
		if (count > 1 && line == lastLine.getInt(category)) {
			line = line % count + 1;
		}
		lastLine.put(category, line);
		return line;
	}

	private static SoundEvent event(String voice, String category, int line) {
		return SoundEvent.createVariableRangeEvent(
				Identifier.fromNamespaceAndPath(TalkingVillagersClient.MOD_ID, voice + "." + category + "." + line));
	}

	/** The line's words, from the subtitle entry minus its quote marks. */
	private static Component text(String category, int line) {
		String quoted = I18n.get("subtitles.talkingvillagers." + category + "." + line);
		return Component.literal(quoted.length() > 2 ? quoted.substring(1, quoted.length() - 1) : quoted);
	}

	/**
	 * What a villager is saying right now, or null. Called for every visible villager
	 * every frame, so it's just a map lookup.
	 */
	public static Component currentText(int entityId) {
		Line line = speaking.get(entityId);
		return line != null && Minecraft.getInstance().getSoundManager().isActive(line.sound()) ? line.text() : null;
	}

	private record VoiceChoice(String id, float pitch) {
	}

	/** Each villager keeps the same voice, plus a slight personal pitch, for life. */
	private static VoiceChoice voice(UUID uuid, boolean baby) {
		List<String> voices = baby ? lines.childVoices() : lines.adultVoices();
		if (voices.isEmpty()) {
			return null;
		}
		long hash = uuid.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ uuid.getLeastSignificantBits();
		hash ^= hash >>> 31;
		String id = voices.get((int) Math.floorMod(hash, (long) voices.size()));
		float pitch = 0.96f + ((hash >>> 40) & 0xFF) / 255f * 0.08f;
		return new VoiceChoice(id, pitch);
	}

	private static void pruneFinished(SoundManager sounds) {
		if (speaking.size() > 16) {
			speaking.values().removeIf(line -> !sounds.isActive(line.sound()));
		}
	}
}
