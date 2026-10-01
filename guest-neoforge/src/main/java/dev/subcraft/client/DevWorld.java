package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.world.SubWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;

/**
 * Opens (or creates) the dedicated SubCraft world once the host is linked. Never touches any
 * other world (MISSION.md rule 13).
 */
public final class DevWorld {
	private static boolean attempted;
	private static long lastLogMs;

	private DevWorld() {
	}

	public static void openWhenReady(Minecraft minecraft) {
		if (attempted || minecraft.level != null || minecraft.getOverlay() != null) {
			return;
		}
		if (!(minecraft.screen instanceof TitleScreen title)) {
			long now = System.currentTimeMillis();
			if (minecraft.screen != null && now - lastLogMs > 5000) {
				lastLogMs = now;
				SubCraft.LOG.info("SubCraft: waiting on screen {} before opening the SubCraft world", minecraft.screen.getClass().getName());
			}
			// First-launch prompts (accessibility onboarding) stand in front of the title screen;
			// nobody can click them in a hidden window.
			if (minecraft.screen == null || minecraft.screen instanceof AccessibilityOnboardingScreen) {
				minecraft.options.onboardAccessibility = false;
				minecraft.options.save();
				minecraft.setScreen(new TitleScreen());
			}
			return;
		}
		attempted = true;
		String name = SubWorld.DEV_WORLD_NAME;
		if (minecraft.getLevelSource().levelExists(name)) {
			SubCraft.LOG.info("SubCraft: opening world '{}'", name);
			minecraft.createWorldOpenFlows().openWorld(name, () -> minecraft.setScreen(title));
			return;
		}
		SubCraft.LOG.info("SubCraft: creating world '{}'", name);
		GameRules rules = new GameRules();
		LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, false, Difficulty.NORMAL, true, rules, WorldDataConfiguration.DEFAULT);
		minecraft.createWorldOpenFlows().createFreshLevel(
			name,
			settings,
			new WorldOptions(0L, false, false),
			registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(SubWorld.PRESET).value().createWorldDimensions(),
			title
		);
	}

	/** Allows another attempt (the world failed to open, or the player left it). */
	public static void reset() {
		attempted = false;
	}
}
