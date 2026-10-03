package org.lsposed.corepatch

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup.LayoutParams
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.lsposed.corepatch.App.Companion.mService
import org.lsposed.corepatch.App.Companion.reloadListener
import org.lsposed.corepatch.adapter.MultiTypeListAdapter
import org.lsposed.corepatch.data.SwitchData
import org.lsposed.corepatch.ui.dp

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reloadListener = {
            runOnUiThread {
                showContent()
            }
        }
        showContent()
    }

    private fun showContent() {
        val service = mService
        if (service == null || "system" !in service.scope) {
            setContentView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                fitsSystemWindows = true
                addView(TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setText(R.string.xposed_service_unavailable)
                    setTextAppearance(android.R.style.TextAppearance_Medium)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setText(R.string.xposed_service_unavailable_summary)
                    gravity = Gravity.CENTER
                })
            })
            return
        }
        loadPrefs()
    }

    private fun loadPrefs() {
        val bypassDowngrade = SwitchData(
            getString(R.string.bypass_downgrade), getString(R.string.bypass_downgrade_summary), Config.BYPASS_DOWNGRADE
        )
        val bypassVerification = SwitchData(
            getString(R.string.bypass_verification), getString(R.string.bypass_verification_summary), Config.BYPASS_VERIFICATION
        )
        val bypassResourceArscRestrictions = SwitchData(
            getString(R.string.bypass_resource_arsc_restrictions),
            getString(R.string.bypass_resource_arsc_restrictions_summary),
            Config.BYPASS_RESOURCE_ARSC_RESTRICTIONS
        )
        val bypassDigest = SwitchData(
            getString(R.string.bypass_digest), getString(R.string.bypass_digest_summary), Config.BYPASS_DIGEST
        )
        val bypassExactSignatureMatch = SwitchData(
            getString(R.string.bypass_exact_signature_match), getString(R.string.bypass_exact_signature_match_summary), Config.BYPASS_EXACT_SIGNATURE_MATCH
        )
        val usePreviousSignatures = SwitchData(
            getString(R.string.use_previous_signatures), getString(R.string.use_previous_signatures_summary), Config.USE_PREVIOUS_SIGNATURES,
            if (isMiui()) {
                getString(R.string.miui_usepresig_warn) + "\n\n" + getString(R.string.use_previous_signatures_warning)
            } else {
                getString(R.string.use_previous_signatures_warning)
            }
        )
        val allowHiddenApisForSystemApps = SwitchData(
            getString(R.string.allow_hidden_apis_for_system_apps),
            getString(R.string.allow_hidden_apis_for_system_apps_summary),
            Config.ALLOW_HIDDEN_APIS_FOR_SYSTEM_APPS
        )
        val bypassSharedUser = SwitchData(
            getString(R.string.bypass_shared_user), getString(R.string.bypass_shared_user_summary), Config.BYPASS_SHARED_USER
        )
        val disableVerificationAgent = SwitchData(
            getString(R.string.disable_verification_agent), getString(R.string.disable_verification_agent_summary), Config.DISABLE_VERIFICATION_AGENT
        )
        val bypassBlock = SwitchData(
            getString(R.string.bypass_block), getString(R.string.bypass_block_summary), Config.BYPASS_BLOCK
        )
        val bypassDuplicatePermission = SwitchData(
            getString(R.string.bypass_duplicate_permission),
            getString(R.string.bypass_duplicate_permission_summary),
            Config.BYPASS_DUPLICATE_PERMISSION,
            getString(R.string.bypass_duplicate_permission_warning)
        )
        val bypassDuplicateProvider = SwitchData(
            getString(R.string.bypass_duplicate_provider),
            getString(R.string.bypass_duplicate_provider_summary),
            Config.BYPASS_DUPLICATE_PROVIDER,
            getString(R.string.bypass_duplicate_provider_warning)
        )
        val bypassDuplicateProviderIncludeSensitive = SwitchData(
            getString(R.string.bypass_duplicate_provider_include_sensitive),
            getString(R.string.bypass_duplicate_provider_include_sensitive_summary),
            Config.BYPASS_DUPLICATE_PROVIDER_INCLUDE_SENSITIVE,
            getString(R.string.bypass_duplicate_provider_include_sensitive_warning)
        )

        val dataSet = arrayListOf<Any>(
            getString(R.string.section_signature_and_verification),
            bypassDowngrade,
            bypassVerification,
            bypassResourceArscRestrictions,
            bypassDigest,
            bypassExactSignatureMatch,
            usePreviousSignatures,
            disableVerificationAgent,
            getString(R.string.section_system_and_shared_user),
            allowHiddenApisForSystemApps,
            bypassSharedUser,
            bypassBlock,
            getString(R.string.section_duplicate_declarations),
            bypassDuplicatePermission,
            bypassDuplicateProvider,
            bypassDuplicateProviderIncludeSensitive
        )

        val adapter = MultiTypeListAdapter(dataSet)

        val listView = ListView(this)
        // addHeaderView must be called before setAdapter.
        listView.addHeaderView(createStatusBanner(), null, false)
        listView.adapter = adapter
        listView.fitsSystemWindows = true
        setContentView(listView)
    }

    /**
     * Rounded status card shown above the toggle list, e.g. "Modül etkin / Hook'lar başarıyla
     * yüklendi.". This code path only runs once we already know the Xposed service is reachable
     * (see the check at the top of [showContent]), so there's no separate "inactive" state to
     * render here -- reaching this point already means the module is active.
     */
    private fun createStatusBanner(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.shape_banner_background)
            layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(20.dp, 20.dp, 20.dp, 12.dp)
            }
            setPadding(20.dp, 18.dp, 20.dp, 18.dp)

            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.module_active)
                setTextColor(getColor(R.color.banner_title))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.module_active_summary)
                setTextColor(getColor(R.color.banner_subtitle))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 6.dp, 0, 0)
            })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, R.string.hide_launcher_icon, Menu.NONE, R.string.hide_launcher_icon).apply {
            isCheckable = true
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val component = ComponentName(this, "$packageName.LauncherAlias")
        menu.findItem(R.string.hide_launcher_icon).isChecked =
            packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.string.hide_launcher_icon) {
            val component = ComponentName(this, "$packageName.LauncherAlias")
            val hidden = packageManager.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            packageManager.setComponentEnabledSetting(
                component,
                if (hidden) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
            item.isChecked = !hidden
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("PrivateApi")
    private fun isMiui(): Boolean = try {
        val systemProperties = Class.forName("android.os.SystemProperties")
        val get = systemProperties.getMethod("get", String::class.java)
        (get.invoke(null, "ro.miui.ui.version.code") as String).isNotEmpty()
    } catch (_: ReflectiveOperationException) {
        false
    }

    override fun onStop() {
        super.onStop()
        reloadListener = {}
    }
}
