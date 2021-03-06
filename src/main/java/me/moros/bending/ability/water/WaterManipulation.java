/*
 *   Copyright 2020-2021 Moros <https://github.com/PrimordialMoros>
 *
 *    This file is part of Bending.
 *
 *   Bending is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   Bending is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with Bending.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.moros.bending.ability.water;

import me.moros.atlas.cf.checker.nullness.qual.NonNull;
import me.moros.atlas.configurate.CommentedConfigurationNode;
import me.moros.bending.Bending;
import me.moros.bending.ability.common.FragileStructure;
import me.moros.bending.ability.common.SelectedSource;
import me.moros.bending.ability.common.basic.AbstractBlockShot;
import me.moros.bending.config.Configurable;
import me.moros.bending.game.temporal.TempBlock;
import me.moros.bending.model.ability.Ability;
import me.moros.bending.model.ability.AbilityInstance;
import me.moros.bending.model.ability.description.AbilityDescription;
import me.moros.bending.model.ability.state.State;
import me.moros.bending.model.ability.state.StateChain;
import me.moros.bending.model.ability.util.ActivationMethod;
import me.moros.bending.model.ability.util.UpdateResult;
import me.moros.bending.model.attribute.Attribute;
import me.moros.bending.model.collision.Collider;
import me.moros.bending.model.collision.geometry.Ray;
import me.moros.bending.model.collision.geometry.Sphere;
import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.predicate.removal.Policies;
import me.moros.bending.model.predicate.removal.RemovalPolicy;
import me.moros.bending.model.predicate.removal.SwappedSlotsRemovalPolicy;
import me.moros.bending.model.user.User;
import me.moros.bending.util.DamageUtil;
import me.moros.bending.util.ParticleUtil;
import me.moros.bending.util.PotionUtil;
import me.moros.bending.util.SoundEffect;
import me.moros.bending.util.SoundUtil;
import me.moros.bending.util.SourceUtil;
import me.moros.bending.util.material.MaterialUtil;
import me.moros.bending.util.material.WaterMaterials;
import me.moros.bending.util.methods.BlockMethods;
import me.moros.bending.util.methods.WorldMethods;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.NumberConversions;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class WaterManipulation extends AbilityInstance implements Ability {
	private static final Config config = new Config();

	private User user;
	private Config userConfig;
	private RemovalPolicy removalPolicy;

	private StateChain states;
	private Manip manip;
	private final Deque<Block> trail = new ArrayDeque<>(2);

	private boolean isIce;

	public WaterManipulation(@NonNull AbilityDescription desc) {
		super(desc);
	}

	@Override
	public boolean activate(@NonNull User user, @NonNull ActivationMethod method) {
		this.user = user;
		recalculateConfig();

		Block source = SourceUtil.getSource(user, userConfig.selectRange, WaterMaterials::isWaterBendable).orElse(null);
		if (source == null) return false;

		Collection<WaterManipulation> manips = Bending.getGame().getAbilityManager(user.getWorld()).getUserInstances(user, WaterManipulation.class)
			.filter(m -> m.manip == null).collect(Collectors.toList());
		for (WaterManipulation manip : manips) {
			State state = manip.states.getCurrent();
			if (state instanceof SelectedSource) {
				((SelectedSource) state).reselect(source);
				return false;
			}
		}

		states = new StateChain()
			.addState(new SelectedSource(user, source, userConfig.selectRange + 5))
			.start();
		removalPolicy = Policies.builder().add(new SwappedSlotsRemovalPolicy(getDescription())).build();
		return true;
	}

	@Override
	public void recalculateConfig() {
		userConfig = Bending.getGame().getAttributeSystem().calculate(this, config);
	}

	@Override
	public @NonNull UpdateResult update() {
		if (removalPolicy.test(user, getDescription())) {
			return UpdateResult.REMOVE;
		}
		if (manip != null) {
			SoundEffect effect = isIce ? SoundUtil.ICE_SOUND : SoundUtil.WATER_SOUND;
			if (ThreadLocalRandom.current().nextInt(5) == 0) {
				effect.play(manip.getCurrent().toLocation(user.getWorld()));
			}

			if (isIce) {
				Location center = manip.getCurrent().add(Vector3.HALF).toLocation(user.getWorld());
				ParticleUtil.create(Particle.ITEM_CRACK, center).count(10)
					.offset(0.5, 0.5, 0.5).data(new ItemStack(Material.ICE)).spawn();
				ParticleUtil.create(Particle.SNOW_SHOVEL, center).count(10)
					.offset(0.5, 0.5, 0.5).spawn();
			} else {
				Block previous = manip.getPreviousBlock();
				if (previous != null) {
					if (!trail.isEmpty()) manip.clean(trail.peekFirst());
					if (trail.size() == 2) manip.clean(trail.removeLast());
					trail.addFirst(previous);
					TempBlock.MANAGER.get(previous).ifPresent(tb -> tb.setRevertTask(() -> renderTrail(previous, 7)));
				}
			}

			return manip.update();
		} else {
			return states.update();
		}
	}

	private void renderTrail(Block block, int level) {
		if (MaterialUtil.isTransparentOrWater(block)) {
			BlockMethods.breakPlant(block);
			if (MaterialUtil.isWater(block)) {
				ParticleUtil.create(Particle.WATER_BUBBLE, block.getLocation().add(0.5, 0.5, 0.5))
					.count(5).offset(0.25, 0.25, 0.25).spawn();
			} else {
				Optional<TempBlock> tempBlock = TempBlock.create(block, MaterialUtil.getWaterData(level), true);
				if (level == 7 && tempBlock.isPresent()) {
					tempBlock.get().setRevertTask(() -> renderTrail(block, level - 1));
				}
			}
		}
	}

	public static void launch(User user) {
		if (user.getSelectedAbility().map(AbilityDescription::getName).orElse("").equals("WaterManipulation")) {
			Collection<WaterManipulation> manips = Bending.getGame().getAbilityManager(user.getWorld()).getUserInstances(user, WaterManipulation.class)
				.collect(Collectors.toList());
			redirectAny(user);
			for (WaterManipulation manip : manips) {
				if (manip.manip == null) {
					manip.launch();
					return;
				} else {
					manip.manip.redirect();
				}
			}
		}
	}

	private void launch() {
		State state = states.getCurrent();
		if (state instanceof SelectedSource) {
			state.complete();
			Block source = states.getChainStore().stream().findAny().orElse(null);
			if (source == null || !TempBlock.isBendable(source)) return;
			if (WaterMaterials.isWaterBendable(source)) {
				isIce = WaterMaterials.isIceBendable(source);
				manip = new Manip(user, source);
				Policies.builder().build();
				user.setCooldown(getDescription(), userConfig.cooldown);
				TempBlock.create(source, Material.AIR, true);
			}
		}
	}

	private static void redirectAny(@NonNull User user) {
		Collection<WaterManipulation> manips = Bending.getGame().getAbilityManager(user.getWorld()).getInstances(WaterManipulation.class)
			.filter(m -> m.manip != null && !user.equals(m.user)).collect(Collectors.toList());
		for (WaterManipulation manip : manips) {
			Vector3 center = manip.manip.getCurrent().add(Vector3.HALF);
			double dist = center.distanceSq(user.getEyeLocation());
			if (dist < config.rMin * config.rMin || dist > config.rMax * config.rMax) continue;
			Sphere selectSphere = new Sphere(center, config.redirectGrabRadius);
			if (selectSphere.intersects(user.getRay(dist))) {
				Vector3 dir = center.subtract(user.getEyeLocation());
				if (WorldMethods.blockCast(user.getWorld(), new Ray(user.getEyeLocation(), dir), config.rMax + 2).isPresent()) {
					Bending.getGame().getAbilityManager(user.getWorld()).changeOwner(manip, user);
					manip.manip.redirect();
				}
			}
		}
	}

	@Override
	public void onDestroy() {
		if (manip != null) {
			trail.forEach(b -> {
				TempBlock.MANAGER.get(b).ifPresent(tb -> tb.setRevertTask(null));
				manip.clean(b);
			});
			manip.clean();
		}
	}

	@Override
	public @NonNull User getUser() {
		return user;
	}

	@Override
	public boolean setUser(@NonNull User user) {
		if (manip == null) return false;
		this.user = user;
		manip.setUser(user);
		return true;
	}

	@Override
	public @NonNull Collection<@NonNull Collider> getColliders() {
		if (manip == null) return Collections.emptyList();
		return Collections.singletonList(manip.getCollider());
	}

	private class Manip extends AbstractBlockShot {
		public Manip(User user, Block block) {
			super(user, block, userConfig.range, isIce ? 80 : 100);
			material = isIce ? block.getType() : Material.WATER;
			allowUnderWater = true;
		}

		@Override
		public boolean onEntityHit(@NonNull Entity entity) {
			Vector3 origin = getCurrent().add(Vector3.HALF);
			Vector3 entityLoc = new Vector3(entity.getLocation().add(0, entity.getHeight() / 2, 0));
			Vector3 push = entityLoc.subtract(origin).normalize().scalarMultiply(0.8);
			entity.setVelocity(push.clampVelocity());
			DamageUtil.damageEntity(entity, user, userConfig.damage, getDescription());
			int potionDuration = NumberConversions.round(userConfig.slowDuration / 50F);
			PotionUtil.addPotion(entity, PotionEffectType.SLOW, potionDuration, userConfig.power);
			return true;
		}

		@Override
		public void onBlockHit(@NonNull Block block) {
			FragileStructure.attemptDamageStructure(Collections.singletonList(block), 3);
		}
	}

	private static class Config extends Configurable {
		@Attribute(Attribute.COOLDOWN)
		public long cooldown;
		@Attribute(Attribute.RANGE)
		public double range;
		@Attribute(Attribute.SELECTION)
		public double selectRange;
		@Attribute(Attribute.DAMAGE)
		public double damage;

		@Attribute(Attribute.STRENGTH)
		public int power;
		@Attribute(Attribute.DURATION)
		public long slowDuration;

		public double redirectGrabRadius;
		public double rMin;
		public double rMax;

		@Override
		public void onConfigReload() {
			CommentedConfigurationNode abilityNode = config.node("abilities", "water", "watermanipulation");

			cooldown = abilityNode.node("cooldown").getLong(750);
			range = abilityNode.node("range").getDouble(28.0);
			selectRange = abilityNode.node("select-range").getDouble(12.0);
			damage = abilityNode.node("damage").getDouble(2.0);
			redirectGrabRadius = abilityNode.node("redirect-grab-radius").getDouble(2.0);
			rMin = abilityNode.node("min-redirect-range").getDouble(5.0);
			rMax = abilityNode.node("max-redirect-range").getDouble(20.0);

			CommentedConfigurationNode iceNode = abilityNode.node("iceblast");

			power = iceNode.node("slow-power").getInt(2) - 1;
			slowDuration = iceNode.node("slow-duration").getLong(1500);
		}
	}
}
