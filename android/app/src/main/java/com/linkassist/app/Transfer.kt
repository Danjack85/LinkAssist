package com.linkassist.app

data class Transfer(
    val id: String,
    val name: String,
    val size: Long,
    val status: String,
    val progress: Int = 0,
    val error: String = "",
    val downloadUrl: String? = null,
    val ts: Long = System.currentTimeMillis(),
)
