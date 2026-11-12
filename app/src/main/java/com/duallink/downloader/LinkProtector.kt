package com.duallink.downloader

import android.os.ParcelFileDescriptor

/**
 * Called by Xray (Go) for every outgoing socket, right before it connects.
 * We pick a link with the Balancer and bind the socket to that network, so the
 * proxy's connections are spread over Wi-Fi and mobile data.
 */
class LinkProtector(private val balancer: Balancer) : xraybridge.Protector {

    override fun protect(fd: Long, network: String?, address: String?): Boolean {
        val addr = address.orEmpty()
        if (addr.startsWith("127.") || addr.startsWith("[::1]") || addr.startsWith("localhost")) return true

        // Host part only, so sticky mode keeps one server on one link.
        val hostKey = addr.substringBeforeLast(':').trim('[', ']')
        for (link in balancer.order(hostKey)) {
            val net = link.network ?: continue
            var pfd: ParcelFileDescriptor? = null
            try {
                // fromFd dups the descriptor; binding the dup binds the same socket.
                pfd = ParcelFileDescriptor.fromFd(fd.toInt())
                net.bindSocket(pfd.fileDescriptor)
                link.total.incrementAndGet()
                balancer.reportSuccess(link)
                return true
            } catch (_: Exception) {
                balancer.reportFailure(link)
            } finally {
                try { pfd?.close() } catch (_: Exception) {}
            }
        }
        return false
    }
}
