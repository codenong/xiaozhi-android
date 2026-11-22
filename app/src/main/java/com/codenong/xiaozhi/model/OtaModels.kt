package com.codenong.xiaozhi.model

data class DeviceIdentity(
    val deviceId: String,
    val clientId: String,
    val otaUrl: String
)

data class WebSocketConfig(
    val url: String,
    val token: String,
    val version: Int = 1
)

data class ActivationInfo(
    val code: String,
    val message: String,
    val challenge: String
)

data class OtaResponse(
    val webSocket: WebSocketConfig?,
    val activation: ActivationInfo?
)
