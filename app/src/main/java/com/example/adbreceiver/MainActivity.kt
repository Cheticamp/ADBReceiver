package com.example.adbreceiver

// See comments in AdbInteface in androidTest of this project.

// Use another app to receive test data. Using this same app for the AdbInterface and
// command testing will not work.

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.adbreceiver.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
    }
}