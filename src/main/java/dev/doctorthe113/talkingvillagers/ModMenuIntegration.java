package dev.doctorthe113.talkingvillagers;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Only loaded by Mod Menu, so the mod still runs without it. */
public class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return TalkingVillagersConfigScreen::new;
	}
}
