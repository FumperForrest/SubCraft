package dev.subcraft.capture;

import dev.subcraft.SubCraft;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * Mods that draw in the world from outside entity renderers, during the capture (which replaces
 * Minecraft's skipped world pass):
 * - NeoForge's RenderLevelStageEvent for the in-world stages. Mods draw into Minecraft's buffer
 *   source and flush it; the flush goes through vertex buffers, which VboCapture replays. The
 *   model-view handed to them is the identity, so what they draw comes out camera-relative in world
 *   orientation like everything else captured; the frustum is the real one, for their culling.
 *   AFTER_LEVEL (full-screen effects) and the sky/weather stages are left out.
 * - Immersive Vehicles' translucent pass (glass) runs from its own hook at the tail of the skipped
 *   LevelRenderer.renderLevel; mtsTranslucent can call it, but it is off: that pass also draws the
 *   vehicles' light and brightness layers, which came out as a dark overlay (glass is missing).
 */
public final class StageEvents {
	private static final List<RenderLevelStageEvent.Stage> STAGES = List.of(RenderLevelStageEvent.Stage.AFTER_ENTITIES,
		RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES, RenderLevelStageEvent.Stage.AFTER_PARTICLES,
		RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS);
	private static final Set<String> warned = new HashSet<>();
	private static Method mtsRenderCall;
	private static boolean mtsLooked;

	private StageEvents() {
	}

	static void fire(Minecraft minecraft, Camera camera, float partial, int ticks) {
		Matrix4f identity = new Matrix4f();
		Matrix4f projection = new Matrix4f(com.mojang.blaze3d.systems.RenderSystem.getProjectionMatrix());
		Matrix4f rotation = new Matrix4f().rotation(camera.rotation().conjugate(new Quaternionf()));
		Frustum frustum = new Frustum(rotation, projection);
		var pos = camera.getPosition();
		frustum.prepare(pos.x, pos.y, pos.z);
		for (RenderLevelStageEvent.Stage stage : STAGES) {
			try {
				ClientHooks.dispatchRenderStage(stage, minecraft.levelRenderer, null, identity, projection, ticks, camera, frustum);
			} catch (RuntimeException e) {
				warnOnce("stage " + stage, e);
			}
		}
		// Not mtsTranslucent(partial): Immersive Vehicles' blended pass also holds its light and
		// brightness layers (special blending), which came out as a dark overlay on the vehicle.
		// Whatever the listeners left queued in Minecraft's shared buffers, drawn (and captured) now.
		try {
			minecraft.renderBuffers().bufferSource().endBatch();
		} catch (RuntimeException e) {
			warnOnce("buffer source flush", e);
		}
	}

	private static void mtsTranslucent(float partial) {
		if (!mtsLooked) {
			mtsLooked = true;
			try {
				mtsRenderCall = Class.forName("mcinterface1211.InterfaceRender").getMethod("doRenderCall", boolean.class, float.class);
				SubCraft.LOG.info("SubCraft: Immersive Vehicles found: its translucent pass runs inside the capture");
			} catch (ReflectiveOperationException | LinkageError e) {
				mtsRenderCall = null;
			}
		}
		if (mtsRenderCall == null) {
			return;
		}
		try {
			mtsRenderCall.invoke(null, true, partial);
		} catch (ReflectiveOperationException | RuntimeException e) {
			warnOnce("Immersive Vehicles translucent pass", e);
		}
	}

	private static void warnOnce(String what, Exception e) {
		if (warned.add(what)) {
			Throwable root = e;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			SubCraft.LOG.warn("SubCraft: {} failed during the capture: {}", what, root.toString());
		}
	}
}
