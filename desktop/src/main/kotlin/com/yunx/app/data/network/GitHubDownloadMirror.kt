package com.yunx.app.data.network

object GitHubDownloadMirror {
    const val DEFAULT_PREFIX = "https://cdn.gh-proxy.org/"
    fun url(url: String, prefix: String) = prefix + url
}
