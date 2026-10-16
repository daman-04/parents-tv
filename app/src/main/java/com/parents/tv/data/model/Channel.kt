package com.parents.tv.data.model

data class Stream(
    val url: String,
    val status: String = "working"
)

data class Channel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val category: String? = "Punjabi",
    val streams: List<Stream> = emptyList(),
    val aliases: List<String> = emptyList()
)
