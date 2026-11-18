package com.atuy.hoyodashboard.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

data class PageSpec(
    val cursorParameter: String = "end_id",
    val cursorField: String = "next_end_id",
    val itemsField: String = "list",
    val moreField: String = "has_more",
    val pageSize: Int? = null,
    val lastItemCursorField: String? = null,
    val maxPages: Int = 100,
    val pauseMilliseconds: Long = 250
)

class UpstreamPaginator(private val client: UpstreamApiClient) {
    fun pages(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String> = emptyMap(),
        spec: PageSpec = PageSpec()
    ): Flow<JSONObject> = flow {
        require(spec.maxPages in 1..1000)
        require(spec.pauseMilliseconds in 0..60_000)
        var cursor: String? = arguments[spec.cursorParameter]
        var previousCursor: String? = null
        var page = 0
        do {
            val query = arguments.toMutableMap()
            if (!cursor.isNullOrEmpty()) query[spec.cursorParameter] = cursor
            if (spec.cursorParameter in setOf("page", "current_page") && cursor.isNullOrEmpty()) {
                query[spec.cursorParameter] = "1"
            }
            val response = client.call(cookie, account, method, query)
            emit(response)
            page++

            val rows = response.optJSONArray(spec.itemsField) ?: JSONArray()
            val responseCursor = response.optString(spec.cursorField).ifEmpty {
                response.optString("next_page_token").ifEmpty {
                    response.optString("next_max_id")
                }
            }
            val lastItemCursor = spec.lastItemCursorField?.let { field ->
                rows.optJSONObject(rows.length() - 1)?.opt(field)?.toString().orEmpty()
            }.orEmpty()
            val nextCursor = responseCursor.ifBlank { lastItemCursor }
            val isPageNumber = spec.cursorParameter == "page" || spec.cursorParameter == "current_page"
            val hasMore = when {
                response.has(spec.moreField) -> response.optBoolean(spec.moreField)
                response.has("is_end") -> !response.optBoolean("is_end")
                isPageNumber -> {
                    val size = spec.pageSize
                    if (size != null) rows.length() >= size else rows.length() > 0
                }
                spec.pageSize != null -> rows.length() >= spec.pageSize
                else -> nextCursor.isNotBlank()
            }
            previousCursor = cursor
            cursor = if (isPageNumber) {
                (query[spec.cursorParameter]?.toIntOrNull() ?: 1).plus(1).toString()
            } else nextCursor
            if (!hasMore || cursor.isNullOrBlank() || cursor == previousCursor) break
            if (page < spec.maxPages) delay(spec.pauseMilliseconds)
        } while (page < spec.maxPages)
    }

    fun items(
        cookie: String,
        account: ApiAccount,
        method: ApiMethod,
        arguments: Map<String, String> = emptyMap(),
        spec: PageSpec = PageSpec()
    ): Flow<JSONObject> = flow {
        pages(cookie, account, method, arguments, spec).collect { page ->
            val list = page.optJSONArray(spec.itemsField) ?: JSONArray()
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i)
                if (item != null) emit(item)
            }
        }
    }
}
