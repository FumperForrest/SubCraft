package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Platform;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import dev.subcraft.mixin.client.GameRendererInvoker;
import dev.subcraft.world.SubBlocks;
import dev.subcraft.world.SubWorld;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Per-frame glue between the Minecraft client and the host. Render thread only.
 *
 * Frame order: {@link #beginFrame} (start of Minecraft.runTick, before client ticks) pulls host
 * state and input; {@link #clientTick} publishes each physics tick; {@link #afterRender} (after
 * GameRenderer.render) publishes the player and the overlay; {@link #paceFrame} waits for the
 * next host frame.
 */
public final class SubClient {
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("subcraft.showWindow");
	public static final boolean DIAGNOSTICS = Boolean.getBoolean("subcraft.diagnostics");

	private static final LinkView.HostState host = new LinkView.HostState();
	private static final LinkView.McState mc = new LinkView.McState();
	private static volatile boolean linked;
	private static boolean tookOver;
	private static boolean windowHidden;
	private static int appliedViewportW, appliedViewportH;
	private static Screen pauseWeOpened;

	private static int lastTeleportSeq = -1;
	private static int teleportAck;
	private static boolean teleportPending;
	private static LocalPlayer lastPlayer;
	private static Vec3 holdPos;
	private static long holdSinceMs;
	private static LocalPlayer eyePlayer;
	private static float eyeSmoothed;
	private static long frameCounter;
	private static int lastPacedSeq;
	private static boolean hostStalled;
	private static long lastDiagMs;

	private SubClient() {
	}

	public static boolean linked() {
		return linked;
	}

	/** True once a host has connected this session; from then on Minecraft never reads the real input again. */
	public static boolean tookOver() {
		return tookOver;
	}

	/** The host draws the world (Minecraft's world pass is skipped). */
	public static boolean hostDrawsWorld() {
		Minecraft minecraft = Minecraft.getInstance();
		return tookOver && SubWorld.is(minecraft.level);
	}

	public static LinkView.HostState host() {
		return host;
	}

	/** Start of Minecraft.runTick: pull state and input from the host before anything else runs. */
	public static void beginFrame() {
		SubLink.poll();
		Minecraft minecraft = Minecraft.getInstance();
		boolean nowLinked = SubLink.active();
		LinkView view = SubLink.view();
		if (nowLinked) {
			view.readHostState(host); // on a torn read we keep last frame's state
		}
		if (nowLinked != linked) {
			linked = nowLinked;
			SubCraft.LOG.info("SubCraft: host link {}", linked ? "up" : "down");
			onLinkChanged(minecraft);
		}
		if (!linked) {
			return;
		}

		hideWindowOnce(minecraft);
		applyViewportSize(minecraft);
		DevWorld.openWhenReady(minecraft);

		if (host.menuOpen() || host.loading()) {
			InputBridge.releaseAll(minecraft);
		}
		InputBridge.drain(minecraft, view);

		LocalPlayer player = minecraft.player;
		if (player == null) {
			lastPlayer = null;
			return;
		}
		// A new player object means we just joined or respawned: put it where the host's player is.
		if (player != lastPlayer) {
			lastPlayer = player;
			teleportPending = true;
		}
		if (host.teleportSeq != lastTeleportSeq) {
			lastTeleportSeq = host.teleportSeq;
			teleportPending = true;
		}
		if (teleportPending && host.inGame() && !host.loading()) {
			teleport(minecraft, host.x, host.y, host.z, host.yaw, host.pitch);
			teleportAck = host.teleportSeq;
			teleportPending = false;
			holdPos = new Vec3(host.x, host.y, host.z);
		}
		// The host drives the look (zero-latency camera); Minecraft uses it for everything else.
		if (minecraft.screen == null) {
			player.setYRot(host.yaw);
			player.setXRot(host.pitch);
			player.yRotO = host.yaw;
			player.xRotO = host.pitch;
		}
	}

	private static void onLinkChanged(Minecraft minecraft) {
		if (linked) {
			tookOver = true;
			applyLinkedOptions(minecraft);
			// Fail-safe pause (MISSION.md rule 7) ends when the host is back.
			if (pauseWeOpened != null && minecraft.screen == pauseWeOpened) {
				minecraft.setScreen(null);
			}
			pauseWeOpened = null;
		} else {
			InputBridge.releaseAll(minecraft);
			if (minecraft.player != null && minecraft.screen == null) {
				pauseWeOpened = new PauseScreen(true);
				minecraft.setScreen(pauseWeOpened);
				SubCraft.LOG.info("SubCraft: host heartbeat lost; Minecraft paused");
			}
		}
	}

	/** End of every client tick. */
	public static void clientTick(Minecraft minecraft) {
		holdUntilReady(minecraft);
		publishTick(minecraft);
	}

	/** Hands the host the raw physics tick so it can interpolate on its own frame clock. */
	private static void publishTick(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		LinkView view = SubLink.view();
		if (!linked || player == null || view == null) {
			return;
		}
		float tickMs = minecraft.level != null ? minecraft.level.tickRateManager().millisecondsPerTick() : 50.0F;
		// The tick really "happened" partial ticks ago (the timer keeps the remainder).
		float remainder = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
		mc.tickNs = Platform.monoNanos() - (long) (remainder * tickMs * 1_000_000.0);
		mc.tickMs = tickMs;
		mc.prevX = player.xo;
		mc.prevY = player.yo;
		mc.prevZ = player.zo;
		mc.curX = player.getX();
		mc.curY = player.getY();
		mc.curZ = player.getZ();
		// Same smoothing as Camera.tick(): eye height eases halfway toward the target each tick.
		if (player != eyePlayer) {
			eyePlayer = player;
			eyeSmoothed = player.getEyeHeight();
		}
		mc.tickEyeO = eyeSmoothed;
		eyeSmoothed += (player.getEyeHeight() - eyeSmoothed) * 0.5F;
		mc.tickEye = eyeSmoothed;
		boolean bob = minecraft.options.bobView().get();
		mc.walkDistO = bob ? player.walkDistO : 0.0F;
		mc.walkDist = bob ? player.walkDist : 0.0F;
		mc.bobO = bob ? player.oBob : 0.0F;
		mc.bob = bob ? player.bob : 0.0F;
		view.writeMcState(mc);
	}

	/** Freeze the player after a teleport until the host's collision under them has arrived. */
	private static void holdUntilReady(Minecraft minecraft) {
		LocalPlayer player = minecraft.player;
		if (!linked || player == null) {
			return;
		}
		if (!host.inGame() || host.loading()) {
			if (holdPos == null) {
				holdPos = player.position();
			}
			teleportPending = true;
		}
		if (holdPos == null) {
			holdSinceMs = 0;
			return;
		}
		if (holdSinceMs == 0) {
			holdSinceMs = System.currentTimeMillis();
		}
		boolean ground = hasTerrainBelow(minecraft, holdPos, 12);
		if ((ground || System.currentTimeMillis() - holdSinceMs > 6000) && host.inGame() && !host.loading()) {
			SubCraft.LOG.info("SubCraft: released player at {} ({})", holdPos, ground ? "ground below" : "timed out waiting for ground");
			holdPos = null;
			return;
		}
		player.setDeltaMovement(Vec3.ZERO);
		player.setPos(holdPos.x, holdPos.y, holdPos.z);
		player.xo = holdPos.x;
		player.yo = holdPos.y;
		player.zo = holdPos.z;
		player.resetFallDistance();
	}

	private static boolean hasTerrainBelow(Minecraft minecraft, Vec3 pos, int depth) {
		if (minecraft.level == null) {
			return false;
		}
		BlockPos.MutableBlockPos p = BlockPos.containing(pos).mutable();
		for (int i = 0; i <= depth; i++, p.move(0, -1, 0)) {
			if (minecraft.level.getBlockState(p).is(SubBlocks.TERRAIN.get())) {
				return true;
			}
		}
		return false;
	}

	private static void teleport(Minecraft minecraft, double x, double y, double z, float yaw, float pitch) {
		LocalPlayer player = minecraft.player;
		player.setPos(x, y, z);
		player.setDeltaMovement(Vec3.ZERO);
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.teleportTo(x, y, z);
					sp.setYRot(yaw);
					sp.setXRot(pitch);
					sp.resetFallDistance();
				}
			});
		}
		SubCraft.LOG.info("SubCraft: teleported to {} {} {}", x, y, z);
	}

	/** After GameRenderer.render(): report the player to the host and ship the overlay frame. */
	public static void afterRender() {
		LinkView view = SubLink.view();
		if (!linked || view == null) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		int flags = 0;
		if (player != null && minecraft.level != null) {
			float partial = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
			Vec3 feet = player.getPosition(partial);
			Camera camera = minecraft.gameRenderer.getMainCamera();
			flags |= Proto.MC_IN_WORLD;
			if (player.onGround()) flags |= Proto.MC_ON_GROUND;
			if (player.isShiftKeyDown()) flags |= Proto.MC_SNEAKING;
			if (player.isSprinting()) flags |= Proto.MC_SPRINTING;
			if (player.isDeadOrDying()) flags |= Proto.MC_DEAD;
			if (player.isSwimming()) flags |= Proto.MC_SWIMMING;
			if (player.getAbilities().flying) flags |= Proto.MC_FLYING;
			if (player.isInWater()) flags |= Proto.MC_IN_WATER;
			if (player.isEyeInFluid(FluidTags.WATER)) flags |= Proto.MC_EYE_IN_WATER;
			mc.x = feet.x;
			mc.y = feet.y;
			mc.z = feet.z;
			mc.yaw = player.getYRot();
			mc.pitch = player.getXRot();
			Vec3 eye = camera.isDetached() ? player.getEyePosition(partial) : camera.getPosition();
			mc.eyeHeight = (float) (eye.y - feet.y);
			mc.eyeX = eye.x;
			mc.eyeY = eye.y;
			mc.eyeZ = eye.z;
			mc.fov = (float) ((GameRendererInvoker) minecraft.gameRenderer).subcraft$getFov(camera, partial, true);
			mc.cameraMode = minecraft.options.getCameraType().ordinal();
			mc.cameraDistance = camera.isDetached() ? (float) camera.getPosition().distanceTo(player.getEyePosition(partial)) : 0.0F;
			boolean bob = minecraft.options.bobView().get();
			float walkDelta = player.walkDist - player.walkDistO;
			mc.bobPhase = bob ? -(player.walkDist + walkDelta * partial) : 0.0F;
			mc.bobAmount = bob ? net.minecraft.util.Mth.lerp(partial, player.oBob, player.bob) : 0.0F;
			mc.health = player.getHealth();
			mc.maxHealth = player.getMaxHealth();
			mc.food = player.getFoodData().getFoodLevel();
			mc.saturation = player.getFoodData().getSaturationLevel();
			mc.air = player.getAirSupply();
			mc.maxAir = player.getMaxAirSupply();
		}
		if (minecraft.screen != null) {
			flags |= Proto.MC_SCREEN_OPEN;
		}
		mc.flags = flags;
		mc.sensitivity = minecraft.options.sensitivity().get().floatValue();
		mc.teleportAck = holdPos == null ? teleportAck : teleportAck - 1; // not "arrived" until released
		mc.guiScale = (int) minecraft.getWindow().getGuiScale();
		mc.frameCounter = ++frameCounter;
		view.writeMcState(mc);
		OverlayExporter.capture(minecraft, view);
		diagnostics(minecraft);
	}

	private static void diagnostics(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (!DIAGNOSTICS || now - lastDiagMs < 5000) {
			return;
		}
		lastDiagMs = now;
		Runtime rt = Runtime.getRuntime();
		SubCraft.LOG.info("SubCraft diag: frame {} fps {} pos ({}, {}, {}) flags {} heap {}/{} MB", frameCounter, minecraft.getFps(),
			String.format("%.2f", mc.x), String.format("%.2f", mc.y), String.format("%.2f", mc.z), Integer.toBinaryString(mc.flags),
			(rt.totalMemory() - rt.freeMemory()) >> 20, rt.maxMemory() >> 20);
	}

	/** End of the frame: render at most once per host frame instead of spinning freely. */
	public static void paceFrame() {
		LinkView view = SubLink.view();
		if (!linked || view == null) {
			return;
		}
		if (hostStalled && (view.hostStateSeq() >>> 1) == lastPacedSeq) {
			return; // the host is paused: don't block every frame waiting for it
		}
		hostStalled = false;
		long deadline = System.nanoTime() + 25_000_000L;
		// HostState.seq advances by 2 per host frame (odd while writing).
		while ((view.hostStateSeq() >>> 1) == lastPacedSeq && System.nanoTime() < deadline) {
			Thread.onSpinWait();
			if (deadline - System.nanoTime() > 2_000_000L) {
				Thread.yield();
			}
		}
		int seqNow = view.hostStateSeq() >>> 1;
		hostStalled = seqNow == lastPacedSeq;
		lastPacedSeq = seqNow;
	}

	private static void applyLinkedOptions(Minecraft minecraft) {
		var options = minecraft.options;
		options.pauseOnLostFocus = false;
		options.enableVsync().set(false);
		options.framerateLimit().set(260);
		// Minecraft doesn't draw the world; these only decide how far out blocks and mobs stay
		// loaded and simulated. Small for the 8 GB dev machine (MISSION.md section 2).
		options.renderDistance().set(6);
		options.simulationDistance().set(6);
		options.autoJump().set(false);
		options.onboardAccessibility = false;
		options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
		options.save();
	}

	private static void hideWindowOnce(Minecraft minecraft) {
		if (windowHidden || SHOW_WINDOW) {
			return;
		}
		windowHidden = true;
		GLFW.glfwHideWindow(minecraft.getWindow().getWindow());
		SubCraft.LOG.info("SubCraft: game window hidden (run with -Dsubcraft.showWindow=true to keep it)");
	}

	/** Sizes Minecraft's framebuffer to the host viewport (window size is in points on Retina screens). */
	private static void applyViewportSize(Minecraft minecraft) {
		int w = Math.min(host.viewportW, Proto.MAX_OVERLAY_W);
		int h = Math.min(host.viewportH, Proto.MAX_OVERLAY_H);
		if (w <= 0 || h <= 0 || (w == appliedViewportW && h == appliedViewportH)) {
			return;
		}
		appliedViewportW = w;
		appliedViewportH = h;
		float[] sx = new float[1], sy = new float[1];
		GLFW.glfwGetWindowContentScale(minecraft.getWindow().getWindow(), sx, sy);
		int ww = Math.max(1, Math.round(w / Math.max(sx[0], 1.0F)));
		int wh = Math.max(1, Math.round(h / Math.max(sy[0], 1.0F)));
		minecraft.getWindow().setWindowed(ww, wh);
		SubCraft.LOG.info("SubCraft: sizing to host viewport {}x{} (window {}x{} points, content scale {})", w, h, ww, wh, sx[0]);
	}
}
