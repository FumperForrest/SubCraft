package dev.subcraft.link;

import static dev.subcraft.link.Proto.*;

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

	/**
	 * Where the link logs. The link package stays free of Minecraft and the mod's entry point (so
	 * it runs in plain unit tests); the client points this at the mod's logger at start-up.
	 */
	public static volatile java.util.function.Consumer<String> log = message -> System.out.println("[SubCraft link] " + message);

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
				// The host restarted and reset the shared state; start our side over too. It may still
				// be initialising (pid or magic not written yet: look again next frame), or be a build
				// with another protocol: then let go and map afresh.
				if (pid == 0 || v.magic() == 0) {
					return;
				}
				String mismatch = v.protocolProblem();
				if (mismatch != null) {
					problem(mismatch + " after a host restart; remapping");
					close();
					return;
				}
				hostPid = pid;
				v.resetOverlayWriter();
				v.setMcPid(Platform.pid());
				generation++;
				log.accept("SubCraft: host instance changed (pid " + pid + ")");
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
			String mismatch = opened.protocolProblem();
			if (mismatch != null) {
				problem(mismatch + " in " + file);
				return;
			}
			opened.setMcPid(Platform.pid());
			opened.mcHeartbeat(now);
			hostPid = opened.hostPid();
			generation++;
			view = opened;
			lastProblem = null;
			log.accept("SubCraft: mapped " + file + " (host pid " + hostPid + ")");
		} catch (IOException | RuntimeException e) {
			problem("failed to map " + file + ": " + e);
		}
	}

	/** Drops the mapping, e.g. after the host has been gone a while (it may recreate the file). */
	public static void close() {
		if (view != null) {
			log.accept("SubCraft: unmapping the link");
		}
		view = null;
		hostPid = 0;
	}

	private static void problem(String message) {
		if (!message.equals(lastProblem)) {
			lastProblem = message;
			log.accept("SubCraft: " + message);
		}
	}
}
