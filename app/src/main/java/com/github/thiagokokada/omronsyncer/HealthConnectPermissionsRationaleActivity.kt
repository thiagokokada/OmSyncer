package com.github.thiagokokada.omronsyncer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.github.thiagokokada.omronsyncer.databinding.ActivityHealthConnectPermissionsRationaleBinding

class HealthConnectPermissionsRationaleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHealthConnectPermissionsRationaleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityHealthConnectPermissionsRationaleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.closeButton.setOnClickListener {
            finish()
        }
    }

    private fun applyWindowInsets() {
        val contentPaddingLeft = binding.rationaleContent.paddingLeft
        val contentPaddingTop = binding.rationaleContent.paddingTop
        val contentPaddingRight = binding.rationaleContent.paddingRight
        val contentPaddingBottom = binding.rationaleContent.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.rationaleScroll) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.rationaleContent.updatePadding(
                left = contentPaddingLeft + systemBars.left,
                top = contentPaddingTop + systemBars.top,
                right = contentPaddingRight + systemBars.right,
                bottom = contentPaddingBottom + systemBars.bottom,
            )
            windowInsets
        }

        ViewCompat.requestApplyInsets(binding.rationaleScroll)
    }
}
