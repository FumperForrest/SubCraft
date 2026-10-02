package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Minecraft's half of the sound mix (MISSION.md 3.4): while linked, Minecraft plays nothing itself.
 * Its gameplay sounds (players, blocks, mobs, items, UI) go to the host, which plays them through
 * Subnautica's audio (3D, underwater muffling); Minecraft's music, ambience and weather are dropped
 * because Subnautica's own ambience and music stay. Each sound file is shipped once (kRenSound);
 * plays, updates and stops are events. Looping and moving sounds are ticked here, since the sound
 * engine no longer holds them.
 */
public final class SoundBridge {
	private static final Map<ResourceLocation, Integer> fileIds = new HashMap<>();
	private static final Set<ResourceLocation> unreadable = new HashSet<>();
	private static final Map<SoundInstance, Integer> live = new IdentityHashMap<>();
	private static int nextFileId = 1;
	private static int nextInstance = 1;
	private static int seenGeneration = -1;
	private static long played, dropped;

	private SoundBridge() {
	}

	/** Sounds the mix leaves to Subnautica. */
	private static boolean subnauticas(SoundSource source) {
		return source == SoundSource.MUSIC || source == SoundSource.AMBIENT || source == SoundSource.WEATHER || source == SoundSource.RECORDS;
	}

	public static void onPlay(PlaySoundEvent e) {
		SoundInstance s = e.getSound();
		LinkView view = SubLink.view();
		if (s == null || view == null || !SubClient.linked()) {
			return;
		}
		e.setSound(null); // Minecraft stays silent while linked
		resetIfNewHost();
		if (subnauticas(s.getSource()) || !s.canPlaySound()) {
			return;
		}
		SoundManager manager = Minecraft.getInstance().getSoundManager();
		if (s.resolve(manager) == null) {
			return;
		}
		Sound sound = s.getSound();
		if (sound == null || sound == SoundManager.EMPTY_SOUND || sound == SoundManager.INTENTIONALLY_EMPTY_SOUND || sound.shouldStream()) {
			return;
		}
		int file = fileId(view, sound.getPath());
		if (file == 0) {
			dropped++;
			return;
		}
		int instance = nextInstance++;
		int flags = file;
		if (s.isRelative() || s.getAttenuation() == SoundInstance.Attenuation.NONE) {
			flags |= Proto.SOUND_RELATIVE;
		}
		if (s.isLooping()) {
			flags |= Proto.SOUND_LOOP;
		}
		float range = Math.max(s.getVolume(), 1.0F) * sound.getAttenuationDistance();
		if (!push(view, Proto.EV_SOUND_PLAY, instance, s, flags, range)) {
			dropped++;
			return;
		}
		played++;
		if (s.isLooping() || s instanceof TickableSoundInstance) {
			live.put(s, instance);
		}
	}

	/** Every client tick: move and stop the sounds the bridge keeps alive. */
	public static void tick() {
		LinkView view = SubLink.view();
		if (live.isEmpty() || view == null) {
			return;
		}
		if (!SubClient.linked()) {
			live.clear();
			return;
		}
		var it = live.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			SoundInstance s = entry.getKey();
			if (s instanceof TickableSoundInstance t) {
				t.tick();
				if (t.isStopped()) {
					view.pushEvent(Proto.EV_SOUND_STOP, entry.getValue(), 0, 0, 0, 0, 0, 0);
					it.remove();
					continue;
				}
			}
			Sound sound = s.getSound();
			float range = Math.max(s.getVolume(), 1.0F) * (sound != null ? sound.getAttenuationDistance() : 16);
			push(view, Proto.EV_SOUND_UPDATE, entry.getValue(), s, 0, range);
		}
	}

	/** SoundEngine.stop(instance) (mixin). */
	public static void stopped(SoundInstance s) {
		Integer instance = live.remove(s);
		LinkView view = SubLink.view();
		if (instance != null && view != null) {
			view.pushEvent(Proto.EV_SOUND_STOP, instance, 0, 0, 0, 0, 0, 0);
		}
	}

	/** SoundEngine.stopAll() (mixin). */
	public static void stoppedAll() {
		live.clear();
		LinkView view = SubLink.view();
		if (view != null && SubClient.linked()) {
			view.pushEvent(Proto.EV_SOUND_STOP, 0, 0, 0, 0, 0, 0, 0);
		}
	}

	/** SoundEngine.isActive (mixin): a sound the host is playing for us counts as playing. */
	public static boolean isLive(SoundInstance s) {
		return live.containsKey(s);
	}

	public static String stats() {
		return "sounds played " + played + ", dropped " + dropped + ", files " + fileIds.size() + ", live " + live.size();
	}

	private static boolean push(LinkView view, int type, int instance, SoundInstance s, int flags, float range) {
		float volume = Mth.clamp(s.getVolume() * categoryVolume(s.getSource()), 0.0F, 1.0F);
		float pitch = Mth.clamp(s.getPitch(), 0.5F, 2.0F);
		int extra = Math.min(65535, Math.round(range * 16)) << 16 | Math.round(pitch * 10000) & 0xFFFF;
		return view.pushEvent(type, instance, (float) s.getX(), (float) s.getY(), (float) s.getZ(), volume, flags, extra);
	}

	/** SoundEngine.getVolume: the category's slider; master is the host's own volume. */
	private static float categoryVolume(SoundSource source) {
		return source == SoundSource.MASTER ? 1.0F : Minecraft.getInstance().options.getSoundSourceVolume(source);
	}

	/** The file's id, shipping the file first if the host hasn't got it; 0 if it can't be sent now. */
	private static int fileId(LinkView view, ResourceLocation path) {
		Integer id = fileIds.get(path);
		if (id != null) {
			return id;
		}
		if (unreadable.contains(path)) {
			return 0;
		}
		byte[] bytes;
		var resource = Minecraft.getInstance().getResourceManager().getResource(path);
		if (resource.isEmpty()) {
			unreadable.add(path);
			return 0;
		}
		try (InputStream in = resource.get().open()) {
			bytes = in.readAllBytes();
		} catch (IOException ex) {
			unreadable.add(path);
			SubCraft.LOG.warn("SubCraft: can't read sound {}: {}", path, ex.toString());
			return 0;
		}
		int newId = nextFileId;
		ByteBuffer head = ByteBuffer.allocate(Proto.REN_SOUND_BYTES).order(ByteOrder.LITTLE_ENDIAN);
		head.putInt(newId).putInt(bytes.length).putInt(0).putInt(0).flip();
		if (!view.tryWriteRender(Proto.REN_SOUND, head, ByteBuffer.wrap(bytes))) {
			return 0; // the host is behind; try again next time this sound plays
		}
		nextFileId++;
		fileIds.put(path, newId);
		return newId;
	}

	/** A new host instance has none of the files: start over. */
	private static void resetIfNewHost() {
		if (SubLink.generation() != seenGeneration) {
			seenGeneration = SubLink.generation();
			reset();
		}
	}

	/** The host drops every file when the link goes down or comes up (SubClient.onLinkChanged). */
	public static void reset() {
		fileIds.clear();
		unreadable.clear();
		live.clear();
		nextFileId = 1;
	}
}
