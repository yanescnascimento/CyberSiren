package org.torproject.arti

import info.guardianproject.arti.ArtiLogListener

object ArtiNative {

    init {
        System.loadLibrary("arti_android")
    }

    external fun getVersion(): String

    external fun setLogCallback(callback: ArtiLogListener)

    external fun initialize(dataDir: String): Int

    external fun startSocksProxy(port: Int): Int

    external fun stop(): Int
}
