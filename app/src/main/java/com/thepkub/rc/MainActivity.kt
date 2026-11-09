package com.thepkub.rc

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile private var btSocket: BluetoothSocket? = null
    @Volatile private var tcpSocket: Socket? = null
    @Volatile private var outStream: OutputStream? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var running = false
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val REQ_BT_CONNECT = 1001
        private const val WIFI_SSID = "THEPKUBWIFI"
        private const val WIFI_PASS = "12345678"
        private const val BOARD_IP = "192.168.4.1"
        private const val BOARD_PORT = 8888
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)
        hideSystemBars()

        val ws: WebSettings = webView.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.mediaPlaybackRequiresUserGesture = false
        ws.allowFileAccess = true

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(NativeBridge(), "Native")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    override fun onPause() {
        super.onPause()
        sendRaw("S", true)
        notifyJs("pause", "1")
    }

    override fun onDestroy() {
        sendRaw("S", true)
        closeActiveLinks()
        ioExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun hasBtPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BT_CONNECT) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            notifyJs("perm", if (granted) "1" else "0")
        }
    }

    private fun notifyJs(type: String, payload: String) {
        val safeType = JSONObject.quote(type)
        val safePayload = JSONObject.quote(payload)
        runOnUiThread {
            if (!isDestroyed) {
                webView.evaluateJavascript("if(window.nat)window.nat($safeType,$safePayload);", null)
            }
        }
    }

    private fun sendRaw(line: String, force: Boolean) {
        val clean = line.trim()
        if (clean.isEmpty()) return
        ioExecutor.execute {
            try {
                val os = outStream
                if (os != null) {
                    os.write((clean + "\n").toByteArray(Charsets.UTF_8))
                    if (force) os.flush()
                }
            } catch (e: Exception) {
                closeActiveLinks()
                notifyJs("lost", e.message ?: "Write error")
            }
        }
    }

    private fun startReaderLoop(input: java.io.InputStream, label: String) {
        running = true
        notifyJs("ok", label)
        readerThread = Thread {
            try {
                val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
                while (running) {
                    val line = reader.readLine() ?: break
                    notifyJs("line", line)
                }
            } catch (_: Exception) {
            } finally {
                if (running) {
                    closeActiveLinks()
                    notifyJs("lost", "Connection closed")
                }
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun closeActiveLinks() {
        running = false
        try { outStream?.close() } catch (_: Exception) {}
        outStream = null
        try { btSocket?.close() } catch (_: Exception) {}
        btSocket = null
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpSocket = null
        try { readerThread?.interrupt() } catch (_: Exception) {}
        readerThread = null
    }

    inner class NativeBridge {

        @JavascriptInterface
        fun askBt(): Boolean {
            if (hasBtPermission()) {
                notifyJs("perm", "1")
                return true
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                    REQ_BT_CONNECT
                )
            }
            return false
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun paired(): String {
            if (!hasBtPermission()) {
                askBt()
                return "[]"
            }
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter: BluetoothAdapter? = bm?.adapter
            if (adapter == null || !adapter.isEnabled) return "[]"
            val arr = JSONArray()
            val bonded: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
            for (dev in bonded) {
                val obj = JSONObject()
                obj.put("name", dev.name ?: "Unknown")
                obj.put("address", dev.address ?: "")
                arr.put(obj)
            }
            return arr.toString()
        }

        @SuppressLint("MissingPermission")
        @JavascriptInterface
        fun connectBt(addr: String) {
            if (!hasBtPermission()) {
                askBt()
                return
            }
            ioExecutor.execute {
                closeActiveLinks()
                try {
                    val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = bm.adapter
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                    val device = adapter.getRemoteDevice(addr)
                    val sock = device.createRfcommSocketToServiceRecord(sppUuid)
                    sock.connect()
                    btSocket = sock
                    outStream = sock.outputStream
                    startReaderLoop(sock.inputStream, device.name ?: addr)
                } catch (e: Exception) {
                    closeActiveLinks()
                    notifyJs("err", e.message ?: "BT Fail")
                }
            }
        }

        @JavascriptInterface
        fun connectWifi() {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    wifiCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
                    val specifier = WifiNetworkSpecifier.Builder()
                        .setSsid(WIFI_SSID)
                        .setWpa2Passphrase(WIFI_PASS)
                        .build()
                    val req = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .setNetworkSpecifier(specifier)
                        .build()

                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            cm.bindProcessToNetwork(network)
                            openTcpSocket(network)
                        }
                        override fun onUnavailable() {
                            openTcpSocket(null)
                        }
                    }
                    wifiCallback = cb
                    cm.requestNetwork(req, cb, 12000)
                } catch (_: Exception) {
                    openTcpSocket(null)
                }
            } else {
                openTcpSocket(null)
            }
        }

        private fun openTcpSocket(network: Network?) {
            ioExecutor.execute {
                closeActiveLinks()
                try {
                    val sock = network?.socketFactory?.createSocket() ?: Socket()
                    sock.tcpNoDelay = true
                    sock.connect(InetSocketAddress(BOARD_IP, BOARD_PORT), 4000)
                    tcpSocket = sock
                    outStream = sock.getOutputStream()
                    startReaderLoop(sock.getInputStream(), "THEPKUBWIFI")
                } catch (e: Exception) {
                    closeActiveLinks()
                    notifyJs("err", e.message ?: "TCP Fail")
                }
            }
        }

        @JavascriptInterface
        fun send(line: String, force: Boolean) = sendRaw(line, force)

        @JavascriptInterface
        fun disconnect() {
            sendRaw("S", true)
            ioExecutor.execute {
                closeActiveLinks()
                notifyJs("lost", "Disconnected")
            }
        }

        @JavascriptInterface
        fun vibrate(ms: Long) {
            val dur = ms.coerceIn(5L, 300L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    val vib = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vib.vibrate(VibrationEffect.createOneShot(dur, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vib.vibrate(dur)
                    }
                }
            } catch (_: Exception) {}
        }

        @JavascriptInterface
        fun openBtSettings() {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
    }
}
