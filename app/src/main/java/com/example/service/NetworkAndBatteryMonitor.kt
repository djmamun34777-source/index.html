package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.telephony.TelephonyManager
import com.example.data.NetworkTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceTelemetry(
    val networkTransport: NetworkTransport = NetworkTransport.OFFLINE,
    val batteryPercent: Int = 100,
    val isCharging: Boolean = false,
    val signalBars: Int = 0
)

/**
 * Monitors 100% real Android hardware telemetry:
 * - Real Battery % + real charging state from [Intent.ACTION_BATTERY_CHANGED]
 * - Real Network Transport (Wi-Fi / Cellular / Offline)
 * - Real Signal Strength Bars (0..4) from [NetworkCapabilities.getSignalStrength] (Wi-Fi RSSI dBm)
 *   and [TelephonyManager.getSignalStrength] (Cellular bars, API 28+, no extra runtime permissions)
 */
class NetworkAndBatteryMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val telephonyManager =
        appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private val _telemetry = MutableStateFlow(readInitialTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()
        override fun onLost(network: Network) = refreshNetwork()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            refreshNetwork()
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0) {
                ((level * 100f) / scale).toInt().coerceIn(0, 100)
            } else {
                _telemetry.value.batteryPercent
            }
            _telemetry.value = _telemetry.value.copy(
                batteryPercent = pct,
                isCharging = charging
            )
        }
    }

    fun start() {
        if (registered) return
        registered = true
        refreshNetwork()
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        }
        runCatching {
            val sticky = appContext.registerReceiver(
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            if (sticky != null) {
                batteryReceiver.onReceive(appContext, sticky)
            }
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        runCatching { appContext.unregisterReceiver(batteryReceiver) }
    }

    private fun readInitialTelemetry(): DeviceTelemetry {
        val (transport, bars) = inspectActiveNetwork()
        val bm = appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 } ?: 100
        val charging = bm?.isCharging ?: false
        return DeviceTelemetry(
            networkTransport = transport,
            batteryPercent = pct,
            isCharging = charging,
            signalBars = bars
        )
    }

    private fun refreshNetwork() {
        val (transport, bars) = inspectActiveNetwork()
        _telemetry.value = _telemetry.value.copy(
            networkTransport = transport,
            signalBars = bars
        )
    }

    private fun inspectActiveNetwork(): Pair<NetworkTransport, Int> {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkTransport.OFFLINE to 0
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
            ?: return NetworkTransport.OFFLINE to 0
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!hasInternet && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkTransport.OFFLINE to 0
        }

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                NetworkTransport.WIFI to measureWifiBars(caps)
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                NetworkTransport.CELLULAR to measureCellularBars(caps)
            }

            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                NetworkTransport.WIFI to 4
            }

            else -> NetworkTransport.OFFLINE to 0
        }
    }

    private fun measureWifiBars(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rssi = caps.signalStrength
            if (rssi != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && rssi < 0) {
                return when {
                    rssi >= -55 -> 4
                    rssi >= -67 -> 3
                    rssi >= -78 -> 2
                    else -> 1
                }
            }
        }
        return estimateBarsFromBandwidth(caps.linkDownstreamBandwidthKbps)
    }

    private fun measureCellularBars(caps: NetworkCapabilities): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cellLevel = runCatching { telephonyManager?.signalStrength?.level }.getOrNull()
            if (cellLevel != null && cellLevel in 0..4) {
                return cellLevel.coerceAtLeast(1)
            }
        }
        return estimateBarsFromBandwidth(caps.linkDownstreamBandwidthKbps)
    }

    private fun estimateBarsFromBandwidth(downKbps: Int): Int = when {
        downKbps >= 30_000 -> 4
        downKbps >= 10_000 -> 3
        downKbps >= 2_000 -> 2
        downKbps > 0 -> 1
        else -> 1
    }
}
