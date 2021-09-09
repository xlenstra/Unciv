package com.unciv.logic.battle

import com.unciv.logic.map.TileInfo
import com.unciv.models.Counter
import com.unciv.ui.utils.toPercent
import java.util.*
import kotlin.collections.set
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class BattleDamageModifier(val vs:String, val modificationAmount:Float){
    fun getText(): String = "vs $vs"
}

object BattleDamage {

    private fun getGeneralModifiers(combatant: ICombatant, enemy: ICombatant): Counter<String> {
        val modifiers = Counter<String>()

        val civInfo = combatant.getCivInfo()
        if (combatant is MapUnitCombatant) {
            for (unique in combatant.getMatchingApplyingUniques("+[]% Strength vs []", civInfo)) {
                if (enemy.matchesCategory(unique.params[1]))
                    modifiers.add("vs [${unique.params[1]}]", unique.params[0].toInt())
            }
            for (unique in combatant.getMatchingApplyingUniques("-[]% Strength vs []", civInfo)) {
                if (enemy.matchesCategory(unique.params[1]))
                    modifiers.add("vs [${unique.params[1]}]", -unique.params[0].toInt())
            }

            for (unique in combatant.unit.getMatchingApplyingUniques("+[]% Combat Strength"))
                modifiers.add("Combat Strength", unique.params[0].toInt())

            //https://www.carlsguides.com/strategy/civilization5/war/combatbonuses.php
            val civHappiness = if (civInfo.isCityState() && civInfo.getAllyCiv() != null)
                // If we are a city state with an ally we are vulnerable to their unhappiness.
                min(civInfo.gameInfo.getCivilization(civInfo.getAllyCiv()!!).getHappiness(), civInfo.getHappiness())
                else civInfo.getHappiness()
            if (civHappiness < 0)
                modifiers["Unhappiness"] = max(
                    2 * civHappiness,
                    -90
                ) // otherwise it could exceed -100% and start healing enemy units...

            for (unique in civInfo.getMatchingApplyingUniques("[] units deal +[]% damage")) {
                if (combatant.matchesCategory(unique.params[0])) {
                    modifiers.add(unique.params[0], unique.params[1].toInt())
                }
            }

            val adjacentUnits = combatant.getTile().neighbors.flatMap { it.getUnits() }
            
            for (unique in civInfo.getMatchingApplyingUniques("[]% Strength for [] units which have another [] unit in an adjacent tile")) {
                if (combatant.matchesCategory(unique.params[1])
                    && adjacentUnits.any { it.civInfo == civInfo && it.matchesFilter(unique.params[2]) } 
                ) {
                    modifiers.add("Adjacent units", unique.params[0].toInt())
                }
            }
            
            for (unique in adjacentUnits
                .filter { it.civInfo.isAtWarWith(combatant.getCivInfo()) }
                .flatMap { it.getMatchingApplyingUniques("[]% Strength for enemy [] units in adjacent [] tiles", it.civInfo) }
            )
                if (combatant.matchesCategory(unique.params[1]) && combatant.getTile().matchesFilter(unique.params[2]))
                    modifiers.add("Adjacent enemy units", unique.params[0].toInt())

            val civResources = civInfo.getCivResourcesByName()
            for (resource in combatant.unit.baseUnit.getResourceRequirements().keys)
                if (civResources[resource]!! < 0 && !civInfo.isBarbarian())
                    modifiers["Missing resource"] = -25


            val nearbyCivUnits = combatant.unit.getTile().getTilesInDistance(2)
                .flatMap { it.getUnits() }.filter { it.civInfo == combatant.unit.civInfo }
            if (nearbyCivUnits.any { it.hasApplyingUnique("Bonus for units in 2 tile radius 15%") }) {
                val greatGeneralModifier =
                    if (combatant.unit.civInfo.hasApplyingUnique("Great General provides double combat bonus")) 30 else 15

                modifiers["Great General"] = greatGeneralModifier
            }

            for (unique in combatant.getMatchingApplyingUniques("[]% Strength when stacked with []", combatant.getCivInfo())) {
                var stackedUnitsBonus = 0
                if (combatant.unit.getTile().getUnits().any { it.matchesFilter(unique.params[1]) } )
                    stackedUnitsBonus += unique.params[0].toInt()

                if (stackedUnitsBonus > 0)
                    modifiers["Stacked with [${unique.params[1]}]"] = stackedUnitsBonus
            }

            if (civInfo.goldenAges.isGoldenAge() && civInfo.hasApplyingUnique("+10% Strength for all units during Golden Age"))
                modifiers["Golden Age"] = 10

            if (enemy.getCivInfo().isCityState() 
                && civInfo.hasApplyingUnique("+30% Strength when fighting City-State units and cities")
            )
                modifiers["vs [City-States]"] = 30
        }

        if (enemy.getCivInfo().isBarbarian()) {
            modifiers["Difficulty"] =
                (civInfo.gameInfo.getDifficulty().barbarianBonus * 100).toInt()
        }

        return modifiers
    }

    fun getAttackModifiers(
        attacker: ICombatant,
        defender: ICombatant
    ): Counter<String> {
        val modifiers = getGeneralModifiers(attacker, defender)

        if (attacker is MapUnitCombatant) {
            modifiers.add(getTileSpecificModifiers(attacker, defender.getTile()))

            for (unique in attacker.getMatchingApplyingUniques("+[]% Strength when attacking", attacker.getCivInfo())) {
                modifiers.add("Attacker Bonus", unique.params[0].toInt())
            }

            if (attacker.unit.isEmbarked() && !attacker.unit.hasApplyingUnique("Eliminates combat penalty for attacking from the sea"))
                modifiers["Landing"] = -50


            if (attacker.isMelee()) {
                val numberOfAttackersSurroundingDefender = defender.getTile().neighbors.count {
                    it.militaryUnit != null
                            && it.militaryUnit!!.owner == attacker.getCivInfo().civName
                            && MapUnitCombatant(it.militaryUnit!!).isMelee()
                }
                if (numberOfAttackersSurroundingDefender > 1) {
                    var flankingBonus = 10f //https://www.carlsguides.com/strategy/civilization5/war/combatbonuses.php
                    for (unique in attacker.getMatchingApplyingUniques("[]% to Flank Attack bonuses", attacker.getCivInfo()))
                        flankingBonus *= unique.params[0].toPercent()
                    modifiers["Flanking"] =
                        (flankingBonus * (numberOfAttackersSurroundingDefender - 1)).toInt()
                }
                if (attacker.getTile()
                        .aerialDistanceTo(defender.getTile()) == 1 && attacker.getTile()
                        .isConnectedByRiver(defender.getTile())
                    && !attacker.unit.hasApplyingUnique("Eliminates combat penalty for attacking over a river")
                ) {
                    if (!attacker.getTile()
                            .hasConnection(attacker.getCivInfo()) // meaning, the tiles are not road-connected for this civ
                        || !defender.getTile().hasConnection(attacker.getCivInfo())
                        || !attacker.getCivInfo().tech.roadsConnectAcrossRivers
                    ) {
                        modifiers["Across river"] = -20
                    }
                }
            }

            for (unique in attacker.getCivInfo().getMatchingApplyingUniques("+[]% attack strength to all [] units for [] turns")) {
                if (attacker.matchesCategory(unique.params[1])) {
                    modifiers.add("Temporary Bonus", unique.params[0].toInt())
                }
            }

            if (defender is CityCombatant &&
                attacker.getCivInfo()
                    .hasApplyingUnique("+15% Combat Strength for all units when attacking Cities")
            )
                modifiers["Statue of Zeus"] = 15
        } else if (attacker is CityCombatant) {
            if (attacker.city.getCenterTile().militaryUnit != null) {
                val garrisonBonus = attacker.city.getMatchingApplyingUniques("+[]% attacking strength for cities with garrisoned units")
                    .sumOf { it.params[0].toInt() }
                if (garrisonBonus != 0)
                    modifiers["Garrisoned unit"] = garrisonBonus
            }
            for (unique in attacker.city.getMatchingApplyingUniques("[]% attacking Strength for cities")) {
                modifiers.add("Attacking Bonus", unique.params[0].toInt())
            }
        }

        return modifiers
    }

    fun getDefenceModifiers(attacker: ICombatant, defender: ICombatant): Counter<String> {
        val modifiers = getGeneralModifiers(defender, attacker)
        val tile = defender.getTile()
    
        if (defender is MapUnitCombatant) {

            if (defender.unit.isEmbarked()) {
                // embarked units get no defensive modifiers apart from this unique
                if (defender.unit.hasApplyingUnique("Defense bonus when embarked") ||
                    defender.getCivInfo().hasApplyingUnique("Embarked units can defend themselves")
                )
                    modifiers["Embarked"] = 100

                return modifiers
            }

            modifiers.putAll(getTileSpecificModifiers(defender, tile))

            val tileDefenceBonus = tile.getDefensiveBonus()
            if (!defender.unit.hasApplyingUnique("No defensive terrain bonus"))
                modifiers["Tile"] = (tileDefenceBonus * 100).toInt()

            for (unique in defender.getMatchingApplyingUniques("[]% Strength when defending vs [] units", defender.getCivInfo())) {
                if (attacker.matchesCategory(unique.params[1]))
                    modifiers.add("defence vs [${unique.params[1]}] ", unique.params[0].toInt())
            }

            for (unique in defender.getMatchingApplyingUniques("+[]% Strength when defending")) {
                modifiers.add("Defender Bonus", unique.params[0].toInt())
            }

            for (unique in defender.getMatchingApplyingUniques("+[]% defence in [] tiles")) {
                if (tile.matchesFilter(unique.params[1]))
                    modifiers.add("[${unique.params[1]}] defence", unique.params[0].toInt())
            }

            if (defender.unit.isFortified())
                modifiers["Fortification"] = 20 * defender.unit.getFortificationTurns()
        } else if (defender is CityCombatant) {

            modifiers["Defensive Bonus"] =
                defender.city.civInfo.getMatchingApplyingUniques("+[]% Defensive strength for cities")
                    .map { it.params[0].toFloat() / 100f }.sum().toInt()
        }

        return modifiers
    }
    
    private fun getTileSpecificModifiers(unit: MapUnitCombatant, tile: TileInfo): Counter<String> {
        val modifiers = Counter<String>()

        for (unique in unit.getMatchingApplyingUniques("+[]% Strength in []")
                + unit.getCivInfo()
            // Deprecated since 3.16.7
                .getMatchingUniques("+[]% Strength for units fighting in []")) {
            //
            val filter = unique.params[1]
            if (tile.matchesFilter(filter, unit.getCivInfo()))
                modifiers.add(filter, unique.params[0].toInt())
        }

        for (unique in unit.getCivInfo().getMatchingApplyingUniques("+[]% Strength if within [] tiles of a []")) {
            if (tile.getTilesInDistance(unique.params[1].toInt())
                    .any { it.matchesFilter(unique.params[2]) }
            )
                modifiers[unique.params[2]] = unique.params[0].toInt()
        }
        for (unique in unit.getCivInfo().getMatchingApplyingUniques("[]% Strength for [] units in []")) {
            if (unit.matchesCategory(unique.params[1]) && tile.matchesFilter(unique.params[2], unit.getCivInfo())) {
                modifiers.add(unique.params[2], unique.params[0].toInt())
            }
        }
    
        return modifiers
    }

    private fun modifiersToMultiplicationBonus(modifiers: Counter<String>): Float {
        var finalModifier = 1f
        for (modifierValue in modifiers.values) finalModifier *= modifierValue.toPercent()
        return finalModifier
    }

    private fun getHealthDependantDamageRatio(combatant: ICombatant): Float {
        return if (combatant !is MapUnitCombatant // is city
            || combatant.getCivInfo()
                .hasApplyingUnique("Units fight as though they were at full strength even when damaged")
            && !combatant.unit.baseUnit.movesLikeAirUnits()
        )
            1f
        else 1 - (100 - combatant.getHealth()) / 300f// Each 3 points of health reduces damage dealt by 1% like original game
    }


    /**
     * Includes attack modifiers
     */
    private fun getAttackingStrength(
        attacker: ICombatant,
        tileToAttackFrom: TileInfo?,
        defender: ICombatant
    ): Float {
        val attackModifier = modifiersToMultiplicationBonus(getAttackModifiers(attacker, defender))
        return attacker.getAttackingStrength() * attackModifier
    }


    /**
     * Includes defence modifiers
     */
    private fun getDefendingStrength(attacker: ICombatant, defender: ICombatant): Float {
        val defenceModifier = modifiersToMultiplicationBonus(getDefenceModifiers(attacker, defender))
        return defender.getDefendingStrength() * defenceModifier
    }

    fun calculateDamageToAttacker(
        attacker: ICombatant,
        tileToAttackFrom: TileInfo?,
        defender: ICombatant
    ): Int {
        if (attacker.isRanged()) return 0
        if (defender.isCivilian()) return 0
        val ratio =
            getAttackingStrength(attacker, tileToAttackFrom, defender) / getDefendingStrength(
                attacker,
                defender
            )
        return (damageModifier(ratio, true) * getHealthDependantDamageRatio(defender)).roundToInt()
    }

    fun calculateDamageToDefender(
        attacker: ICombatant,
        tileToAttackFrom: TileInfo?,
        defender: ICombatant
    ): Int {
        val ratio =
            getAttackingStrength(attacker, tileToAttackFrom, defender) / getDefendingStrength(
                attacker,
                defender
            )
        return (damageModifier(ratio, false) * getHealthDependantDamageRatio(attacker)).roundToInt()
    }

    private fun damageModifier(attackerToDefenderRatio: Float, damageToAttacker: Boolean): Float {
        // https://forums.civfanatics.com/threads/getting-the-combat-damage-math.646582/#post-15468029
        val strongerToWeakerRatio =
            attackerToDefenderRatio.pow(if (attackerToDefenderRatio < 1) -1 else 1)
        var ratioModifier = (((strongerToWeakerRatio + 3) / 4).pow(4) + 1) / 2
        if (damageToAttacker && attackerToDefenderRatio > 1 || !damageToAttacker && attackerToDefenderRatio < 1) // damage ratio from the weaker party is inverted
            ratioModifier = ratioModifier.pow(-1)
        val randomCenteredAround30 = 24 + 12 * Random().nextFloat()
        return randomCenteredAround30 * ratioModifier
    }
}