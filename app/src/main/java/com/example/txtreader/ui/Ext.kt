package com.example.txtreader.ui

import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.txtreader.App

val Fragment.app: App get() = requireActivity().application as App

fun Fragment.toast(msg: String) {
    val c = context ?: return
    Toast.makeText(c, msg, Toast.LENGTH_SHORT).show()
}

fun SeekBar.onChange(onProgress: (Int) -> Unit, onDone: () -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {}

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            onDone()
        }
    })
}
