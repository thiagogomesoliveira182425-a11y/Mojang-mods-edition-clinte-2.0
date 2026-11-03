package com.example.mojangmods

import android.app.*
import android.content.*
import android.graphics.*
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import java.net.*

// ====== SERVIDOR DO PING ======
const val SERVER_HOST = "play.cubecraft.net" // CubeCraft (Bedrock)
const val SERVER_PORT = 19132                // porta UDP padrão do Bedrock
// ==============================

const val PREFS = "mods"
const val ACTION_UPDATE = "mods.UPDATE"
const val ACTION_STOP = "mods.STOP"

// ---------------- TELA DO MENU: "Mojang Edition Mods" ----------------
class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        col.addView(TextView(this).apply { text = "Mojang Edition Mods"; textSize = 22f })

        // Interruptor geral: liga/desliga o HUD inteiro
        col.addView(Switch(this).apply {
            text = "HUD ativo"
            isChecked = prefs.getBoolean("hud_on", false) // mostra o estado real ao reabrir o app
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        // pede a permissão "Sobrepor a outros apps"
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                        isChecked = false
                    } else startForegroundService(Intent(context, HudService::class.java))
                } else startService(Intent(context, HudService::class.java).setAction(ACTION_STOP))
            }
        })

        // Interruptores de cada mod
        for ((key, label) in listOf("fps" to "FPS", "cps" to "CPS", "ping" to "Ping")) {
            col.addView(Switch(this).apply {
                text = label
                isChecked = prefs.getBoolean(key, true)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean(key, on).apply()
                    startService(Intent(context, HudService::class.java).setAction(ACTION_UPDATE))
                }
            })
        }
        setContentView(col)
        if (Build.VERSION.SDK_INT >= 33)
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
    }
}

// ---------------- SERVIÇO QUE DESENHA O HUD SOBRE O JOGO ----------------
class HudService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var box: LinearLayout
    private lateinit var fpsV: TextView
    private lateinit var cpsV: TextView
    private lateinit var pingV: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val clicks = ArrayDeque<Long>()
    @Volatile private var running = true
    private var frames = 0
    private var lastFps = 0L

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val ch = "hud"
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(ch, "HUD", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, ch).setContentTitle("Mojang Edition Mods ativo")
            .setSmallIcon(android.R.drawable.ic_menu_info_details).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hud_on", true).apply()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        fun tv() = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; setBackgroundColor(0x99000000.toInt()); setPadding(14, 4, 14, 4)
        }
        fpsV = tv(); cpsV = tv(); pingV = tv()
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fpsV); addView(cpsV); addView(pingV)
            // CPS: toques FORA da janela do HUD (no jogo) chegam como ACTION_OUTSIDE
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) clicks.addLast(SystemClock.uptimeMillis())
                false
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 120 }
        wm.addView(box, lp)
        applyPrefs()

        // FPS (taxa de quadros da tela, ver observação)
        Choreographer.getInstance().postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(t: Long) {
                if (!running) return
                frames++
                val now = t / 1_000_000
                if (now - lastFps >= 1000) { fpsV.text = "FPS: $frames"; frames = 0; lastFps = now }
                Choreographer.getInstance().postFrameCallback(this)
            }
        })
        // CPS: cliques dos últimos 1000 ms
        ui.post(object : Runnable {
            override fun run() {
                if (!running) return
                val now = SystemClock.uptimeMillis()
                while (clicks.isNotEmpty() && now - clicks.first() > 1000) clicks.removeFirst()
                cpsV.text = "CPS: ${clicks.size}"
                ui.postDelayed(this, 100)
            }
        })
        // Ping real: RakNet "Unconnected Ping" (UDP) ao servidor Bedrock
        Thread {
            val magic = byteArrayOf(0, -1, -1, 0, -2, -2, -2, -2, -3, -3, -3, -3, 0x12, 0x34, 0x56, 0x78)
            while (running) {
                var ms = -1L
                try {
                    DatagramSocket().use { s ->
                        s.soTimeout = 1500
                        val pkt = ByteArray(33)
                        pkt[0] = 0x01
                        System.arraycopy(magic, 0, pkt, 9, 16)
                        val t0 = SystemClock.elapsedRealtime()
                        s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(SERVER_HOST), SERVER_PORT))
                        val rx = DatagramPacket(ByteArray(1500), 1500)
                        s.receive(rx)
                        ms = SystemClock.elapsedRealtime() - t0
                    }
                } catch (_: Exception) {}
                ui.post { pingV.text = if (ms >= 0) "Ping: $ms ms" else "Ping: --" }
                Thread.sleep(2000)
            }
        }.start()
    }

    // Liga/desliga cada mod conforme os interruptores
    private fun applyPrefs() {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        fun vis(k: String) = if (p.getBoolean(k, true)) View.VISIBLE else View.GONE
        fpsV.visibility = vis("fps"); cpsV.visibility = vis("cps"); pingV.visibility = vis("ping")
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        when (i?.action) {
            ACTION_UPDATE -> applyPrefs()
            ACTION_STOP -> { stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("hud_on", false).apply()
        try { wm.removeView(box) } catch (_: Exception) {}
        super.onDestroy()
    }
}
