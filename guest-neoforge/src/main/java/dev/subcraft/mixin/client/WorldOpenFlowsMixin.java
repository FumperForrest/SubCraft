package dev.subcraft.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.subcraft.world.SubWorld;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The SubCraft dimension type makes Minecraft call the world "experimental" and ask for a backup
 * when it opens; nobody can click that in a hidden window. Skip it for the SubCraft world only.
 */
@Mixin(WorldOpenFlows.class)
public abstract class WorldOpenFlowsMixin {
	@WrapOperation(
		method = "openWorldCheckWorldStemCompatibility",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/worldselection/WorldOpenFlows;askForBackup(Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;ZLjava/lang/Runnable;Ljava/lang/Runnable;)V"
		)
	)
	private void subcraft$skipBackupPrompt(
		WorldOpenFlows self, LevelStorageSource.LevelStorageAccess access, boolean oldCustomized, Runnable proceed, Runnable cancel, Operation<Void> original
	) {
		if (SubWorld.DEV_WORLD_NAME.equals(access.getLevelId())) {
			proceed.run();
		} else {
			original.call(self, access, oldCustomized, proceed, cancel);
		}
	}
}
