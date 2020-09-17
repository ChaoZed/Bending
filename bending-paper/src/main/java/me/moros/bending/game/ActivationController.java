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

package me.moros.bending.game;

import me.moros.bending.ability.air.passives.*;
import me.moros.bending.ability.fire.*;
import me.moros.bending.model.Element;
import me.moros.bending.model.ability.Ability;
import me.moros.bending.model.ability.ActivationMethod;
import me.moros.bending.model.ability.description.AbilityDescription;
import me.moros.bending.model.ability.sequence.Action;
import me.moros.bending.model.user.User;
import me.moros.bending.model.user.player.BendingPlayer;
import me.moros.bending.util.Flight;
import me.moros.bending.util.methods.WorldMethods;
import org.bukkit.util.Vector;

// TODO remove comments once i re add the code
public final class ActivationController {
	public boolean activateAbility(User user, ActivationMethod method) {
		AbilityDescription desc = user.getSelectedAbility().orElse(null);
		if (desc == null || !desc.isActivatedBy(method) || !user.canBend(desc)) return false;
		Ability ability = desc.createAbility();
		if (ability.activate(user, method)) {
			Game.addAbility(user, ability);
			return true;
		}
		return false;
	}

	public void onPlayerLogout(BendingPlayer player) {
		player.removeLastSlotContainer();
		Game.getAttributeSystem().clearModifiers(player);

		Game.getStorage().savePlayerAsync(player);
		Flight.remove(player);

		Game.getPlayerManager().invalidatePlayer(player);
		Game.getAbilityInstanceManager(player.getWorld()).clearPassives(player);
	}

	public void onUserSwing(User user) {
        /*if (Game.getAbilityInstanceManager().destroyInstanceType(user, AirScooter.class)) {
            if (user.getSelectedAbility().orElse(null) == Game.getAbilityRegistry().getAbilityByName("AirScooter")) {
                return;
            }
        }

        if (user.getSelectedAbility().orElse(null) == Game.getAbilityRegistry().getAbilityByName("FireJet")) {
            if (Game.getAbilityInstanceManager().destroyInstanceType(user, FireJet.class)) {
                return;
            }

            if (Game.getAbilityInstanceManager().destroyInstanceType(user, JetBlast.class)) {
                return;
            }

            if (Game.getAbilityInstanceManager().destroyInstanceType(user, JetBlaze.class)) {
                return;
            }
        }*/

		//Combustion.combust(user);
		//FireBurst.activateCone(user);
		//AirBurst.activateCone(user);

		if (WorldMethods.getTargetEntity(user, 4).isPresent()) {
			Game.getSequenceManager().registerAction(user, Action.PUNCH_ENTITY);
		} else {
			Game.getSequenceManager().registerAction(user, Action.PUNCH);
		}

		activateAbility(user, ActivationMethod.PUNCH);
	}

	public void onUserSneak(User user, boolean sneaking) {
		Game.getSequenceManager().registerAction(user, sneaking ? Action.SNEAK : Action.SNEAK_RELEASE);

		if (!sneaking) return;

		activateAbility(user, ActivationMethod.SNEAK);

		//Game.getAbilityInstanceManager().destroyInstanceType(user, AirScooter.class);
	}

	public void onUserMove(User user, Vector velocity) {
		//AirSpout.handleMovement(user, velocity);
		//WaterSpout.handleMovement(user, velocity);
	}

	public boolean onFallDamage(User user) {
		activateAbility(user, ActivationMethod.FALL);

		if (user.hasElement(Element.AIR) && GracefulDescent.isGraceful(user)) {
			return false;
		}

        /*if (user.hasElement(Element.EARTH) && DensityShift.isSoftened(user)) {
            Block block = user.getLocation().getBlock().getRelative(BlockFace.DOWN);
            Location location = block.getLocation().add(0.5, 0.5, 0.5);
            DensityShift.softenArea(user, location);
            return false;
        }*/

		return !Flight.hasFlight(user);
	}

	public void onUserInteract(User user, boolean rightClickAir) {
		if (rightClickAir) {
			Game.getSequenceManager().registerAction(user, Action.INTERACT);
		} else {
			Game.getSequenceManager().registerAction(user, Action.INTERACT_BLOCK);
			activateAbility(user, ActivationMethod.USE);
		}
	}

	public void onUserInteractEntity(User user) {
		Game.getSequenceManager().registerAction(user, Action.INTERACT_ENTITY);
	}

	public boolean onFireTickDamage(User user) {
		return HeatControl.canBurn(user);
	}
}
