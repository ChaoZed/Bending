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

package me.moros.bending.ability.common;

import me.moros.bending.model.ability.Ability;
import me.moros.bending.model.ability.Burstable;
import me.moros.bending.model.ability.UpdateResult;
import me.moros.bending.model.math.Vector3;
import me.moros.bending.model.user.User;
import org.apache.commons.math3.util.FastMath;

import java.util.ArrayList;
import java.util.List;

public abstract class BurstAbility implements Ability {
	protected final List<Burstable> blasts = new ArrayList<>();

	protected <T extends Burstable> void createBurst(User user, double thetaMin, double thetaMax, double thetaStep, double phiMin, double phiMax, double phiStep, Class<T> type) {
		for (double theta = thetaMin; theta < thetaMax; theta += thetaStep) {
			for (double phi = phiMin; phi < phiMax; phi += phiStep) {
				double x = FastMath.cos(phi) * FastMath.sin(theta);
				double y = FastMath.cos(phi) * FastMath.cos(theta);
				double z = FastMath.sin(phi);
				Vector3 direction = new Vector3(x, y, z);
				T blast;
				try {
					blast = type.getDeclaredConstructor().newInstance();
				} catch (ReflectiveOperationException e) {
					e.printStackTrace();
					return;
				}
				blast.initialize(user, user.getEyeLocation().add(direction), direction);
				blasts.add(blast);
			}
		}
	}

	protected <T extends Burstable> void createCone(User user, Class<T> type) {
		for (double theta = 0; theta < FastMath.PI; theta += FastMath.toRadians(10)) {
			for (double phi = 0; phi < FastMath.PI * 2; phi += FastMath.toRadians(10)) {
				double x = FastMath.cos(phi) * FastMath.sin(theta);
				double y = FastMath.cos(phi) * FastMath.cos(theta);
				double z = FastMath.sin(phi);
				Vector3 direction = new Vector3(x, y, z);
				if (Vector3.angle(direction, user.getDirection()) > FastMath.toRadians(30)) {
					continue;
				}
				T blast;
				try {
					blast = type.getDeclaredConstructor().newInstance();
				} catch (ReflectiveOperationException e) {
					e.printStackTrace();
					return;
				}
				blast.initialize(user, user.getEyeLocation().add(direction), direction);
				blasts.add(blast);
			}
		}
	}

	// Return false if all blasts are finished.
	protected UpdateResult updateBurst() {
		blasts.removeIf(b -> b.update() == UpdateResult.REMOVE);
		return blasts.isEmpty() ? UpdateResult.REMOVE : UpdateResult.CONTINUE;
	}

	protected void setRenderInterval(long interval) {
		blasts.forEach(b -> b.setRenderInterval(interval));
	}

	protected void setRenderParticleCount(int count) {
		blasts.forEach(b -> b.setRenderParticleCount(count));
	}
}
