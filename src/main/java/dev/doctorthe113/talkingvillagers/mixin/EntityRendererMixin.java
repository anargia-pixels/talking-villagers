package dev.doctorthe113.talkingvillagers.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.doctorthe113.talkingvillagers.SpeechText;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks the speech text into entity rendering without touching names or models. */
@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin<T extends Entity, S extends EntityRenderState> {
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void talkingvillagers$extractSpeech(T entity, S state, float partialTick, CallbackInfo ci) {
		SpeechText.extract(entity, state);
	}

	@Inject(method = "submit", at = @At("TAIL"))
	private void talkingvillagers$submitSpeech(S state, PoseStack pose, SubmitNodeCollector collector,
			CameraRenderState camera, CallbackInfo ci) {
		SpeechText.submit(state, pose, collector, camera);
	}
}
