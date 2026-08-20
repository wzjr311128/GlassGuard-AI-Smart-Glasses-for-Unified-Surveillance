package com.example.parking.config

import android.content.Context
import java.util.Properties

object AddressConfig {
    private const val CONFIG_FILE = "address.properties"
    private const val WS_PATH = "/ws/parking"

    fun getWebSocketUrl(context: Context): String {
        return "ws://${getHost(context)}:${getPort(context)}$WS_PATH"
    }

    private fun loadProperties(context: Context): Properties {
        val properties = Properties()
        context.assets.open(CONFIG_FILE).use { input ->
            properties.load(input)
        }
        return properties
    }

    private fun getHost(context: Context): String {
        return loadProperties(context).getProperty("ws.host")
            ?: throw IllegalStateException("ws.host is missing in $CONFIG_FILE")
    }

    private fun getPort(context: Context): String {
        return loadProperties(context).getProperty("ws.port")
            ?: throw IllegalStateException("ws.port is missing in $CONFIG_FILE")
    }
}
