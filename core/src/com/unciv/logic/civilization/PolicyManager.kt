package com.unciv.logic.civilization

import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.Unique
import com.unciv.models.ruleset.UniqueMap
import com.unciv.models.ruleset.UniqueTriggerActivation
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt


class PolicyManager {

    @Transient
    lateinit var civInfo: CivilizationInfo

    // Needs to be separate from the actual adopted policies, so that
    //  in different game versions, policies can have different effects
    @Transient
    internal val policyUniques = UniqueMap()

    var freePolicies = 0
    var storedCulture = 0
    internal val adoptedPolicies = HashSet<String>()
    var numberOfAdoptedPolicies = 0
    var shouldOpenPolicyPicker = false
        get() = field && canAdoptPolicy()

    private var cultureBuildingsAdded = HashMap<String, String>() // Maps cities to buildings
    private var specificBuildingsAdded = HashMap<String, MutableSet<String>>() // Maps buildings to cities

    @Deprecated("Deprecated since 3.15")
    var autocracyCompletedTurns = 0

    @Deprecated("Deprecated since 3.14.17") // Replaced with cultureBuildingsAdded
    var legalismState = HashMap<String, String>() // Maps cities to buildings


    fun clone(): PolicyManager {
        val toReturn = PolicyManager()
        toReturn.numberOfAdoptedPolicies = numberOfAdoptedPolicies
        toReturn.adoptedPolicies.addAll(adoptedPolicies)
        toReturn.freePolicies = freePolicies
        toReturn.shouldOpenPolicyPicker = shouldOpenPolicyPicker
        toReturn.storedCulture = storedCulture
        toReturn.cultureBuildingsAdded.putAll(cultureBuildingsAdded)
        toReturn.specificBuildingsAdded.putAll(specificBuildingsAdded)

        // Deprecated since 3.15 left for backwards compatibility
            toReturn.legalismState.putAll(cultureBuildingsAdded)
            toReturn.autocracyCompletedTurns = autocracyCompletedTurns
        //
        return toReturn
    }

    fun getPolicyByName(name: String): Policy = civInfo.gameInfo.ruleSet.policies[name]!!

    fun setTransients() {
        // Reassign policies deprecated in 3.14.17, left for backwards compatibility
            if (adoptedPolicies.contains("Patronage") && 
                !civInfo.gameInfo.ruleSet.policies.contains("Patronage")
            ) {
                adoptedPolicies.add("Merchant Navy")
                adoptedPolicies.remove("Patronage")
            }
            if (adoptedPolicies.contains("Entrepreneurship") &&
                !civInfo.gameInfo.ruleSet.policies.contains("Entrepreneurship")
            ) {
                adoptedPolicies.add("Naval Tradition")
                adoptedPolicies.remove("Entrepreneurship")
            }
        //
        for (policyName in adoptedPolicies)
            addPolicyToTransients(getPolicyByName(policyName))
        // Deprecated since 3.14.17, left for backwards compatibility
            if (cultureBuildingsAdded.isEmpty() && legalismState.isNotEmpty()) {
                cultureBuildingsAdded.putAll(legalismState)
            }
        //
        
        // Deprecated since 3.15.2, left for backwards compatibility
            if (autocracyCompletedTurns != 0) {
                civInfo.temporaryUniques.add(Pair(Unique("+[25]% attack strength to all [Military] units for [50] turns"), autocracyCompletedTurns))
                autocracyCompletedTurns = 0
            }
        //
    }

    fun addPolicyToTransients(policy: Policy) {
        for (unique in policy.uniqueObjects)
            policyUniques.addUnique(unique)
    }

    fun startTurn() {
        tryToAddPolicyBuildings()
    }

    fun addCulture(culture: Int) {
        val couldAdoptPolicyBefore = canAdoptPolicy()
        storedCulture += culture
        if (!couldAdoptPolicyBefore && canAdoptPolicy())
            shouldOpenPolicyPicker = true
    }

    fun endTurn(culture: Int) {
        addCulture(culture)
    }

    // from https://forums.civfanatics.com/threads/the-number-crunching-thread.389702/
    // round down to nearest 5
    fun getCultureNeededForNextPolicy(): Int {
        var policyCultureCost = 25 + (numberOfAdoptedPolicies * 6).toDouble().pow(1.7)
        var cityModifier = 0.3f * (civInfo.cities.count { !it.isPuppet } - 1)

        for (unique in civInfo.getMatchingUniques("Each city founded increases culture cost of policies []% less than normal"))
            cityModifier *= 1 - unique.params[0].toFloat() / 100
        for (unique in civInfo.getMatchingUniques("Culture cost of adopting new Policies reduced by []%"))
            policyCultureCost *= 1 - unique.params[0].toFloat() / 100
        if (civInfo.isPlayerCivilization())
            policyCultureCost *= civInfo.getDifficulty().policyCostModifier
        policyCultureCost *= civInfo.gameInfo.gameParameters.gameSpeed.modifier
        val cost: Int = (policyCultureCost * (1 + cityModifier)).roundToInt()
        return cost - (cost % 5)
    }

    fun getAdoptedPolicies(): HashSet<String> = adoptedPolicies

    fun isAdopted(policyName: String): Boolean = adoptedPolicies.contains(policyName)

    fun isAdoptable(policy: Policy): Boolean {
        if (isAdopted(policy.name)) return false
        if (policy.name.endsWith("Complete")) return false
        if (!getAdoptedPolicies().containsAll(policy.requires!!)) return false
        if (civInfo.gameInfo.ruleSet.getEraNumber(policy.branch.era) > civInfo.getEraNumber()) return false
        if (policy.uniqueObjects.any { it.placeholderText == "Incompatible with []" && adoptedPolicies.contains(it.params[0]) }) return false
        return true
    }

    fun canAdoptPolicy(): Boolean {
        if (freePolicies == 0 && storedCulture < getCultureNeededForNextPolicy())
            return false

        val hasAdoptablePolicies = civInfo.gameInfo.ruleSet.policies.values
                .any { civInfo.policies.isAdoptable(it) }
        return hasAdoptablePolicies
    }

    fun adopt(policy: Policy, branchCompletion: Boolean = false) {

        if (!branchCompletion) {
            if (freePolicies > 0) freePolicies--
            else if (!civInfo.gameInfo.gameParameters.godMode) {
                val cultureNeededForNextPolicy = getCultureNeededForNextPolicy()
                if (cultureNeededForNextPolicy > storedCulture)
                    throw Exception("How is this possible??????")
                storedCulture -= cultureNeededForNextPolicy
                numberOfAdoptedPolicies++
            }
        }

        adoptedPolicies.add(policy.name)
        addPolicyToTransients(policy)

        if (!branchCompletion) {
            val branch = policy.branch
            if (branch.policies.count { isAdopted(it.name) } == branch.policies.size - 1) { // All done apart from branch completion
                adopt(branch.policies.last(), true) // add branch completion!
            }
        }

        for (unique in policy.uniqueObjects)
            UniqueTriggerActivation.triggerCivwideUnique(unique, civInfo)

        tryToAddPolicyBuildings()

        // This ALSO has the side-effect of updating the CivInfo statForNextTurn so we don't need to call it explicitly
        for (cityInfo in civInfo.cities)
            cityInfo.cityStats.update()

        if (!canAdoptPolicy()) shouldOpenPolicyPicker = false
    }

    fun tryToAddPolicyBuildings() {
        tryAddCultureBuildings()
        tryAddFreeBuildings()
    }

    private fun tryAddCultureBuildings() {
        val cultureBuildingUniques = civInfo.getMatchingUniques("Immediately creates the cheapest available cultural building in each of your first [] cities for free")
        val citiesToReceiveCultureBuilding = cultureBuildingUniques.sumOf { it.params[0].toInt() }
        if (!cultureBuildingUniques.any()) return
        if (cultureBuildingsAdded.size >= citiesToReceiveCultureBuilding) return

        val candidateCities = civInfo.cities
                .sortedBy { it.turnAcquired }
                .subList(0, min(citiesToReceiveCultureBuilding, civInfo.cities.size))
                .filter {
                    it.id !in cultureBuildingsAdded
                            && it.cityConstructions.hasBuildableCultureBuilding()
                }
        for (city in candidateCities) {
            val builtBuilding = city.cityConstructions.addCultureBuilding()
            if (builtBuilding != null) cultureBuildingsAdded[city.id] = builtBuilding!!

        }
    }

    private fun tryAddFreeBuildings() {
        val matchingUniques = civInfo.getMatchingUniques("Immediately creates a [] in each of your first [] cities for free")
        // If we have "create a free aqueduct in first 3 cities" and "create free aqueduct in first 4 cities", we do: "create free aqueduct in first 3+4=7 cities"
        val sortedUniques = matchingUniques.groupBy {it.params[0]}
        for (unique in sortedUniques) {
            tryAddSpecificBuilding(unique.key, unique.value.sumBy {it.params[1].toInt()})
        }
    }

    private fun tryAddSpecificBuilding(building: String, cityCount: Int) {
        if (specificBuildingsAdded[building] == null) specificBuildingsAdded[building] = mutableSetOf()
        val citiesAlreadyGivenBuilding = specificBuildingsAdded[building]
        if (citiesAlreadyGivenBuilding!!.size >= cityCount) return
        val candidateCities = civInfo.cities
            .sortedBy { it.turnAcquired }
            .subList(0, min(cityCount, civInfo.cities.size))
            .filter {
                it.id !in citiesAlreadyGivenBuilding && !it.cityConstructions.containsBuildingOrEquivalent(building)
            }

        for (city in candidateCities) {
            city.cityConstructions.getConstruction(building).postBuildEvent(city.cityConstructions, false)
            citiesAlreadyGivenBuilding.add(city.id)
        }
    }

    fun getListOfFreeBuildings(cityId: String): MutableSet<String> {
        val freeBuildings = cultureBuildingsAdded.filter { it.key == cityId }.values.toMutableSet()
        for (building in specificBuildingsAdded.filter { it.value.contains(cityId) }) {
            freeBuildings.add(building.key)
        }
        return freeBuildings
    }
}
