package dev.doctorthe113.talkingvillagers;

import dev.doctorthe113.talkingvillagers.Situation.Threat;
import dev.doctorthe113.talkingvillagers.Situation.TimeOfDay;
import dev.doctorthe113.talkingvillagers.Situation.Weather;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SituationTest {
	private static Situation farmer(Threat threat, boolean indoors, Weather weather, TimeOfDay time) {
		return new Situation(false, "farmer", threat, indoors, weather, time, false, false);
	}

	/** Every category a situation can produce over many rolls. */
	private static Set<String> ambientOutcomes(Situation situation) {
		RandomGenerator random = new Random(1);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			seen.add(situation.ambient(random));
		}
		return seen;
	}

	@Test
	void dangerOverridesEverything() {
		assertEquals(Set.of("flee_zombie"), ambientOutcomes(farmer(Threat.ZOMBIE, true, Weather.THUNDER, TimeOfDay.NIGHT)));
		assertEquals(Set.of("flee_pillager"), ambientOutcomes(farmer(Threat.RAIDER, false, Weather.CLEAR, TimeOfDay.DAY)));
		assertEquals(Set.of("ambient_danger"), ambientOutcomes(farmer(Threat.MONSTER, false, Weather.CLEAR, TimeOfDay.DAY)));
	}

	@Test
	void weatherAndTimeShowUp() {
		assertTrue(ambientOutcomes(farmer(Threat.NONE, false, Weather.SNOW, TimeOfDay.DAY)).contains("ambient_snow"));
		assertTrue(ambientOutcomes(farmer(Threat.NONE, false, Weather.RAIN, TimeOfDay.DAY)).contains("ambient_rain"));
		assertTrue(ambientOutcomes(farmer(Threat.NONE, false, Weather.THUNDER, TimeOfDay.DAY)).contains("ambient_thunder"));
		Set<String> night = ambientOutcomes(farmer(Threat.NONE, false, Weather.CLEAR, TimeOfDay.NIGHT));
		assertEquals(Set.of("ambient_night", "ambient_farmer", "ambient"), night);
	}

	@Test
	void snowNeverProducesRainLines() {
		Set<String> snow = ambientOutcomes(farmer(Threat.NONE, false, Weather.SNOW, TimeOfDay.DAY));
		assertTrue(!snow.contains("ambient_rain"));
	}

	@Test
	void indoorsShowsUpOnlyIndoors() {
		assertTrue(ambientOutcomes(farmer(Threat.NONE, true, Weather.CLEAR, TimeOfDay.DAY)).contains("ambient_indoors"));
		assertTrue(!ambientOutcomes(farmer(Threat.NONE, false, Weather.CLEAR, TimeOfDay.DAY)).contains("ambient_indoors"));
	}

	@Test
	void babiesOnlyUseChildCategories() {
		Situation baby = new Situation(true, "none", Threat.NONE, true, Weather.RAIN, TimeOfDay.NIGHT, true, true);
		RandomGenerator random = new Random(2);
		for (int i = 0; i < 200; i++) {
			assertTrue(baby.ambient(random).startsWith("baby_"));
			assertTrue(baby.greet(random).startsWith("baby_"));
			assertTrue(baby.hurt(random).startsWith("baby_"));
		}
	}

	@Test
	void nitwitGreetsAsNitwit() {
		Situation nitwit = new Situation(false, "nitwit", Threat.NONE, false, Weather.CLEAR, TimeOfDay.DAY, false, false);
		assertEquals("greet_nitwit", nitwit.greet(new Random(3)));
	}

	@Test
	void hurtWhileChasedSometimesNamesTheChaser() {
		Situation chased = farmer(Threat.ZOMBIE, false, Weather.CLEAR, TimeOfDay.DAY);
		RandomGenerator random = new Random(4);
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < 200; i++) {
			seen.add(chased.hurt(random));
		}
		assertEquals(Set.of("hurt", "flee_zombie"), seen);
	}
}
