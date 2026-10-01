package dev.subcraft.link;

import static dev.subcraft.link.Proto.*;

import dev.subcraft.SubCraft;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The Minecraft end of the link. The host creates and sizes the shared file; we map it when it
 * appears and treat the host as gone when its heartbeat is older than {@link Proto#HEARTBEAT_TIMEOUT_NS}.
 */
public final class SubLink {
	private static volatile LinkView view;
	private static long lastOpenAttemptNs;
	private static String lastProblem;
	private static int hostPid;
	private static volatile int generation;

	private SubLink() {
	}

	/** The mapping if open (whether or not the host is alive), else null. */
	public static LinkView view() {
		return view;
	}

	/** True when a live host is on the other end. Cheap; any thread. */
	public static boolean active() {
		LinkView v = view;
		return v != null && Platform.monoNanos() - v.hostHeartbeat() < HEARTBEAT_TIMEOUT_NS;
	}

	/** Bumps whenever a (new) host instance is on the other end: everything it caches must be resent. */
	public static int generation() {
		return generation;
	}

	public static int hostPid() {
		return hostPid;
	}

	/** Opens the mapping when it appears (at most once a second) and beats our heartbeat. Render thread. */
	public static void poll() {
		LinkView v = view;
		long now = Platform.monoNanos();
		if (v != null) {
			v.mcHeartbeat(now);
			int pid = v.hostPid();
			if (pid != hostPid) {
				// The host restarted and reset the shared state; start our side over too.
				hostPid = pid;
				v.resetOverlayWriter();
				v.setMcPid(Platform.pid());
				generation++;
				SubCraft.LOG.info("SubCraft: host instance changed (pid {})", pid);
			}
			return;
		}
		if (now - lastOpenAttemptNs < 1_000_000_000L) {
			return;
		}
		lastOpenAttemptNs = now;
		Path file = Platform.linkFile();
		try {
			if (!Files.exists(file)) {
				problem("waiting for the host to create " + file);
				return;
			}
			long size = Files.size(file);
			if (size < MAPPING_BYTES) {
				problem("link file " + file + " is " + size + " bytes, expected " + MAPPING_BYTES + " (host still starting, or an old protocol)");
				return;
			}
			MappedByteBuffer mapped;
			try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
				mapped = ch.map(FileChannel.MapMode.READ_WRITE, 0, MAPPING_BYTES);
			}
			LinkView opened = new LinkView(mapped);
			if (opened.magic() != MAGIC || opened.version() != VERSION) {
				problem(String.format("protocol mismatch in %s: magic %08x version %d, expected %08x version %d", file, opened.magic(), opened.version(), MAGIC, VERSION));
				return;
			}
			opened.setMcPid(Platform.pid());
			opened.mcHeartbeat(now);
			hostPid = opened.hostPid();
			generation++;
			view = opened;
			lastProblem = null;
			SubCraft.LOG.info("SubCraft: mapped {} (host pid {})", file, hostPid);
		} catch (IOException | RuntimeException e) {
			problem("failed to map " + file + ": " + e);
		}
	}

	/** Drops the mapping, e.g. after the host has been gone a while (it may recreate the file). */
	public static void close() {
		if (view != null) {
			SubCraft.LOG.info("SubCraft: unmapping the link");
		}
		view = null;
		hostPid = 0;
	}

	private static void problem(String message) {
		if (!message.equals(lastProblem)) {
			lastProblem = message;
			SubCraft.LOG.info("SubCraft: {}", message);
		}
	}
}
