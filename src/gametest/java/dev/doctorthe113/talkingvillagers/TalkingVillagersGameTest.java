package dev.doctorthe113.talkingvillagers;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;
import java.util.function.Predicate;

/**
 * Plays through the main triggers in a real world and checks that the right
 * kind of line gets spoken and resolves to an actual sound.
 */
public class TalkingVillagersGameTest implements FabricClientGameTest {
	/** Villager idle sounds come every few seconds at random; give them time. */
	private static final int AMBIENT_TIMEOUT = 20 * 120;

	@Override
	public void runTest(ClientGameTestContext context) {
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		config.ambientChance = 1f;
		config.greetChance = 1f;

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksDownload();
			server.runCommand("difficulty easy");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			server.runCommand("execute as @p at @p run summon minecraft:villager ~3 ~ ~ {NoAI:1b,PersistenceRequired:1b,CustomName:\"Bob\",CustomNameVisible:1b}");
			context.waitFor(mc -> villager(mc) != null);
			// Face it, so whoever watches the test window can see who's talking.
			lookAtVillager(context);

			// Hurt: should interrupt anything and yelp.
			reset();
			server.runCommand("damage @e[type=minecraft:villager,limit=1] 1 minecraft:generic");
			expectLine(context, "hurt", c -> c.equals("hurt"), 100);
			expectSpeechText(context, "hurt");

			// A zombie next to it: the next idle sound becomes a panic line. Midnight so it doesn't burn.
			server.runCommand("time set midnight");
			server.runCommand("effect give @e[type=minecraft:villager] minecraft:resistance infinite 255 true");
			server.runCommand("execute as @p at @p run summon minecraft:zombie ~5 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
			reset();
			expectLine(context, "zombie panic", c -> c.equals("flee_zombie"), AMBIENT_TIMEOUT);
			server.runCommand("kill @e[type=minecraft:zombie]");

			server.runCommand("execute as @p at @p run summon minecraft:pillager ~5 ~ ~ {NoAI:1b,PersistenceRequired:1b}");
			reset();
			expectLine(context, "pillager panic", c -> c.equals("flee_pillager"), AMBIENT_TIMEOUT);
			server.runCommand("kill @e[type=minecraft:pillager]");
			server.runCommand("time set noon");
			context.waitTicks(20);

			// Snow: snowy biome plus precipitation. The test world is superflat near the
			// bottom of the world, so keep the range above the floor or the command fails.
			server.runCommand("execute as @p at @p run fillbiome ~-16 ~-2 ~-16 ~16 ~6 ~16 minecraft:snowy_plains");
			try {
				context.waitFor(mc -> villager(mc) != null && mc.level.getBiome(villager(mc).blockPosition())
						.unwrapKey().map(k -> k.identifier().getPath().equals("snowy_plains")).orElse(false), 100);
			} catch (AssertionError | RuntimeException e) {
				throw new AssertionError("fillbiome didn't turn the villager's spot snowy", e);
			}
			server.runCommand("weather rain");
			context.waitFor(mc -> mc.level != null && mc.level.isRaining(), 20 * 30);
			reset();
			expectLine(context, "snow chatter", c -> c.equals("ambient_snow"), AMBIENT_TIMEOUT * 2);
			if (Speech.lastSituation.weather() != Situation.Weather.SNOW) {
				throw new AssertionError("expected snow, saw " + Speech.lastSituation.weather());
			}
			server.runCommand("weather clear");

			// Right-click goes through the real interaction path.
			context.waitTicks(20 * 4);
			reset();
			interact(context, server);
			expectLine(context, "greeting", c -> c.startsWith("greet"), 40);
			expectSpeechText(context, "greeting");

			// Shut the player and villager in a stone room: greetings notice.
			server.runCommand("execute as @p at @p run fill ~-3 ~-1 ~-3 ~5 ~3 ~3 minecraft:stone hollow");
			context.waitTicks(20);
			boolean indoors = false;
			for (int attempt = 0; attempt < 15 && !indoors; attempt++) {
				context.waitTicks(20 * 4);
				reset();
				interact(context, server);
				context.waitTicks(10);
				if (Speech.lastSituation == null || !Speech.lastSituation.indoorsWithPlayer()) {
					throw new AssertionError("player and villager in a closed room weren't detected as indoors");
				}
				indoors = "greet_indoors".equals(Speech.lastCategory);
			}
			if (!indoors) {
				throw new AssertionError("never heard an indoor greeting");
			}
		}
	}

	/** The line shows above the head while it plays; screenshot it for a look. */
	private static void expectSpeechText(ClientGameTestContext context, String what) {
		context.waitTicks(3);
		boolean shown = context.computeOnClient(mc -> Speech.currentText(villager(mc).getId()) != null);
		if (!shown) {
			throw new AssertionError("no speech text for the " + what + " line");
		}
		context.takeScreenshot("speech-" + what);
	}

	private static void lookAtVillager(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, villager(mc).getBoundingBox().getCenter()));
	}

	private static Villager villager(Minecraft mc) {
		if (mc.level == null || mc.player == null) {
			return null;
		}
		List<Villager> found = mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(8));
		return found.isEmpty() ? null : found.getFirst();
	}

	/** A real right-click: aim at the villager and press the use key. */
	private static void interact(ClientGameTestContext context, TestServerContext server) {
		lookAtVillager(context);
		context.waitFor(mc -> mc.crosshairPickEntity instanceof Villager, 40);
		context.getInput().pressKey(options -> options.keyUse);
	}

	private static void reset() {
		Speech.lastCategory = null;
		Speech.lastEvent = null;
	}

	/** Waits for a line whose category matches, then checks its sound exists. */
	private static void expectLine(ClientGameTestContext context, String what, Predicate<String> category, int timeout) {
		try {
			context.waitFor(mc -> Speech.lastCategory != null && category.test(Speech.lastCategory), timeout);
		} catch (AssertionError | RuntimeException e) {
			throw new AssertionError("no " + what + " line; last category was " + Speech.lastCategory, e);
		}
		Identifier event = Speech.lastEvent;
		boolean resolves = context.computeOnClient(mc -> mc.getSoundManager().getSoundEvent(event) != null);
		if (!resolves) {
			throw new AssertionError(what + " line " + event + " isn't in sounds.json");
		}
		TalkingVillagersClient.LOGGER.info("[gametest] {}: {} ({})", what, Speech.lastCategory, event);
	}
}
