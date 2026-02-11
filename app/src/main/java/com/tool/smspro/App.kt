package com.tool.smspro

import android.app.Application
import com.tool.smspro.data.database.AppDatabase
import com.tool.smspro.util.CrashHandler

class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.instance.init(this)
    }
}
