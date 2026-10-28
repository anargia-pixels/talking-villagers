package dev.doctorthe113.talkingvillagers;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;

/**
 * Draws what a villager is saying above its head, nametag-style but with no
 * background. It's separate text placed above any existing name, so named
 * villagers keep their names.
 */
public final class SpeechText {
	/** Same world-to-text scale vanilla uses for nametags. */
	private static final float SCALE = 0.025f;
	private static final int MAX_WIDTH = 160;
	private static final int ROW_HEIGHT = 10;
	/** Lift the text above a nametag when there is one: one text row, plus a gap. */
	private static final double NAME_CLEARANCE = (ROW_HEIGHT + 2) * SCALE;

	/** Implemented on EntityRenderState by a mixin, to carry the text from extract to submit. */
	public interface Holder {
		Component talkingvillagers$getSpeech();

		void talkingvillagers$setSpeech(Component speech);
	}

	private SpeechText() {
	}

	/** Called for every entity when its render state is filled in. */
	public static void extract(Entity entity, EntityRenderState state) {
		Holder holder = (Holder) state;
		if (!(entity instanceof Villager)) {
			if (holder.talkingvillagers$getSpeech() != null) {
				holder.talkingvillagers$setSpeech(null);
			}
			return;
		}
		TalkingVillagersConfig config = TalkingVillagersConfig.get();
		boolean show = config.enabled && config.showSpeechText
				&& state.distanceToCameraSq <= (double) config.speechTextDistance * config.speechTextDistance;
		holder.talkingvillagers$setSpeech(show ? Speech.currentText(entity.getId()) : null);
	}

	public static void submit(EntityRenderState state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
		Component text = ((Holder) state).talkingvillagers$getSpeech();
		if (text == null || state.isInvisible) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		List<FormattedCharSequence> rows = font.split(text, MAX_WIDTH);
		// Vanilla draws nametags half a block above the attachment point (the top of the head).
		double y = (state.nameTagAttachment != null ? state.nameTagAttachment.y : state.boundingBoxHeight) + 0.5;
		if (state.nameTag != null) {
			y += NAME_CLEARANCE;
		}
		int light = LightCoordsUtil.lightCoordsWithEmission(state.lightCoords, 2);

		pose.pushPose();
		pose.translate(0, y, 0);
		pose.rotate(camera.orientation);
		pose.scale(SCALE, -SCALE, SCALE);
		// Bottom row sits where a nametag would; earlier rows stack upward.
		for (int i = 0; i < rows.size(); i++) {
			FormattedCharSequence row = rows.get(i);
			float rowY = (i - rows.size() + 1) * ROW_HEIGHT;
			collector.submitText(pose, -font.width(row) / 2f, rowY, row, true, Font.DisplayMode.NORMAL,
					light, 0xFFFFFFFF, 0, 0);
		}
		pose.popPose();
	}
}
