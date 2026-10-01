package dev.subcraft.link;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * Everything that differs between macOS and Windows on the Minecraft side of the link: where the
 * shared file lives, the cross-process clock, and the "Minecraft is running" lock.
 */
public final class Platform {
	public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
	public static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");

	private static FileChannel lockChannel;
	private static FileLock lock;

	private Platform() {
	}

	/**
	 * The directory both games share: macOS {@code $TMPDIR/subcraft}, Windows
	 * {@code %LOCALAPPDATA%\SubCraft}. {@code -Dsubcraft.dir} or {@code SUBCRAFT_DIR} override it.
	 */
	public static Path sharedDir() {
		String override = System.getProperty("subcraft.dir", System.getenv("SUBCRAFT_DIR"));
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		if (WINDOWS) {
			String local = System.getenv("LOCALAPPDATA");
			return Path.of(local != null ? local : System.getProperty("user.home"), "SubCraft");
		}
		String tmp = System.getenv("TMPDIR");
		return Path.of(tmp != null && !tmp.isBlank() ? tmp : System.getProperty("java.io.tmpdir"), "subcraft");
	}

	/** The link file. {@code -Dsubcraft.link} or {@code SUBCRAFT_LINK} override it. */
	public static Path linkFile() {
		String override = System.getProperty("subcraft.link", System.getenv("SUBCRAFT_LINK"));
		if (override != null && !override.isBlank()) {
			return Path.of(override);
		}
		return sharedDir().resolve(Proto.LINK_FILE_NAME);
	}

	/**
	 * Monotonic nanoseconds, comparable across processes. HotSpot's nanoTime is
	 * mach_absolute_time scaled to ns on macOS (CLOCK_UPTIME_RAW) and QueryPerformanceCounter scaled
	 * to ns on Windows: the same clocks the host reads.
	 */
	public static long monoNanos() {
		return System.nanoTime();
	}

	public static int pid() {
		return (int) ProcessHandle.current().pid();
	}

	public static boolean processAlive(int pid) {
		return pid != 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
	}

	/**
	 * Takes {@code minecraft.lock} in the shared directory for as long as this JVM lives, so the
	 * host can tell a SubCraft Minecraft is already running. Returns false if another one holds it.
	 */
	public static synchronized boolean announceRunning() {
		if (lock != null) {
			return true;
		}
		try {
			Path dir = sharedDir();
			Files.createDirectories(dir);
			lockChannel = FileChannel.open(dir.resolve("minecraft.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
			lock = lockChannel.tryLock();
			if (lock == null) {
				lockChannel.close();
				lockChannel = null;
				return false;
			}
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
