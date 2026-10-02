package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.mixin.client.MouseHandlerInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Replays host-captured input (GLFW codes) into Minecraft's own input handlers, as if the hidden
 * window had focus. Keeps a virtual keyboard so InputConstants.isKeyDown() still answers.
 */
public final class InputBridge {
	private static final boolean[] KEYS = new boolean[GLFW.GLFW_KEY_LAST + 1];
	private static final boolean[] BUTTONS = new boolean[GLFW.GLFW_MOUSE_BUTTON_LAST + 1];
	private static int clickLogs;
	private static int keyLogs;

	private InputBridge() {
	}

	public static boolean isKeyDown(int key) {
		return key >= 0 && key < KEYS.length && KEYS[key];
	}

	public static void drain(Minecraft minecraft, LinkView view) {
		view.drainInput((type, code, a, b, c) -> dispatch(minecraft, type, code, a, b, c));
	}

	/**
	 * The host's creature (or environment) hurt the player: Minecraft owns health, so the server
	 * player takes it, from the creature's proxy when there is one (knockback, hurt direction).
	 */
	private static void hurt(Minecraft minecraft, int kind, float amount, int attacker) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null || amount <= 0) {
			return;
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			var sp = server.getPlayerList().getPlayer(uuid);
			if (sp == null) {
				return;
			}
			var sources = sp.level().damageSources();
			var proxy = attacker != 0 ? dev.subcraft.combat.Proxies.proxy(attacker) : null;
			var source = proxy != null ? sources.mobAttack(proxy) : sources.generic();
			sp.hurt(source, amount);
		});
	}

	private static void dispatch(Minecraft minecraft, int type, int code, int a, int b, int c) {
		long handle = minecraft.getWindow().getWindow();
		MouseHandlerInvoker mouse = (MouseHandlerInvoker) minecraft.mouseHandler;
		switch (type) {
			case Proto.IN_KEY -> {
				if (code < 0 || code >= KEYS.length) {
					return;
				}
				KEYS[code] = a != 0;
				if (SubClient.DIAGNOSTICS && keyLogs++ < 40) {
					SubCraft.LOG.info("SubCraft: key {} action {} mods {} (screen {})", code, a, b, minecraft.screen);
				}
				minecraft.keyboardHandler.keyPress(handle, code, c, a, b);
			}
			case Proto.IN_MOUSE_BUTTON -> {
				if (code < 0 || code >= BUTTONS.length) {
					return;
				}
				BUTTONS[code] = a != 0;
				if (a != 0 && clickLogs++ < 20) {
					SubCraft.LOG.info("SubCraft: click {} -> {}", code, minecraft.hitResult == null ? "nothing" : minecraft.hitResult.getType());
				}
				mouse.subcraft$onPress(handle, code, a != 0 ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, b);
			}
			case Proto.IN_SCROLL -> mouse.subcraft$onScroll(handle, 0.0, a / 120.0);
			case Proto.IN_CURSOR -> {
				// Overlay pixels -> window points (GLFW cursor coordinates; Retina: half).
				var window = minecraft.getWindow();
				double scale = window.getWidth() > 0 ? (double) window.getScreenWidth() / window.getWidth() : 1.0;
				mouse.subcraft$onMove(handle, a * scale, b * scale);
			}
			case Proto.IN_TEXT -> {
				if (minecraft.screen != null && Character.isValidCodePoint(a)) {
					// KeyboardHandler.charTyped is private; screens take characters directly.
					for (char ch : Character.toChars(a)) {
						minecraft.screen.charTyped(ch, 0);
					}
				}
			}
			case Proto.IN_RELEASE_ALL -> releaseAll(minecraft);
			case Proto.IN_HURT -> hurt(minecraft, code, a / 100.0F, b);
			case Proto.IN_HURT_MOB -> dev.subcraft.combat.MobTable.hurt(minecraft.getSingleplayerServer(), b, a / 100.0F, c);
			case Proto.IN_OPEN_MENU -> {
				if (minecraft.screen == null && minecraft.player != null) {
					releaseAll(minecraft);
					minecraft.setScreen(new PauseScreen(true));
				}
			}
			default -> {
			}
		}
	}

	/** Lifts every key and button we think is held (focus moved to the host, link dropped, ...). */
	public static void releaseAll(Minecraft minecraft) {
		long handle = minecraft.getWindow().getWindow();
		for (int key = 0; key < KEYS.length; key++) {
			if (KEYS[key]) {
				KEYS[key] = false;
				minecraft.keyboardHandler.keyPress(handle, key, 0, GLFW.GLFW_RELEASE, 0);
			}
		}
		MouseHandlerInvoker mouse = (MouseHandlerInvoker) minecraft.mouseHandler;
		for (int button = 0; button < BUTTONS.length; button++) {
			if (BUTTONS[button]) {
				BUTTONS[button] = false;
				mouse.subcraft$onPress(handle, button, GLFW.GLFW_RELEASE, 0);
			}
		}
	}
}
