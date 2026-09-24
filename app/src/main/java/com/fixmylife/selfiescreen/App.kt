package com.fixmylife.selfiescreen

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Checks GitHub for a newer release on launch, and finishes pending updates on resume. */
class App : Application() {

    private var checked = false

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                val updater = Updater(activity)
                if (!checked) {
                    checked = true
                    updater.check(silent = true)
                } else {
                    // Back from the install-permission screen? Carry on with the download.
                    updater.resumePending()
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
