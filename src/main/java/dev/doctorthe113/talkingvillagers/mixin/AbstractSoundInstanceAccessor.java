package dev.doctorthe113.talkingvillagers.mixin;

import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets a villager's vanilla mumble be re-pitched to match its voice. */
@Mixin(AbstractSoundInstance.class)
public interface AbstractSoundInstanceAccessor {
	@Accessor("pitch")
	float talkingvillagers$getPitch();

	@Accessor("pitch")
	void talkingvillagers$setPitch(float pitch);
}
