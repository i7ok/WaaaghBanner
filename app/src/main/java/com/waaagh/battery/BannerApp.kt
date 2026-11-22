package com.waaagh.battery

import android.app.Application

class BannerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Every process start (widget update, listener bind, boot, call broadcast,
        // activity) restores the current battery value and re-registers sources.
        BannerController.init(this)
    }
}
