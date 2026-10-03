package dev.subcraft.client;

import dev.subcraft.SubCraft;
import dev.subcraft.link.Platform;
import dev.subcraft.link.Proto;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = SubCraft.MOD_ID, dist = Dist.CLIENT)
public final class SubCraftClient {
	/** Bars the host's survival dials replace in the unified HUD (MISSION.md 3.4). */
	private static final Set<ResourceLocation> HOST_SHOWN = Set.of(VanillaGuiLayers.PLAYER_HEALTH,
		VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL);

	private static void hideBarsTheHostShows(RenderGuiLayerEvent.Pre e) {
		if (SubClient.linked() && (SubClient.host().flags & Proto.HOST_UNIFIED_HUD) != 0 && HOST_SHOWN.contains(e.getName())) {
			e.setCanceled(true);
		}
	}

	public SubCraftClient(IEventBus modBus) {
		// Creature proxies are hitboxes only: Subnautica draws the creature.
		modBus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers e) ->
			e.registerEntityRenderer(dev.subcraft.combat.SubEntities.CREATURE_PROXY.get(), net.minecraft.client.renderer.entity.NoopRenderer::new));
		dev.subcraft.link.SubLink.log = SubCraft.LOG::info;
		if (!Platform.announceRunning()) {
			SubCraft.LOG.warn("SubCraft: another SubCraft Minecraft already holds the running lock");
		}
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> SubClient.clientTick(Minecraft.getInstance()));
		NeoForge.EVENT_BUS.addListener((RenderFrameEvent.Post e) -> {
			SubClient.afterRender();
			SectionStreamer.frame(net.minecraft.client.Minecraft.getInstance());
		});
		NeoForge.EVENT_BUS.addListener(SubCraftClient::hideBarsTheHostShows);
		NeoForge.EVENT_BUS.addListener(SoundBridge::onPlay);
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> SoundBridge.tick());
		NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post e) -> dev.subcraft.compat.StaffGrab.tick(Minecraft.getInstance()));
		SubCraft.LOG.info("SubCraft client ready; link file {}", Platform.linkFile());
	}
}
