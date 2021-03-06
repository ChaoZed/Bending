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
import me.moros.bending.ability.common.SelectedSource;
import me.moros.bending.ability.common.basic.AbstractLine;
import me.moros.bending.config.Configurable;
import me.moros.bending.game.temporal.BendingFallingBlock;
import me.moros.bending.game.temporal.TempArmorStand;
import me.moros.bending.game.temporal.TempBlock;
import me.moros.bending.model.ability.Ability;
import me.moros.bending.model.ability.AbilityInstance;
import me.moros.bending.model.ability.description.AbilityDescription;
import me.moros.bending.model.ability.state.State;
import me.moros.bending.model.ability.state.StateChain;
import me.moros.bending.model.ability.util.ActionType;
import me.moros.bending.model.ability.util.ActivationMethod;
import me.moros.bending.model.ability.util.UpdateResult;
import me.moros.bending.model.attribute.Attribute;
import me.moros.bending.model.collision.Collider;
import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.predicate.removal.Policies;
import me.moros.bending.model.predicate.removal.RemovalPolicy;
import me.moros.bending.model.predicate.removal.SwappedSlotsRemovalPolicy;
import me.moros.bending.model.user.User;
import me.moros.bending.util.DamageUtil;
import me.moros.bending.util.MovementHandler;
import me.moros.bending.util.SoundUtil;
import me.moros.bending.util.SourceUtil;
import me.moros.bending.util.material.MaterialUtil;
import me.moros.bending.util.material.WaterMaterials;
import me.moros.bending.util.methods.BlockMethods;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

public class IceCrawl extends AbilityInstance implements Ability {
	private static final Config config = new Config();

	private User user;
	private Config userConfig;
	private RemovalPolicy removalPolicy;

	private StateChain states;
	private Line iceLine;

	public IceCrawl(@NonNull AbilityDescription desc) {
		super(desc);
	}

	@Override
	public boolean activate(@NonNull User user, @NonNull ActivationMethod method) {
		this.user = user;
		recalculateConfig();

		Optional<Block> source = SourceUtil.getSource(user, userConfig.selectRange, WaterMaterials::isWaterOrIceBendable);
		if (!source.isPresent()) return false;

		Optional<IceCrawl> line = Bending.getGame().getAbilityManager(user.getWorld()).getFirstInstance(user, IceCrawl.class);
		if (method == ActivationMethod.SNEAK && line.isPresent()) {
			State state = line.get().states.getCurrent();
			if (state instanceof SelectedSource) {
				((SelectedSource) state).reselect(source.get());
			}
			return false;
		}

		states = new StateChain()
			.addState(new SelectedSource(user, source.get(), userConfig.selectRange + 5))
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
		if (iceLine != null) {
			return iceLine.update();
		} else {
			return states.update();
		}
	}

	public static void launch(User user) {
		if (user.getSelectedAbility().map(AbilityDescription::getName).orElse("").equals("IceCrawl")) {
			Bending.getGame().getAbilityManager(user.getWorld()).getFirstInstance(user, IceCrawl.class).ifPresent(IceCrawl::launch);
		}
	}

	private void launch() {
		if (iceLine != null) return;
		State state = states.getCurrent();
		if (state instanceof SelectedSource) {
			state.complete();
			Optional<Block> src = states.getChainStore().stream().findAny();
			if (src.isPresent()) {
				iceLine = new Line(src.get());
				Policies.builder().build();
				user.setCooldown(getDescription(), userConfig.cooldown);
			}
		}
	}

	@Override
	public @NonNull User getUser() {
		return user;
	}

	@Override
	public @NonNull Collection<@NonNull Collider> getColliders() {
		if (iceLine == null) return Collections.emptyList();
		return Collections.singletonList(iceLine.getCollider());
	}

	private class Line extends AbstractLine {
		public Line(Block source) {
			super(user, source, userConfig.range, 0.5, true);
			skipVertical = true;
		}

		@Override
		public void render() {
			double x = ThreadLocalRandom.current().nextDouble(-0.125, 0.125);
			double z = ThreadLocalRandom.current().nextDouble(-0.125, 0.125);
			Location spawnLoc = location.subtract(new Vector3(x, 2, z)).toLocation(user.getWorld());
			new TempArmorStand(spawnLoc, Material.PACKED_ICE, 1400);
		}

		@Override
		public void postRender() {
			if (ThreadLocalRandom.current().nextInt(5) == 0) {
				SoundUtil.ICE_SOUND.play(location.toLocation(user.getWorld()));
			}
		}

		@Override
		public boolean onEntityHit(@NonNull Entity entity) {
			DamageUtil.damageEntity(entity, user, userConfig.damage, getDescription());
			if (entity.isValid() && entity instanceof LivingEntity) {
				Location spawnLoc = entity.getLocation().clone().add(0, -0.2, 0);
				new BendingFallingBlock(spawnLoc, Material.PACKED_ICE.createBlockData(), userConfig.freezeDuration);
				MovementHandler.restrictEntity((LivingEntity) entity, userConfig.freezeDuration).disableActions(ActionType.MOVE);
			}
			return true;
		}

		@Override
		public boolean onBlockHit(@NonNull Block block) {
			if (MaterialUtil.isWater(block)) {
				TempBlock.create(block, Material.ICE, userConfig.iceDuration, true);
			}
			return BlockMethods.coolLava(user, block);
		}

		@Override
		protected boolean isValidBlock(@NonNull Block block) {
			Block above = block.getRelative(BlockFace.UP);
			if (!MaterialUtil.isTransparentOrWater(above)) return false;
			return MaterialUtil.isWater(block) || WaterMaterials.isIceBendable(block) || !block.isPassable();
		}
	}

	private static class Config extends Configurable {
		@Attribute(Attribute.COOLDOWN)
		public long cooldown;
		@Attribute(Attribute.DURATION)
		public long iceDuration;
		@Attribute(Attribute.DURATION)
		public long freezeDuration;
		@Attribute(Attribute.RANGE)
		public double range;
		@Attribute(Attribute.SELECTION)
		public double selectRange;
		@Attribute(Attribute.DAMAGE)
		public double damage;

		@Override
		public void onConfigReload() {
			CommentedConfigurationNode abilityNode = config.node("abilities", "water", "icecrawl");

			cooldown = abilityNode.node("cooldown").getLong(5000);
			iceDuration = abilityNode.node("ice-duration").getLong(8000);
			freezeDuration = abilityNode.node("freeze-duration").getLong(2000);
			range = abilityNode.node("range").getDouble(24.0);
			selectRange = abilityNode.node("select-range").getDouble(8.0);
			damage = abilityNode.node("damage").getDouble(4.0);
		}
	}
}
