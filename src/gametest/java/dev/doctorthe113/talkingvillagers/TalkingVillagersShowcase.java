package dev.doctorthe113.talkingvillagers;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.screenshot.TestScreenshotOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.EntityBoundSoundInstance;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Records README footage: a short village scene captured frame by frame, plus a
 * timeline of which lines started when, so tools/showcase.py can lay the voice
 * clips under the frames. Runs only under ./gradlew runShowcase.
 */
public class TalkingVillagersShowcase implements FabricClientGameTest {
	static final String PROPERTY = "talkingvillagers.showcase";
	private static final int WIDTH = 960;
	private static final int HEIGHT = 540;
	/** Longest any one line may take before the scene moves on. */
	private static final int MAX_LINE_TICKS = 20 * 8;

	private final List<String> timeline = new ArrayList<>();
	private Path frames;
	private long start;
	private int frame;

	@Override
	public void runTest(ClientGameTestContext context) {
		if (!Boolean.getBoolean(PROPERTY)) {
			return;
		}
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		config.ambientChance = 0f;
		config.greetChance = 1f;
		frames = Path.of("showcase").toAbsolutePath();

		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			TestServerContext server = world.getServer();
			world.getConnection().waitForChunksDownload();
			int y = context.computeOnClient(mc -> mc.player.getBlockY());
			setScene(server, y);
			UUID librarian = summon(server, "librarian", "female", false, -2.2, y, 5.5);
			UUID baby = summon(server, "none", "girl", true, 0.4, y, 4.6);
			UUID farmer = summon(server, "farmer", "male", false, 2.8, y, 5.8);
			context.waitFor(mc -> find(mc, librarian) != null && find(mc, baby) != null && find(mc, farmer) != null, 100);
			// F1: no hotbar or hand in the shot.
			context.getInput().pressKey(options -> options.keyToggleGui);
			context.runOnClient(mc -> {
				mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(0.5, y + 1.2, 5.5));
				mc.player.setXRot(mc.player.getXRot() - 6);
			});
			// Let chunks and lighting settle so the first frames aren't half-built.
			context.waitTicks(40);

			start = System.nanoTime();
			record(context, 15);
			say(context, librarian, mc -> Speech.onInteract(find(mc, librarian)));
			record(context, 6);
			say(context, baby, mc -> mumble(mc, find(mc, baby)));
			record(context, 6);
			server.runCommand("summon minecraft:zombie 6.5 " + y + " 7.5 {NoAI:1b,PersistenceRequired:1b,Rotation:[110f,0f]}");
			server.runCommand("item replace entity @e[type=minecraft:zombie] armor.head with minecraft:leather_helmet");
			record(context, 8);
			say(context, farmer, mc -> mumble(mc, find(mc, farmer)));
			say(context, librarian, mc -> mumble(mc, find(mc, librarian)));
			record(context, 10);

			Files.write(frames.resolve("timeline.txt"), timeline);
			TalkingVillagersClient.LOGGER.info("[showcase] {} frames in {}", frame, frames);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static void setScene(TestServerContext server, int y) {
		server.runCommand("time set 1000");
		server.runCommand("weather clear");
		server.runCommand("difficulty easy");
		server.runCommand("tp @p 0.5 " + y + " 0.5 0 0");
		server.runCommand("place template minecraft:village/plains/houses/plains_small_house_1 -9 " + y + " 9");
		server.runCommand("place template minecraft:village/plains/houses/plains_small_house_5 4 " + y + " 10");
		// Templates keep the jigsaw blocks that join village pieces; nobody wants those in frame.
		server.runCommand("fill -16 " + (y - 2) + " 8 16 " + (y + 12) + " 24 minecraft:air replace minecraft:jigsaw");
		server.runCommand("fill -12 " + (y - 1) + " 3 12 " + (y - 1) + " 8 minecraft:dirt_path");
		for (int[] flower : new int[][] {{-5, 2}, {-4, 1}, {5, 1}, {6, 2}, {-7, 3}}) {
			server.runCommand("setblock " + flower[0] + " " + y + " " + flower[1] + " minecraft:poppy");
		}
	}

	/** Summons a villager whose UUID gives it the wanted voice at its natural pitch. */
	private static UUID summon(TestServerContext server, String profession, String voice, boolean baby,
			double x, int y, double z) {
		UUID uuid;
		Speech.VoiceChoice choice;
		do {
			uuid = UUID.randomUUID();
			choice = Speech.voice(uuid, baby);
		} while (!choice.id().equals(voice) || Math.abs(choice.pitch() - 1f) > 0.004f);
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();
		// Face the camera, turned a little toward it.
		float yaw = (float) Math.toDegrees(Math.atan2(-(0.5 - x), 0.5 - z));
		server.runCommand(String.format(Locale.ROOT,
				"summon minecraft:villager %.2f %d %.2f {NoAI:1b,PersistenceRequired:1b,Age:%d,Rotation:[%.1ff,0f],"
						+ "UUID:[I;%d,%d,%d,%d],VillagerData:{profession:\"minecraft:%s\",level:2,type:\"minecraft:plains\"}}",
				x, y, z, baby ? -2400000 : 0, yaw,
				(int) (most >> 32), (int) most, (int) (least >> 32), (int) least, profession));
		return uuid;
	}

	private static Villager find(Minecraft mc, UUID uuid) {
		List<Villager> found = mc.level.getEntitiesOfClass(Villager.class, mc.player.getBoundingBox().inflate(16),
				v -> v.getUUID().equals(uuid));
		return found.isEmpty() ? null : found.getFirst();
	}

	/** Plays the villager's vanilla idle sound, which the mod turns into a line. */
	private static void mumble(Minecraft mc, Villager villager) {
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		config.ambientChance = 1f;
		mc.getSoundManager().play(new EntityBoundSoundInstance(SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
				1f, 1f, villager, 0L));
		config.ambientChance = 0f;
	}

	/** Starts a line, notes it in the timeline, and records until it's finished. */
	private void say(ClientGameTestContext context, UUID speaker, Consumer<Minecraft> trigger) {
		Speech.lastEvent = null;
		context.runOnClient(trigger::accept);
		Identifier event = Speech.lastEvent;
		if (event == null) {
			throw new AssertionError("nothing was said");
		}
		timeline.add(String.format(Locale.ROOT, "line %.3f %s", seconds(), event));
		for (int tick = 0; tick < MAX_LINE_TICKS; tick++) {
			record(context, 1);
			boolean talking = context.computeOnClient(mc -> Speech.currentText(find(mc, speaker).getId()) != null);
			if (!talking && tick > 5) {
				break;
			}
		}
		record(context, 4);
	}

	private void record(ClientGameTestContext context, int ticks) {
		for (int i = 0; i < ticks; i++) {
			context.waitTick();
			String name = String.format(Locale.ROOT, "frame_%04d", frame++);
			context.takeScreenshot(TestScreenshotOptions.of(name).disableCounterPrefix()
					.withSize(WIDTH, HEIGHT).withDestinationDir(frames));
			timeline.add(String.format(Locale.ROOT, "frame %.3f %s.png", seconds(), name));
		}
	}

	private double seconds() {
		return (System.nanoTime() - start) / 1e9;
	}
}
