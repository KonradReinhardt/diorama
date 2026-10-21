package dev.diorama.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.diorama.SettingsScreen;

/** Optional Mod Menu entrypoint. The world map and key binding open the same settings screen. */
public final class DioramaModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<SettingsScreen> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
