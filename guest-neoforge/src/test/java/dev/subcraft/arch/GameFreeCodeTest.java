package dev.subcraft.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps the game-free code game-free (docs/ARCH-REVIEW.md section 4.2): the files listed here
 * may not mention Minecraft, NeoForge, Mojang, LWJGL, or any SubCraft class outside this list.
 * They are the ones that run in plain unit tests and in any future port; anything that needs the
 * game goes behind an interface in the edge code. To move a class into the core, make it pass
 * and add it here.
 */
class GameFreeCodeTest {
	private static final Path SRC = Path.of("src/main/java");

	/** Packages (every file in them) and single files that must stay game-free. */
	private static final List<String> GAME_FREE = List.of(
		"dev/subcraft/link/",
		"dev/subcraft/world/tri/TriCollider.java",
		"dev/subcraft/world/tri/TriGeometry.java",
		"dev/subcraft/world/tri/TriStore.java",
		"dev/subcraft/world/ghost/Voxelizer.java",
		"dev/subcraft/world/SubBiomes.java",
		"dev/subcraft/core/");

	private static final Pattern GAME = Pattern.compile("\\b(net\\.minecraft|com\\.mojang|net\\.neoforged|org\\.lwjgl|org\\.spongepowered)\\b");
	private static final Pattern OURS = Pattern.compile("\\bdev\\.subcraft\\.([\\w.]+)");

	private static boolean gameFree(String rel) {
		return GAME_FREE.stream().anyMatch(p -> p.endsWith("/") ? rel.startsWith(p) : rel.equals(p));
	}

	@Test
	void gameFreeFilesDontReachTheGame() throws IOException {
		List<String> problems = new ArrayList<>();
		try (Stream<Path> files = Files.walk(SRC)) {
			for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
				String rel = SRC.relativize(f).toString().replace('\\', '/');
				if (!gameFree(rel)) {
					continue;
				}
				String text = Files.readString(f);
				Matcher g = GAME.matcher(text);
				if (g.find()) {
					problems.add(rel + " mentions " + g.group(1));
				}
				Matcher m = OURS.matcher(text);
				while (m.find()) {
					String ref = resolve(m.group(1));
					if (ref != null && !gameFree(ref)) {
						problems.add(rel + " uses " + ref);
					}
				}
			}
		}
		assertEquals(List.of(), problems);
	}

	/** dev.subcraft.world.tri.TriStore[.inner] -> the source file it lives in, or null for a package import. */
	private static String resolve(String dotted) {
		String[] parts = dotted.split("\\.");
		StringBuilder path = new StringBuilder("dev/subcraft/");
		for (String part : parts) {
			if (part.equals("*")) {
				return null;
			}
			path.append(part);
			if (Files.exists(SRC.resolve(path + ".java"))) {
				return path + ".java";
			}
			path.append('/');
		}
		return null;
	}
}
