package com.chakker.phoneblocker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local DNS filter built on VpnService.
 *
 * Only the fake DNS server address is routed into the tunnel, so normal traffic never touches
 * this service. Every DNS query is read from the TUN device; blocked names get NXDOMAIN, the
 * rest are forwarded to a real resolver over a protected (non-tunnelled) socket.
 *
 * Because it works on DNS, incognito mode and any other browser are covered.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "blocker"
        private const val NOTIF_ID = 1
        private const val TUN_ADDR = "10.111.222.1"
        private const val DNS_ADDR = "10.111.222.2"
        private val UPSTREAMS = listOf("1.1.1.1", "9.9.9.9")

        @Volatile
        var running = false
            private set

        @Volatile
        private var instance: DnsVpnService? = null

        /** Make the service state match the saved on/off flag. */
        fun sync(ctx: Context) {
            if (Config.vpnWanted(ctx)) {
                ensureRunning(ctx)
            } else {
                // stopService alone isn't enough: the system keeps a VpnService bound while its
                // tunnel is open, so the tunnel has to be closed from inside.
                instance?.shutdown()
                ctx.stopService(Intent(ctx, DnsVpnService::class.java))
            }
        }

        fun ensureRunning(ctx: Context) {
            if (running || !Config.vpnWanted(ctx)) return
            if (VpnService.prepare(ctx) != null) return // VPN permission not granted yet
            try {
                ctx.startForegroundService(Intent(ctx, DnsVpnService::class.java))
            } catch (e: Exception) {
                // Background start refused; the next accessibility event or app open retries.
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    @Volatile
    private var stopping = false
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** Close the tunnel and stop, so the VPN key icon and DNS filtering go away. */
    fun shutdown() {
        stopping = true
        running = false
        try {
            tun?.close()
        } catch (e: IOException) {
            // already closed
        }
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        showForeground()
        if (!Config.vpnWanted(this)) {
            shutdown()
            return START_NOT_STICKY
        }
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Blocker status", NotificationManager.IMPORTANCE_MIN)
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone blocker is on")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startTunnel() {
        val pfd = Builder()
            .setSession("PhoneBlocker")
            .setMtu(1500)
            .addAddress(TUN_ADDR, 32)
            .addDnsServer(DNS_ADDR)
            .addRoute(DNS_ADDR, 32)
            .establish()
        if (pfd == null) {
            stopSelf()
            return
        }
        tun = pfd
        stopping = false
        running = true
        Thread({ runLoop(pfd) }, "dns-loop").start()
    }

    private fun runLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        // A blocking read() would keep the tunnel alive after close(), so wait with poll() and a
        // short timeout and check the stop flag between rounds.
        val pfds = arrayOf(StructPollfd())
        pfds[0].fd = pfd.fileDescriptor
        pfds[0].events = OsConstants.POLLIN.toShort()
        try {
            while (!stopping) {
                pfds[0].revents = 0
                val ready = Os.poll(pfds, 500)
                if (ready <= 0) continue
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val pkt = buf.copyOf(n)
                pool.execute { handle(pkt, output) }
            }
        } catch (e: Exception) {
            // Tunnel closed (service stopped or VPN revoked).
        } finally {
            running = false
        }
    }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        try {
            if (p.size < 28 || (p[0].toInt() ushr 4) != 4) return // IPv4 only
            val ihl = (p[0].toInt() and 0x0F) * 4
            if (p[9].toInt() != 17 || p.size < ihl + 8) return // UDP only
            if (u16(p, ihl + 2) != 53) return // DNS only

            val dnsOff = ihl + 8
            val udpLen = u16(p, ihl + 4)
            val dnsLen = minOf(udpLen - 8, p.size - dnsOff)
            if (dnsLen < 12) return
            val query = p.copyOfRange(dnsOff, dnsOff + dnsLen)

            val q = parseQuestion(query)
            val reply: ByteArray = if (q != null && isBlocked(q.first)) {
                nxdomain(query, q.second)
            } else {
                forward(query) ?: return
            }
            if (reply.size > 1472) return // would need fragmentation; client will retry

            val packet = buildPacket(p, ihl, reply)
            synchronized(out) { out.write(packet) }
        } catch (e: Exception) {
            // Drop the query; the client resolver will retry.
        }
    }

    /** Always-on list while protection is on, plus the sites of any schedule that is active right now. */
    private fun isBlocked(name: String): Boolean {
        val list = ArrayList<String>()
        if (Config.isEnabled(this)) list += Config.blockedDomains(this)
        val scheduled = Schedules.activeDomains(this)
        if (scheduled.isNotEmpty()) {
            list += scheduled
            list += Config.DOH_DOMAINS // keep secure-DNS providers out of reach during a schedule too
        }
        return list.any { name == it || name.endsWith(".$it") }
    }

    /** Returns (lowercased query name, offset just past the question section), or null. */
    private fun parseQuestion(dns: ByteArray): Pair<String, Int>? {
        var i = 12
        val sb = StringBuilder()
        while (true) {
            if (i >= dns.size) return null
            val len = dns[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if ((len and 0xC0) != 0) return null // compression pointers don't appear in questions
            if (i + 1 + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        if (i + 4 > dns.size) return null // QTYPE + QCLASS
        return Pair(sb.toString().lowercase(), i + 4)
    }

    private fun nxdomain(query: ByteArray, questionEnd: Int): ByteArray {
        val r = query.copyOfRange(0, questionEnd)
        r[2] = ((query[2].toInt() and 0x79) or 0x80).toByte() // QR=1, keep opcode + RD
        r[3] = 0x83.toByte() // RA=1, RCODE=3 (NXDOMAIN)
        r[4] = 0
        r[5] = 1 // QDCOUNT = 1
        for (i in 6..11) r[i] = 0 // no answer/authority/additional records
        return r
    }

    private fun forward(query: ByteArray): ByteArray? {
        for (host in UPSTREAMS) {
            try {
                DatagramSocket().use { s ->
                    protect(s) // keep this socket out of the tunnel, or we loop forever
                    s.soTimeout = 3000
                    s.connect(InetAddress.getByName(host), 53)
                    s.send(DatagramPacket(query, query.size))
                    val buf = ByteArray(4096)
                    val dp = DatagramPacket(buf, buf.size)
                    s.receive(dp)
                    return buf.copyOf(dp.length)
                }
            } catch (e: Exception) {
                // try the next upstream
            }
        }
        return null
    }

    /** Wrap a DNS payload in IPv4 + UDP, addressed back to whoever asked. */
    private fun buildPacket(req: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = 20 + 8 + dns.size
        val o = ByteArray(total)
        o[0] = 0x45
        put16(o, 2, total)
        put16(o, 6, 0x4000) // don't fragment
        o[8] = 64
        o[9] = 17
        System.arraycopy(req, 16, o, 12, 4) // src = original dst
        System.arraycopy(req, 12, o, 16, 4) // dst = original src
        put16(o, 10, checksum(o, 20))

        put16(o, 20, 53)
        put16(o, 22, u16(req, ihl)) // back to the client's source port
        put16(o, 24, 8 + dns.size)
        put16(o, 26, 0) // UDP checksum optional over IPv4
        System.arraycopy(dns, 0, o, 28, dns.size)
        return o
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    override fun onRevoke() {
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        running = false
        try {
            tun?.close()
        } catch (e: IOException) {
            // already closed
        }
        tun = null
        pool.shutdownNow()
        super.onDestroy()
    }
}
