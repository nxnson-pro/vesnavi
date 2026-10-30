package com.nxnson.vesnavi

import android.app.Application

/** Holds the one AppModel for the activity and the foreground service. */
class VesNaviApp : Application() {
    val model: AppModel by lazy { AppModel(this) }
}
