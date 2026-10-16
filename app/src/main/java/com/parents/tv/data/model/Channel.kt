package com.parents.tv.data.model

data class Stream(
    val url: String,
    val status: String = "working"
)

data class Channel(
    val id: String,
    val name: String,
    val logo: String? = null,
    val category: String? = "General",
    val languages: List<String> = emptyList(),
    val isPunjabi: Boolean = false,
    val streams: List<Stream> = emptyList(),
    val aliases: List<String> = emptyList(),
    var isNew: Boolean = false,
    var failureCount: Int = 0
)

data class ChannelSection(
    val id: String,
    val title: String,
    val channels: List<Channel>
)

data class ChannelFeed(
    val generated: String,
    val defaultChannel: String?,
    val totalChannels: Int,
    val sections: List<ChannelSection>
)
