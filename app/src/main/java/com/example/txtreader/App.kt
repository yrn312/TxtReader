package com.example.txtreader

import android.app.Application
import com.example.txtreader.core.IndexManager
import com.example.txtreader.core.ReaderPrefs
import com.example.txtreader.data.AppDatabase

class App : Application() {
    val db: AppDatabase by lazy { AppDatabase.build(this) }
    val prefs: ReaderPrefs by lazy { ReaderPrefs(this) }
    val index: IndexManager by lazy { IndexManager(this, db, prefs) }
}
