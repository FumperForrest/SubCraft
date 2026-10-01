package dev.subcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.subcraft.world.SubWorld;
import dev.subcraft.world.tri.TriCollider;
import dev.subcraft.world.tri.TriStore;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Players in the SubCraft world collide with the host's exact triangles (TriCollider) after
 * vanilla has collided them with real blocks. Runs on the client and the integrated server alike,
 * so the server's movement check agrees with the client.
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
	@Unique
	private Vec3 subcraft$velocityBefore;
	@Unique
	private boolean subcraft$triContact;
	@Unique
	private double subcraft$nx, subcraft$ny, subcraft$nz;

	@ModifyReturnValue(method = "collide", at = @At("RETURN"))
	private Vec3 subcraft$collideWithHostTriangles(Vec3 allowed) {
		Entity self = (Entity) (Object) this;
		this.subcraft$triContact = false;
		if (!(self instanceof Player) || self.noPhysics || self.isSpectator() || TriStore.isEmpty() || !SubWorld.is(self.level())) {
			return allowed;
		}
		// Space the host hasn't described yet (still streaming in) is treated as solid: the player
		// waits at its edge instead of falling into terrain that isn't there yet.
		double nx = self.getX() + allowed.x, ny = self.getY() + allowed.y, nz = self.getZ() + allowed.z;
		if (!TriStore.isKnown((int) Math.floor(nx), (int) Math.floor(ny), (int) Math.floor(nz))
			|| !TriStore.isKnown((int) Math.floor(nx), (int) Math.floor(ny + self.getBbHeight()), (int) Math.floor(nz))) {
			return new Vec3(0, 0, 0);
		}
		TriCollider c = TriCollider.get();
		double[] m = c.resolve(self.getX(), self.getY(), self.getZ(), self.getBbWidth() / 2.0, self.getBbHeight(), self.maxUpStep(), self.onGround(),
			allowed.x, allowed.y, allowed.z);
		if (c.touched) {
			double l = Math.sqrt(c.contactX * c.contactX + c.contactY * c.contactY + c.contactZ * c.contactZ);
			if (l > 1e-9) {
				this.subcraft$triContact = true;
				this.subcraft$nx = c.contactX / l;
				this.subcraft$ny = c.contactY / l;
				this.subcraft$nz = c.contactZ / l;
			}
		}
		return new Vec3(m[0], m[1], m[2]);
	}

	@Inject(method = "move", at = @At("HEAD"))
	private void subcraft$rememberVelocity(MoverType type, Vec3 movement, CallbackInfo ci) {
		this.subcraft$velocityBefore = ((Entity) (Object) this).getDeltaMovement();
	}

	/**
	 * Vanilla zeroes the whole x or z velocity on any horizontal collision, which would stall
	 * gliding along a slanted face. Against host triangles, only the part of the velocity going
	 * into the surface is removed (the collision response of that surface).
	 */
	@Inject(method = "move", at = @At("TAIL"))
	private void subcraft$slideVelocity(MoverType type, Vec3 movement, CallbackInfo ci) {
		Entity self = (Entity) (Object) this;
		if (!this.subcraft$triContact || this.subcraft$velocityBefore == null || !self.horizontalCollision) {
			return;
		}
		Vec3 v = this.subcraft$velocityBefore;
		double into = v.x * this.subcraft$nx + v.y * this.subcraft$ny + v.z * this.subcraft$nz;
		if (into < 0) {
			v = v.subtract(this.subcraft$nx * into, this.subcraft$ny * into, this.subcraft$nz * into);
		}
		self.setDeltaMovement(v.x, self.getDeltaMovement().y, v.z);
	}
}
