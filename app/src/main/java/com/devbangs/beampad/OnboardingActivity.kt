package com.devbangs.beampad

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.FragmentActivity
import com.devbangs.beampad.databinding.ActivityOnboardingBinding

/**
 * Shown once on first launch. Page three exists because a keyboard layout
 * mismatch fails silently: the user has no way to guess why symbols arrived
 * wrong unless they are told before it happens.
 */
class OnboardingActivity : FragmentActivity() {

    private data class Page(
        val icon: Int,
        val art: Int?,
        val title: Int,
        val body: Int
    )

    private val pages = listOf(
        Page(R.drawable.ic_keyboard, R.drawable.bg_room, R.string.ob1_title, R.string.ob1_body),
        Page(R.drawable.ic_bluetooth, null, R.string.ob2_title, R.string.ob2_body),
        Page(R.drawable.ic_gear, null, R.string.ob3_title, R.string.ob3_body)
    )

    private lateinit var ui: ActivityOnboardingBinding
    private var index = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        ui = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(ui.root)

        WindowInsetsControllerCompat(window, ui.root).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        buildDots()
        render()

        ui.next.setOnClickListener {
            if (index == pages.lastIndex) finishOnboarding() else {
                index++
                render()
            }
        }

        ui.skip.setOnClickListener { finishOnboarding() }
    }

    private fun buildDots() {
        val size = (8 * resources.displayMetrics.density).toInt()
        val gap = (6 * resources.displayMetrics.density).toInt()
        pages.indices.forEach { i ->
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.dot_page)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (i == 0) 0 else gap
                }
            }
            ui.dots.addView(dot)
        }
    }

    private fun render() {
        val page = pages[index]

        ui.icon.setImageResource(page.icon)
        ui.title.setText(page.title)
        ui.body.setText(page.body)

        if (page.art != null) {
            ui.art.setImageResource(page.art)
            ui.art.visibility = View.VISIBLE
        } else {
            ui.art.visibility = View.GONE
        }

        ui.next.setText(
            if (index == pages.lastIndex) R.string.get_started else R.string.next
        )
        ui.skip.visibility = if (index == pages.lastIndex) View.INVISIBLE else View.VISIBLE

        for (i in 0 until ui.dots.childCount) {
            ui.dots.getChildAt(i).isSelected = i == index
        }

        // Cross-fade rather than a slide: there is no gesture paging here, so
        // a slide would imply swiping works when it does not.
        listOf(ui.iconWrap, ui.title, ui.body).forEach { v ->
            v.alpha = 0f
            v.animate().alpha(1f).setDuration(220).start()
        }
    }

    private fun finishOnboarding() {
        getSharedPreferences("beampad", MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SEEN, true)
            .apply()
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val KEY_SEEN = "onboarding_seen"

        fun shouldShow(activity: FragmentActivity): Boolean =
            !activity.getSharedPreferences("beampad", MODE_PRIVATE)
                .getBoolean(KEY_SEEN, false)
    }
}
