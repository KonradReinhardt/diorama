package dev.diorama.test.fabric;

import dev.diorama.SettingsScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

/** Tests the entrypoint actually registered with Fabric, without a test-time Mod Menu dependency. */
public final class ModMenuScenario {
    public static void run(ClientGameTestContext context) {
        context.runOnClient(mc -> {
            if (!FabricLoader.getInstance().isModLoaded("modmenu"))
                throw new AssertionError("Run modmenu scenario with -PdioramaModMenuTest=true");
            try {
                Object entrypoint = FabricLoader.getInstance().getEntrypoints("modmenu", Object.class).stream()
                    .filter(value -> value.getClass().getName().equals("dev.diorama.fabric.DioramaModMenu"))
                    .findFirst().orElseThrow();
                Object factory = entrypoint.getClass().getMethod("getModConfigScreenFactory").invoke(entrypoint);
                Class<?> factoryType = Class.forName("com.terraformersmc.modmenu.api.ConfigScreenFactory");
                Screen parent = new TitleScreen();
                Screen screen = (Screen) factoryType.getMethod("create", Screen.class).invoke(factory, parent);
                if (!(screen instanceof SettingsScreen)) throw new AssertionError("Wrong configuration screen");
                mc.gui.setScreen(screen);
                screen.onClose();
                if (mc.gui.screen() != parent) throw new AssertionError("Done didn't return to the Mod Menu parent");
                mc.gui.setScreen(screen);
            } catch (ReflectiveOperationException failure) { throw new AssertionError("Mod Menu integration failed", failure); }
        });
        context.takeScreenshot("modmenu-diorama-settings");
        context.setScreen(() -> null);
    }
}
