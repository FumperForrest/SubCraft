package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.link.Platform;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = SubCraft.MOD_ID, dist = Dist.CLIENT)
public final class SubCraftClient {
	public SubCraftClient(IEventBus modBus) {
		if (!Platform.announceRunning()) {
			SubCraft.LOG.warn("SubCraft: another SubCraft Minecraft already holds the running lock");
		}
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> SubClient.clientTick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> {
			SubClient.afterRender();
			SectionStreamer.frame(net.minecraft.client.Minecraft.getInstance());
		});
		SubCraft.LOG.info("SubCraft client ready; link file {}", Platform.linkFile());
	}
}
