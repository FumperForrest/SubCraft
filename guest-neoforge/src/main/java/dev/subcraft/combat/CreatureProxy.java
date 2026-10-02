package dev.subcraft.combat;

import dev.subcraft.link.LinkView;
import dev.subcraft.link.Proto;
import dev.subcraft.link.SubLink;
import net.minecraft.core.NonNullList;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A Subnautica creature as Minecraft sees it: an invisible, physics-free living entity with the
 * creature's box, moved by {@link Proxies} from the host's creature table. Minecraft's own combat
 * (swords, crits, sweeps, arrows, enchantments) hits it; the damage goes to the host instead of
 * this entity's health, and Subnautica's creature takes it. Never saved: proxies come and go with
 * the host's table.
 */
public class CreatureProxy extends LivingEntity {
	private static final EntityDataAccessor<Integer> HOST_ID = SynchedEntityData.defineId(CreatureProxy.class, EntityDataSerializers.INT);
	private static final NonNullList<ItemStack> NO_ARMOR = NonNullList.withSize(4, ItemStack.EMPTY);
	private float width = 0.6F, height = 0.6F;

	public CreatureProxy(EntityType<? extends CreatureProxy> type, Level level) {
		super(type, level);
		this.noPhysics = true;
		this.setNoGravity(true);
		this.setSilent(true);
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		super.defineSynchedData(builder);
		builder.define(HOST_ID, 0);
	}

	public int hostId() {
		return this.entityData.get(HOST_ID);
	}

	void setHostId(int id) {
		this.entityData.set(HOST_ID, id);
	}

	/** Box from the creature table (both sides read it: same process, same shared memory). */
	void sizeTo(float w, float h) {
		w = Math.max(0.2F, w);
		h = Math.max(0.2F, h);
		if (Math.abs(w - this.width) > 0.05F || Math.abs(h - this.height) > 0.05F) {
			this.width = w;
			this.height = h;
			this.refreshDimensions();
		}
	}

	@Override
	public void tick() {
		if (this.level().isClientSide()) {
			LinkView.Creature c = Proxies.lookup(this.hostId());
			if (c != null) {
				this.sizeTo(c.width(), c.height());
			}
		}
		super.tick();
	}

	@Override
	protected EntityDimensions getDefaultDimensions(Pose pose) {
		return EntityDimensions.scalable(this.width, this.height);
	}

	@Override
	public boolean hurt(DamageSource source, float amount) {
		if (this.level().isClientSide() || this.isInvulnerableTo(source) || amount <= 0) {
			return false;
		}
		// Only hits from something (a player, a mob, an arrow, an explosion): the proxy sits in
		// Minecraft's water and blocks, and drowning or suffocation there are not Subnautica's.
		if (source.getEntity() == null && source.getDirectEntity() == null && !source.is(net.minecraft.tags.DamageTypeTags.IS_EXPLOSION)) {
			return false;
		}
		LinkView view = SubLink.view();
		LinkView.Creature c = Proxies.lookup(this.hostId());
		if (view == null || c == null || (c.flags() & Proto.CREATURE_INVULNERABLE) != 0) {
			return false;
		}
		// Knockback away from whoever (or whatever) hit it, at Minecraft's base strength.
		Vec3 from = source.getSourcePosition();
		double dx = 0, dz = 0;
		if (from != null) {
			dx = this.getX() - from.x;
			dz = this.getZ() - from.z;
			double len = Math.sqrt(dx * dx + dz * dz);
			if (len > 1e-4) {
				dx /= len;
				dz /= len;
			}
		}
		float strength = 0.4F;
		if (source.getEntity() instanceof LivingEntity attacker && attacker.isSprinting()) {
			strength += 0.5F;
		}
		view.pushEvent(Proto.EV_HIT_CREATURE, this.hostId(), amount, (float) dx, (float) dz, strength, 0, 0);
		Proxies.hits++;
		return true;
	}

	@Override
	public void knockback(double strength, double x, double z) {
		// The host moves the creature (kEvHitCreature carries the knockback).
	}

	@Override
	public boolean isPushable() {
		return false;
	}

	@Override
	public boolean canBeCollidedWith() {
		return false;
	}

	@Override
	public boolean shouldBeSaved() {
		return false;
	}

	@Override
	public boolean isAffectedByPotions() {
		return false;
	}

	@Override
	protected @Nullable SoundEvent getHurtSound(DamageSource source) {
		return null; // the creature's own Subnautica sound plays
	}

	@Override
	protected @Nullable SoundEvent getDeathSound() {
		return null;
	}

	@Override
	public Iterable<ItemStack> getArmorSlots() {
		return NO_ARMOR;
	}

	@Override
	public ItemStack getItemBySlot(EquipmentSlot slot) {
		return ItemStack.EMPTY;
	}

	@Override
	public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
	}

	@Override
	public HumanoidArm getMainArm() {
		return HumanoidArm.RIGHT;
	}
}
