package com.yzddmr6.prismspace.action

import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.AsyncTask
import android.os.Bundle
import android.os.UserHandle
import android.widget.Toast
import androidx.core.content.getSystemService
import com.yzddmr6.prismspace.engine.PrismManager
import com.yzddmr6.prismspace.mobile.BuildConfig
import com.yzddmr6.prismspace.mobile.R
import com.yzddmr6.prismspace.prism.service.ProfileBridgeResult
import com.yzddmr6.prismspace.prism.service.profileBridgeFailureMessage
import com.yzddmr6.prismspace.prism.service.runProfileBridgeOperation
import com.yzddmr6.prismspace.util.CallerAwareActivity
import com.yzddmr6.prismspace.util.Toasts
import com.yzddmr6.prismspace.util.Users

/**
 * Activity to handle app action "Open Feature"
 *
 * Created by Oasis on 2019-7-1.
 */
private const val URI_HOST = "feature"

class FeatureActionActivity : CallerAwareActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent()
        finish()
    }

    private fun handleIntent() {
        val data = intent.data; val caller = callingPackage
        if (BuildConfig.DEBUG) Toast.makeText(this, "$data\nfrom: $caller", Toast.LENGTH_LONG).show()
        if (intent.action != Intent.ACTION_VIEW || data?.host != URI_HOST || data.pathSegments.size < 1) return

        val query = data.pathSegments[0]
        AsyncTask.execute {
            val profile = Users.profile ?: return@execute Toasts.showLong(this, R.string.fb_need_create_space)
            findApp(query, profile)?.also { activity ->
                val pkg = activity.componentName.packageName
                when (val result = runProfileBridgeOperation(this, TAG, "feature launch pkg=$pkg", target = profile) {
                    if (PrismManager.ensureAppFreeToLaunch(this, pkg).isEmpty())
                        PrismManager.launchApp(this, pkg, Users.current())
                }) {
                    is ProfileBridgeResult.Value -> Unit
                    else -> Toasts.showLong(
                        this,
                        profileBridgeFailureMessage(this, result, getString(R.string.prompt_space_not_ready)),
                    )
                }
            } ?: Toasts.showLong(this, "Not found: $query")
        }
    }

    private fun findApp(query: String, profile: UserHandle): LauncherActivityInfo? {
        getSystemService<LauncherApps>()!!.getActivityList(null, profile).also { candidates ->
            if (query.all(Char::isLetterOrDigit))
                candidates.filter { candidate -> candidate.componentName.packageName.contains(query, ignoreCase = true) }.apply {
                    if (size in 1..3) return this[0]        // Not a good query word if more than 3 matches
                }
            candidates.firstOrNull { candidate -> candidate.label.contains(query, ignoreCase = true) }?.apply { return this }
        }
        return null
    }
}

private const val TAG = "Prism.FeatureAction"
