package com.hsd.universal.dex

import com.hsd.universal.R
import com.hsd.universal.DesktopTransport

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import com.hsd.universal.desktop.DesktopAppCatalog
import java.util.concurrent.Executors

class DpiCorrectionActivity : ProjectionAppsActivity() {
    override val titleRes = R.string.desktop_adjust_app_dpi
    override val emptyRes = R.string.desktop_the_list_is_blank_add_the_dpi_correction_app
    override var selectedPackages: Set<String>
        get() = prefs.dpiCorrectionPackages
        set(value) { prefs.dpiCorrectionPackages = value }

    override fun addSettings(page: LinearLayout) {
        val correction = ui.toggle(ui.section(page), getString(R.string.desktop_enable_dpi_adjustment), prefs.compactMapSymbols) {
            prefs.compactMapSymbols = it
        }
        ui.help(correction.row, getString(R.string.desktop_enable_dpi_adjustment)) {
            getString(R.string.desktop_for_applications_on_the_list_increases_the_internal_resolution, DesktopTransport.shortName)
        }
    }
}

class NavigationAppsActivity : ProjectionAppsActivity() {
    override val titleRes = R.string.projection_navigation_apps
    override val emptyRes = R.string.projection_navigation_apps_empty
    override var selectedPackages: Set<String>
        get() = prefs.navigationAudioApps
        set(value) { prefs.navigationAudioApps = value }

    // A system UID can be shared by unrelated services and cannot identify one app's media.
    override fun acceptsUid(uid: Int) = uid % 100_000 >= android.os.Process.FIRST_APPLICATION_UID
    override fun addSettings(page: LinearLayout) {
        ui.text(page, getString(R.string.projection_navigation_apps_help), 13f)
    }
}

/** The saved selection is visible while the installed launcher inventory loads. */
abstract class ProjectionAppsActivity : Activity() {
    protected abstract val titleRes: Int
    protected abstract val emptyRes: Int
    protected abstract var selectedPackages: Set<String>
    protected abstract fun addSettings(page: LinearLayout)
    protected open fun acceptsUid(uid: Int) = true
    protected lateinit var prefs: DexPrefs
    internal lateinit var ui: DesktopSetupViews
    private lateinit var rows: LinearLayout
    private lateinit var add: Button
    private val loader = Executors.newSingleThreadExecutor()
    private var installed: Map<String, String> = emptyMap()

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        prefs = DexPrefs(this)
        ui = DesktopSetupViews(this)
        val page = ui.column().apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(ui.background); addView(page) })
        ui.button(page, getString(R.string.set_back)) { finish() }
        ui.text(page, getString(titleRes), 23f, true)
        addSettings(page)
        val apps = ui.section(page, getString(R.string.desktop_apps))
        rows = ui.group(apps)
        add = ui.button(apps, getString(R.string.desktop_add_app)) { chooseApp() }
        renderRows()
    }

    override fun onResume() {
        super.onResume()
        add.isEnabled = false
        loader.execute {
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val candidates = packageManager.queryIntentActivities(query, 0).mapNotNull { result ->
                val info = result.activityInfo ?: return@mapNotNull null
                DesktopAppCatalog.Entry(info.packageName, info.name, runCatching { result.loadLabel(packageManager).toString() }.getOrDefault(info.packageName),
                    info.enabled && info.applicationInfo.enabled && acceptsUid(info.applicationInfo.uid), info.exported)
            }
            val inventory = DesktopAppCatalog.select(candidates, packageName).distinctBy { it.packageName }
                .associate { it.packageName to it.label }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                installed = inventory
                add.isEnabled = true
                renderRows()
            }
        }
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    private fun label(pkg: String): String = installed[pkg] ?: when (pkg) {
        "ru.yandex.yandexmaps" -> getString(R.string.desktop_yandex_maps)
        "ru.yandex.yandexnavi" -> getString(R.string.desktop_yandex_navigator)
        else -> pkg
    }

    private fun renderRows() {
        rows.removeAllViews()
        val selected = selectedPackages
        if (selected.isEmpty()) ui.text(rows, getString(emptyRes))
        for (pkg in selected.sortedBy(::label)) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = ui.dp(58)
            }
            rows.addView(row)
            val icon = ImageView(this)
            icon.setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrDefault(packageManager.defaultActivityIcon))
            row.addView(icon, LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)))
            val labels = ui.column().apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) }
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            ui.text(labels, label(pkg), 16f, true)
            ui.text(labels, pkg, 12f)
            row.addView(ImageButton(this).apply {
                contentDescription = getString(R.string.desktop_remove_from_the_list, label(pkg))
                setImageResource(android.R.drawable.ic_menu_delete)
                setOnClickListener {
                    selectedPackages = selectedPackages - pkg
                    renderRows()
                }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
    }

    private fun chooseApp() {
        val available = installed.keys.filterNot(selectedPackages::contains)
        val content = ui.column().apply { setPadding(ui.dp(16), 0, ui.dp(16), 0) }
        val search = EditText(this).apply { hint = getString(R.string.desktop_search_by_app_or_package_name); setSingleLine(true) }
        val list = ListView(this)
        val empty = android.widget.TextView(this).apply { text = getString(R.string.desktop_no_apps_found); gravity = Gravity.CENTER }
        content.addView(search)
        content.addView(list, LinearLayout.LayoutParams(-1, ui.dp(280)))
        content.addView(empty)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1)
        list.adapter = adapter
        var visible: List<String> = emptyList()
        fun filter(query: String) {
            visible = available.filter { label(it).contains(query, true) || it.contains(query, true) }
            adapter.clear()
            adapter.addAll(visible.map { "${label(it)}\n$it" })
            list.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
            empty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        }
        filter("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(text: Editable?) = filter(text?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        val dialog = AlertDialog.Builder(this).setTitle(getString(R.string.desktop_add_app)).setView(content)
            .setNegativeButton(getString(R.string.mock_cancel), null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            selectedPackages = selectedPackages + visible[position]
            renderRows()
            dialog.dismiss()
        }
        dialog.show()
    }
}
