package com.example.txtreader.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.txtreader.R
import com.example.txtreader.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    /** Set by the reader while it is visible: (keyCode, repeatCount). Null = volume keys behave normally. */
    var volumeHandler: ((Int, Int) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ActivityMainBinding.inflate(layoutInflater).root)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, LibraryFragment())
                .commit()
        }
    }

    fun push(f: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, f)
            .addToBackStack(null)
            .commit()
    }

    /** Intercepts both DOWN and UP of the volume keys so the system volume bar never shows. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val h = volumeHandler
        if (h != null &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) h(event.keyCode, event.repeatCount)
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
