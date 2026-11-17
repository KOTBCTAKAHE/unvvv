package com.unvvv.app.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID

data class Found(
    val ip: String,
    val name: String? = null,
    val model: String? = null,
    val onvif: Boolean = false,
    val rtsp: Boolean = false,
) {
    fun merge(o: Found) = copy(
        name = name ?: o.name,
        model = model ?: o.model,
        onvif = onvif || o.onvif,
        rtsp = rtsp || o.rtsp,
    )

    fun title(): String {
        val base = name?.takeIf { it.isNotBlank() } ?: "Камера"
        return "$base ${ip.substringAfterLast('.')}"
    }
}

/**
 * Поиск камер в локальной сети:
 *  1) ONVIF WS-Discovery (мультикаст 239.255.255.250:3702) — находит камеры и NVR UNV с именем и моделью;
 *  2) проверка порта RTSP 554 на всех адресах подсети /24 — находит устройства с выключенным ONVIF.
 */
object Scanner {

    fun localNet(ctx: Context): Pair<Inet4Address, Int>? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) continue
            val lp = cm.getLinkProperties(n) ?: continue
            val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            return (la.address as Inet4Address) to la.prefixLength
        }
        return null
    }

    suspend fun scan(ctx: Context, onFound: suspend (Found) -> Unit) = coroutineScope {
        launch(Dispatchers.IO) { runCatching { onvif(ctx, onFound) } }
        launch(Dispatchers.IO) { runCatching { ports(ctx, onFound) } }
    }

    private suspend fun onvif(ctx: Context, onFound: suspend (Found) -> Unit) {
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("unv-scan")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 400
                s.broadcast = true
                val group = InetAddress.getByName("239.255.255.250")
                val bcast = InetAddress.getByName("255.255.255.255")
                val seen = HashSet<String>()
                val buf = ByteArray(65535)
                val end = System.currentTimeMillis() + 5000
                var sent = 0
                var nextSend = 0L
                while (System.currentTimeMillis() < end && currentCoroutineContext().isActive) {
                    if (sent < 3 && System.currentTimeMillis() >= nextSend) {
                        for (type in listOf("dn:NetworkVideoTransmitter", "tds:Device")) {
                            val b = probe(type).toByteArray()
                            runCatching { s.send(DatagramPacket(b, b.size, group, 3702)) }
                            runCatching { s.send(DatagramPacket(b, b.size, bcast, 3702)) }
                        }
                        sent++
                        nextSend = System.currentTimeMillis() + 1000
                    }
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val xml = String(p.data, 0, p.length)
                    if (!xml.contains("ProbeMatch")) continue
                    val xaddr = Regex("XAddrs>([^<]+)<").find(xml)?.groupValues?.get(1)
                    val host = xaddr?.let { Regex("https?://(\\d+\\.\\d+\\.\\d+\\.\\d+)").find(it)?.groupValues?.get(1) }
                        ?: p.address.hostAddress ?: continue
                    if (!seen.add(host)) continue
                    val scopes = Regex("Scopes[^>]*>([^<]+)<").find(xml)?.groupValues?.get(1).orEmpty()
                    fun scope(k: String) = Regex("onvif://www\\.onvif\\.org/$k/(\\S+)", RegexOption.IGNORE_CASE)
                        .find(scopes)?.groupValues?.get(1)
                        ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
                    onFound(Found(host, scope("name"), scope("hardware"), onvif = true))
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
    }

    private suspend fun ports(ctx: Context, onFound: suspend (Found) -> Unit) = coroutineScope {
        val (addr, _) = localNet(ctx) ?: return@coroutineScope
        val b = addr.address.map { it.toInt() and 0xFF }
        val self = b[3]
        val sem = Semaphore(64)
        for (i in 1..254) {
            if (i == self) continue
            launch {
                sem.withPermit {
                    val ip = "${b[0]}.${b[1]}.${b[2]}.$i"
                    val open = runCatching {
                        Socket().use { it.connect(InetSocketAddress(ip, 554), 700) }
                        true
                    }.getOrDefault(false)
                    if (open) onFound(Found(ip, rtsp = true))
                }
            }
        }
    }

    private fun probe(type: String) =
        """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl" xmlns:tds="http://www.onvif.org/ver10/device/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>$type</d:Types></d:Probe></e:Body></e:Envelope>"""
}
