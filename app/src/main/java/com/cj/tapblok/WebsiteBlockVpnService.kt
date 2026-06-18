package com.cj.tapblok

import app.untethered.BuildConfig
import app.untethered.R

import android.app.PendingIntent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cj.tapblok.database.AppDatabase
import com.cj.tapblok.database.BlockMode
import com.cj.tapblok.database.BlockedWebsite
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class WebsiteVpnStartStatus {
    ALREADY_RUNNING,
    STARTING,
    DISABLED,
    NEEDS_PERMISSION,
    EXTERNAL_VPN_ACTIVE,
    FAILED
}

class WebsiteBlockVpnService : VpnService() {
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private lateinit var db: AppDatabase
    private var vpnInterface: ParcelFileDescriptor? = null
    private var observeJob: Job? = null
    private var packetJob: Job? = null

    @Volatile
    private var blockedWebsites: List<BlockedWebsite> = emptyList()

    companion object {
        private const val TAG = "WebsiteBlockVpnService"
        private const val NOTIFICATION_ID = 2
        private const val VPN_ADDRESS = "10.111.0.2"
        private const val VPN_DNS_ADDRESS = "10.111.0.1"
        private const val VPN_ADDRESS_V6 = "fd00:111:111::2"
        private const val VPN_DNS_ADDRESS_V6 = "fd00:111:111::1"
        private const val PREFS = "app_prefs"
        private const val KEY_WEBSITE_BLOCKING_ENABLED = "website_blocking_enabled"
        private val FALLBACK_UPSTREAM_DNS = listOf("1.1.1.1", "8.8.8.8")

        @Volatile
        var isRunning = false

        @Volatile
        private var isStarting = false

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_WEBSITE_BLOCKING_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_WEBSITE_BLOCKING_ENABLED, enabled)
                .apply()
            if (!enabled) stop(context)
        }

        fun hasExternalVpn(context: Context): Boolean {
            if (isRunning || isStarting) return false
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            return connectivityManager.allNetworks.any { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
        }

        fun startIfAllowed(context: Context): WebsiteVpnStartStatus {
            if (isRunning || isStarting) return WebsiteVpnStartStatus.ALREADY_RUNNING
            if (!isEnabled(context)) return WebsiteVpnStartStatus.DISABLED
            if (prepare(context) != null) return WebsiteVpnStartStatus.NEEDS_PERMISSION
            if (hasExternalVpn(context)) return WebsiteVpnStartStatus.EXTERNAL_VPN_ACTIVE
            val intent = Intent(context, WebsiteBlockVpnService::class.java)
            try {
                isStarting = true
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                return WebsiteVpnStartStatus.STARTING
            } catch (exception: RuntimeException) {
                isStarting = false
                Log.w(TAG, "Unable to start website blocking VPN.", exception)
                return WebsiteVpnStartStatus.FAILED
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WebsiteBlockVpnService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.getDatabase(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        observeBlockedDomains()
        startVpnLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isStarting = false
        packetJob?.cancel()
        vpnInterface?.close()
        serviceScope.cancel()
    }

    private fun notification() = NotificationCompat.Builder(this, AppMonitoringService.CHANNEL_ID)
        .setContentTitle("Untethered Website Blocking")
        .setContentText("Website domains are blocked during this session.")
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, WebsiteRulesActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .build()

    private fun observeBlockedDomains() {
        if (observeJob != null) return
        observeJob = serviceScope.launch {
            db.blockedWebsiteDao()
                .getAllBlockedWebsites()
                .distinctUntilChanged()
                .collect { websites ->
                    blockedWebsites = websites
                    if (activeBlockedDomains().isEmpty()) {
                        stopSelf()
                    }
                }
        }
    }

    private fun startVpnLoop() {
        if (packetJob != null) return

        vpnInterface = Builder()
            .setSession("Untethered Website Blocking")
            .setMtu(1500)
            .addAddress(VPN_ADDRESS, 32)
            .addAddress(VPN_ADDRESS_V6, 128)
            .addDnsServer(VPN_DNS_ADDRESS)
            .addDnsServer(VPN_DNS_ADDRESS_V6)
            .addRoute(VPN_DNS_ADDRESS, 32)
            .addRoute(VPN_DNS_ADDRESS_V6, 128)
            .establish()

        val descriptor = vpnInterface ?: run {
            isRunning = false
            isStarting = false
            stopSelf()
            return
        }

        isRunning = true
        isStarting = false

        packetJob = serviceScope.launch {
            val input = FileInputStream(descriptor.fileDescriptor)
            val output = FileOutputStream(descriptor.fileDescriptor)
            val packet = ByteArray(32767)

            try {
                while (true) {
                    val length = try {
                        input.read(packet)
                    } catch (exception: Exception) {
                        Log.w(TAG, "VPN packet read failed.", exception)
                        break
                    }
                    if (length < 0) break
                    if (length == 0) continue

                    val queryV4 = DnsPacket.parseUdpDnsQuery(packet, length)
                    val queryV6 = if (queryV4 == null) DnsPacket.parseUdpDnsQueryV6(packet, length) else null
                    if (queryV4 == null && queryV6 == null) {
                        DnsPacket.buildTcpDnsResetPacket(packet, length)?.let { resetPacket ->
                            runCatching { output.write(resetPacket) }
                                .onFailure { Log.w(TAG, "VPN TCP reset write failed.", it) }
                        }
                        continue
                    }

                    val response = if (queryV4 != null) {
                        if (WebsiteDomainRules.isBlocked(queryV4.questionDomain, activeBlockedDomains())) {
                            DnsPacket.buildBlockedResponsePacket(packet, length)
                        } else {
                            val upstreamPayload = forwardDnsQuery(queryV4.dnsPayload)
                            if (upstreamPayload != null) {
                                DnsPacket.buildAllowedResponsePacket(packet, length, upstreamPayload)
                            } else {
                                DnsPacket.buildServerFailureResponsePacket(packet, length)
                            }
                        }
                    } else {
                        checkNotNull(queryV6)
                        if (WebsiteDomainRules.isBlocked(queryV6.questionDomain, activeBlockedDomains())) {
                            DnsPacket.buildBlockedResponsePacketV6(packet, length)
                        } else {
                            val upstreamPayload = forwardDnsQuery(queryV6.dnsPayload)
                            if (upstreamPayload != null) {
                                DnsPacket.buildAllowedResponsePacketV6(packet, length, upstreamPayload)
                            } else {
                                DnsPacket.buildServerFailureResponsePacketV6(packet, length)
                            }
                        }
                    } ?: continue

                    runCatching { output.write(response) }
                        .onFailure { Log.w(TAG, "VPN packet write failed.", it) }
                }
            } finally {
                isRunning = false
                isStarting = false
                runCatching { vpnInterface?.close() }
                vpnInterface = null
                stopSelf()
            }
        }
    }

    private fun forwardDnsQuery(dnsPayload: ByteArray): ByteArray? {
        for (upstream in upstreamDnsServers()) {
            val socket = DatagramSocket()
            try {
                if (!protect(socket)) {
                    Log.w(TAG, "Unable to protect DNS forwarding socket from VPN.")
                    continue
                }
                socket.soTimeout = 2000
                socket.connect(upstream, 53)
                socket.send(DatagramPacket(dnsPayload, dnsPayload.size))

                val response = ByteArray(4096)
                val responsePacket = DatagramPacket(response, response.size)
                socket.receive(responsePacket)
                val responsePayload = response.copyOf(responsePacket.length)
                if (responsePayload.size >= 2 &&
                    dnsPayload.size >= 2 &&
                    responsePayload[0] == dnsPayload[0] &&
                    responsePayload[1] == dnsPayload[1]
                ) {
                    return responsePayload
                }
                Log.w(TAG, "Ignoring DNS response with mismatched transaction ID.")
            } catch (_: SocketTimeoutException) {
                continue
            } catch (exception: Exception) {
                Log.w(TAG, "DNS forward failed for ${upstream.hostAddress}.", exception)
            } finally {
                socket.close()
            }
        }
        return null
    }

    private fun activeBlockedDomains(): Set<String> {
        val pausedAtHome = LocationRuleManager.shouldAllowBlockedAppsAtHome(this)
        return blockedWebsites
            .filter { !pausedAtHome || BlockMode.isAlways(it.blockMode) }
            .map { it.domain }
            .toSet()
    }

    private fun upstreamDnsServers(): List<InetAddress> {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val systemDnsServers = connectivityManager.allNetworks
            .filter { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            }
            .flatMap { network ->
                connectivityManager.getLinkProperties(network)?.dnsServers.orEmpty()
            }
            .distinctBy { it.hostAddress }

        return systemDnsServers.ifEmpty {
            FALLBACK_UPSTREAM_DNS.mapNotNull { host ->
                runCatching { InetAddress.getByName(host) }.getOrNull()
            }
        }
    }
}
