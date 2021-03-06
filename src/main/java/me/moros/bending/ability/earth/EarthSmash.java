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

package me.moros.bending.ability.earth;

import me.moros.atlas.cf.checker.nullness.qual.NonNull;
import me.moros.atlas.configurate.CommentedConfigurationNode;
import me.moros.bending.Bending;
import me.moros.bending.ability.common.FragileStructure;
import me.moros.bending.config.Configurable;
import me.moros.bending.game.temporal.TempBlock;
import me.moros.bending.model.ability.Ability;
import me.moros.bending.model.ability.AbilityInstance;
import me.moros.bending.model.ability.Updatable;
import me.moros.bending.model.ability.description.AbilityDescription;
import me.moros.bending.model.ability.util.ActivationMethod;
import me.moros.bending.model.ability.util.UpdateResult;
import me.moros.bending.model.attribute.Attribute;
import me.moros.bending.model.collision.Collider;
import me.moros.bending.model.collision.geometry.AABB;
import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.predicate.removal.Policies;
import me.moros.bending.model.predicate.removal.RemovalPolicy;
import me.moros.bending.model.predicate.removal.SwappedSlotsRemovalPolicy;
import me.moros.bending.model.user.User;
import me.moros.bending.util.BendingProperties;
import me.moros.bending.util.DamageUtil;
import me.moros.bending.util.ParticleUtil;
import me.moros.bending.util.SoundUtil;
import me.moros.bending.util.SourceUtil;
import me.moros.bending.util.collision.CollisionUtil;
import me.moros.bending.util.material.EarthMaterials;
import me.moros.bending.util.material.MaterialUtil;
import me.moros.bending.util.methods.UserMethods;
import me.moros.bending.util.methods.VectorMethods;
import me.moros.bending.util.methods.WorldMethods;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.util.BlockVector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class EarthSmash extends AbilityInstance implements Ability {
	private static final Config config = new Config();

	private User user;
	private Config userConfig;
	private RemovalPolicy removalPolicy;
	private RemovalPolicy swappedSlotsPolicy;

	private EarthSmashState state;
	private Boulder boulder;

	public EarthSmash(@NonNull AbilityDescription desc) {
		super(desc);
	}

	@Override
	public boolean activate(@NonNull User user, @NonNull ActivationMethod method) {
		if (method == ActivationMethod.SNEAK) {
			boolean hasGrabbed = Bending.getGame().getAbilityManager(user.getWorld()).getUserInstances(user, EarthSmash.class)
				.anyMatch(s -> s.state instanceof GrabState);
			if (hasGrabbed) return false;
		}
		this.user = user;
		recalculateConfig();

		state = new ChargeState();
		removalPolicy = Policies.builder().build();
		swappedSlotsPolicy = new SwappedSlotsRemovalPolicy(getDescription());

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
		if (!state.canSlotSwitch() && swappedSlotsPolicy.test(user, getDescription())) {
			return UpdateResult.REMOVE;
		}
		if (boulder != null && boulder.data.isEmpty()) return UpdateResult.REMOVE;
		return state.update();
	}

	private boolean createBoulder() {
		Block center = SourceUtil.getSource(user, userConfig.selectRange, b -> EarthMaterials.isEarthNotLava(user, b)).orElse(null);
		if (center == null) return false;

		// Check blocks above center
		for (int i = 0; i <= userConfig.radius; i++) {
			Block b = center.getRelative(BlockFace.UP, i + 1);
			if (!MaterialUtil.isTransparent(b) || !TempBlock.isBendable(b) || !Bending.getGame().getProtectionSystem().canBuild(user, b)) {
				return false;
			}
		}

		boulder = new Boulder(user, center, userConfig.radius, userConfig.maxDuration);
		state = new LiftState();
		user.setCooldown(getDescription(), userConfig.cooldown);
		return true;
	}

	private void launchBoulder() {
		state = new ShotState();
	}

	private void grabBoulder() {
		state = new GrabState();
	}

	public static void attemptGrab(User user) {
		if (user.getSelectedAbility().map(AbilityDescription::getName).orElse("").equals("EarthSmash")) {
			Optional<Block> target = WorldMethods.rayTraceBlocks(user.getWorld(), user.getRay(config.grabRange));
			if (!target.isPresent()) return;
			AABB blockBounds = AABB.BLOCK_BOUNDS.at(new Vector3(target.get()));
			EarthSmash earthSmash = Bending.getGame().getAbilityManager(user.getWorld()).getInstances(EarthSmash.class)
				.filter(s -> s.state.canGrab())
				.filter(s -> s.boulder.preciseBounds.at(s.boulder.center).intersects(blockBounds))
				.findAny().orElse(null);
			if (earthSmash == null) return;
			Bending.getGame().getAbilityManager(user.getWorld()).changeOwner(earthSmash, user);
			earthSmash.grabBoulder();
		}
	}

	public static void launch(User user) {
		if (user.getSelectedAbility().map(AbilityDescription::getName).orElse("").equals("EarthSmash")) {
			Bending.getGame().getAbilityManager(user.getWorld()).getUserInstances(user, EarthSmash.class)
				.filter(s -> s.state instanceof GrabState).findAny().ifPresent(EarthSmash::launchBoulder);
		}
	}

	private void cleanAll() {
		for (Map.Entry<Block, BlockData> entry : boulder.getData().entrySet()) {
			Block block = entry.getKey();
			if (block.getType() != entry.getValue().getMaterial()) continue;
			TempBlock.MANAGER.get(block).ifPresent(TempBlock::revert);
		}
	}

	private void render() {
		for (Map.Entry<Block, BlockData> entry : boulder.getData().entrySet()) {
			Block block = entry.getKey();
			if (!MaterialUtil.isTransparent(block)) continue;
			TempBlock.create(block, entry.getValue());
		}
	}

	@Override
	public void onDestroy() {
		if (boulder != null) cleanAll();
	}

	@Override
	public @NonNull User getUser() {
		return user;
	}

	@Override
	public boolean setUser(@NonNull User user) {
		if (boulder == null) return false;
		this.user = user;
		boulder.user = user;
		return true;
	}

	@Override
	public @NonNull Collection<@NonNull Collider> getColliders() {
		if (!state.canCollide()) return Collections.emptyList();
		return Collections.singletonList(boulder.getCollider());
	}

	interface EarthSmashState extends Updatable {
		default boolean canGrab() {
			return false;
		}

		default boolean canCollide() {
			return true;
		}

		default boolean canSlotSwitch() {
			return false;
		}
	}

	private class ChargeState implements EarthSmashState {
		private final long startTime;

		private ChargeState() {
			startTime = System.currentTimeMillis();
		}

		@Override
		public @NonNull UpdateResult update() {
			if (System.currentTimeMillis() >= startTime + userConfig.chargeTime) {
				if (user.isSneaking()) {
					ParticleUtil.create(Particle.SMOKE_NORMAL, UserMethods.getMainHandSide(user).toLocation(user.getWorld())).spawn();
					return UpdateResult.CONTINUE;
				} else {
					return createBoulder() ? UpdateResult.CONTINUE : UpdateResult.REMOVE;
				}
			} else if (user.isSneaking()) {
				return UpdateResult.CONTINUE;
			}
			return UpdateResult.REMOVE;
		}

		@Override
		public boolean canCollide() {
			return false;
		}
	}

	private class LiftState implements EarthSmashState {
		private final Vector3 origin;
		private int tick = 0;

		private LiftState() {
			this.origin = new Vector3(boulder.center.toArray());
		}

		@Override
		public @NonNull UpdateResult update() {
			Block newCenter = boulder.center.add(Vector3.PLUS_J).toBlock(boulder.world);
			if (!boulder.isValidCenter(newCenter)) return UpdateResult.REMOVE;
			cleanAll();
			boulder.setCenter(newCenter);
			SoundUtil.EARTH_SOUND.play(boulder.center.toLocation(boulder.world));
			CollisionUtil.handleEntityCollisions(user, boulder.getCollider(), entity -> {
				entity.setVelocity(new Vector3(entity.getVelocity()).setY(userConfig.raiseEntityPush).clampVelocity());
				return true;
			}, true, true);
			render();
			clearSourceArea();
			return UpdateResult.CONTINUE;
		}

		private void clearSourceArea() {
			tick++;
			int half = (boulder.size - 1) / 2;
			if (tick >= boulder.size) {
				state = new IdleState();
			} else if (tick == half) {
				for (int z = -half; z <= half; z++) {
					for (int x = -half; x <= half; x++) {
						// Remove bottom layer
						if ((FastMath.abs(x) + FastMath.abs(z)) % 2 != 0) {
							Block block = origin.add(new Vector3(x, -1, z)).toBlock(boulder.world);
							if (EarthMaterials.isEarthNotLava(user, block)) {
								TempBlock.create(block, Material.AIR, BendingProperties.EARTHBENDING_REVERT_TIME, true);
							}
						}
						// Remove top layer
						Block block = origin.add(new Vector3(x, 0, z)).toBlock(boulder.world);
						if (EarthMaterials.isEarthNotLava(user, block)) {
							TempBlock.create(block, Material.AIR, BendingProperties.EARTHBENDING_REVERT_TIME, true);
						}
					}
				}
			}
		}

		@Override
		public boolean canSlotSwitch() {
			return true;
		}
	}

	private class GrabState implements EarthSmashState {
		private final double grabbedDistance;

		private GrabState() {
			this.grabbedDistance = FastMath.min(boulder.center.distance(user.getEyeLocation()), userConfig.grabRange);
		}

		@Override
		public @NonNull UpdateResult update() {
			if (user.isSneaking()) {
				Vector3 dir = user.getDirection().normalize().scalarMultiply(grabbedDistance);
				Block newCenter = user.getEyeLocation().add(dir).toBlock(boulder.world);
				if (newCenter.equals(boulder.center.toBlock(boulder.world))) return UpdateResult.CONTINUE;
				boulder.updateData();
				cleanAll();
				if (boulder.isValidCenter(newCenter)) boulder.setCenter(newCenter);
				render();
			} else {
				state = new IdleState();
			}
			return UpdateResult.CONTINUE;
		}
	}

	private class ShotState implements EarthSmashState {
		private final Set<Entity> affectedEntities;
		private final Vector3 origin;
		private final Vector3 direction;

		private ShotState() {
			affectedEntities = new HashSet<>();
			origin = new Vector3(boulder.center.toArray());
			direction = user.getDirection();
			SoundUtil.EARTH_SOUND.play(boulder.center.toLocation(boulder.world));
		}

		@Override
		public @NonNull UpdateResult update() {
			CollisionUtil.handleEntityCollisions(user, boulder.getCollider(), this::onEntityHit);
			cleanAll();
			Block newCenter = boulder.center.add(direction).toBlock(boulder.world);
			if (!boulder.isValidBlock(newCenter)) {
				return UpdateResult.REMOVE;
			}
			boulder.setCenter(newCenter);
			if (origin.distanceSq(boulder.center) > userConfig.shootRange * userConfig.shootRange) {
				return UpdateResult.REMOVE;
			}
			if (!boulder.blendSmash()) {
				return UpdateResult.REMOVE;
			}
			render();
			return UpdateResult.CONTINUE;
		}

		private boolean onEntityHit(Entity entity) {
			if (affectedEntities.contains(entity)) return false;
			affectedEntities.add(entity);
			DamageUtil.damageEntity(entity, user, userConfig.damage, getDescription());
			Vector3 velocity = VectorMethods.getEntityCenter(entity).subtract(boulder.center).setY(userConfig.knockup).normalize();
			entity.setVelocity(velocity.scalarMultiply(userConfig.knockback).clampVelocity());
			return false;
		}

		@Override
		public boolean canGrab() {
			return true;
		}

		@Override
		public boolean canSlotSwitch() {
			return true;
		}
	}

	private class IdleState implements EarthSmashState {
		@Override
		public @NonNull UpdateResult update() {
			return System.currentTimeMillis() > boulder.expireTime ? UpdateResult.REMOVE : UpdateResult.CONTINUE;
		}

		@Override
		public boolean canGrab() {
			return true;
		}

		@Override
		public boolean canSlotSwitch() {
			return true;
		}
	}

	private static class Boulder {
		private final Map<BlockVector, BlockData> data;
		private final AABB bounds;
		private final AABB preciseBounds;
		private final World world;
		private User user;
		private Vector3 center;

		private final int size;
		private final long expireTime;

		private Boulder(User user, Block centerBlock, int size, long duration) {
			this.user = user;
			this.world = user.getWorld();
			this.size = size;
			expireTime = System.currentTimeMillis() + duration;
			data = new HashMap<>();
			center = new Vector3(centerBlock).add(Vector3.HALF);
			double hr = size / 2.0;
			preciseBounds = new AABB(new Vector3(-hr, -hr, -hr), new Vector3(hr, hr, hr));
			bounds = preciseBounds.grow(Vector3.ONE);
			int half = (size - 1) / 2;
			Vector3 tempVector = center.add(Vector3.MINUS_J.scalarMultiply(half)); // When mapping blocks use the real center block
			for (int dy = -half; dy <= half; dy++) {
				for (int dz = -half; dz <= half; dz++) {
					for (int dx = -half; dx <= half; dx++) {
						BlockVector point = new BlockVector(dx, dy, dz);
						Block block = tempVector.add(new Vector3(point)).toBlock(world);
						if (!EarthMaterials.isEarthNotLava(user, block) || !Bending.getGame().getProtectionSystem().canBuild(user, block)) {
							continue;
						}
						if ((FastMath.abs(dx) + FastMath.abs(dy) + FastMath.abs(dz)) % 2 == 0) {
							data.put(point, MaterialUtil.getSolidType(block.getBlockData()));
						}
					}
				}
			}
		}

		private boolean isValidBlock(Block block) {
			if (!MaterialUtil.isTransparent(block) || !TempBlock.isBendable(block)) return false;
			return Bending.getGame().getProtectionSystem().canBuild(user, block);
		}

		private void updateData() {
			data.entrySet().removeIf(e -> {
				Material type = center.add(new Vector3(e.getKey())).toBlock(world).getType();
				return type != e.getValue().getMaterial();
			});
		}

		private boolean blendSmash() {
			int originalSize = data.size();
			Collection<Block> removed = new ArrayList<>();
			Iterator<BlockVector> iterator = data.keySet().iterator();
			while (iterator.hasNext()) {
				Block block = center.add(new Vector3(iterator.next())).toBlock(world);
				if (!isValidBlock(block)) {
					removed.add(block);
					iterator.remove();
				}
			}
			FragileStructure.attemptDamageStructure(removed, 4 * removed.size());
			return !data.isEmpty() && originalSize - data.size() <= size;
		}

		private boolean isValidCenter(Block check) {
			Vector3 temp = new Vector3(check).add(Vector3.HALF);
			return data.keySet().stream().map(bv -> temp.add(new Vector3(bv)).toBlock(world)).allMatch(this::isValidBlock);
		}

		private void setCenter(Block block) {
			this.center = new Vector3(block).add(Vector3.HALF);
		}

		private Collider getCollider() {
			return bounds.at(center);
		}

		private Map<Block, BlockData> getData() {
			return data.entrySet().stream()
				.collect(Collectors.toMap(e -> center.add(new Vector3(e.getKey())).toBlock(world), Map.Entry::getValue));
		}
	}

	private static class Config extends Configurable {
		@Attribute(Attribute.COOLDOWN)
		public long cooldown;
		@Attribute(Attribute.RADIUS)
		public int radius;
		@Attribute(Attribute.CHARGE_TIME)
		public long chargeTime;
		@Attribute(Attribute.SELECTION)
		public double selectRange;
		@Attribute(Attribute.DURATION)
		public long maxDuration;
		public double raiseEntityPush;

		@Attribute(Attribute.SELECTION)
		public double grabRange;

		@Attribute(Attribute.RANGE)
		public double shootRange;
		@Attribute(Attribute.DAMAGE)
		public double damage;
		@Attribute(Attribute.STRENGTH)
		public double knockback;
		@Attribute(Attribute.STRENGTH)
		public double knockup;

		@Override
		public void onConfigReload() {
			CommentedConfigurationNode abilityNode = config.node("abilities", "earth", "earthsmash");

			cooldown = abilityNode.node("cooldown").getLong(6000);
			radius = FastMath.max(3, abilityNode.node("radius").getInt(3));
			chargeTime = abilityNode.node("charge-time").getLong(1500);
			selectRange = abilityNode.node("select-range").getDouble(14.0);
			maxDuration = abilityNode.node("max-duration").getLong(45000);
			raiseEntityPush = abilityNode.node("raise-entity-push").getDouble(0.85);

			grabRange = abilityNode.node("grab-range").getDouble(16.0);

			shootRange = abilityNode.node("range").getDouble(32.0);
			damage = abilityNode.node("damage").getDouble(5.0);
			knockback = abilityNode.node("knockback").getDouble(3.2);
			knockup = abilityNode.node("knockup").getDouble(0.15);

			if (radius % 2 == 0) radius++;
		}
	}
}
