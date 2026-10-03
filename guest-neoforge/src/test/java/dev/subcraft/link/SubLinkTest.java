package dev.subcraft.link;

import static dev.subcraft.link.Proto.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.RandomAccessFile;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** SubLink.poll over a real (sparse) link file, with this test playing the host. */
class SubLinkTest {
	private Path dir;

	@AfterEach
	void tearDown() throws Exception {
		SubLink.close();
		System.clearProperty("subcraft.link");
		if (this.dir != null) {
			Files.deleteIfExists(this.dir.resolve("link.bin"));
			Files.deleteIfExists(this.dir);
		}
	}

	/** The host's InitAsHost: control blocks zeroed, version, pid, then the magic last. */
	private static void initAsHost(LinkView host, int pid, int version) {
		for (int i = 0; i < 0x1000; i += 4) {
			host.putInt(i, 0);
		}
		host.putInt(OFF_HEADER + H_VERSION, version);
		host.setIntRelease(OFF_HEADER + H_HOST_PID, pid);
		host.setIntRelease(OFF_HEADER + H_MAGIC, MAGIC);
	}

	private LinkView hostView() throws Exception {
		this.dir = Files.createTempDirectory("subcraft-link-test");
		Path file = this.dir.resolve("link.bin");
		try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
			f.setLength(MAPPING_BYTES); // sparse, like the host's
		}
		System.setProperty("subcraft.link", file.toString());
		try (FileChannel ch = FileChannel.open(file, java.nio.file.StandardOpenOption.READ, java.nio.file.StandardOpenOption.WRITE)) {
			MappedByteBuffer m = ch.map(FileChannel.MapMode.READ_WRITE, 0, MAPPING_BYTES);
			m.order(ByteOrder.LITTLE_ENDIAN);
			return new LinkView(m);
		}
	}

	@Test
	void hostRestartWithAnotherProtocolDropsTheMapping() throws Exception {
		LinkView host = hostView();
		initAsHost(host, 100, VERSION);
		SubLink.poll();
		assertNotNull(SubLink.view(), "mapped");
		assertEquals(100, SubLink.hostPid());
		int generation = SubLink.generation();

		// Same protocol, new instance: kept, generation bumps, our pid re-announced.
		initAsHost(host, 101, VERSION);
		SubLink.poll();
		assertNotNull(SubLink.view());
		assertEquals(101, SubLink.hostPid());
		assertEquals(generation + 1, SubLink.generation());
		assertEquals(Platform.pid(), host.getInt(OFF_HEADER + H_MC_PID));

		// Mid-initialisation (magic not written yet): wait, don't decide.
		for (int i = 0; i < 0x1000; i += 4) {
			host.putInt(i, 0);
		}
		host.setIntRelease(OFF_HEADER + H_HOST_PID, 102);
		SubLink.poll();
		assertNotNull(SubLink.view());
		assertEquals(101, SubLink.hostPid(), "not adopted before the magic is there");

		// A host built with another protocol rewrote the file: let go instead of misreading it.
		initAsHost(host, 102, VERSION + 1);
		SubLink.poll();
		assertNull(SubLink.view(), "a different protocol must not stay mapped");
	}

	@Test
	void protocolProblemNamesTheMismatch() throws Exception {
		LinkView host = hostView();
		initAsHost(host, 7, VERSION);
		assertNull(host.protocolProblem());
		host.putInt(OFF_HEADER + H_VERSION, VERSION - 1);
		assertTrue(host.protocolProblem().contains("version " + (VERSION - 1)));
	}
}
