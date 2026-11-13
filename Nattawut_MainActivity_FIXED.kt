package com.nattawut.ecu

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.view.Gravity
import android.view.View
import android.widget.*
import android.view.ViewGroup
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.max
import java.util.Locale

class MainActivity : Activity() {
    private val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var readerThread: Thread? = null
    private var logging = false
    private val logRows = mutableListOf<String>()
    private val samples = mutableListOf<Float>()
    private val histories = mutableMapOf<String, MutableList<Float>>()
    private var receivedPackets = 0L
    private var lastPacketAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private var telemetryTimedOut = true
    private val telemetry = linkedMapOf(
        "RPM" to "--", "TPS" to "--", "ECT" to "--", "IAT" to "--",
        "BAT" to "--", "O2" to "--", "AFR" to "--", "SPEED" to "--",
        "INJ" to "--", "IGN" to "--", "MAP" to "--", "STFT" to "--", "LTFT" to "--"
    )
    private var latestRawFrame = "ยังไม่ได้รับ RAW DATA จาก ECU"
    private var rawDataView: TextView? = null
    private val units = mapOf("RPM" to "rpm", "TPS" to "%", "ECT" to "°C", "IAT" to "°C",
        "BAT" to "V", "O2" to "V", "AFR" to "AFR", "SPEED" to "km/h",
        "INJ" to "ms", "IGN" to "deg", "MAP" to "kPa", "STFT" to "%", "LTFT" to "%")
    private lateinit var root: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var graph: LiveGraph
    private var activePage = "DASHBOARD"

    // Vehicle speed has exactly two sources: phone GPS or the GPS module on ESP32.
    private var speedSource = "ESP32_GPS"
    private var phoneGpsLastFixAt = 0L
    private var phoneGpsListener: LocationListener? = null
    private var locationManager: LocationManager? = null
    private var phoneGpsPermissionRequested = false

    private val bg = Color.rgb(8, 12, 21)
    private val panel = Color.rgb(20, 29, 44)
    private val cyan = Color.rgb(43, 220, 235)
    private val white = Color.WHITE
    private val muted = Color.rgb(155, 170, 190)
    private var accent = Color.rgb(0, 160, 255)
    private var themeColor = Color.rgb(0, 160, 255)
    private var selectedMetric = "RPM"
    private var ecuInfoText = "ECU: ยังไม่ได้รับข้อมูลระบุตัวตนจาก ECU"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        speedSource = getSharedPreferences("smartgauge_settings", MODE_PRIVATE)
            .getString("speed_source", "ESP32_GPS") ?: "ESP32_GPS"
        buildShell()
        requestBtPermission()
        showPage("DASHBOARD")
        startTelemetryWatchdog()
    }

    private fun startTelemetryWatchdog() {
        mainHandler.post(object : Runnable {
            override fun run() {
                val stale = lastPacketAt == 0L ||
                    System.currentTimeMillis() - lastPacketAt > 1500L
                if (stale && !telemetryTimedOut) {
                    telemetryTimedOut = true
                    synchronized(telemetry) {
                        telemetry.keys.filter { it != "SPEED" || speedSource != "PHONE_GPS" }
                            .forEach { telemetry[it] = "--" }
                    }
                    if (activePage == "DASHBOARD") updateDashboardValues()
                    if (socket != null) {
                        status.text = "● CONNECTED • NO TELEMETRY"
                        status.setTextColor(Color.YELLOW)
                    }
                }
                if (speedSource == "PHONE_GPS" && phoneGpsLastFixAt > 0L &&
                    System.currentTimeMillis() - phoneGpsLastFixAt > 3000L) {
                    phoneGpsLastFixAt = 0L
                    telemetry["SPEED"] = "--"
                    updateSpeedDisplays()
                    sendBluetoothLine("SPEED,NA")
                }
                mainHandler.postDelayed(this, 300L)
            }
        })
    }

    private fun rounded(color: Int, stroke: Int = Color.TRANSPARENT, radius: Float = 14f): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat(); if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke) }

    private fun styledButton(textValue: String, color: Int = Color.rgb(0, 105, 230), onClick: () -> Unit): Button =
        Button(this).apply {
            text = textValue; textSize = 12f; setTextColor(Color.WHITE); isAllCaps = false
            background = rounded(color, Color.rgb(70, 155, 255), 12f)
            setPadding(dp(8), dp(8), dp(8), dp(8)); setOnClickListener { onClick() }
        }

    private fun sectionTitle(textValue: String): TextView = label(textValue, 18f, white, true).apply {
        setPadding(dp(4), dp(12), dp(4), dp(8)); includeFontPadding = false
    }

    private fun panelBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12))
        background = rounded(Color.rgb(13, 22, 36), Color.rgb(35, 91, 145), 16f)
    }

    private fun buildShell() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(8)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            header.setPadding(0, insets.systemWindowInsetTop + dp(8), 0, dp(8)); insets
        }
        header.addView(label("NATTAWUT SMARTGAUGE V3", 23f, cyan, true))
        header.addView(label("RACING ECU DASHBOARD  •  HONDA PGM-FI  •  BLUETOOTH SPP", 9f, muted, true))
        status = label("● DISCONNECTED", 12f, Color.rgb(255, 70, 75), true).apply { setPadding(0, dp(8), 0, dp(8)) }
        header.addView(status)
        root.addView(header)
        connectBtn = styledButton("CONNECT TO ESP32", Color.rgb(55, 65, 82)) { if (socket == null) showPairedDevices() else disconnect() }
        root.addView(connectBtn, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        val navScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("DASHBOARD", "GRAPH", "DTC", "ECU", "MAP", "CONTROL", "GPS", "BLUETOOTH", "SETTINGS", "LOGGER").forEach { page ->
            val caption = when(page) { "DASHBOARD"->"HOME"; "BLUETOOTH"->"BT"; else->page }
            val b = styledButton(caption, Color.rgb(28, 43, 65)) { showPage(page) }
            nav.addView(b, LinearLayout.LayoutParams(dp(104), dp(42)).apply { setMargins(dp(3), dp(2), dp(3), dp(5)) })
        }
        navScroll.addView(nav); root.addView(navScroll)
        val scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(12)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(label("READ-ONLY ECU TELEMETRY  •  ECU WRITE / REMAP DISABLED", 9f, muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, dp(4)) })
        setContentView(root)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun showPage(page: String) {
        activePage = page
        body.removeAllViews()
        when (page) {
            "DASHBOARD" -> dashboard()
            "GRAPH" -> graphPage()
            "LOGGER" -> loggerPage()
            "DTC" -> dtcPage()
            "ECU" -> ecuPage()
            "MAP" -> mapPage()
            "CONTROL" -> controlPage()
            "GPS" -> gpsPage()
            "BLUETOOTH" -> bluetoothPage()
            "SETTINGS" -> settingsPage()
        }
    }

    // Convert dp to physical pixels. LayoutParams dimensions are pixels, not dp.
    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun card(key: String): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(12), dp(8), dp(5))
            setBackgroundColor(panel)
            minimumHeight = dp(116)
        }

        // Keep the title in its own top row so it cannot collide with the value.
        val title = label(key, 13f, cyan, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        box.addView(title, LinearLayout.LayoutParams(-1, dp(25)))

        // The value receives the remaining card height and is vertically centered.
        val value = label(telemetry[key] ?: "--", 28f, white, true).apply {
            tag = "value:$key"
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = false
            isSingleLine = true
        }
        box.addView(value, LinearLayout.LayoutParams(-1, 0, 1f))

        val unit = label(units[key] ?: "", 10f, muted, false).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        box.addView(unit, LinearLayout.LayoutParams(-1, dp(17)))
        return box
    }

    private fun rawDataPanel(): LinearLayout {
        val panelBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 9, 10, 9)
            setBackgroundColor(panel)
        }
        panelBox.addView(label("RAW ECU DATA", 13f, cyan, true))
        val raw = label(latestRawFrame, 10f, white, false).apply {
            tag = "latest_raw_data"
            setTextIsSelectable(true)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        rawDataView = raw
        panelBox.addView(raw, LinearLayout.LayoutParams(-1, -2))
        return panelBox
    }

    private fun dashboard() {
        body.addView(sectionTitle("LIVE ENGINE DASHBOARD"))
        val hero = panelBox()
        hero.addView(label("ENGINE RPM", 12f, cyan, true).apply { gravity = Gravity.CENTER })
        val rpmGauge = RpmGauge(this).apply { tag = "rpm_gauge" }
        hero.addView(rpmGauge, LinearLayout.LayoutParams(-1, dp(188)))
        hero.addView(label("RPM  •  LIVE ECU DATA", 10f, muted, true).apply { gravity = Gravity.CENTER })
        body.addView(hero)

        body.addView(sectionTitle("GPS VEHICLE SPEED"))
        val speedPanel = panelBox()
        speedPanel.addView(label("SPEED", 12f, cyan, true).apply { gravity = Gravity.CENTER })
        speedPanel.addView(label(telemetry["SPEED"] ?: "--", 34f, white, true).apply {
            tag = "value:SPEED"
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
        })
        speedPanel.addView(label("km/h  •  ${if (speedSource == "PHONE_GPS") "PHONE GPS" else "ESP32 GPS"}", 11f, muted, false).apply {
            tag = "speed_source_caption"
            gravity = Gravity.CENTER
        })
        body.addView(speedPanel)

        body.addView(sectionTitle("ENGINE SENSOR DATA"))
        val keys = listOf("TPS", "ECT", "IAT", "BAT", "AFR", "O2", "MAP", "INJ", "IGN", "STFT", "LTFT")
        keys.forEach { key ->
            val sensor = card(key).apply {
                minimumHeight = dp(88)
                background = rounded(panel, Color.rgb(29, 75, 115), 12f)
            }
            body.addView(sensor, LinearLayout.LayoutParams(-1, dp(88)).apply {
                setMargins(dp(2), dp(4), dp(2), dp(4))
            })
        }

        body.addView(sectionTitle("QUICK ACCESS"))
        listOf(
            "GRAPH" to "LIVE GRAPH",
            "LOGGER" to "DATA LOGGER",
            "DTC" to "DTC DIAGNOSTIC",
            "ECU" to "ECU INFORMATION",
            "MAP" to "หารีแมพกล่อง ECU",
            "GPS" to "GPS SPEED SOURCE",
            "BLUETOOTH" to "BLUETOOTH"
        ).forEach { (page, title) ->
            body.addView(styledButton(title, Color.rgb(0, 92, 190)) { showPage(page) },
                LinearLayout.LayoutParams(-1, dp(46)).apply {
                    setMargins(dp(2), dp(4), dp(2), dp(4))
                })
        }

        body.addView(sectionTitle("LATEST ECU FRAME"))
        body.addView(rawDataPanel())
        body.addView(label("ความเร็วใช้ GPS จากมือถือหรือ GPS ที่ต่อกับ ESP32 เท่านั้น ไม่ใช้ความเร็วจาก ECU", 10f, muted, false)
            .apply { setPadding(dp(4), dp(8), dp(4), dp(4)) })
    }

    private fun graphPage() {
        body.addView(sectionTitle("LIVE GRAPH ANALYZER"))
        val picker = Spinner(this)
        val options = arrayOf("RPM", "TPS", "ECT", "IAT", "BAT", "SPEED", "MAP", "AFR", "O2")
        picker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        body.addView(picker)
        graph = LiveGraph(this).apply { metric = selectedMetric; background = rounded(Color.rgb(8, 13, 22), Color.rgb(35, 91, 145), 14f) }
        body.addView(graph, LinearLayout.LayoutParams(-1, dp(270)).apply { topMargin = dp(8) })
        picker.setSelection(options.indexOf(selectedMetric).coerceAtLeast(0))
        picker.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { selectedMetric = options[position]; graph.metric = selectedMetric; graph.invalidate() }
        }
        body.addView(label("ข้อมูลกราฟมาจาก telemetry ที่ได้รับจริงผ่าน Bluetooth เท่านั้น", 11f, muted, false).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun loggerPage() {
        body.addView(sectionTitle("RAW ECU LOGGER"))
        body.addView(label("บันทึกทุกบรรทัดที่ ESP32 ส่งผ่าน Bluetooth รวม telemetry และเฟรม RAW ที่เปิดใช้ใน Firmware", 12f, muted, false))
        val stats = panelBox()
        stats.addView(label("SESSION RECORDS", 10f, cyan, true))
        val count = label("${synchronized(logRows) { logRows.size }} RECORDS", 28f, white, true).apply { tag = "logger_count" }
        stats.addView(count); body.addView(stats)
        body.addView(sectionTitle("LATEST RAW FRAME")); body.addView(rawDataPanel())
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val start = styledButton(if (logging) "STOP CAPTURE" else "START CAPTURE", if (logging) Color.rgb(150,45,45) else Color.rgb(0,130,65)) { logging = !logging; showPage("LOGGER"); toast(if (logging) "เริ่มบันทึกข้อมูลแล้ว" else "หยุดบันทึกแล้ว") }
        val export = styledButton("EXPORT CSV", Color.rgb(0,100,200)) {
            if (logRows.isEmpty()) toast("ยังไม่มีข้อมูล กรุณาเชื่อมต่อและเริ่มบันทึก") else {
                val csv = buildString { append("record_type,time_ms,raw_table,raw_length,raw_hex,"); append(telemetry.keys.joinToString(",")).append('\n'); synchronized(logRows) { logRows.forEach { append(it).append('\n') } } }
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "text/csv"; putExtra(Intent.EXTRA_TITLE, "Nattawut_RAW_ECU_Log.csv") }; pendingCsv = csv; startActivityForResult(intent,77)
            }
        }
        actions.addView(start, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(0, dp(8), dp(4), 0) }); actions.addView(export, LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(4), dp(8), 0, 0) }); body.addView(actions)
        body.addView(label("หมายเหตุ: บันทึกข้อมูลที่ผ่าน Serial/Bluetooth API ของ Firmware ไม่ใช่การดักจับสัญญาณ K-Line แบบ passive", 10f, Color.YELLOW, false).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun updateLoggerCount() { body.findViewWithTag<TextView>("logger_count")?.text = "${synchronized(logRows) { logRows.size }} RECORDS" }

    private fun infoRow(title: String, value: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(8)) }
        row.addView(label(title, 12f, muted, false), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label(value, 12f, white, true), LinearLayout.LayoutParams(0, -2, 1f)); return row
    }

    private fun dtcPage() {
        body.addView(sectionTitle("DTC DIAGNOSTICS"))
        val box = panelBox(); box.addView(label("HONDA PGM-FI  •  ECU S4", 13f, cyan, true))
        box.addView(label("ระบบอ่าน/ลบรหัสขัดข้องผ่านแอปยังรอคำสั่ง Bluetooth API ที่ผ่านการตรวจสอบกับ Firmware V8 และ ECU จริง", 12f, muted, false).apply { setPadding(0, dp(8), 0, dp(8)) })
        box.addView(label("ไม่มีการสร้างรหัส DTC ตัวอย่างหรือแสดงผลปลอม", 11f, Color.YELLOW, true)); body.addView(box)
        val read = styledButton("SCAN STORED / CURRENT DTC", Color.rgb(0,100,190)) { toast("Firmware ที่ใช้อยู่ยังไม่เปิด API DTC ผ่าน Bluetooth — ยังไม่ส่งคำสั่งไป ECU") }
        val clear = styledButton("CLEAR DTC  •  LOCKED", Color.rgb(140,40,45)) { toast("การลบ DTC ยังล็อกไว้จนกว่าจะยืนยันคำสั่งและผลตอบกลับจาก ECU") }
        body.addView(read, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) }); body.addView(clear, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
    }

    private fun ecuPage() {
        body.addView(sectionTitle("ECU INFORMATION")); val box = panelBox()
        listOf("Motorcycle" to "Honda Wave 110i 2009", "ECU family" to "Keihin S4 (user supplied)", "Protocol" to "K-Line / ISO 14230-4", "Baud rate" to "10400", "Bluetooth" to if (socket != null) "CONNECTED" else "DISCONNECTED", "Telemetry packets" to receivedPackets.toString(), "ECU identity" to "ยังไม่ได้อ่านจาก ECU ใน session นี้", "Write / Flash / Remap" to "DISABLED").forEach { box.addView(infoRow(it.first, it.second)) }
        body.addView(box); body.addView(styledButton("REFRESH LINK STATUS", Color.rgb(0,100,190)) { showPage("ECU") })
    }

    private fun mapPage() {
        body.addView(sectionTitle("MAP FILE MANAGER")); val box = panelBox()
        box.addView(label("MAP FILE WORKSPACE", 13f, cyan, true)); box.addView(label("เลือกไฟล์ .map จากมือถือเพื่อจัดเก็บและเตรียมวิเคราะห์ได้ แต่การอัปโหลดเข้า ECU/SD ของบอร์ดยังไม่เปิด เพราะ Firmware ยังไม่มี MAP transfer API ที่ยืนยันแล้ว", 12f, muted, false).apply { setPadding(0, dp(8), 0, dp(8)) }); body.addView(box)
        body.addView(styledButton("SELECT MAP FILE", Color.rgb(0,100,190)) { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }; startActivityForResult(i, 78) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        body.addView(label("ECU MAP WRITE / FLASH: DISABLED", 11f, Color.YELLOW, true).apply { setPadding(0, dp(10), 0, 0) })
    }

    private fun controlPage() {
        body.addView(sectionTitle("REMOTE CONTROL PANEL")); val box = panelBox()
        box.addView(label("LOCAL APP CONTROLS", 13f, cyan, true)); box.addView(label("ปุ่มด้านล่างเป็นการนำทางและตั้งค่าภายในแอปเท่านั้น ยังไม่ส่งคำสั่งควบคุม ECU หรือฮาร์ดแวร์จนกว่าจะมี API ที่รองรับ", 12f, muted, false).apply { setPadding(0, dp(8), 0, dp(8)) }); body.addView(box)
        listOf("Dashboard" to "DASHBOARD", "Live Graph" to "GRAPH", "RAW Logger" to "LOGGER", "DTC Diagnostics" to "DTC", "Bluetooth" to "BLUETOOTH").forEach { (t,p) -> body.addView(styledButton(t, Color.rgb(0,90,175)) { showPage(p) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) }) }
    }

    private fun gpsPage() {
        body.addView(sectionTitle("GPS VEHICLE SPEED"))
        val box = panelBox()
        box.addView(label("CURRENT SPEED", 12f, cyan, true).apply { gravity = Gravity.CENTER })
        box.addView(label(telemetry["SPEED"] ?: "--", 38f, white, true).apply {
            tag = "gps_speed_value"
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        })
        box.addView(label("km/h", 12f, muted, false).apply { gravity = Gravity.CENTER })
        box.addView(label(speedStatusText(), 11f, muted, false).apply {
            tag = "gps_speed_status"
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        })
        body.addView(box)

        body.addView(sectionTitle("SELECT SPEED SOURCE"))
        body.addView(styledButton(
            if (speedSource == "PHONE_GPS") "✓ PHONE GPS  •  SELECTED" else "USE PHONE GPS",
            if (speedSource == "PHONE_GPS") Color.rgb(0, 130, 75) else Color.rgb(0, 92, 190)
        ) { selectSpeedSource("PHONE_GPS") }, LinearLayout.LayoutParams(-1, dp(50)).apply {
            bottomMargin = dp(8)
        })
        body.addView(styledButton(
            if (speedSource == "ESP32_GPS") "✓ ESP32 GPS  •  SELECTED" else "USE ESP32 GPS MODULE",
            if (speedSource == "ESP32_GPS") Color.rgb(0, 130, 75) else Color.rgb(0, 92, 190)
        ) { selectSpeedSource("ESP32_GPS") }, LinearLayout.LayoutParams(-1, dp(50)))

        body.addView(label(
            "PHONE GPS: อ่าน GPS ภายในมือถือ แล้วส่งความเร็วผ่าน Bluetooth ไปแสดงทั้งบนแอปและจอ ESP32\n\nESP32 GPS: อ่านความเร็วจากโมดูล GPS ที่ต่อกับบอร์ด ESP32 CYD แล้วส่งกลับมายังแอป\n\nไม่ใช้ค่า SPEED จาก ECU",
            11f, muted, false
        ).apply { setPadding(0, dp(12), 0, 0) })
    }

    private fun speedStatusText(): String = when (speedSource) {
        "PHONE_GPS" -> if (phoneGpsLastFixAt > 0L &&
            System.currentTimeMillis() - phoneGpsLastFixAt <= 3000L) "PHONE GPS • FIX OK" else "PHONE GPS • WAITING FOR FIX"
        else -> if (telemetry["SPEED"] != "--") "ESP32 GPS • DATA RECEIVED" else "ESP32 GPS • WAITING FOR FIX"
    }

    private fun updateSpeedDisplays() {
        runOnUiThread {
            body.findViewWithTag<TextView>("gps_speed_value")?.text = telemetry["SPEED"] ?: "--"
            body.findViewWithTag<TextView>("gps_speed_status")?.text = speedStatusText()
            body.findViewWithTag<TextView>("speed_source_caption")?.text =
                "km/h  •  ${if (speedSource == "PHONE_GPS") "PHONE GPS" else "ESP32 GPS"}"
            if (activePage == "DASHBOARD") updateDashboardValues()
            if (activePage == "GRAPH" && selectedMetric == "SPEED") graph.invalidate()
        }
    }

    private fun selectSpeedSource(source: String) {
        if (source != "PHONE_GPS" && source != "ESP32_GPS") return
        speedSource = source
        getSharedPreferences("smartgauge_settings", MODE_PRIVATE).edit()
            .putString("speed_source", source).apply()
        phoneGpsLastFixAt = 0L
        telemetry["SPEED"] = "--"
        synchronized(histories) { histories["SPEED"]?.clear() }
        sendBluetoothLine(if (source == "PHONE_GPS") "SPEED_SOURCE,PHONE" else "SPEED_SOURCE,GPS")
        if (source == "PHONE_GPS") startPhoneGps() else stopPhoneGps()
        showPage("GPS")
        updateSpeedDisplays()
        toast(if (source == "PHONE_GPS") "เลือก GPS มือถือแล้ว" else "เลือก GPS บนบอร์ด ESP32 แล้ว")
    }

    private fun sendBluetoothLine(command: String) {
        try {
            val activeSocket = socket ?: return
            synchronized(activeSocket) {
                activeSocket.outputStream.write((command.trim() + "\n").toByteArray(Charsets.UTF_8))
                activeSocket.outputStream.flush()
            }
        } catch (_: Exception) {
            // Connection watchdog will report a disconnected link; do not fake a send.
        }
    }

    private fun startPhoneGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!phoneGpsPermissionRequested) {
                phoneGpsPermissionRequested = true
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 42)
            }
            return
        }
        if (socket == null) {
            toast("เชื่อมต่อ ESP32 ก่อน เพื่อเริ่มส่งความเร็ว GPS เบื้องหลัง")
            return
        }
        startPhoneGpsService()
    }

    private fun startPhoneGpsService() {
        val intent = Intent(this, SmartGaugeService::class.java).apply { action = SmartGaugeService.ACTION_START }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun stopPhoneGps() {
        try { stopService(Intent(this, SmartGaugeService::class.java)) } catch (_: Exception) { }
        phoneGpsLastFixAt = 0L
    }

    override fun onResume() {
        super.onResume()
        if (speedSource == "PHONE_GPS" && socket != null) startPhoneGpsService()
    }

    override fun onPause() {
        // Keep the foreground GPS/Bluetooth bridge alive when the dashboard is backgrounded.
        super.onPause()
    }

    private fun bluetoothPage() {
        body.addView(sectionTitle("BLUETOOTH MANAGER")); val box = panelBox()
        box.addView(label("NATTAWUT ECU", 22f, cyan, true).apply { gravity = Gravity.CENTER })
        box.addView(label(if (socket != null) "● CONNECTED" else "● DISCONNECTED", 16f, if (socket != null) Color.GREEN else Color.RED, true).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) })
        box.addView(label("Bluetooth Classic SPP / RFCOMM", 11f, muted, false).apply { gravity = Gravity.CENTER }); body.addView(box)
        body.addView(styledButton(if (socket == null) "CONNECT PAIRED DEVICE" else "DISCONNECT", Color.rgb(0,105,200)) { if (socket == null) showPairedDevices() else disconnect() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        body.addView(label("จับคู่ชื่ออุปกรณ์ Nattawut ECU ในการตั้งค่า Bluetooth ของ Android ก่อนเชื่อมต่อ", 11f, muted, false).apply { setPadding(0, dp(8), 0, 0) })
    }

    private fun settingsPage() {
        body.addView(sectionTitle("SMARTGAUGE SETTINGS")); val box = panelBox()
        box.addView(label("ACCENT COLOR", 12f, cyan, true))
        val colors = listOf(Color.rgb(0,160,255), Color.RED, Color.GREEN, Color.MAGENTA, Color.YELLOW)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        colors.forEach { c -> val b = View(this).apply { background = rounded(c, Color.WHITE, 30f); setOnClickListener { accent = c; themeColor = c; toast("เปลี่ยนสีเน้นของแอปแล้ว"); showPage("SETTINGS") } }; row.addView(b, LinearLayout.LayoutParams(dp(42), dp(42)).apply { setMargins(dp(8), dp(10), dp(8), dp(10)) }) }
        box.addView(row); box.addView(infoRow("Telemetry protocol", "NATTWUT,1 CSV")); box.addView(infoRow("Bluetooth", "Classic SPP")); box.addView(infoRow("Missing ECU values", "-- / NA")); body.addView(box)
        body.addView(styledButton("DISCONNECT BLUETOOTH", Color.rgb(130,40,45)) { disconnect() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
    }

    private fun requestBtPermission() {
    if (Build.VERSION.SDK_INT >= 31) {
        val permissions = mutableListOf<String>()

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), 41)
        }
    }
}

    override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    if (requestCode == 41 && Build.VERSION.SDK_INT >= 31) {
        val connectGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        val scanGranted =
            checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

        if (!connectGranted || !scanGranted) {
            toast("กรุณาอนุญาต Nearby devices / Bluetooth")
        } else {
            toast("อนุญาต Bluetooth แล้ว กรุณากด CONNECT อีกครั้ง")
        }
    }
    if (requestCode == 42) {
        phoneGpsPermissionRequested = false
        val granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (granted && speedSource == "PHONE_GPS") {
            startPhoneGps()
        } else {
            toast("ต้องอนุญาตตำแหน่งแบบ Precise เพื่อใช้ GPS มือถือ")
        }
    }
}

    private fun showPairedDevices() {
        if (Build.VERSION.SDK_INT >= 31 &&
    (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
     checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
) {
            requestBtPermission(); return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { toast("โทรศัพท์ไม่รองรับ Bluetooth Classic"); return }
        if (!adapter.isEnabled) { toast("กรุณาเปิด Bluetooth ก่อน"); return }
        val devices = adapter.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) { toast("จับคู่ ESP32 ชื่อ Nattawut ECU ใน Settings > Bluetooth ก่อน"); return }
        val names = devices.map { "${it.name ?: "Unknown"} (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("เลือกอุปกรณ์ SmartGauge").setItems(names) { _, which -> connect(devices[which]) }
            .setNegativeButton("ยกเลิก", null).show()
    }
private fun connect(device: BluetoothDevice) {
    if (Build.VERSION.SDK_INT >= 31 &&
    (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
     checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
) {
    requestBtPermission()
    return
}

    connectBtn.isEnabled = false
    status.text = "● CONNECTING..."
    status.setTextColor(Color.YELLOW)

    thread {
        var newSocket: BluetoothSocket? = null

        try {

            val connectedSocket =
                device.createRfcommSocketToServiceRecord(sppUuid)

            newSocket = connectedSocket
            connectedSocket.connect()

            socket = connectedSocket
            SmartGaugeRuntime.socket = connectedSocket
            val sourceCommand = if (speedSource == "PHONE_GPS") "SPEED_SOURCE,PHONE\n" else "SPEED_SOURCE,GPS\n"
            connectedSocket.outputStream.write(("PING\nSTATUS\n" + sourceCommand).toByteArray(Charsets.UTF_8))
            connectedSocket.outputStream.flush()
            if (speedSource == "PHONE_GPS") {
                runOnUiThread { startPhoneGpsService() }
            }

            runOnUiThread {
                status.text = "● CONNECTED • WAITING DATA"
                status.setTextColor(Color.GREEN)
                connectBtn.text = "DISCONNECT"
                connectBtn.isEnabled = true
            }

            readLoop(connectedSocket)

        } catch (e: Exception) {
            try {
                newSocket?.close()
            } catch (_: Exception) {
            }

            socket = null
            SmartGaugeRuntime.socket = null

            runOnUiThread {
                status.text = "● CONNECT FAILED: ${e.message}"
                status.setTextColor(Color.RED)
                connectBtn.text = "CONNECT"
                connectBtn.isEnabled = true
            }
        }
    }
}


    
private fun readLoop(s: BluetoothSocket) {
    readerThread = thread {
        try {
            runOnUiThread {
                if (socket === s) {
                    status.text = "● CONNECTED • WAITING DATA"
                    status.setTextColor(Color.YELLOW)
                }
            }

            val reader = BufferedReader(
                InputStreamReader(s.inputStream, Charsets.UTF_8)
            )

            while (!Thread.currentThread().isInterrupted) {
                val line = reader.readLine() ?: break

                if (line.isNotBlank()) {
                    parsePacket(line)
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                if (socket === s) {
                    status.text = "● READ ERROR: ${e.message}"
                    status.setTextColor(Color.RED)
                }
            }
            return@thread
        }

        runOnUiThread {
            if (socket === s) {
                socket = null
                status.text = "● DISCONNECTED"
                status.setTextColor(Color.RED)
                connectBtn.text = "CONNECT"
                connectBtn.isEnabled = true
            }
        }
    }
 }

    

    
private fun parsePacket(line: String) {

    val cleanLine = line.trim().removePrefix("\uFEFF")
    if (cleanLine.isEmpty()) return

    // Preserve every complete line received over Bluetooth while logging.
    if (logging) {
        val stamp = System.currentTimeMillis().toString()
        val escaped = cleanLine.replace("\\", "\\\\").replace("\"", "\"\"")
        val emptyTelemetry = telemetry.keys.joinToString(",") { "" }
        synchronized(logRows) {
            logRows.add(listOf("BT_RAW", stamp, "", "", "\"$escaped\"").joinToString(",") + "," + emptyTelemetry)
        }
    }
    latestRawFrame = cleanLine
    val p = cleanLine.split(',').map { it.trim() }

    if (p.firstOrNull() == "NATTWUT_ACK" || p.firstOrNull() == "NATTWUT_STATUS") {
        runOnUiThread {
            rawDataView?.text = latestRawFrame
            updateLoggerCount()
            if (p.firstOrNull() == "NATTWUT_STATUS") {
                status.text = "● LINK OK • ${p.drop(2).joinToString(" • ")}"
                status.setTextColor(Color.GREEN)
            }
        }
        return
    }

    // RAW frame sent separately by ESP32; keep it in the same session CSV.
    if (p.size >= 6 && p[0] == "NATTWUT_RAW" && p[1] == "1") {
        val timeMs = p[2].toLongOrNull() ?: System.currentTimeMillis()
        val table = p[3]
        val length = p[4]
        val hex = p.drop(5).joinToString(" ")
        latestRawFrame = "t=$timeMs ms | table=$table | len=$length\n$hex"
        if (logging) synchronized(logRows) {
            val emptyTelemetry = telemetry.keys.joinToString(",") { "" }
            logRows.add(listOf("RAW", timeMs.toString(), table, length, ("\"" + hex + "\"")).joinToString(",") + "," + emptyTelemetry)
        }
        runOnUiThread {
            rawDataView?.text = latestRawFrame
            updateLoggerCount()
        }
        return
    }

    // รับข้อมูลตั้งแต่ 15 ช่องขึ้นไป
    if (p.size < 15 || p[0] != "NATTWUT" || p[1] != "1") {
        runOnUiThread {
            status.text = "● INVALID PACKET: ${cleanLine.take(55)}"
            status.setTextColor(Color.RED)
        }
        return
    }

    val keys = listOf(
        "RPM", "TPS", "ECT", "IAT", "BAT",
        "O2", "AFR", "SPEED", "INJ", "IGN",
        "MAP", "STFT", "LTFT"
    )

    val row = mutableMapOf<String, String>()

    keys.forEachIndexed { i, key ->
        if (key == "SPEED" && speedSource == "PHONE_GPS") return@forEachIndexed
        row[key] = if (p[i + 2] == "NA" || p[i + 2].isBlank()) "--" else p[i + 2]
    }

    synchronized(telemetry) {
        row.forEach { (k, v) -> telemetry[k] = v }
    }

    synchronized(this) {
        receivedPackets++
        lastPacketAt = System.currentTimeMillis()
        telemetryTimedOut = false
    }

    synchronized(histories) {
        keys.forEach { key ->
            if (key == "SPEED" && speedSource == "PHONE_GPS") return@forEach
            val value = row[key]?.toFloatOrNull() ?: return@forEach
            val list = histories.getOrPut(key) { mutableListOf() }
            list.add(value)
            while (list.size > 120) list.removeAt(0)
        }
    }

    val rpm = row["RPM"]?.toFloatOrNull()
    if (rpm != null) {
        synchronized(samples) {
            samples.add(rpm)
            while (samples.size > 100) samples.removeAt(0)
        }
    }

    if (logging) {
        val stamp = System.currentTimeMillis().toString()
        synchronized(logRows) {
            logRows.add(
                listOf("TELEMETRY", stamp, "", "", "").joinToString(",") + "," +
                keys.joinToString(",") { key ->
                    if (key == "SPEED" && speedSource == "PHONE_GPS") telemetry["SPEED"] ?: "NA"
                    else if (p[keys.indexOf(key) + 2] == "NA") "NA" else (row[key] ?: "0")
                }
            )
        }
        runOnUiThread { updateLoggerCount() }
    }

    runOnUiThread {
        status.text = "● DATA RECEIVED • #$receivedPackets"
        status.setTextColor(Color.GREEN)

        if (speedSource == "ESP32_GPS") updateSpeedDisplays()
        if (activePage == "DASHBOARD") {
            updateDashboardValues()
        } else if (activePage == "GRAPH") {
            graph.invalidate()
        }
    }
}

    private fun updateDashboardValues() {
        fun walk(v: View) {
            if (v is TextView && v.tag is String && (v.tag as String).startsWith("value:")) {
                val key = (v.tag as String).substringAfter("value:")
                v.text = telemetry[key] ?: "--"
            }
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(body)
        body.findViewWithTag<View>("rpm_gauge")?.invalidate()
    }

    private var pendingCsv: String? = null
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 78 && resultCode == RESULT_OK && data?.data != null) {
            toast("เลือกไฟล์ MAP แล้ว: ${data.data?.lastPathSegment ?: "ไฟล์"} — ยังไม่ส่งเข้า ECU")
        }
        if (requestCode == 77 && resultCode == RESULT_OK && data?.data != null) {
            try {
                contentResolver.openOutputStream(data.data!!)?.bufferedWriter()?.use { it.write(pendingCsv ?: "") }
                toast("ส่งออก CSV สำเร็จ")
            } catch (e: Exception) { toast("บันทึกไฟล์ไม่สำเร็จ: ${e.message}") }
        }
    }

    private fun disconnect() {
        stopPhoneGps()
        try { readerThread?.interrupt(); socket?.close() } catch (_: Exception) { }
        SmartGaugeRuntime.socket = null
        socket = null
        lastPacketAt = 0L
        telemetryTimedOut = true
        synchronized(telemetry) {
            telemetry.keys.filter { it != "SPEED" || speedSource != "PHONE_GPS" }
                .forEach { telemetry[it] = "--" }
        }
        if (speedSource == "ESP32_GPS") telemetry["SPEED"] = "--"
        if (activePage == "DASHBOARD") updateDashboardValues()
        updateSpeedDisplays()
        status.text = "● DISCONNECTED"; status.setTextColor(Color.RED)
        connectBtn.text = "CONNECT"; connectBtn.isEnabled = true
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    override fun onDestroy() {
        // The foreground service and its shared SPP socket intentionally outlive this Activity.
        super.onDestroy()
    }

    inner class RpmGauge(context: android.content.Context) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f; val cy = height * 0.78f
            val radius = minOf(width * 0.40f, height * 0.68f)
            val rect = android.graphics.RectF(cx-radius, cy-radius, cx+radius, cy+radius)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(9).toFloat(); p.strokeCap = Paint.Cap.ROUND
            p.color = Color.rgb(0, 155, 255); canvas.drawArc(rect, 135f, 150f, false, p)
            p.color = Color.rgb(255, 190, 0); canvas.drawArc(rect, 285f, 55f, false, p)
            p.color = Color.rgb(245, 55, 55); canvas.drawArc(rect, 340f, 65f, false, p)
            p.strokeWidth = dp(2).toFloat(); p.color = Color.WHITE
            for (i in 0..10) {
                val a = Math.toRadians((135.0 + i * 27.0)); val inner = radius - dp(17); val outer = radius - dp(5)
                canvas.drawLine(cx + kotlin.math.cos(a).toFloat()*inner, cy + kotlin.math.sin(a).toFloat()*inner,
                    cx + kotlin.math.cos(a).toFloat()*outer, cy + kotlin.math.sin(a).toFloat()*outer, p)
            }
            val rpm = telemetry["RPM"]?.toFloatOrNull() ?: 0f
            val angle = Math.toRadians(135.0 + (rpm.coerceIn(0f, 10000f) / 10000f) * 270.0)
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(4).toFloat(); p.color = Color.WHITE
            canvas.drawLine(cx, cy, cx + kotlin.math.cos(angle).toFloat()*(radius-dp(24)), cy + kotlin.math.sin(angle).toFloat()*(radius-dp(24)), p)
            p.style = Paint.Style.FILL; p.color = cyan; canvas.drawCircle(cx, cy, dp(7).toFloat(), p)
            p.color = white; p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.DEFAULT_BOLD; p.textSize = sp(31f)
            canvas.drawText(telemetry["RPM"] ?: "--", cx, cy-dp(19), p)
            p.textSize = sp(12f); p.color = muted; canvas.drawText("RPM x1000", cx, cy+dp(10), p)
        }
    }

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity

    inner class LiveGraph(context: android.content.Context) : View(context) {
        var metric = "RPM"
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(panel)
            paint.color = muted; paint.strokeWidth = 1f; paint.style = Paint.Style.STROKE
            for (i in 1..4) canvas.drawLine(20f, height * i / 5f, width - 12f, height * i / 5f, paint)
            val vals = synchronized(histories) { histories[metric]?.toList().orEmpty() }
            if (vals.size > 1) {
                val maxV = max(1000f, vals.maxOrNull() ?: 1000f)
                paint.color = cyan; paint.strokeWidth = 3f
                for (i in 1 until vals.size) {
                    val x1 = 20f + (width - 40f) * (i - 1) / (vals.size - 1)
                    val x2 = 20f + (width - 40f) * i / (vals.size - 1)
                    val y1 = height - 15f - (height - 30f) * vals[i - 1] / maxV
                    val y2 = height - 15f - (height - 30f) * vals[i] / maxV
                    canvas.drawLine(x1, y1, x2, y2, paint)
                }
            } else {
                paint.style = Paint.Style.FILL; paint.color = muted; paint.textSize = 13f
                canvas.drawText("Waiting for $metric telemetry…", 24f, height / 2f, paint)
            }
        }
    }
}
