package dev.subcraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TimeSyncTest {
	@Test
	void hostFractionsMapToMinecraftTimes() {
		assertEquals(0, TimeSync.timeOfDay(0.25F));     // sunrise
		assertEquals(6000, TimeSync.timeOfDay(0.5F));   // noon
		assertEquals(12000, TimeSync.timeOfDay(0.75F)); // sunset
		assertEquals(18000, TimeSync.timeOfDay(0.0F));  // midnight
		assertEquals(18000, TimeSync.timeOfDay(1.0F));
	}

	@Test
	void followsForwardAndKeepsTheDayCount() {
		long day5Noon = 5 * TimeSync.DAY + 6000;
		assertEquals(day5Noon, TimeSync.follow(day5Noon, 0.5F), "already there");
		assertEquals(day5Noon + 6000, TimeSync.follow(day5Noon, 0.75F), "on to sunset, same day");
		// Across midnight the counter goes on into day 6, never back to day 5's morning.
		long day5Late = 5 * TimeSync.DAY + 23990;
		assertEquals(6 * TimeSync.DAY + 10, TimeSync.follow(day5Late, (10 + 6000) / 24000F + 0.0F));
	}

	@Test
	void jitterBackwardsStaysSmall() {
		long t = 3 * TimeSync.DAY + 6000;
		assertEquals(t - 5, TimeSync.follow(t, (6000 - 5 + 6000) / 24000F));
		// Half a day apart: forward, not back (ties go forward).
		assertEquals(t + 12000, TimeSync.follow(t, 0.0F));
	}
}
