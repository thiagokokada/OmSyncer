package com.github.thiagokokada.omronsyncer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.github.thiagokokada.omronsyncer.databinding.ActivityHealthConnectPermissionsRationaleBinding

class HealthConnectPermissionsRationaleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthConnectPermissionsRationaleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityHealthConnectPermissionsRationaleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.closeButton.setOnClickListener {
            finish()
        }
    }
}
