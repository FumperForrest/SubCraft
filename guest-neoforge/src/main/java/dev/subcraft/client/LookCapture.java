package dev.subcraft.client;

/**
 * Who uses the mouse right now. The host owns the look (its camera turns with the mouse), but the
 * raw mouse movement also goes to Minecraft (kInLook) so mods that drag with it work: Create:
 * Aeronautics' physics assembler lever and physics staff, and the like. Such mods take the movement
 * in MouseHandler.turnPlayer before LocalPlayer.turn runs. A frame with movement and no turn means
 * a mod took it: Minecraft reports kMcLookCaptured and the host's camera stands still until a
 * movement turns the player again. The turn itself is dropped: the host already turned.
 */
public final class LookCapture {
	private static boolean expecting;
	private static boolean turned;
	private static boolean captured;

	private LookCapture() {
	}

	/** MouseHandler.handleAccumulatedMovement, before it runs. */
	public static void beforeMovement(boolean hasMovement) {
		expecting = hasMovement && SubClient.tookOver();
		turned = false;
	}

	/** MouseHandler.handleAccumulatedMovement, after it ran. */
	public static void afterMovement() {
		if (expecting) {
			captured = !turned;
		}
		expecting = false;
	}

	/** Entity.turn on the local player while linked: true = drop it (the host owns the look). */
	public static boolean turn() {
		turned = true;
		return SubClient.tookOver();
	}

	public static boolean captured() {
		return captured && SubClient.linked();
	}
}
