package dev.doctorthe113.talkingvillagers.mixin;

import dev.doctorthe113.talkingvillagers.SpeechText;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Gives render states a slot for the speech text; null for everything not talking. */
@Mixin(EntityRenderState.class)
abstract class EntityRenderStateMixin implements SpeechText.Holder {
	@Unique
	private Component talkingvillagers$speech;

	@Override
	public Component talkingvillagers$getSpeech() {
		return talkingvillagers$speech;
	}

	@Override
	public void talkingvillagers$setSpeech(Component speech) {
		talkingvillagers$speech = speech;
	}
}
