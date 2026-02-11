package com.tool.smspro

import android.app.Application
import com.tool.smspro.data.database.AppDatabase

class App : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
}
