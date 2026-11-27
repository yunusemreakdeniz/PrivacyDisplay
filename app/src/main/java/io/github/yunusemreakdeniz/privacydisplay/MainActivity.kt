package io.github.yunusemreakdeniz.privacydisplay

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var mainSwitch: MaterialSwitch
    private var updatingSwitch = false
    private var startWhenReady = false

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        startWhenReady = intent.getBooleanExtra(EXTRA_START, false)

        mainSwitch = findViewById(R.id.main_switch)
        mainSwitch.setOnCheckedChangeListener { _, on ->
            if (updatingSwitch) return@setOnCheckedChangeListener
            if (on) tryStart() else PrivacyService.stop(this)
        }

        val intensity = findViewById<Slider>(R.id.intensity)
        intensity.value = prefs.intensity.roundTo(0.05f)
        intensity.addOnChangeListener { _, value, _ ->
            prefs.intensity = value
            showLabels()
            PrivacyService.refresh(this)
        }

        val idle = findViewById<Slider>(R.id.idle_check)
        idle.value = prefs.idleCheckSeconds.toFloat()
        idle.addOnChangeListener { _, value, _ ->
            prefs.idleCheckSeconds = value.toInt()
            showLabels()
        }

        findViewById<MaterialSwitch>(R.id.battery_saver).apply {
            isChecked = prefs.batterySaver
            idle.isEnabled = prefs.batterySaver
            setOnCheckedChangeListener { _, on ->
                prefs.batterySaver = on
                idle.isEnabled = on
                PrivacyService.refresh(this@MainActivity)
            }
        }

        findViewById<MaterialSwitch>(R.id.no_face).apply {
            isChecked = prefs.shieldWhenNoFace
            setOnCheckedChangeListener { _, on -> prefs.shieldWhenNoFace = on }
        }

        findViewById<MaterialSwitch>(R.id.only_selected).apply {
            isChecked = prefs.onlySelectedApps
            setOnCheckedChangeListener { _, on ->
                prefs.onlySelectedApps = on
                refresh()
                PrivacyService.refresh(this@MainActivity)
            }
        }
        findViewById<MaterialButton>(R.id.pick_apps).setOnClickListener { pickApps() }
        findViewById<MaterialButton>(R.id.test).setOnClickListener { testShield() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PrivacyService.status.collect { showStatus(it) }
            }
        }
        showLabels()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false)) startWhenReady = true
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (startWhenReady || (prefs.enabled && !PrivacyService.status.value.running)) {
            startWhenReady = false
            tryStart()
        }
    }

    // ---- Permissions ---------------------------------------------------------------------

    private enum class Need { OVERLAY, CAMERA, NOTIFICATIONS, USAGE }

    private fun missing(): List<Need> = buildList {
        if (!Settings.canDrawOverlays(this@MainActivity)) add(Need.OVERLAY)
        if (!granted(Manifest.permission.CAMERA)) add(Need.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            add(Need.NOTIFICATIONS)
        }
        if (prefs.onlySelectedApps && !hasUsageAccess()) add(Need.USAGE)
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun grant(need: Need) {
        when (need) {
            Need.OVERLAY -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            Need.CAMERA -> askPermission.launch(Manifest.permission.CAMERA)
            Need.NOTIFICATIONS -> askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            Need.USAGE -> startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun refresh() {
        val needs = missing()
        val card = findViewById<MaterialCardView>(R.id.permission_card)
        val list = findViewById<LinearLayout>(R.id.permission_list)
        while (list.childCount > 1) list.removeViewAt(1)
        needs.forEach { need ->
            val (title, body) = when (need) {
                Need.OVERLAY -> R.string.perm_overlay to R.string.perm_overlay_body
                Need.CAMERA -> R.string.perm_camera to R.string.perm_camera_body
                Need.NOTIFICATIONS -> R.string.perm_notifications to R.string.perm_notifications_body
                Need.USAGE -> R.string.perm_usage to R.string.perm_usage_body
            }
            val row = layoutInflater.inflate(R.layout.row_permission, list, false)
            row.findViewById<TextView>(R.id.perm_name).setText(title)
            row.findViewById<TextView>(R.id.perm_body).setText(body)
            row.findViewById<MaterialButton>(R.id.perm_grant).setOnClickListener { grant(need) }
            list.addView(row)
        }
        card.visibility = if (needs.isEmpty()) View.GONE else View.VISIBLE
        val selected = prefs.selectedApps.size
        findViewById<TextView>(R.id.selected_summary).text = if (prefs.onlySelectedApps) {
            resources.getQuantityString(R.plurals.selected_apps, selected, selected)
        } else {
            getString(R.string.everywhere)
        }
        findViewById<MaterialButton>(R.id.pick_apps).isEnabled = prefs.onlySelectedApps
    }

    private fun tryStart() {
        val needs = missing()
        if (needs.isNotEmpty()) {
            setSwitch(false)
            prefs.enabled = false
            refresh()
            grant(needs.first())
            startWhenReady = true
            return
        }
        PrivacyService.start(this)
    }

    // ---- Apps ----------------------------------------------------------------------------

    private fun pickApps() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val chosen = prefs.selectedApps.toMutableSet()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_apps)
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), apps.map { it.first in chosen }.toBooleanArray()) { _, i, on ->
                if (on) chosen += apps[i].first else chosen -= apps[i].first
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.selectedApps = chosen
                refresh()
                PrivacyService.refresh(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Status --------------------------------------------------------------------------

    private fun showStatus(status: PrivacyStatus) {
        setSwitch(status.running)
        val stateText = when {
            !status.running -> getString(R.string.state_off)
            else -> getString(
                when (status.state) {
                    PrivacyState.IDLE -> R.string.state_idle
                    PrivacyState.SENSOR_WATCH -> R.string.state_watch
                    PrivacyState.ACTIVE_CHECK -> R.string.state_check
                    PrivacyState.SHIELD_ACTIVE -> R.string.state_shield
                },
            )
        }
        findViewById<TextView>(R.id.status).text = stateText
        findViewById<TextView>(R.id.details).text = when {
            !status.running -> ""
            status.lowLight -> getString(R.string.details_low_light)
            status.faces >= 0 -> getString(R.string.details_faces, status.faces, status.yaw.toInt())
            else -> ""
        }
    }

    private fun showLabels() {
        findViewById<TextView>(R.id.intensity_label).text =
            getString(R.string.intensity_label, (prefs.intensity * 100).toInt())
        findViewById<TextView>(R.id.idle_label).text = getString(R.string.idle_label, prefs.idleCheckSeconds)
    }

    private fun setSwitch(on: Boolean) {
        if (mainSwitch.isChecked == on) return
        updatingSwitch = true
        mainSwitch.isChecked = on
        updatingSwitch = false
    }

    private fun testShield() {
        if (!Settings.canDrawOverlays(this)) {
            grant(Need.OVERLAY)
            return
        }
        val overlay = ShieldOverlay(applicationContext)
        overlay.attach(prefs.intensity)
        overlay.show()
        mainSwitch.postDelayed({
            overlay.hide()
            mainSwitch.postDelayed({ overlay.detach() }, ShieldOverlay.FADE_IN_MS + 50)
        }, 2_000)
    }

    private fun Float.roundTo(step: Float) = (Math.round(this / step) * step).coerceIn(0.5f, 0.8f)

    companion object {
        const val EXTRA_START = "start"
    }
}
