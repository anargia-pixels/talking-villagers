package dev.doctorthe113.talkingvillagers;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Mod Menu config screen. Plain literals instead of translation keys, since
 * en_us.json is generated from the dialogue files.
 */
public class TalkingVillagersConfigScreen extends Screen {
	private static final int ROW = 24;
	private static final int WIDTH = 220;

	private static final List<Float> CHATTINESS = List.of(0.15f, 0.35f, 0.6f, 1f);
	private static final List<Float> GREETING = List.of(0f, 0.5f, 0.85f, 1f);
	private static final List<Float> VOLUMES = List.of(0.25f, 0.5f, 0.75f, 1f);
	private static final List<Integer> SPEAKERS = List.of(1, 2, 3, 4, 6, 8);
	private static final List<Integer> DISTANCES = List.of(8, 16, 24, 32);

	private final Screen parent;

	public TalkingVillagersConfigScreen(Screen parent) {
		super(Component.literal("Talking Villagers"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		int x = (width - WIDTH) / 2;
		int y = height / 6;

		addRenderableWidget(new StringWidget(0, y - 24, width, 9, title, font));

		y = toggle(x, y, "Talking villagers", "Off stops all villager speech; the game behaves as if the mod weren't there.",
				config.enabled, Speech::setEnabled);
		y = toggle(x, y, "Speech above heads", "Show what villagers say as floating text above them.",
				config.showSpeechText, v -> config.showSpeechText = v);
		y = choice(x, y, "Speech text range", "How close a villager must be for its words to show.",
				DISTANCES, config.speechTextDistance, v -> Component.literal(v + " blocks"), v -> config.speechTextDistance = v);
		y = choice(x, y, "Chattiness", "How often villagers talk instead of just mumbling.",
				CHATTINESS, config.ambientChance, TalkingVillagersConfigScreen::chattiness, v -> config.ambientChance = v);
		y = choice(x, y, "Greet on right-click", null,
				GREETING, config.greetChance, TalkingVillagersConfigScreen::frequency, v -> config.greetChance = v);
		y = choice(x, y, "Talking at once", "Most villagers speaking at the same time. Pain and panic ignore this.",
				SPEAKERS, config.maxSpeakers, v -> Component.literal(String.valueOf(v)), v -> config.maxSpeakers = v);
		y = choice(x, y, "Voice volume", "On top of the Voice/Speech slider in Music & Sounds.",
				VOLUMES, config.volume, v -> Component.literal(Math.round(v * 100) + "%"), v -> config.volume = v);
		y = toggle(x, y, "Mute mumbles while talking", "Silence a villager's vanilla \"hmm\" sounds mid-sentence.",
				config.muteVanillaWhileSpeaking, v -> config.muteVanillaWhileSpeaking = v);

		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
				.bounds(x, y + 8, WIDTH, 20)
				.build());
	}

	private int toggle(int x, int y, String label, String tooltip, boolean value, Consumer<Boolean> set) {
		CycleButton.Builder<Boolean> builder = CycleButton.onOffBuilder(value);
		if (tooltip != null) {
			builder.withTooltip(v -> Tooltip.create(Component.literal(tooltip)));
		}
		addRenderableWidget(builder.create(x, y, WIDTH, 20, Component.literal(label), (button, v) -> {
			set.accept(v);
			TalkingVillagersConfig.save();
		}));
		return y + ROW;
	}

	private <T extends Number> int choice(int x, int y, String label, String tooltip, List<T> values, T current,
			Function<T, Component> name, Consumer<T> set) {
		CycleButton.Builder<T> builder = CycleButton.builder(name, nearest(values, current)).withValues(values);
		if (tooltip != null) {
			builder.withTooltip(v -> Tooltip.create(Component.literal(tooltip)));
		}
		addRenderableWidget(builder.create(x, y, WIDTH, 20, Component.literal(label), (button, v) -> {
			set.accept(v);
			TalkingVillagersConfig.save();
		}));
		return y + ROW;
	}

	private static Component chattiness(float chance) {
		return Component.literal(chance <= 0.15f ? "Quiet" : chance <= 0.35f ? "Normal" : chance < 1f ? "Chatty" : "Nonstop");
	}

	private static Component frequency(float chance) {
		return Component.literal(chance <= 0f ? "Never" : chance <= 0.5f ? "Sometimes" : chance < 1f ? "Usually" : "Always");
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** Keeps hand-edited config values usable with the fixed choices. */
	private static <T extends Number> T nearest(List<T> options, T value) {
		T best = options.getFirst();
		for (T option : options) {
			if (Math.abs(option.doubleValue() - value.doubleValue()) < Math.abs(best.doubleValue() - value.doubleValue())) {
				best = option;
			}
		}
		return best;
	}
}
