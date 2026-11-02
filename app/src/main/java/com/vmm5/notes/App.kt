package com.vmm5.notes

import android.app.Application
import com.vmm5.notes.sync.SyncService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncService.channels(this)
        com.vmm5.notes.sync.Pairing.init(this)
    }
}
