package com.waaagh.battery.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import com.waaagh.battery.BannerController
import com.waaagh.battery.events.KeepAliveService

class WaaaghWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)

    override fun onEnabled(ctx: Context) = refresh(ctx)

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) = refresh(ctx)

    private fun refresh(ctx: Context) {
        BannerController.init(ctx)
        BannerController.republish()          // re-reads the current charge first
        KeepAliveService.tryStart(ctx)
    }
}
