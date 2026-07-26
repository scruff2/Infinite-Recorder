package com.mark.infiniterecorder

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import com.mark.infiniterecorder.data.SettingsRepository
import com.mark.infiniterecorder.databinding.ActivitySettingsBinding

class SettingsActivity : Activity() {
    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: SettingsRepository
    private var loading = true

    private val bitrates = listOf(
        "32 kbps — smallest",
        "64 kbps — recommended",
        "96 kbps — higher quality",
        "128 kbps — largest",
    )
    private val bitrateValues = listOf(32_000, 64_000, 96_000, 128_000)
    private val segments = listOf("15 minutes", "30 minutes", "60 minutes", "120 minutes")
    private val segmentValues = listOf(15, 30, 60, 120)
    private val sensitivities = listOf("Low", "Medium", "High")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        settings = SettingsRepository(this)

        binding.backButton.setOnClickListener { finish() }
        binding.bitrateSpinner.adapter = spinnerAdapter(bitrates)
        binding.segmentSpinner.adapter = spinnerAdapter(segments)
        binding.sensitivitySpinner.adapter = spinnerAdapter(sensitivities)
        binding.bitrateSpinner.setSelection(
            bitrateValues.indexOf(settings.bitrate).coerceAtLeast(0),
        )
        binding.segmentSpinner.setSelection(
            segmentValues.indexOf(settings.segmentMinutes).coerceAtLeast(0),
        )
        binding.sensitivitySpinner.setSelection(
            sensitivities.indexOf(settings.sensitivity).coerceAtLeast(1),
        )
        binding.silenceSwitch.isChecked = settings.silenceSuppression
        binding.pocketProtectionSwitch.isChecked = settings.pocketProtection

        binding.bitrateSpinner.onItemSelectedListener = selectionListener {
            settings.bitrate = bitrateValues[it]
        }
        binding.segmentSpinner.onItemSelectedListener = selectionListener {
            settings.segmentMinutes = segmentValues[it]
        }
        binding.sensitivitySpinner.onItemSelectedListener = selectionListener {
            settings.sensitivity = sensitivities[it]
        }
        binding.silenceSwitch.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.silenceSuppression = checked
        }
        binding.pocketProtectionSwitch.setOnCheckedChangeListener { _, checked ->
            if (!loading) settings.pocketProtection = checked
        }
        binding.root.post { loading = false }
    }

    private fun spinnerAdapter(items: List<String>) =
        ArrayAdapter(this, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun selectionListener(save: (Int) -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (!loading) save(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
}
