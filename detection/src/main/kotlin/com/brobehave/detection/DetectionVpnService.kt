package com.brobehave.detection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.brobehave.core.data.AppPreferences
import com.brobehave.core.data.StreakRepository
import com.brobehave.core.model.DetectionState
import com.brobehave.core.model.DetectionStatus
import com.brobehave.core.model.FlagSource
import com.brobehave.detection.engine.DailyFlagger
import com.brobehave.detection.engine.DnsMessage
import com.brobehave.detection.engine.DomainMatcher
import com.brobehave.detection.engine.IpPackets
import com.brobehave.detection.engine.UdpDatagram
import com.brobehave.detection.engine.UpstreamResolver
import dagger.hilt.android.AndroidEntryPoint
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The optional automatic detection: an on-device VPN that receives ONLY DNS.
 *
 * It gives the phone a virtual DNS server address and routes just that one address into
 * the tunnel, so every other packet bypasses this service entirely. Each DNS question is
 * forwarded unchanged to the network's real DNS server and the answer relayed back;
 * nothing is blocked or modified.
 *
 * Privacy rules for this file: a site name exists only as a local variable while it is
 * compared with the blocklist. It is never stored, never logged, never put in an
 * exception message, and never leaves this process. The only thing recorded is that
 * today was flagged.
 */
@AndroidEntryPoint
class DetectionVpnService : VpnService() {
    @Inject
    lateinit var controller: DetectionController

    @Inject
    lateinit var streakRepository: StreakRepository

    @Inject
    lateinit var preferences: AppPreferences

    @Inject
    lateinit var blocklistStore: BlocklistStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = Semaphore(MAX_CONCURRENT_QUERIES)
    private val writeLock = Any()

    private var tunnel: ParcelFileDescriptor? = null
    private var tunnelOutput: FileOutputStream? = null
    private var worker: Job? = null

    /** The real (non-VPN) network and its DNS servers, kept current by [networkCallback]. */
    @Volatile
    private var upstreamNetwork: Network? = null

    @Volatile
    private var upstreamServers: List<InetAddress> = emptyList()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            upstreamNetwork = network
            upstreamServers = linkProperties.dnsServers
            // A named Private DNS provider means lookups go there encrypted, bypassing this service.
            val bypass = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && linkProperties.privateDnsServerName != null
            controller.update { it.copy(privateDnsBypass = bypass) }
        }

        override fun onLost(network: Network) {
            if (network == upstreamNetwork) {
                upstreamNetwork = null
                upstreamServers = emptyList()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown(DetectionStatus.OFF)
            return START_NOT_STICKY
        }
        // ACTION_START from the app, a sticky restart, or the system starting an always-on VPN.
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            } else {
                0
            },
        )
        if (worker == null) {
            worker = scope.launch { run() }
        }
        return START_STICKY
    }

    /** Another VPN took over, or the user disconnected this one in system settings. */
    override fun onRevoke() {
        shutDown(DetectionStatus.REVOKED)
    }

    override fun onDestroy() {
        releaseTunnel()
        scope.cancel()
        controller.update { if (it.status == DetectionStatus.ACTIVE) DetectionState() else it }
        super.onDestroy()
    }

    private fun shutDown(finalStatus: DetectionStatus) {
        releaseTunnel()
        controller.update { DetectionState(status = finalStatus) }
        scope.launch { preferences.setDetectionEnabled(false) }.invokeOnCompletion {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseTunnel() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        runCatching { tunnel?.close() }
        tunnel = null
        tunnelOutput = null
    }

    private suspend fun run() {
        val matcher = blocklistStore.matcher()
        val established = Builder()
            .setSession(getString(R.string.detection_session))
            .addAddress(TUNNEL_ADDRESS, PREFIX_SINGLE_HOST)
            .addDnsServer(VIRTUAL_DNS)
            // The ONLY route into the tunnel: the virtual DNS server. Everything else bypasses the VPN.
            .addRoute(VIRTUAL_DNS, PREFIX_SINGLE_HOST)
            .setMtu(MTU)
            .setBlocking(true)
            .establish()
        if (established == null) {
            // VPN consent is missing (never given, or revoked): nothing to run.
            shutDown(DetectionStatus.OFF)
            return
        }
        tunnel = established
        tunnelOutput = FileOutputStream(established.fileDescriptor)
        trackUpstreamNetwork()
        preferences.setDetectionEnabled(true)
        controller.update { it.copy(status = DetectionStatus.ACTIVE) }

        BlocklistUpdater.schedule(this)
        scope.launch { heartbeatLoop() }
        readLoop(FileInputStream(established.fileDescriptor), matcher)
    }

    private fun trackUpstreamNetwork() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        getSystemService(ConnectivityManager::class.java).registerNetworkCallback(request, networkCallback)
    }

    /** One heartbeat per local day while running; the repository ignores repeats. */
    private suspend fun heartbeatLoop() {
        while (scope.isActive && tunnel != null) {
            streakRepository.recordHeartbeat()
            delay(HEARTBEAT_INTERVAL_MILLIS)
        }
    }

    private fun readLoop(input: FileInputStream, matcher: DomainMatcher) {
        val resolver = UpstreamResolver(
            servers = { upstreamServers },
            maxAnswerBytes = MTU - IP_UDP_OVERHEAD,
            prepareUdp = { socket ->
                protect(socket)
                runCatching { upstreamNetwork?.bindSocket(socket) }
            },
            prepareTcp = { socket ->
                protect(socket)
                runCatching { upstreamNetwork?.bindSocket(socket) }
            },
        )
        val flagger = DailyFlagger(today = LocalDate::now) {
            streakRepository.reportSlip(note = null, source = FlagSource.AUTO)
        }
        val buffer = ByteArray(MTU)
        try {
            while (scope.isActive) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length > 0) handlePacket(buffer, length, matcher, resolver, flagger)
            }
        } catch (_: IOException) {
            // The tunnel was closed (turned off or revoked): the loop simply ends.
        }
    }

    private fun handlePacket(
        packet: ByteArray,
        length: Int,
        matcher: DomainMatcher,
        resolver: UpstreamResolver,
        flagger: DailyFlagger,
    ) {
        val datagram = IpPackets.parseUdp(packet, length)
        if (datagram != null) {
            if (datagram.destinationPort == DNS_PORT) {
                val query = packet.copyOfRange(datagram.payloadOffset, datagram.payloadOffset + datagram.payloadLength)
                scope.launch { inFlight.withPermit { answer(datagram, query, matcher, resolver, flagger) } }
            }
            return
        }
        // No TCP stack here: refuse DNS-over-TCP/TLS at once so the resolver falls back to UDP.
        IpPackets.parseTcpSyn(packet, length)?.let { write(IpPackets.buildTcpReset(it)) }
    }

    private suspend fun answer(
        datagram: UdpDatagram,
        query: ByteArray,
        matcher: DomainMatcher,
        resolver: UpstreamResolver,
        flagger: DailyFlagger,
    ) {
        // The name lives only inside this expression; just the yes/no result survives it.
        val listed = DnsMessage.questionName(query)?.let(matcher::matches) == true
        if (listed) runCatching { flagger.onMatch() }

        val reply = resolver.resolve(query) ?: return
        write(
            IpPackets.buildUdp(
                sourceAddress = datagram.destinationAddress,
                sourcePort = datagram.destinationPort,
                destinationAddress = datagram.sourceAddress,
                destinationPort = datagram.sourcePort,
                payload = reply,
            ),
        )
    }

    private fun write(packet: ByteArray) {
        synchronized(writeLock) {
            try {
                tunnelOutput?.write(packet)
            } catch (_: IOException) {
                // Tunnel closed while a reply was on its way; the app will simply retry its lookup.
            }
        }
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.detection_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.detection_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val turnOff = PendingIntent.getService(
            this,
            0,
            Intent(this, DetectionVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_detection)
            .setContentTitle(getString(R.string.detection_notification_title))
            .setContentText(getString(R.string.detection_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.detection_notification_off), turnOff)
            .build()
    }

    companion object {
        const val ACTION_START = "com.brobehave.detection.START"
        const val ACTION_STOP = "com.brobehave.detection.STOP"

        private const val CHANNEL_ID = "detection"
        private const val NOTIFICATION_ID = 4201

        /** Private addresses that exist only inside this tunnel. */
        private const val TUNNEL_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val PREFIX_SINGLE_HOST = 32
        private const val DNS_PORT = 53

        /** Large enough for a full DNS answer fetched over TCP to be relayed in one packet. */
        private const val MTU = 16_384
        private const val IP_UDP_OVERHEAD = 28
        private const val MAX_CONCURRENT_QUERIES = 64
        private const val HEARTBEAT_INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}
