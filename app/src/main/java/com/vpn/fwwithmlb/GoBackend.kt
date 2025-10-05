package com.vpn.fwwithmlb

class GoBackend(private val context: android.content.Context) {
    fun createTunnel(name: String, config: Config): Tunnel? = Tunnel()
    fun setState(tunnel: Tunnel?, state: Tunnel.State) { /* stub */ }
}