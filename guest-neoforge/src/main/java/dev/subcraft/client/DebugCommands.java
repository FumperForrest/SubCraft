package dev.subcraft.client;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.subcraft.SubCraft;
import dev.subcraft.capture.SceneDump;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Platform;
import dev.subcraft.link.Proto;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Runs the host's debug commands from the command box (MISSION.md rule 12): "/..." as a Minecraft
 * command (operator, at the player), "subcraft ..." as SubCraft's own. One at a time; Minecraft
 * commands finish on the server thread, so the reply comes a tick later.
 */
public final class DebugCommands {
	private static int running = -1;
	private static int runningGeneration = -1;

	private DebugCommands() {
	}

	public static void poll(Minecraft minecraft, LinkView view) {
		LinkView.Command cmd = view.pendingCommand();
		// A new host resets the box (seq starts over), so "already running" is per host instance.
		if (cmd == null || (cmd.seq() == running && dev.subcraft.link.SubLink.generation() == runningGeneration)) {
			return;
		}
		running = cmd.seq();
		runningGeneration = dev.subcraft.link.SubLink.generation();
		String text = cmd.text().trim();
		SubCraft.LOG.info("SubCraft: host command #{}: {}", cmd.seq(), text);
		try {
			if (text.startsWith("/")) {
				minecraftCommand(minecraft, view, cmd.seq(), text.substring(1));
			} else if (text.startsWith("subcraft ")) {
				view.completeCommand(cmd.seq(), Proto.CMD_OK, subcraft(minecraft, text.substring(9).trim().split("\\s+")));
			} else {
				view.completeCommand(cmd.seq(), Proto.CMD_UNKNOWN, "commands start with / or 'subcraft '");
			}
		} catch (Exception e) {
			SubCraft.LOG.warn("SubCraft: host command failed", e);
			view.completeCommand(cmd.seq(), Proto.CMD_FAILED, e.toString());
		}
	}

	/**
	 * "subcraft field <class> <a.b.c()>": a static field of any loaded class (any mod's), then
	 * fields or no-argument methods along the path, by reflection. For compat debugging.
	 */
	private static String field(String[] args) throws Exception {
		Class<?> cls = Class.forName(args[1], false, Thread.currentThread().getContextClassLoader());
		Object at = null;
		Class<?> type = cls;
		for (String step : args[2].split("\\.")) {
			if (step.endsWith("()")) {
				var m = findMethod(type, step.substring(0, step.length() - 2));
				m.setAccessible(true);
				at = m.invoke(at);
			} else {
				var f = findField(type, step);
				f.setAccessible(true);
				at = f.get(at);
			}
			if (at == null) {
				return step + " is null";
			}
			type = at.getClass();
		}
		String text = at instanceof java.util.Collection<?> c ? c.getClass().getSimpleName() + " size " + c.size()
			: at instanceof java.util.Map<?, ?> m ? m.getClass().getSimpleName() + " size " + m.size() : String.valueOf(at);
		return text.length() > 900 ? text.substring(0, 900) + "..." : text;
	}

	private static java.lang.reflect.Field findField(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
			}
		}
		throw new NoSuchFieldException(name + " in " + type.getName());
	}

	private static java.lang.reflect.Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			for (var m : c.getDeclaredMethods()) {
				if (m.getName().equals(name) && m.getParameterCount() == 0) {
					return m;
				}
			}
		}
		throw new NoSuchMethodException(name + "() in " + type.getName());
	}

	private static String subcraft(Minecraft minecraft, String[] args) throws Exception {
		switch (args[0]) {
			case "dump" -> {
				// subcraft dump <file> [radius] [x y z]
				Path file = args.length > 1 ? Path.of(args[1]) : Platform.sharedDir().resolve("out").resolve("scene.scdump");
				int radius = args.length > 2 ? Integer.parseInt(args[2]) : 8;
				BlockPos center = args.length > 5
					? new BlockPos(Integer.parseInt(args[3]), Integer.parseInt(args[4]), Integer.parseInt(args[5]))
					: minecraft.player.blockPosition();
				return SceneDump.run(minecraft, file, center, radius);
			}
			case "tris" -> {
				return dev.subcraft.world.tri.TriDebug.report(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
			}
			case "sections" -> {
				return SectionStreamer.stats() + "\n" + dev.subcraft.capture.DynamicCapture.stats() + "\n" + dev.subcraft.capture.VboCapture.stats() + "\n" + dev.subcraft.compat.SubLevels.stats();
			}
			case "creatures" -> {
				return dev.subcraft.combat.Proxies.stats() + ", " + dev.subcraft.combat.MobTable.stats() + ", " + dev.subcraft.compat.StaffGrab.stats();
			}
			case "ghostaudit" -> {
				return dev.subcraft.world.ghost.GhostAudit.run(minecraft.level, minecraft.player.blockPosition(), args.length > 1 ? Integer.parseInt(args[1]) : 24);
			}
			case "revox" -> {
				var server = minecraft.getSingleplayerServer();
				return server.submit(() -> dev.subcraft.world.ghost.GhostAudit.revox(server.overworld(), Integer.parseInt(args[1]), Integer.parseInt(args[2]),
					Integer.parseInt(args[3]))).join();
			}
			case "whysolid" -> {
				return dev.subcraft.world.ghost.GhostAudit.whySolid(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
			}
			case "mtskey" -> {
				// What Immersive Vehicles' input layer answers for a key held on the virtual keyboard.
				int key = Integer.parseInt(args[1]);
				Object input = Class.forName("minecrafttransportsimulator.mcinterface.InterfaceManager").getField("inputInterface").get(null);
				var m = input.getClass().getMethod("isKeyPressed", int.class);
				boolean before = (boolean) m.invoke(input, key);
				InputBridge.setKey(key, true);
				boolean held = (boolean) m.invoke(input, key);
				InputBridge.setKey(key, false);
				return "isKeyPressed(" + key + "): released " + before + ", held " + held + " (" + input.getClass().getName() + ")";
			}
			case "column" -> {
				return dev.subcraft.world.ghost.GhostAudit.column(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
			}
			case "rawdebug" -> {
				dev.subcraft.capture.VboCapture.debugDraws = args.length > 1 ? Integer.parseInt(args[1]) : 4;
				dev.subcraft.capture.VboCapture.debugPass = args.length > 2 ? args[2] : "world";
				return "logging the next raw draws";
			}
			case "save" -> {
				// The whole world, now (tools/mc_dev stop calls this before it kills the JVM).
				var server = minecraft.getSingleplayerServer();
				if (server == null) {
					return "no integrated server";
				}
				server.submit(() -> server.saveEverything(false, true, true)).join();
				return "saved";
			}
			case "field" -> {
				return field(args);
			}
			case "sounds" -> {
				return SoundBridge.stats();
			}
			case "pos" -> {
				return minecraft.player == null ? "no player" : minecraft.player.position().toString();
			}
			default -> throw new IllegalArgumentException("unknown subcraft command " + args[0]);
		}
	}

	private static void minecraftCommand(Minecraft minecraft, LinkView view, int seq, String command) {
		var server = minecraft.getSingleplayerServer();
		if (server == null || minecraft.player == null) {
			view.completeCommand(seq, Proto.CMD_FAILED, "no integrated server or player");
			return;
		}
		var uuid = minecraft.player.getUUID();
		server.execute(() -> {
			List<String> output = new ArrayList<>();
			CommandSource sink = new CommandSource() {
				@Override
				public void sendSystemMessage(Component message) {
					output.add(message.getString());
				}

				@Override
				public boolean acceptsSuccess() {
					return true;
				}

				@Override
				public boolean acceptsFailure() {
					return true;
				}

				@Override
				public boolean shouldInformAdmins() {
					return false;
				}
			};
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			CommandSourceStack stack = (player != null ? player.createCommandSourceStack() : server.createCommandSourceStack())
				.withSource(sink).withPermission(4);
			int status = Proto.CMD_OK;
			try {
				var parsed = server.getCommands().getDispatcher().parse(command, stack);
				int result = server.getCommands().getDispatcher().execute(parsed);
				output.add(0, "result " + result);
			} catch (CommandSyntaxException e) {
				status = Proto.CMD_FAILED;
				output.add(e.getMessage());
			} catch (RuntimeException e) {
				status = Proto.CMD_FAILED;
				output.add(e.toString());
			}
			view.completeCommand(seq, status, String.join("\n", output));
		});
	}
}
