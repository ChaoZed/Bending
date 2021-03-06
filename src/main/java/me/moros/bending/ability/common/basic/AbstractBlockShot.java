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

package me.moros.bending.ability.common.basic;

import me.moros.atlas.cf.checker.nullness.qual.NonNull;
import me.moros.bending.Bending;
import me.moros.bending.game.temporal.TempBlock;
import me.moros.bending.model.ability.Updatable;
import me.moros.bending.model.ability.util.UpdateResult;
import me.moros.bending.model.collision.Collider;
import me.moros.bending.model.collision.geometry.AABB;
import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.user.User;
import me.moros.bending.util.ParticleUtil;
import me.moros.bending.util.collision.CollisionUtil;
import me.moros.bending.util.material.MaterialUtil;
import me.moros.bending.util.methods.BlockMethods;
import me.moros.bending.util.methods.VectorMethods;
import me.moros.bending.util.methods.WorldMethods;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.util.NumberConversions;

import java.util.Collections;
import java.util.function.Predicate;

public abstract class AbstractBlockShot implements Updatable {
	private static final AABB BOX = AABB.BLOCK_BOUNDS.grow(new Vector3(0.3, 0.3, 0.3));

	private User user;

	private Block current;
	private Block previousBlock;
	private Collider collider;
	private Vector3 firstDestination;

	protected Predicate<Block> diagonalsPredicate = b -> !MaterialUtil.isTransparentOrWater(b);
	protected Vector3 target;
	protected Vector3 direction;
	protected Material material;

	private boolean settingUp;
	private int buffer;
	private final int speed;

	protected boolean allowUnderWater = false;
	protected final double range;

	/**
	 * The maximum speed is 100 and represents movement of 1 block per tick.
	 * Example: A speed of 75 means that the stream will advance 15 (75/100 * 20) blocks in a full cycle (20 ticks).
	 * We multiply speed steps by 100 to allow enough control over speed while ensuring accuracy.
	 */
	public AbstractBlockShot(@NonNull User user, @NonNull Block block, double range, int speed) {
		this.user = user;
		this.material = block.getType();
		this.current = block;
		this.range = range;
		this.speed = FastMath.min(100, speed);
		buffer = speed;

		redirect();
		settingUp = true;
		firstDestination = getCurrent().add(Vector3.HALF);
		int targetY = NumberConversions.floor(target.getY());
		if (targetY > current.getY() + 2) {
			firstDestination = firstDestination.setY(targetY + 0.5);
		} else if (current.getY() > user.getEyeLocation().getY() && current.getRelative(BlockFace.UP).isPassable()) {
			firstDestination = firstDestination.subtract(new Vector3(0, 2, 0));
		} else if (current.getRelative(BlockFace.UP).isPassable() && current.getRelative(BlockFace.UP, 2).isPassable()) {
			firstDestination = firstDestination.add(new Vector3(0, 2, 0));
		} else {
			Vector3 dir = target.subtract(firstDestination).normalize().setY(0);
			firstDestination = firstDestination.add(dir).floor().add(Vector3.HALF);
		}
	}

	@Override
	public @NonNull UpdateResult update() {
		buffer += speed;
		if (buffer < 100) return UpdateResult.CONTINUE;
		buffer -= 100; // Reduce buffer by one since we moved

		clean();
		if (current.getY() == NumberConversions.floor(firstDestination.getY())) {
			settingUp = false;
		}
		Vector3 dest = settingUp ? firstDestination : target;
		Vector3 currentVector = getCurrent().add(Vector3.HALF);
		direction = dest.subtract(currentVector).normalize();
		Vector3 originalVector = new Vector3(currentVector.toArray());
		currentVector = currentVector.add(direction).floor().add(Vector3.HALF);

		Block originBlock = originalVector.toBlock(user.getWorld());
		for (Vector3 v : VectorMethods.decomposeDiagonals(originalVector, direction)) {
			int x = NumberConversions.floor(v.getX());
			int y = NumberConversions.floor(v.getY());
			int z = NumberConversions.floor(v.getZ());
			if (diagonalsPredicate.test(originBlock.getRelative(x, y, z))) {
				return UpdateResult.REMOVE;
			}
		}

		if (currentVector.distanceSq(user.getEyeLocation()) > range * range) {
			return UpdateResult.REMOVE;
		}

		current = currentVector.toBlock(user.getWorld());
		previousBlock = current;
		if (!Bending.getGame().getProtectionSystem().canBuild(user, current)) {
			return UpdateResult.REMOVE;
		}
		collider = BOX.at(getCurrent());
		if (CollisionUtil.handleEntityCollisions(user, collider, this::onEntityHit)) {
			return UpdateResult.REMOVE;
		}
		if (!MaterialUtil.isTransparent(current)) {
			onBlockHit(current);
		}
		if (MaterialUtil.isTransparent(current) || (MaterialUtil.isWater(current) && allowUnderWater)) {
			BlockMethods.breakPlant(current);
			if (material == Material.WATER && MaterialUtil.isWater(current)) {
				ParticleUtil.create(Particle.WATER_BUBBLE, current.getLocation().add(0.5, 0.5, 0.5))
					.count(5).offset(0.25, 0.25, 0.25).spawn();
			} else {
				TempBlock.create(current, material, true);
			}
		} else {
			return UpdateResult.REMOVE;
		}
		return currentVector.distanceSq(target) < 0.8 ? UpdateResult.REMOVE : UpdateResult.CONTINUE;
	}

	public void redirect() {
		target = WorldMethods.getTargetEntity(user, range)
			.map(VectorMethods::getEntityCenter)
			.orElseGet(() -> WorldMethods.getTarget(user.getWorld(), user.getRay(range), Collections.singleton(material)))
			.floor().add(Vector3.HALF);
		settingUp = false;
	}

	public Block getPreviousBlock() {
		return previousBlock;
	}

	public Vector3 getCurrent() {
		return new Vector3(current);
	}

	public abstract boolean onEntityHit(@NonNull Entity entity);

	public void onBlockHit(@NonNull Block block) {
	}

	public @NonNull Collider getCollider() {
		return collider;
	}

	public boolean isValid(@NonNull Block block) {
		if (material == Material.WATER) return MaterialUtil.isWater(block);
		return material == block.getType();
	}

	public void clean() {
		clean(current);
	}

	public void clean(@NonNull Block block) {
		TempBlock.MANAGER.get(block).filter(tb -> isValid(tb.getBlock())).ifPresent(TempBlock::revert);
	}

	public void setUser(@NonNull User user) {
		this.user = user;
	}
}
