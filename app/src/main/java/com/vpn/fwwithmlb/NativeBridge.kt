package com.vpn.fwwithmlb

object NativeBridge {
    init {
        System.loadLibrary("nativefirewall")
    }

    external fun startNative(tunFd: Int)
    external fun stopNative()
    external fun setBlockedUidsNative(uids: IntArray)

    // ✅ Wrapper (optional)
    fun setBlockedUids(uids: IntArray) = setBlockedUidsNative(uids)
}
