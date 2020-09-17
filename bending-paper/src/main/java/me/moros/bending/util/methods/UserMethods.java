/*
 *   Copyright 2020 Moros <https://github.com/PrimordialMoros>
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

package me.moros.bending.util.methods;

import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.user.User;
import me.moros.bending.model.user.player.BendingPlayer;
import org.apache.commons.math3.util.FastMath;
import org.bukkit.Particle;

public final class UserMethods {
	private static final Vector3 playerOffset = new Vector3(0, 1.2, 0);

	public static Vector3 getMainHandSide(User user) {
		Vector3 dir = user.getDirection().scalarMultiply(0.4);
		if (user instanceof BendingPlayer) {
			switch (((BendingPlayer) user).getEntity().getMainHand()) {
				case LEFT:
					return getLeftSide(user).add(playerOffset).add(dir);
				case RIGHT:
				default:
					return getRightSide(user).add(playerOffset).add(dir);
			}
		}
		return user.getEyeLocation().add(dir);
	}

	public static Vector3 getRightSide(User user) {
		double angle = FastMath.toRadians(user.getEntity().getLocation().getYaw());
		return user.getLocation().subtract(new Vector3(FastMath.cos(angle), 0, FastMath.sin(angle)).normalize().scalarMultiply(0.3));
	}

	public static Vector3 getLeftSide(User user) {
		double angle = FastMath.toRadians(user.getEntity().getLocation().getYaw());
		return user.getLocation().add(new Vector3(FastMath.cos(angle), 0, FastMath.sin(angle)).normalize().scalarMultiply(0.3));
	}

	public static Particle getFireParticles(User user) {
		return user.hasPermission("bending.fire.bluefire") ? Particle.SOUL_FIRE_FLAME : Particle.FLAME;
	}
}
