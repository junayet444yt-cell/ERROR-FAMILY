package com.familyshield

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Family Mode = a local "DNS-only" VPN.
 * Every website name lookup (DNS) of the whole phone (Chrome, other browsers, apps) goes through here and is answered by a
 * family-safe DNS server (CleanBrowsing Family: adult sites blocked, Google/Bing safe search forced).
 * No web traffic is routed through this app, only the name lookups. Nothing is recorded.
 */
class FamilyVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.familyshield.START"
        const val ACTION_STOP = "com.familyshield.STOP"
        const val FAKE_DNS = "10.88.0.2"
        private val UPSTREAM = arrayOf("185.228.168.168", "185.228.169.168", "1.1.1.3", "1.0.0.3")

        @Volatile
        var running = false

        fun prefs(c: Context) = c.getSharedPreferences("family", Context.MODE_PRIVATE)
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs(this).getBoolean("enabled", false)) {   // restarted by the system but Family Mode is switched off
            stopSelf()
            return START_NOT_STICKY
        }
        startVpn()
        return START_STICKY
    }

    private fun startVpn() {
        if (tun != null) return
        val b = Builder()
            .setSession("Family Mode")
            .addAddress("10.88.0.1", 24)
            .addDnsServer(FAKE_DNS)
            .addRoute(FAKE_DNS, 32)      // only the DNS address goes through us, everything else stays direct
            .setMtu(1500)
        val fd: ParcelFileDescriptor? = try { b.establish() } catch (e: Exception) { null }
        if (fd == null) return
        tun = fd
        running = true
        worker = Thread { loop(fd) }.also { it.isDaemon = true; it.start() }
    }

    private fun stopVpn() {
        running = false
        try { tun?.close() } catch (e: Exception) { }
        tun = null
    }

    override fun onRevoke() {            // the person switched the VPN off in Android settings
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            handle(buf.copyOf(n), output)
        }
    }

    private fun u16(p: ByteArray, i: Int) = ((p[i].toInt() and 0xFF) shl 8) or (p[i + 1].toInt() and 0xFF)

    private fun handle(p: ByteArray, out: FileOutputStream) {
        if (p.size < 20 || (p[0].toInt() shr 4) != 4) return
        val ihl = (p[0].toInt() and 0x0F) * 4
        val proto = p[9].toInt() and 0xFF
        val dst = "" + (p[16].toInt() and 0xFF) + "." + (p[17].toInt() and 0xFF) + "." + (p[18].toInt() and 0xFF) + "." + (p[19].toInt() and 0xFF)
        if (dst != FAKE_DNS || p.size < ihl + 8) return
        val sport = u16(p, ihl)
        val dport = u16(p, ihl + 2)
        if (proto == 17 && dport == 53) {                    // a DNS question (UDP)
            val len = u16(p, ihl + 4)
            val question = p.copyOfRange(ihl + 8, minOf(p.size, ihl + len))
            pool.execute {
                val answer = forward(question) ?: return@execute
                val reply = ipv4(p, 17, udp(53, sport, answer))
                synchronized(out) { try { out.write(reply) } catch (e: Exception) { } }
            }
        } else if (proto == 6 && p.size >= ihl + 20 && (p[ihl + 13].toInt() and 0x02) != 0) {
            // someone tries a TCP / encrypted-DNS connection to our DNS address: refuse at once so the phone falls back to normal DNS fast
            val seq = ((p[ihl + 4].toLong() and 0xFF) shl 24) or ((p[ihl + 5].toLong() and 0xFF) shl 16) or ((p[ihl + 6].toLong() and 0xFF) shl 8) or (p[ihl + 7].toLong() and 0xFF)
            val rst = ipv4(p, 6, tcpReset(p, dport, sport, (seq + 1) and 0xFFFFFFFFL))
            synchronized(out) { try { out.write(rst) } catch (e: Exception) { } }
        }
    }

    /** ask a family-safe DNS server (first one that answers) */
    private fun forward(question: ByteArray): ByteArray? {
        for (ip in UPSTREAM) {
            try {
                DatagramSocket().use { s ->
                    protect(s)                          // this question must NOT go through our own VPN again
                    s.soTimeout = 2500
                    s.send(DatagramPacket(question, question.size, InetAddress.getByName(ip), 53))
                    val rb = ByteArray(4096)
                    val rp = DatagramPacket(rb, rb.size)
                    s.receive(rp)
                    return rb.copyOf(rp.length)
                }
            } catch (e: Exception) { }
        }
        return null
    }

    // ---- small packet builders (IPv4 + UDP answer, IPv4 + TCP reset) ----
    private fun udp(sport: Int, dport: Int, data: ByteArray): ByteArray {
        val len = 8 + data.size
        val h = ByteArray(len)
        h[0] = (sport shr 8).toByte(); h[1] = sport.toByte()
        h[2] = (dport shr 8).toByte(); h[3] = dport.toByte()
        h[4] = (len shr 8).toByte(); h[5] = len.toByte()          // checksum stays 0 (allowed for IPv4)
        System.arraycopy(data, 0, h, 8, data.size)
        return h
    }

    private fun tcpReset(orig: ByteArray, sport: Int, dport: Int, ack: Long): ByteArray {
        val t = ByteArray(20)
        t[0] = (sport shr 8).toByte(); t[1] = sport.toByte()
        t[2] = (dport shr 8).toByte(); t[3] = dport.toByte()
        t[8] = (ack shr 24).toByte(); t[9] = (ack shr 16).toByte(); t[10] = (ack shr 8).toByte(); t[11] = ack.toByte()
        t[12] = (5 shl 4).toByte()
        t[13] = 0x14.toByte()                                      // RST + ACK
        val ph = ByteArray(12 + 20)                                // checksum over the "pseudo header" + tcp header
        System.arraycopy(orig, 16, ph, 0, 4)                       // from = the address the phone asked
        System.arraycopy(orig, 12, ph, 4, 4)                       // to = the phone
        ph[9] = 6; ph[11] = 20
        System.arraycopy(t, 0, ph, 12, 20)
        val c = checksum(ph, ph.size)
        t[16] = (c shr 8).toByte(); t[17] = c.toByte()
        return t
    }

    /** wrap a UDP/TCP piece into an IPv4 packet that goes back to the sender of [orig] */
    private fun ipv4(orig: ByteArray, proto: Int, payload: ByteArray): ByteArray {
        val total = 20 + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte()
        p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40.toByte()                                       // don't fragment
        p[8] = 64
        p[9] = proto.toByte()
        System.arraycopy(orig, 16, p, 12, 4)                       // from = the address the phone asked
        System.arraycopy(orig, 12, p, 16, 4)                       // to = the phone
        val c = checksum(p, 20)
        p[10] = (c shr 8).toByte(); p[11] = c.toByte()
        System.arraycopy(payload, 0, p, 20, payload.size)
        return p
    }

    private fun checksum(b: ByteArray, len: Int): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            sum += (((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < len) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
