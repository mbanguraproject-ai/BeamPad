package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.devbangs.beampad.databinding.ActivityProBinding
import com.devbangs.beampad.databinding.ItemCompareBinding
import com.devbangs.beampad.databinding.ItemFeatureBinding
import com.devbangs.beampad.databinding.ItemPlanBinding
import java.text.NumberFormat
import java.util.Currency
import kotlin.math.roundToInt

/**
 * The Pro page, trial first: the plans Play offers (a plan with a free
 * trial leads and is preselected), what happens when for that trial, then
 * what Pro adds and Free next to Pro.
 *
 * Play's subscription policy wants the price, the billing period, that it
 * renews, and how to cancel stated next to the button, before the user taps
 * it. The fine print under the button carries all four for the selected
 * plan, and changes with it.
 */
class ProActivity : BeamActivity() {

    private lateinit var ui: ActivityProBinding
    private val app get() = application as BeamPadApp
    private val billing get() = app.billing

    private var selected: Billing.Plan? = null

    private val entitlementObserver: (Entitlements.Change) -> Unit = { render() }

    private data class Feature(
        val which: Features.Pro,
        val icon: Int,
        val title: Int,
        val body: Int
    )

    private val features = listOf(
        Feature(Features.Pro.FULL_KEYBOARD, R.drawable.ic_keyboard, R.string.feat_full_keyboard, R.string.feat_full_keyboard_body),
        Feature(Features.Pro.COMMANDS, R.drawable.ic_command, R.string.feat_commands, R.string.feat_commands_body),
        Feature(Features.Pro.LAUNCH, R.drawable.ic_rocket_launch, R.string.feat_launch, R.string.feat_launch_body),
        Feature(Features.Pro.AIR_MOUSE, R.drawable.ic_hand_pointing, R.string.feat_air_mouse, R.string.feat_air_mouse_body),
        Feature(Features.Pro.NO_ADS, R.drawable.ic_sparkle_fill, R.string.feat_no_ads, R.string.feat_no_ads_body),
        Feature(Features.Pro.PANELS, R.drawable.ic_layout, R.string.feat_panels, R.string.feat_panels_body),
        Feature(Features.Pro.MACROS, R.drawable.ic_magic_wand, R.string.feat_macros, R.string.feat_macros_body),
        Feature(Features.Pro.PROFILES, R.drawable.ic_devices, R.string.feat_profiles, R.string.feat_profiles_body),
        Feature(Features.Pro.PRESENTATION, R.drawable.ic_presentation, R.string.feat_presentation, R.string.feat_presentation_body),
        Feature(Features.Pro.TRACKPAD, R.drawable.ic_sliders_horizontal, R.string.feat_trackpad, R.string.feat_trackpad_body),
        Feature(Features.Pro.LIVE_TYPING, R.drawable.ic_lightning, R.string.feat_live, R.string.feat_live_body),
        Feature(Features.Pro.VOICE, R.drawable.ic_microphone, R.string.feat_voice, R.string.feat_voice_body),
        Feature(Features.Pro.CLIPBOARD, R.drawable.ic_clipboard_text, R.string.feat_clipboard, R.string.feat_clipboard_body),
        Feature(Features.Pro.PRO_KEYS, R.drawable.ic_squares_four, R.string.feat_keys, R.string.feat_keys_body),
        Feature(Features.Pro.SNIPPETS, R.drawable.ic_vault, R.string.feat_snippets, R.string.feat_snippets_body)
    )

    /** Label, Free value, Pro value. A null value draws a dash, CHECK a tick. */
    private val comparison = listOf(
        Triple(R.string.cmp_remote, CHECK, CHECK),
        Triple(R.string.cmp_reconnect, CHECK, CHECK),
        Triple(R.string.cmp_themes, CHECK, CHECK),
        Triple(R.string.cmp_full_keyboard, null, CHECK),
        Triple(R.string.cmp_commands_launch, null, CHECK),
        Triple(R.string.cmp_panels_macros, null, CHECK),
        Triple(R.string.cmp_profiles_presentation, null, CHECK),
        Triple(R.string.cmp_trackpad, null, CHECK),
        Triple(R.string.cmp_live_voice, null, CHECK),
        Triple(R.string.cmp_keys_clipboard, null, CHECK),
        Triple(R.string.cmp_snippets, R.string.cmp_snippets_free, R.string.cmp_unlimited),
        Triple(R.string.cmp_ads, R.string.cmp_ads_free, R.string.cmp_ads_pro)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityProBinding.inflate(layoutInflater)
        setContentView(ui.root)


        ViewCompat.setOnApplyWindowInsetsListener(ui.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            ui.scroll.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            ui.footer.updatePadding(
                left = bars.left + dp(16),
                right = bars.right + dp(16),
                bottom = bars.bottom + dp(12)
            )
            insets
        }

        ui.close.setOnClickListener { finish() }
        ui.restore.setOnClickListener { restore() }
        ui.terms.setOnClickListener { openDoc("terms.txt", R.string.terms_title) }
        ui.privacy.setOnClickListener { openDoc("privacy.txt", R.string.privacy_title) }
        ui.cta.setOnClickListener { onCta() }

        val highlight = Features.Pro.of(intent.getStringExtra(EXTRA_FEATURE))
        features.firstOrNull { it.which == highlight }?.let {
            ui.highlight.text = getString(R.string.pro_highlight, getString(it.title))
            ui.highlight.isVisible = true
        }

        buildComparison()

        billing.onPlansChanged = { render() }
        billing.onPurchaseOutcome = { outcome -> onOutcome(outcome) }
        app.observeEntitlement(entitlementObserver)

        render()
    }

    override fun onResume() {
        super.onResume()
        // Plans that failed to load offline appear once Play answers.
        billing.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        app.stopObservingEntitlement(entitlementObserver)
        billing.onPlansChanged = null
        billing.onPurchaseOutcome = null
    }

    private fun render() {
        val pro = app.entitlements.isPro
        buildFeatures(pro)

        val trialDays = billing.plans.firstNotNullOfOrNull { p -> p.freeTrial?.let { trialDays(it) } }
        ui.title.text = when {
            pro -> getString(R.string.pro_title_active)
            trialDays != null -> getString(R.string.pro_title_trial, trialDays)
            else -> getString(R.string.pro_title)
        }
        ui.subtitle.setText(if (pro) R.string.pro_subtitle_active else R.string.pro_subtitle)
        ui.compareHeader.isVisible = !pro
        ui.compareTable.isVisible = !pro
        ui.restore.isVisible = !pro

        if (pro) {
            ui.highlight.isVisible = false
            ui.plansHeader.isVisible = false
            ui.planList.isVisible = false
            ui.plansStatus.isVisible = false
            ui.trialTimeline.isVisible = false
            val subscribed = app.entitlements.proSubscription && !app.entitlements.proLifetime
            ui.cta.setText(if (subscribed) R.string.manage_subscription else R.string.done)
            ui.finePrint.setText(
                if (subscribed) R.string.fine_manage else R.string.fine_lifetime_owned
            )
            ui.cta.isEnabled = true
            return
        }

        // A plan with a free trial leads, then shortest period first.
        val plans = billing.plans.sortedWith(
            compareBy<Billing.Plan>({ it.freeTrial == null }, { it.period.ordinal })
        )
        ui.plansHeader.isVisible = true
        ui.planList.isVisible = plans.isNotEmpty()
        ui.plansStatus.isVisible = plans.isEmpty()
        ui.plansStatus.setText(
            if (billing.plansLoaded) R.string.plans_unavailable else R.string.plans_loading
        )

        if (selected == null || plans.none { it === selected }) {
            selected = plans.firstOrNull { it.freeTrial != null }
                ?: plans.firstOrNull { it.period == Billing.Period.YEARLY }
                ?: plans.firstOrNull()
        }
        buildPlans(plans)
        renderCta()
    }

    private fun buildFeatures(pro: Boolean) {
        ui.featureList.removeAllViews()
        features.forEach { f ->
            val row = ItemFeatureBinding.inflate(layoutInflater, ui.featureList, false)
            row.icon.setImageResource(f.icon)
            row.title.setText(f.title)
            row.body.setText(f.body)
            // Ticks mean "yours": on the sales page they would claim
            // something the user does not have yet.
            row.check.isVisible = pro
            ui.featureList.addView(row.root)
        }
    }

    private fun buildComparison() {
        // Row 0 is the header in the layout; rebuild everything after it.
        while (ui.compareTable.childCount > 1) ui.compareTable.removeViewAt(1)
        comparison.forEach { (label, free, pro) ->
            val row = ItemCompareBinding.inflate(layoutInflater, ui.compareTable, false)
            row.label.setText(label)
            fill(row.freeText, row.freeIcon, free, R.attr.bpTextFaint)
            fill(row.proText, row.proIcon, pro, R.attr.bpLive)
            ui.compareTable.addView(row.root)
        }
    }

    private fun fill(text: android.widget.TextView, icon: android.widget.ImageView, value: Int?, tint: Int) {
        when (value) {
            CHECK -> {
                text.isVisible = false
                icon.isVisible = true
                icon.setImageResource(R.drawable.ic_check_circle_fill)
                icon.setColorFilter(themeColor(tint))
            }
            null -> {
                text.isVisible = true
                icon.isVisible = false
                text.setText(R.string.cmp_none)
            }
            else -> {
                text.isVisible = true
                icon.isVisible = false
                text.setText(value)
            }
        }
    }

    private fun buildPlans(plans: List<Billing.Plan>) {
        ui.planList.removeAllViews()

        plans.forEach { plan ->
            val row = ItemPlanBinding.inflate(layoutInflater, ui.planList, false)
            row.price.text = plan.price
            row.badge.isVisible = false

            val trial = plan.freeTrial?.let { trialPhrase(it) }
            val trialDetail = trial?.let {
                getString(R.string.plan_trial_detail, it, plan.price, periodWord(plan.period))
            }

            when (plan.period) {
                Billing.Period.WEEKLY -> {
                    row.name.setText(R.string.plan_weekly)
                    row.per.setText(R.string.per_week)
                    row.detail.text = trialDetail ?: getString(R.string.plan_weekly_detail)
                }
                Billing.Period.MONTHLY -> {
                    row.name.setText(R.string.plan_monthly)
                    row.per.setText(R.string.per_month)
                    row.detail.text = trialDetail ?: getString(R.string.plan_monthly_detail)
                }
                Billing.Period.YEARLY -> {
                    row.name.setText(R.string.plan_yearly)
                    row.per.setText(R.string.per_year)
                    val perMonth = money(plan.priceMicros / 12, plan.currencyCode)
                    row.detail.text = trialDetail
                        ?: perMonth?.let { getString(R.string.plan_yearly_detail, it) }
                        ?: getString(R.string.plan_billed_yearly)
                    savingPercent(plan, plans)?.let {
                        row.badge.text = getString(R.string.badge_save, it)
                        row.badge.isVisible = true
                    }
                }
                Billing.Period.LIFETIME -> {
                    row.name.setText(R.string.plan_lifetime)
                    row.per.setText(R.string.per_once)
                    row.detail.setText(R.string.plan_lifetime_detail)
                    row.badge.setText(R.string.badge_best_value)
                    row.badge.isVisible = true
                }
            }

            // A trial is the strongest reason to start; it outranks any saving.
            plan.freeTrial?.let { trialLabel(it) }?.let {
                row.badge.text = it
                row.badge.isVisible = true
            }

            row.root.isSelected = plan === selected
            row.root.setOnClickListener {
                selected = plan
                Haptics.tick(it)
                for (i in 0 until ui.planList.childCount) {
                    ui.planList.getChildAt(i).isSelected = ui.planList.getChildAt(i) === row.root
                }
                renderCta()
            }
            ui.planList.addView(row.root)
        }
    }

    private fun renderCta() {
        val plan = selected
        ui.cta.isEnabled = plan != null
        renderTimeline(plan)
        if (plan == null) {
            ui.cta.setText(R.string.cta_continue)
            ui.finePrint.setText(R.string.fine_unavailable)
            return
        }

        val trial = plan.freeTrial?.let { trialPhrase(it) }
        val days = plan.freeTrial?.let { trialDays(it) }
        ui.cta.text = when {
            plan.period == Billing.Period.LIFETIME -> getString(R.string.cta_lifetime)
            days != null -> getString(R.string.cta_trial_days, days)
            trial != null -> getString(R.string.cta_trial)
            else -> getString(R.string.cta_continue)
        }

        ui.finePrint.text = when {
            plan.period == Billing.Period.LIFETIME -> getString(R.string.fine_lifetime, plan.price)
            trial != null -> getString(R.string.fine_trial, trial, plan.price, periodWord(plan.period))
            else -> getString(R.string.fine_sub, plan.price, periodWord(plan.period))
        }
    }

    /**
     * Today, the day the trial ends, and after: when Pro starts, when the
     * first charge happens and how to avoid it. Only for a plan with a trial.
     */
    private fun renderTimeline(plan: Billing.Plan?) {
        val box = ui.trialTimeline
        box.removeAllViews()
        val iso = plan?.freeTrial
        if (plan == null || iso == null || trialPhrase(iso) == null || app.entitlements.isPro) {
            box.isVisible = false
            return
        }
        box.isVisible = true
        val days = trialDays(iso)
        val steps = listOf(
            Triple(R.drawable.ic_crown_simple_fill, getString(R.string.trial_step_today), getString(R.string.trial_step_today_body)),
            Triple(
                R.drawable.ic_clock,
                if (days != null) getString(R.string.trial_step_day, days) else getString(R.string.trial_step_end),
                getString(R.string.trial_step_end_body)
            ),
            Triple(
                R.drawable.ic_arrow_clockwise,
                getString(R.string.trial_step_after),
                getString(R.string.trial_step_after_body, plan.price, periodWord(plan.period))
            )
        )
        steps.forEachIndexed { i, (icon, title, body) ->
            box.addView(timelineStep(icon, title, body, first = i == 0, last = i == steps.lastIndex))
        }
    }

    private fun timelineStep(icon: Int, title: String, body: String, first: Boolean, last: Boolean): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        // The rail: a dot per step, joined by a line down to the next one.
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val dot = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (first) {
                    orientation = GradientDrawable.Orientation.TL_BR
                    colors = intArrayOf(getColor(R.color.bp_pro_start), getColor(R.color.bp_pro_end))
                } else {
                    setColor(themeColor(R.attr.bpSurfaceHigh))
                }
            }
            addView(
                ImageView(this@ProActivity).apply {
                    setImageResource(icon)
                    setColorFilter(if (first) getColor(R.color.bp_on_pro) else themeColor(R.attr.bpTextDim))
                },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER)
            )
        }
        rail.addView(dot, LinearLayout.LayoutParams(dp(32), dp(32)))
        if (!last) {
            rail.addView(
                View(this).apply { setBackgroundColor(themeColor(R.attr.bpStroke)) },
                LinearLayout.LayoutParams(dp(2), 0, 1f).apply {
                    topMargin = dp(4)
                    bottomMargin = dp(4)
                }
            )
        }
        row.addView(rail, LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT))

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(5), 0, dp(if (last) 10 else 18))
        }
        text.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Subtitle)
            this.text = title
        })
        text.addView(TextView(this).apply {
            setTextAppearance(R.style.Text_Small)
            setPadding(0, dp(2), 0, 0)
            this.text = body
        })
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun periodWord(period: Billing.Period): String = getString(
        when (period) {
            Billing.Period.WEEKLY -> R.string.period_week
            Billing.Period.MONTHLY -> R.string.period_month
            else -> R.string.period_year
        }
    )

    private fun onCta() {
        if (app.entitlements.isPro) {
            if (app.entitlements.proSubscription && !app.entitlements.proLifetime) {
                openManageSubscription()
            } else {
                finish()
            }
            return
        }
        val plan = selected ?: return
        if (!billing.launch(this, plan)) {
            Toast.makeText(this, R.string.billing_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onOutcome(outcome: Billing.Outcome) {
        when (outcome) {
            Billing.Outcome.PURCHASED -> {
                Haptics.confirm(this)
                render()
            }
            Billing.Outcome.PENDING ->
                Toast.makeText(this, R.string.purchase_pending, Toast.LENGTH_LONG).show()
            Billing.Outcome.FAILED ->
                Toast.makeText(this, R.string.purchase_failed, Toast.LENGTH_LONG).show()
            Billing.Outcome.CANCELLED -> Unit
        }
    }

    private fun restore() {
        ui.restore.isEnabled = false
        billing.restore { answered ->
            ui.restore.isEnabled = true
            val message = when {
                app.entitlements.isPro -> R.string.restore_pro
                app.entitlements.adsRemoved -> R.string.restore_ads_only
                answered -> R.string.restore_none
                else -> R.string.restore_failed
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            render()
        }
    }

    private fun openManageSubscription() {
        val uri = Uri.parse(
            "https://play.google.com/store/account/subscriptions" +
                "?sku=${Entitlements.PRODUCT_PRO_SUB}&package=$packageName"
        )
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

    private fun openDoc(asset: String, titleRes: Int) {
        startActivity(
            Intent(this, DocActivity::class.java)
                .putExtra(DocActivity.EXTRA_ASSET, asset)
                .putExtra(DocActivity.EXTRA_TITLE, titleRes)
        )
    }

    /**
     * Whole-percent saving of yearly over paying monthly (or, without a
     * monthly plan, weekly) for a year. Null if small or not comparable.
     */
    private fun savingPercent(yearly: Billing.Plan, plans: List<Billing.Plan>): Int? {
        val (base, perYear) = plans.firstOrNull { it.period == Billing.Period.MONTHLY }?.let { it to 12.0 }
            ?: plans.firstOrNull { it.period == Billing.Period.WEEKLY }?.let { it to 52.0 }
            ?: return null
        if (yearly.currencyCode != base.currencyCode || base.priceMicros <= 0) return null
        val percent = ((1 - yearly.priceMicros / (base.priceMicros * perYear)) * 100).roundToInt()
        return percent.takeIf { it >= 5 }
    }

    private fun money(micros: Long, currencyCode: String): String? = runCatching {
        NumberFormat.getCurrencyInstance().apply {
            currency = Currency.getInstance(currencyCode)
        }.format(micros / 1_000_000.0)
    }.getOrNull()

    /** Trial length in days, for day and week trials; null otherwise. */
    private fun trialDays(iso: String): Int? {
        val match = Regex("P(\\d+)([DW])").matchEntire(iso) ?: return null
        val n = match.groupValues[1].toInt()
        return if (match.groupValues[2] == "W") n * 7 else n
    }

    /** "P3D" to "Free for 3 days", for sentences. Null for anything unexpected. */
    private fun trialPhrase(iso: String): String? {
        trialDays(iso)?.let { return getString(R.string.trial_phrase_days, it) }
        val match = Regex("P(\\d+)M").matchEntire(iso) ?: return null
        val n = match.groupValues[1].toInt()
        return if (n == 1) getString(R.string.trial_phrase_month) else getString(R.string.trial_phrase_months, n)
    }

    /** "P3D" to "3-DAY FREE TRIAL", for badges. Null for anything unexpected. */
    private fun trialLabel(iso: String): String? {
        val match = Regex("P(\\d+)([DWMY])").matchEntire(iso) ?: return null
        val n = match.groupValues[1].toInt()
        return when (match.groupValues[2]) {
            "D" -> getString(R.string.trial_days, n)
            "W" -> if (n == 1) getString(R.string.trial_days, 7) else getString(R.string.trial_weeks, n)
            "M" -> getString(R.string.trial_months, n)
            else -> null
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val EXTRA_FEATURE = "feature"

        /** Sentinel for a tick in the comparison table. Not a resource ID. */
        private const val CHECK = -1

        fun open(context: Context, feature: Features.Pro?) {
            context.startActivity(
                Intent(context, ProActivity::class.java)
                    .putExtra(EXTRA_FEATURE, feature?.key)
            )
        }
    }
}
