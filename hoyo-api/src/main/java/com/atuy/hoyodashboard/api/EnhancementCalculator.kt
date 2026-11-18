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

data class OwnedEnhancementTargets(
    val characterLevel: Int? = null,
    val weaponLevel: Int? = null,
    val artifactLevels: Map<Int, Int> = emptyMap(),
    val talentLevels: Map<Int, Int> = emptyMap()
)

data class RequiredMaterial(val id: Long, val name: String, val count: Int, val missing: Int, val icon: String?)
data class EnhancementMaterials(
    val character: List<RequiredMaterial>,
    val weapon: List<RequiredMaterial>,
    val artifacts: Map<Int, List<RequiredMaterial>>,
    val talents: Map<Int, List<RequiredMaterial>>,
    val raw: JSONObject
)

class EnhancementCalculator(private val features: UpstreamFeatureClient, private val language: String? = null) {
    private val locale get() = language?.let { mapOf("lang" to it) } ?: emptyMap()

    suspend fun calculate(plan: EnhancementPlan): JSONObject {
        val payload = plan.payload()
        val args = payload.keys().asSequence().associateWith { payload.get(it).toString() }
        return features.raw("calculate_enhancement", args + locale)
    }

    suspend fun calculateBatch(plans: List<EnhancementPlan>): JSONObject {
        require(plans.isNotEmpty()) { "育成対象を追加してください" }
        return features.raw("calculate_batch_enhancement",
            mapOf("items" to JSONArray(plans.map(EnhancementPlan::payload)).toString()) + locale)
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
            val blueprint = features.raw("get_teapot_replica_blueprint", args + locale).optJSONArray("list") ?: JSONArray()
            for (index in 0 until blueprint.length()) {
                val item = blueprint.optJSONObject(index) ?: continue
                val id = item.optInt("id")
                if (id > 0) amounts[id] = item.optInt("num", 1).coerceAtLeast(1)
            }
        }
        require(amounts.isNotEmpty()) { "調度品を追加してください" }
        val items = amounts.map { (id, amount) -> JSONObject().put("id", id).put("cnt", amount) }
        return features.raw("calculate_furnishings", mapOf("list" to JSONArray(items).toString()) + locale)
    }

    suspend fun artifactSet(artifactId: Int, current: Int, target: Int): List<LevelUpgrade> {
        val response = features.raw("get_complete_artifact_set", locale + ("artifact" to artifactId.toString()))
        val ids = listOf(artifactId) + response.optJSONArray("list").objects().map { it.getInt("id") }
        return ids.distinct().map { LevelUpgrade(it, current, target) }
    }

    suspend fun ownedPlan(characterId: Int, targets: OwnedEnhancementTargets, element: Int? = null): EnhancementPlan {
        val response = features.raw("get_character_details", locale + ("character" to characterId.toString()))
        fun upgrade(item: JSONObject?, target: Int?, idKey: String = "id"): LevelUpgrade? {
            if (item == null || target == null) return null
            val current = item.optInt("level_current", item.optInt("level"))
            val id = item.optInt(idKey)
            return if (id > 0 && target > current) LevelUpgrade(id, current, target) else null
        }
        val avatar = if (targets.characterLevel != null) {
            features.raw("get_calculator_characters", locale + ("sync" to "true")).optJSONArray("list").objects()
                .firstOrNull { it.optInt("id") == characterId }
                ?: throw HoYoApiException("所持キャラクター一覧に対象が見つかりません")
        } else null
        val weapon = upgrade(response.optJSONObject("weapon"), targets.weaponLevel)
        val artifacts = response.optJSONArray("reliquary_list").objects().mapNotNull {
            upgrade(it, targets.artifactLevels[it.optInt("reliquary_cat_id", it.optInt("pos"))])
        }
        val talents = response.optJSONArray("skill_list").objects().mapNotNull {
            upgrade(it, targets.talentLevels[it.optInt("group_id")], "group_id")
        }
        return EnhancementPlan(upgrade(avatar, targets.characterLevel), weapon, artifacts, talents, element)
    }

    suspend fun materials(plan: EnhancementPlan): EnhancementMaterials {
        val output = calculate(plan)
        fun materials(rows: JSONArray?): List<RequiredMaterial> = rows.objects().map {
            RequiredMaterial(it.optLong("id"), it.optString("name"), it.optInt("num", 1), it.optInt("lack_num"),
                it.optString("icon").ifBlank { null })
        }
        return EnhancementMaterials(
            materials(output.optJSONArray("avatar_consume")),
            materials(output.optJSONArray("weapon_consume")),
            output.optJSONArray("reliquary_consume").objects().associate {
                it.optInt("reliquary_id") to materials(it.optJSONArray("id_consume_list"))
            },
            output.optJSONArray("skills_consume").objects().associate {
                (it.optJSONObject("skill_info")?.optInt("id") ?: 0) to materials(it.optJSONArray("consume_list"))
            },
            output
        )
    }
}
