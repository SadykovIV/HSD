package com.hsd.universal.update

import com.hsd.universal.R
import com.hsd.universal.DesktopTransport

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hsd.universal.BuildConfig
import com.hsd.universal.CarLifeState
import com.hsd.universal.dex.DesktopSetupViews
import com.hsd.universal.dex.DesktopModePolicy
import com.hsd.universal.dex.DexPrefs
import java.io.File
import java.util.concurrent.Executors

/** Phone-only, explicit updates. Each component keeps its own state and system-window results. */
class ComponentUpdatesActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val cards = mutableListOf<ComponentCard>()
    private lateinit var profiles: InstalledProfileCards
    private lateinit var scroll: ScrollView
    private var legacyTarget = DesktopComponent.entries.first()
    private var busy = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        legacyTarget = DesktopComponent.entries.firstOrNull { it.pkg == state?.getString("component") } ?: legacyTarget
        val ui = DesktopSetupViews(this)
        val pad = ui.dp(16)
        val root = ui.column().apply { setPadding(pad, pad, pad, pad) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ui.background)
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
            addView(root)
        }
        setContentView(scroll)
        val header = LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            root.addView(this, LinearLayout.LayoutParams(-1, -2))
        }
        ui.button(header, getString(R.string.set_back)) { finish() }.layoutParams = LinearLayout.LayoutParams(-2, -2)
        ui.text(header, getString(R.string.desktop_components, DesktopTransport.appName), 22f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(ui.dp(12), 0, 0, 0)
        }
        val mainApp = ui.section(root, "${DesktopTransport.appName}")
        ui.text(mainApp, getString(R.string.desktop_main_app_code, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), 15f)
        ui.button(mainApp, getString(R.string.desktop_check_for_updates, DesktopTransport.appName)) { Updater.check(this, manual = true) }
        val savedCards = state?.getBundle("components")
        DesktopComponent.entries.forEachIndexed { index, spec ->
            // Retain a pending installer/picker from the former single-card screen on recreation.
            val saved = savedCards?.getBundle(spec.pkg) ?: state?.takeIf { savedCards == null && spec == legacyTarget }
            cards += ComponentCard(spec, REQUEST_BASE + index * REQUEST_STRIDE, ui, root, saved)
        }
        profiles = InstalledProfileCards(this, ui, root, worker, state)
        refresh()
        state?.getInt("scrollY")?.let { y -> scroll.post { scroll.scrollTo(0, y) } }
    }

    override fun onResume() { super.onResume(); refresh(); profiles.refresh() }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        state.putBundle("components", Bundle().apply {
            cards.forEach { card -> putBundle(card.spec.pkg, card.save()) }
        })
        state.putString("component", legacyTarget.pkg)
        state.putInt("scrollY", scroll.scrollY)
        profiles.save(state)
    }

    private fun refresh() { cards.forEach { it.refresh() } }

    private inner class ComponentCard(val spec: DesktopComponent, private val requestBase: Int,
        ui: DesktopSetupViews, root: LinearLayout, state: Bundle?) {
        private val version: TextView
        private val status: TextView
        private val check: Button
        private val pick: Button
        private val remote: Button
        private val install: Button
        private val remove: Button
        private val permissions: Button?
        private var available: ComponentAsset? = null
        private var pending: File? = ComponentPackages.restore(this@ComponentUpdatesActivity, state?.getString("pending"))
        private var working = false

        init {
            val card = ui.section(root, spec.title)
            fun button(value: String, click: () -> Unit) = ui.button(card, value, click)
            version = ui.text(card, "", 15f)
            check = button(getString(R.string.desktop_check_component_versions)) { query() }
            remote = button("") {
                val asset = available ?: return@button
                work(getString(R.string.desktop_downloading_and_checking_apk), {
                    ComponentPackages.download(this@ComponentUpdatesActivity, spec, asset)
                }, ::prepared)
            }
            pick = button(getString(R.string.desktop_install_from_file)) {
                launchSystem(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(APK_TYPE), PICK)
            }
            install = button(getString(R.string.desktop_continue_installation)) { installPrepared() }
            remove = button(getString(R.string.desktop_uninstall_component)) {
                work(getString(R.string.desktop_checking_the_installed_component), {
                    check(!componentInUse()) { getString(R.string.desktop_stop_the_component_session_and_repeat_the_action) }
                    ComponentPackages.builtinRemoval(this@ComponentUpdatesActivity, spec)
                }) { launchSystem(it, REMOVE) }
            }
            permissions = if (spec == DesktopComponent.DRIVEDECK)
                button(getString(R.string.desktop_drivedeck_permissions)) { DriveDeckPermissions.open(this@ComponentUpdatesActivity) }
            else null
            status = ui.text(card, state?.getString("status")
                ?: getString(R.string.desktop_the_apk_is_downloaded_from_a_verified_component_repository), 15f)
        }

        fun save() = Bundle().apply {
            putString("pending", pending?.name)
            if (!working) putString("status", status.text.toString())
        }

        fun refresh() {
            val current = ComponentPackages.installed(this@ComponentUpdatesActivity, spec)
            version.text = if (current == null) getString(R.string.desktop_not_installed) else getString(R.string.desktop_installed_build, current.versionName, current.longVersionCode)
            val next = available?.takeIf { current == null || it.code > current.longVersionCode }
            remote.text = getString(R.string.desktop_build_5760, if (current == null) getString(R.string.apps_download) else getString(R.string.desktop_update_to), next?.version, next?.code)
            remote.visibility = if (next != null) View.VISIBLE else View.GONE
            install.visibility = if (pending != null) View.VISIBLE else View.GONE
            remove.visibility = if (current == null) View.GONE else View.VISIBLE
            permissions?.apply { isEnabled = !busy && current != null }
            listOf(check, pick, install, remote, remove).forEach { it.isEnabled = !busy }
        }

        private fun <T> work(message: String, run: () -> T, done: (T) -> Unit) {
            if (busy) return
            busy = true
            working = true
            status.text = message
            this@ComponentUpdatesActivity.refresh()
            worker.execute {
                val result = runCatching(run)
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    busy = false
                    working = false
                    result.fold(done) { status.text = it.message ?: getString(R.string.desktop_couldn_t_verify_the_component) }
                    this@ComponentUpdatesActivity.refresh()
                }
            }
        }

        private fun query() {
            available = null
            work(getString(R.string.desktop_checking_releases, spec.title), {
                var latest: ComponentAsset? = null
                ReleaseFeed.pages(spec.repository) { latest = ComponentCatalog.newest(it, spec, latest) }
                latest
            }) { newest ->
                available = newest
                val current = ComponentPackages.installed(this@ComponentUpdatesActivity, spec)
                status.text = when {
                    newest == null -> getString(R.string.desktop_compatible_release_of_the_component_was_not_found)
                    current == null -> getString(R.string.desktop_available_to_install)
                    current.longVersionCode >= newest.code -> getString(R.string.desktop_up_to_date)
                    else -> getString(R.string.desktop_a_new_version_is_available)
                }
            }
        }

        private fun prepared(apk: PreparedComponent) {
            pending = apk.file
            status.text = getString(R.string.desktop_proven_code, spec.title, apk.version, apk.code)
            // If the user left the screen during the download, wait for another explicit tap.
            if (hasWindowFocus()) handoff(apk)
        }

        private fun installPrepared() {
            val file = pending ?: return
            work(getString(R.string.desktop_checking_apk), { ComponentPackages.validate(this@ComponentUpdatesActivity, spec, file) }, ::handoff)
        }

        private fun componentInUse(): Boolean = CarLifeState.running && (
            (spec == DesktopComponent.DRIVEDECK && DexPrefs(this@ComponentUpdatesActivity).desktopMode == DesktopModePolicy.DRIVEDECK) ||
            com.hsd.universal.component.DesktopComponents.selected(this@ComponentUpdatesActivity)?.packageName == spec.pkg)

        private fun handoff(apk: PreparedComponent) {
            if (componentInUse()) {
                status.text = getString(R.string.desktop_stop_the_component_session_and_click_continue_installation)
                return
            }
            if (!packageManager.canRequestPackageInstalls()) {
                status.text = getString(R.string.desktop_allow_installation_from_the_source_and_come_back_here, DesktopTransport.shortName)
                launchSystem(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), SOURCE)
                return
            }
            val intent = runCatching { ComponentPackages.installerIntent(this@ComponentUpdatesActivity, apk) }
                .getOrElse { status.text = it.message ?: getString(R.string.desktop_the_apk_system_installer_is_not_available); return }
            launchSystem(intent, INSTALL)
        }

        private fun launchSystem(intent: Intent, request: Int) {
            try { startActivityForResult(intent, requestBase + request) }
            catch (e: RuntimeException) { status.text = getString(R.string.desktop_failed_to_open_the_system_window, e.message); refresh() }
        }

        fun action(request: Int): Int? = (request - requestBase).takeIf { it in PICK..REMOVE }

        fun result(action: Int, resultCode: Int, data: Intent?) {
            when (action) {
                PICK -> if (resultCode == RESULT_OK) data?.data?.let { uri ->
                    pending = null
                    work(getString(R.string.desktop_checking_the_selected_apk), {
                        checkNotNull(contentResolver.openInputStream(uri)) { getString(R.string.desktop_couldn_t_open_the_file) }.use {
                            ComponentPackages.stage(this@ComponentUpdatesActivity, spec, it)
                        }
                    }, ::prepared)
                }
                SOURCE -> if (packageManager.canRequestPackageInstalls()) installPrepared()
                    else status.text = getString(R.string.desktop_installation_is_not_allowed_we_can_continue_later)
                INSTALL -> if (resultCode == RESULT_OK) {
                    pending = null; status.text = getString(R.string.desktop_component_installed)
                } else status.text = getString(R.string.desktop_installation_is_incomplete_we_can_do_it_again)
                REMOVE -> if (resultCode == RESULT_OK) {
                    pending = null; available = null; status.text = getString(R.string.desktop_component_removed)
                } else status.text = getString(R.string.desktop_the_component_remained_installed)
            }
        }
    }

    @Deprecated("Platform Activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (profiles.result(requestCode, resultCode, data)) return
        val card = if (requestCode in PICK..REMOVE) cards.firstOrNull { it.spec == legacyTarget }
            else cards.firstOrNull { it.action(requestCode) != null }
        card?.result(card.action(requestCode) ?: requestCode, resultCode, data)
        refresh()
    }

    private companion object {
        const val APK_TYPE = "application/vnd.android.package-archive"
        const val PICK = 1
        const val SOURCE = 2
        const val INSTALL = 3
        const val REMOVE = 4
        const val REQUEST_BASE = 100
        const val REQUEST_STRIDE = 10
    }
}
