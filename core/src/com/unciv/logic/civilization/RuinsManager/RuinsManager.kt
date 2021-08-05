package com.unciv.logic.civilization.RuinsManager

import com.unciv.logic.civilization.CivilizationInfo
import com.unciv.logic.map.MapUnit
import com.unciv.models.ruleset.RuinReward
import com.unciv.models.ruleset.UniqueTriggerActivation
import kotlin.random.Random

class RuinsManager {
    var lastChosenRewards: MutableList<String> = mutableListOf("", "")
    private fun rememberReward(reward: String) {
        lastChosenRewards[0] = lastChosenRewards[1]
        lastChosenRewards[1] = reward
    }
    
    @Transient
    lateinit var civInfo: CivilizationInfo
    @Transient
    lateinit var validRewards: List<RuinReward> 
    
    fun clone(): RuinsManager {
        val toReturn = RuinsManager()
        toReturn.lastChosenRewards = lastChosenRewards
        return toReturn
    }
    
    fun setTransients(civInfo: CivilizationInfo) {
        this.civInfo = civInfo
        validRewards = civInfo.gameInfo.ruleSet.ruinRewards.values.toList()
        println("Rewards:" + validRewards.map { it.name })
    }
    
    fun selectNextRuinsReward(triggeringUnit: MapUnit) {
        val tileBasedRandom = Random(triggeringUnit.getTile().position.toString().hashCode())
        val possibleRewards = validRewards.filter { it.name !in lastChosenRewards }.shuffled(tileBasedRandom)
        for (possibleReward in possibleRewards) {
            if (civInfo.gameInfo.difficulty in possibleReward.excludedDifficulties) continue
            if ("Hidden when religion is disabled" in possibleReward.uniques && !civInfo.gameInfo.hasReligionEnabled()) continue
            if ("Hidden after generating a Great Prophet" in possibleReward.uniques && civInfo.religionManager.greatProphetsEarned > 0) continue
            if (possibleReward.uniqueObjects.any { unique ->
                unique.placeholderText == "Only available after [] turns" 
                && unique.params[0].toInt() < civInfo.gameInfo.turns
            }) continue
            
            var atLeastOneUniqueHadEffect = false
            for (unique in possibleReward.uniqueObjects) {
                atLeastOneUniqueHadEffect = 
                    atLeastOneUniqueHadEffect 
                    || UniqueTriggerActivation.triggerCivwideUnique(unique, civInfo, tile = triggeringUnit.getTile(), notification = possibleReward.notification)
                    || UniqueTriggerActivation.triggerUnitwideUnique(unique, triggeringUnit, notification = possibleReward.notification)
            }
            if (atLeastOneUniqueHadEffect) {
                rememberReward(possibleReward.name)
                break
            }
        }
    }
}
