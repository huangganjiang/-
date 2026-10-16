package com.jishi.monitor

import android.app.Application
import com.jishi.monitor.core.Logx

class JishiApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Logx.i("App", "启动 版本=" + BuildConfig.VERSION_NAME)
    }
}
