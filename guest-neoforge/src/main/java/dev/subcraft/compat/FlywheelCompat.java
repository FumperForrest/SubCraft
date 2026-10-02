package dev.subcraft.compat;

import dev.subcraft.SubCraft;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Flywheel (Create's instanced renderer) draws straight to the GPU, past SubCraft's capture. With
 * its backend off, Flywheel's users fall back to ordinary block entity and entity renderers (Create:
 * KineticBlockEntityRenderer and friends draw when visualization isn't supported), which the capture
 * sees like any vanilla renderer. So while linked, Flywheel's backend is "flywheel:off", the way its
 * own /flywheel backend command switches it; the player's setting comes back on unlink. Reflection
 * only: Flywheel is not a dependency.
 */
public final class FlywheelCompat {
	private static final String OFF = "flywheel:off";
	private static String saved;

	private FlywheelCompat() {
	}

	public static void linked(Minecraft minecraft, boolean linked) {
		if (!ModList.get().isLoaded("flywheel")) {
			return;
		}
		try {
			ModConfigSpec.ConfigValue<String> backend = backendValue();
			if (linked && !OFF.equals(backend.get())) {
				saved = backend.get();
				backend.set(OFF);
				minecraft.levelRenderer.allChanged();
				SubCraft.LOG.info("SubCraft: Flywheel backend '{}' -> '{}' while linked (its renderers fall back to capturable ones)", saved, OFF);
			} else if (!linked && saved != null) {
				backend.set(saved);
				minecraft.levelRenderer.allChanged();
				SubCraft.LOG.info("SubCraft: Flywheel backend back to '{}'", saved);
				saved = null;
			}
		} catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
			SubCraft.LOG.warn("SubCraft: couldn't switch Flywheel's backend ({}); Flywheel-drawn parts won't show in Subnautica", e.toString());
		}
	}

	@SuppressWarnings("unchecked")
	private static ModConfigSpec.ConfigValue<String> backendValue() throws ReflectiveOperationException {
		Class<?> config = Class.forName("dev.engine_room.flywheel.impl.NeoForgeFlwConfig");
		Object instance = config.getField("INSTANCE").get(null);
		Object client = config.getField("client").get(instance);
		return (ModConfigSpec.ConfigValue<String>) client.getClass().getField("backend").get(client);
	}
}
