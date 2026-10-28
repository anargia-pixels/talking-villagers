package dev.doctorthe113.talkingvillagers;

import java.util.random.RandomGenerator;

/**
 * What's going on around a villager when it's about to speak, and which line
 * category fits. Kept free of game classes so the choice logic is easy to test.
 *
 * @param profession profession path such as "farmer", "nitwit" or "none"
 */
public record Situation(
		boolean baby,
		String profession,
		Threat threat,
		boolean indoorsWithPlayer,
		Weather weather,
		TimeOfDay time,
		boolean playerIsHero,
		boolean playerHoldsEmerald) {

	public enum Threat { NONE, MONSTER, ZOMBIE, RAIDER }

	public enum Weather { CLEAR, RAIN, SNOW, THUNDER }

	public enum TimeOfDay { MORNING, DAY, EVENING, NIGHT }

	/** Line category for an idle mumble. */
	public String ambient(RandomGenerator random) {
		if (baby) {
			return threat != Threat.NONE ? "baby_hurt" : "baby_ambient";
		}
		String danger = danger();
		if (danger != null) {
			return danger;
		}
		if (indoorsWithPlayer && random.nextFloat() < 0.5f) {
			return "ambient_indoors";
		}
		switch (weather) {
			case THUNDER -> {
				if (random.nextFloat() < 0.5f) return "ambient_thunder";
			}
			case RAIN -> {
				if (random.nextFloat() < 0.4f) return "ambient_rain";
			}
			case SNOW -> {
				if (random.nextFloat() < 0.4f) return "ambient_snow";
			}
			case CLEAR -> {
			}
		}
		switch (time) {
			case NIGHT -> {
				if (random.nextFloat() < 0.45f) return "ambient_night";
			}
			case MORNING -> {
				if (random.nextFloat() < 0.3f) return "ambient_morning";
			}
			case EVENING -> {
				if (random.nextFloat() < 0.3f) return "ambient_evening";
			}
			case DAY -> {
			}
		}
		return switch (profession) {
			case "nitwit" -> random.nextFloat() < 0.5f ? "ambient_nitwit" : "ambient";
			case "none" -> random.nextFloat() < 0.4f ? "ambient_unemployed" : "ambient";
			default -> random.nextFloat() < 0.4f ? "ambient_" + profession : "ambient";
		};
	}

	/** Line category when the player right-clicks the villager. */
	public String greet(RandomGenerator random) {
		if (baby) {
			return threat != Threat.NONE ? "baby_hurt" : "baby_greet";
		}
		String danger = danger();
		if (danger != null) {
			return danger;
		}
		if (playerIsHero && random.nextFloat() < 0.6f) return "greet_hero";
		if (indoorsWithPlayer && random.nextFloat() < 0.5f) return "greet_indoors";
		if (playerHoldsEmerald && random.nextFloat() < 0.5f) return "greet_emerald";
		if ((weather == Weather.RAIN || weather == Weather.THUNDER) && random.nextFloat() < 0.35f) return "greet_rain";
		if (weather == Weather.SNOW && random.nextFloat() < 0.35f) return "greet_snow";
		if (time == TimeOfDay.NIGHT && random.nextFloat() < 0.35f) return "greet_night";
		return switch (profession) {
			case "nitwit" -> "greet_nitwit";
			case "none" -> random.nextFloat() < 0.6f ? "greet_unemployed" : "greet";
			default -> random.nextFloat() < 0.5f ? "greet_" + profession : "greet";
		};
	}

	/** Line category when the villager gets hurt. */
	public String hurt(RandomGenerator random) {
		if (baby) {
			return "baby_hurt";
		}
		// Being hit while something's chasing you: half the time, yell about the chaser.
		String danger = danger();
		return danger != null && threat != Threat.MONSTER && random.nextFloat() < 0.5f ? danger : "hurt";
	}

	/** Danger lines win over everything else, or null when it's safe. */
	private String danger() {
		return switch (threat) {
			case ZOMBIE -> "flee_zombie";
			case RAIDER -> "flee_pillager";
			case MONSTER -> "ambient_danger";
			case NONE -> null;
		};
	}

	public boolean inDanger() {
		return threat != Threat.NONE;
	}
}
