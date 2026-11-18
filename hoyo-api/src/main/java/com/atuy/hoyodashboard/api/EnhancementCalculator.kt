package com.atuy.hoyodashboard.api

import org.json.JSONArray
import org.json.JSONObject

data class LevelUpgrade(val id: Int, val current: Int, val target: Int) {
    init {
        require(id > 0) { "対象を選択してください" }
        require(current >= 0 && target > current) { "目標レベルは現在のレベルより上にしてください" }
    }

    internal fun payload() = JSONObject().put("id", id)
        .put("level_current", current).put("level_target", target)
}

data class EnhancementPlan(
    val character: LevelUpgrade? = null,
    val weapon: LevelUpgrade? = null,
    val artifacts: List<LevelUpgrade> = emptyList(),
    val talents: List<LevelUpgrade> = emptyList(),
    val element: Int? = null
) {
    init {
        require(character != null || weapon != null || artifacts.isNotEmpty() || talents.isNotEmpty()) {
            "育成対象を追加してください"
        }
    }

    internal fun payload() = JSONObject().apply {
        character?.let {
            put("avatar_id", it.id)
            put("avatar_level_current", it.current)
            put("avatar_level_target", it.target)
            element?.let { id -> put("element_attr_id", id) }
        }
        weapon?.let { put("weapon", it.payload()) }
        if (artifacts.isNotEmpty()) put("reliquary_list", JSONArray(artifacts.map(LevelUpgrade::payload)))
        if (talents.isNotEmpty()) put("skill_list", JSONArray(talents.map(LevelUpgrade::payload)))
    }
}

data class FurnishingQuantity(val id: Int, val amount: Int = 1) {
    init {
        require(id > 0 && amount > 0) { "調度品と個数を指定してください" }
    }
}

class EnhancementCalculator(private val features: UpstreamFeatureClient) {
    suspend fun calculate(plan: EnhancementPlan): JSONObject {
        val payload = plan.payload()
        val args = payload.keys().asSequence().associateWith { payload.get(it).toString() }
        return features.raw("calculate_enhancement", args)
    }

    suspend fun calculateBatch(plans: List<EnhancementPlan>): JSONObject {
        require(plans.isNotEmpty()) { "育成対象を追加してください" }
        return features.raw("calculate_batch_enhancement",
            mapOf("items" to JSONArray(plans.map(EnhancementPlan::payload)).toString()))
    }

    suspend fun calculateFurnishings(
        furnishings: List<FurnishingQuantity> = emptyList(),
        replicaCode: Long? = null,
        region: String? = null
    ): JSONObject {
        val amounts = furnishings.groupingBy { it.id }.fold(0) { total, item -> total + item.amount }.toMutableMap()
        if (replicaCode != null) {
            require(replicaCode > 0) { "洞天コピー番号を入力してください" }
            val args = buildMap {
                put("share_code", replicaCode.toString())
                region?.let { put("region", it) }
            }
            val blueprint = features.raw("get_teapot_replica_blueprint", args).optJSONArray("list") ?: JSONArray()
            for (index in 0 until blueprint.length()) {
                val item = blueprint.optJSONObject(index) ?: continue
                val id = item.optInt("id")
                if (id > 0) amounts[id] = item.optInt("num", 1).coerceAtLeast(1)
            }
        }
        require(amounts.isNotEmpty()) { "調度品を追加してください" }
        val items = amounts.map { (id, amount) -> JSONObject().put("id", id).put("cnt", amount) }
        return features.raw("calculate_furnishings", mapOf("list" to JSONArray(items).toString()))
    }
}
