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
import me.moros.bending.ability.common.basic.AbstractSpout;
import me.moros.bending.config.Configurable;
import me.moros.bending.game.temporal.TempBlock;
import me.moros.bending.model.ability.Ability;
import me.moros.bending.model.ability.AbilityInstance;
import me.moros.bending.model.ability.description.AbilityDescription;
import me.moros.bending.model.ability.util.ActivationMethod;
import me.moros.bending.model.ability.util.UpdateResult;
import me.moros.bending.model.attribute.Attribute;
import me.moros.bending.model.collision.Collider;
import me.moros.bending.model.collision.geometry.Ray;
import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.predicate.removal.Policies;
import me.moros.bending.model.predicate.removal.RemovalPolicy;
import me.moros.bending.model.user.User;
import me.moros.bending.util.SoundUtil;
import me.moros.bending.util.material.MaterialUtil;
import me.moros.bending.util.material.WaterMaterials;
import me.moros.bending.util.methods.WorldMethods;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class WaterSpout extends AbilityInstance implements Ability {
	private static final Config config = new Config();

	private User user;
	private Config userConfig;
	private RemovalPolicy removalPolicy;

	private final Collection<Block> column = new ArrayList<>();
	private final Predicate<Block> predicate = b -> MaterialUtil.isWater(b) || WaterMaterials.isIceBendable(b);
	private Spout spout;

	public WaterSpout(@NonNull AbilityDescription desc) {
		super(desc);
	}

	@Override
	public boolean activate(@NonNull User user, @NonNull ActivationMethod method) {
		if (Bending.getGame().getAbilityManager(user.getWorld()).destroyInstanceType(user, WaterSpout.class)) {
			return false;
		}

		this.user = user;
		recalculateConfig();

		double h = userConfig.height + 2;
		if (WorldMethods.distanceAboveGround(user.getEntity()) > h) {
			return false;
		}

		Block block = WorldMethods.blockCast(user.getWorld(), new Ray(user.getLocation(), Vector3.MINUS_J), h).orElse(null);
		if (block == null || !predicate.test(block)) {
			return false;
		}

		removalPolicy = Policies.builder().build();

		spout = new Spout();
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

		return spout.update();
	}

	@Override
	public void onDestroy() {
		column.forEach(spout::clean);
		spout.getFlight().setFlying(false);
		spout.getFlight().release();
		user.setCooldown(getDescription(), userConfig.cooldown);
	}

	@Override
	public @NonNull User getUser() {
		return user;
	}

	@Override
	public @NonNull Collection<@NonNull Collider> getColliders() {
		return Collections.singletonList(spout.getCollider());
	}

	public void handleMovement(Vector velocity) {
		AbstractSpout.limitVelocity(user, velocity, userConfig.maxSpeed);
	}

	private class Spout extends AbstractSpout {
		private BlockVector blockVector;
		private final Vector g = new Vector(0, -0.1, 0); // Applied as extra gravity

		public Spout() {
			super(user, userConfig.height, userConfig.maxSpeed);
			validBlock = predicate;
		}

		@Override
		public void render() {
			BlockVector userBlockVector = new BlockVector(user.getLocation().toVector());
			if (userBlockVector.equals(blockVector)) return;
			blockVector = userBlockVector;
			column.forEach(this::clean);
			column.clear();
			ignore.clear();

			Block block = user.getLocBlock();
			for (int i = 0; i < distance - 1; i++) {
				TempBlock.create(block.getRelative(BlockFace.DOWN, i), Material.WATER).ifPresent(tb -> column.add(tb.getBlock()));
			}
			ignore.addAll(column);
		}

		@Override
		public void postRender() {
			if (!user.isFlying()) {
				user.getEntity().setVelocity(user.getEntity().getVelocity().add(g));
			}
			if (ThreadLocalRandom.current().nextInt(8) == 0) {
				SoundUtil.WATER_SOUND.play(user.getEntity().getLocation());
			}
		}

		protected void clean(Block block) {
			TempBlock.MANAGER.get(block).filter(tb -> MaterialUtil.isWater(tb.getBlock())).ifPresent(TempBlock::revert);
		}
	}

	private static class Config extends Configurable {
		@Attribute(Attribute.COOLDOWN)
		public long cooldown;
		@Attribute(Attribute.HEIGHT)
		public double height;
		@Attribute(Attribute.SPEED)
		public double maxSpeed;

		@Override
		public void onConfigReload() {
			CommentedConfigurationNode abilityNode = config.node("abilities", "water", "waterspout");

			cooldown = abilityNode.node("cooldown").getLong(0);
			height = abilityNode.node("height").getDouble(14.0);
			maxSpeed = abilityNode.node("max-speed").getDouble(0.2);
		}
	}
}
