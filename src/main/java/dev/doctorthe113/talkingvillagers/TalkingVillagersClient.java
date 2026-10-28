package dev.doctorthe113.talkingvillagers;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TalkingVillagersClient implements ClientModInitializer {
	public static final String MOD_ID = "talkingvillagers";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		TalkingVillagersConfig.load();
		Speech.init(Lines.load(TalkingVillagersConfig.get().disabledVoices));

		// Lines hold their villager; drop them with the world. Disconnect can fire
		// off the main thread, and sounds must be stopped on it.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(Speech::reset));

		// Everything else is driven by vanilla villager sounds (see SoundManagerMixin),
		// so the mod does no work per tick.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (level.isClientSide() && hand == InteractionHand.MAIN_HAND && entity instanceof Villager villager) {
				Speech.onInteract(villager);
			}
			return InteractionResult.PASS;
		});
	}
}
