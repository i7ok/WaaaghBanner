package com.waaagh.battery.ui

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.waaagh.battery.BannerController
import com.waaagh.battery.R
import com.waaagh.battery.core.DisplayState
import com.waaagh.battery.cover.CoverOverlayService
import com.waaagh.battery.core.Mode
import com.waaagh.battery.events.CallMonitor
import com.waaagh.battery.events.KeepAliveService
import com.waaagh.battery.events.WaaaghNotificationListener
import com.waaagh.battery.widget.WidgetUpdater

/** Test screen: charge slider, simulated events, permissions, shake method. */
class TestActivity : Activity() {
    private lateinit var banner: BannerView
    private lateinit var status: TextView
    private lateinit var levelLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var testBox: CheckBox
    private val main = Handler(Looper.getMainLooper())
    private var testNotifCount = 0

    private val listener: (DisplayState) -> Unit = { s ->
        banner.show(BannerController.renderer.base, BannerController.renderer.renderLetters(s.spec), s)
        updateStatus()
    }

    private val statusTicker = object : Runnable {
        override fun run() { updateStatus(); main.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BannerController.init(this)
        KeepAliveService.tryStart(this)

        val dp = resources.displayMetrics.density
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt())
            setBackgroundColor(Color.BLACK)
        }
        fun text(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setTextColor(Color.LTGRAY); setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }
        fun row(vararg v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
        }

        banner = BannerView(this)
        col.addView(banner, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        levelLabel = text("", 16f).apply { gravity = Gravity.CENTER; setTextColor(Color.WHITE) }
        col.addView(levelLabel)

        testBox = CheckBox(this).apply {
            text = "Тестовый заряд (слайдер вместо батареи)"
            setTextColor(Color.LTGRAY)
            setOnCheckedChangeListener { _, on -> BannerController.setTestLevel(if (on) seek.progress else null) }
        }
        col.addView(testBox)
        seek = SeekBar(this).apply {
            max = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) {
                        if (!testBox.isChecked) testBox.isChecked = true
                        BannerController.setTestLevel(p)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addView(seek)

        col.addView(text("Имитация событий"))
        col.addView(row(
            button("Уведомление") { BannerController.onNewNotification() },
            button("Входящий звонок") { BannerController.onCallRinging() },
            button("Завершить звонок") { BannerController.onCallEnded() },
        ))
        col.addView(row(
            button("Настоящее уведомление") { postTestNotification(update = false) },
            button("Обновить его") { postTestNotification(update = true) },
        ))

        col.addView(text("Тряска на виджете"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rFlip = RadioButton(this).apply { text = "ViewFlipper"; setTextColor(Color.LTGRAY); id = View.generateViewId() }
        val rMan = RadioButton(this).apply { text = "Ручная"; setTextColor(Color.LTGRAY); id = View.generateViewId() }
        group.addView(rFlip); group.addView(rMan)
        group.check(if (WidgetUpdater.shakeMethod(this) == WidgetUpdater.ShakeMethod.FLIPPER) rFlip.id else rMan.id)
        group.setOnCheckedChangeListener { _, id ->
            WidgetUpdater.setShakeMethod(this, if (id == rFlip.id) WidgetUpdater.ShakeMethod.FLIPPER else WidgetUpdater.ShakeMethod.MANUAL)
            BannerController.republish()
        }
        col.addView(group)

        col.addView(text("Доступы"))
        col.addView(button("Внешний экран: только баннер, всегда включён") { openAccessibilitySettings() })
        col.addView(row(
            button("Уведомления") { openListenerSettings() },
            button("Звонки") { requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), REQ_PHONE) },
        ))
        col.addView(row(
            button("Без ограничений батареи") { requestNoBatteryLimits() },
            CheckBox(this).apply {
                text = "Фоновая служба"; setTextColor(Color.LTGRAY)
                isChecked = KeepAliveService.enabled(this@TestActivity)
                setOnCheckedChangeListener { _, on -> KeepAliveService.setEnabled(this@TestActivity, on) }
            },
        ))

        status = text("", 13f)
        col.addView(status)

        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(col) })
    }

    override fun onStart() {
        super.onStart()
        BannerController.refreshBatteryNow()
        BannerController.addListener(listener)
        main.post(statusTicker)
    }

    override fun onStop() {
        BannerController.removeListener(listener)
        main.removeCallbacks(statusTicker)
        super.onStop()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        CallMonitor.refresh(this)
        if (requestCode == REQ_POST && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) postTestNotification(false)
        updateStatus()
    }

    private fun updateStatus() {
        val s = BannerController.state
        val test = BannerController.testLevel
        levelLabel.text = "${s.level}%  ·  " + when (s.mode) {
            Mode.BATTERY -> "батарея"
            Mode.NOTIFICATION -> "УВЕДОМЛЕНИЕ"
            Mode.CALL -> "ЗВОНОК"
        } + if (test != null) "  (тест)" else ""
        if (test == null && testBox.isChecked) testBox.isChecked = false
        val pm = getSystemService(PowerManager::class.java)
        status.text = buildString {
            appendLine("Внешний экран: " + if (CoverOverlayService.running) CoverOverlayService.status else "служба выключена")
            appendLine("Реальный заряд: ${BannerController.realLevel}%")
            appendLine("Доступ к уведомлениям: ${yes(BannerController.notificationAccess || listenerEnabled())}")
            appendLine("Состояние звонка: ${yes(CallMonitor.hasPermission(this@TestActivity))}" +
                if (CallMonitor.active) " (слушаем)" else "")
            appendLine("Без ограничений батареи: ${yes(pm.isIgnoringBatteryOptimizations(packageName))}")
            appendLine("Виджетов: ${WidgetUpdater.widgetIds(this@TestActivity).size}, последнее обновление: ${WidgetUpdater.lastStatus}")
        }
    }

    private fun yes(b: Boolean) = if (b) "да" else "нет"

    private fun listenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openListenerSettings() {
        val cn = ComponentName(this, WaaaghNotificationListener::class.java)
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, cn.flattenToString())
        try { startActivity(detail) } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun openAccessibilitySettings() {
        val cn = ComponentName(this, CoverOverlayService::class.java)
        val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString())
        try { startActivity(detail) } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requestNoBatteryLimits() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    /** A real notification in the shade: new one, or an update of the same id. */
    private fun postTestNotification(update: Boolean) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST)
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(WaaaghNotificationListener.TEST_CHANNEL,
            "Тестовые уведомления", NotificationManager.IMPORTANCE_DEFAULT))
        if (!update) testNotifCount++
        val n = Notification.Builder(this, WaaaghNotificationListener.TEST_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_waaagh)
            .setContentTitle("WAAAGH! тест #$testNotifCount")
            .setContentText(if (update) "обновлено в ${System.currentTimeMillis() % 100000}" else "новое уведомление")
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(1000 + testNotifCount, n)     // same id when updating
    }

    companion object {
        private const val REQ_PHONE = 1
        private const val REQ_POST = 2
    }
}
