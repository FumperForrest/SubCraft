package dev.subcraft.core;

/**
 * Subnautica's time of day as Minecraft's day time (MISSION.md 3.4: the host's day/night drives
 * Minecraft's). The host sends 0 = midnight, 0.25 = sunrise, 0.5 = noon, 0.75 = sunset; Minecraft's
 * day starts at sunrise (tick 0), noon 6000, sunset 12000, midnight 18000.
 *
 * <p>Game-free (dev.subcraft.core, see GameFreeCodeTest): the server tick applies the result.
 */
public final class TimeSync {
	public static final long DAY = 24000L;

	private TimeSync() {
	}

	/** Minecraft's time of day (0..23999) for the host's day fraction. */
	public static long timeOfDay(float dayFraction) {
		return Math.floorMod(Math.round((dayFraction - 0.25) * DAY), DAY);
	}

	/**
	 * The day time to set so the time of day matches the host's: the day counter only moves
	 * forward across midnight, and a small step back (host jitter) stays a small step back rather
	 * than a whole day forward. Returns {@code dayTime} itself when nothing needs to change.
	 */
	public static long follow(long dayTime, float dayFraction) {
		long diff = Math.floorMod(timeOfDay(dayFraction) - Math.floorMod(dayTime, DAY), DAY);
		if (diff > DAY / 2) {
			diff -= DAY;
		}
		return dayTime + diff;
	}
}
