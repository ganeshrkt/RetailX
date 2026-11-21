package com.example.retailx

import android.app.Application
import com.example.retailx.app.AppContainer

class RetailXApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
