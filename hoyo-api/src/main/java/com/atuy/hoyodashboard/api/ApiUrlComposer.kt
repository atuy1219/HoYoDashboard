package com.atuy.hoyodashboard.api

import java.net.URI
import java.net.URL
import java.net.URLEncoder

object ApiUrlComposer {
    fun compose(base: String, endpoint: String, params: Map<String, String>): URL {
        require(endpoint.isEmpty() || (
            !endpoint.startsWith("/") &&
            !endpoint.startsWith("http") &&
            endpoint.split('/').none { it == ".." || it == "." }
        )) { "Invalid API endpoint" }
        val origin = URI(base)
        val host = origin.host ?: throw IllegalArgumentException("Missing API host")
        require(origin.scheme == "https" && listOf(
            "hoyolab.com", "hoyoverse.com", "mihoyo.com", "miyoushe.com"
        ).any { host == it || host.endsWith("." + it) }) { "Unsafe API host" }
        val path = origin.rawPath.orEmpty().trimEnd('/') +
            if (endpoint.isEmpty()) "" else "/" + endpoint
        val query = buildList {
            origin.rawQuery?.takeIf { it.isNotBlank() }?.let { add(it) }
            params.forEach { (key, value) -> add(encode(key) + "=" + encode(value)) }
        }.joinToString("&")
        return URL("https://" + host + path + if (query.isEmpty()) "" else "?" + query)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
