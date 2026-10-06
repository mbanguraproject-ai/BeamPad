package com.devbangs.beampad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
 * The Pro page: what Pro adds, Free next to Pro, and the plans Play offers.
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

    override fun onDestroy() {
        super.onDestroy()
        app.stopObservingEntitlement(entitlementObserver)
        billing.onPlansChanged = null
        billing.onPurchaseOutcome = null
    }

    private fun render() {
        val pro = app.entitlements.isPro
        buildFeatures(pro)

        ui.title.setText(if (pro) R.string.pro_title_active else R.string.pro_title)
        ui.subtitle.setText(if (pro) R.string.pro_subtitle_active else R.string.pro_subtitle)
        ui.compareHeader.isVisible = !pro
        ui.compareTable.isVisible = !pro
        ui.restore.isVisible = !pro

        if (pro) {
            ui.highlight.isVisible = false
            ui.plansHeader.isVisible = false
            ui.planList.isVisible = false
            ui.plansStatus.isVisible = false
            val subscribed = app.entitlements.proSubscription && !app.entitlements.proLifetime
            ui.cta.setText(if (subscribed) R.string.manage_subscription else R.string.done)
            ui.finePrint.setText(
                if (subscribed) R.string.fine_manage else R.string.fine_lifetime_owned
            )
            ui.cta.isEnabled = true
            return
        }

        val plans = billing.plans
        ui.plansHeader.isVisible = true
        ui.planList.isVisible = plans.isNotEmpty()
        ui.plansStatus.isVisible = plans.isEmpty()
        ui.plansStatus.setText(
            if (billing.plansLoaded) R.string.plans_unavailable else R.string.plans_loading
        )

        if (selected == null || plans.none { it === selected }) {
            selected = plans.firstOrNull { it.period == Billing.Period.YEARLY } ?: plans.firstOrNull()
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
        val monthly = plans.firstOrNull { it.period == Billing.Period.MONTHLY }

        plans.forEach { plan ->
            val row = ItemPlanBinding.inflate(layoutInflater, ui.planList, false)
            row.price.text = plan.price

            when (plan.period) {
                Billing.Period.YEARLY -> {
                    row.name.setText(R.string.plan_yearly)
                    row.per.setText(R.string.per_year)
                    val perMonth = money(plan.priceMicros / 12, plan.currencyCode)
                    val saving = monthly?.let { savingPercent(plan, it) }
                    row.detail.text = when {
                        perMonth != null && saving != null ->
                            getString(R.string.plan_yearly_detail_saving, perMonth, saving)
                        perMonth != null -> getString(R.string.plan_yearly_detail, perMonth)
                        else -> getString(R.string.plan_billed_yearly)
                    }
                    row.badge.setText(R.string.badge_best_value)
                    row.badge.isVisible = true
                }
                Billing.Period.MONTHLY -> {
                    row.name.setText(R.string.plan_monthly)
                    row.per.setText(R.string.per_month)
                    row.detail.setText(R.string.plan_monthly_detail)
                }
                Billing.Period.LIFETIME -> {
                    row.name.setText(R.string.plan_lifetime)
                    row.per.setText(R.string.per_once)
                    row.detail.setText(R.string.plan_lifetime_detail)
                }
            }

            // A trial is the strongest reason to start; it outranks "best value".
            plan.freeTrial?.let { trialLabel(it) }?.let {
                row.badge.text = it
                row.badge.isVisible = true
            }

            row.root.isSelected = plan === selected
            row.root.setOnClickListener {
                selected = plan
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
        if (plan == null) {
            ui.cta.setText(R.string.cta_continue)
            ui.finePrint.setText(R.string.fine_unavailable)
            return
        }

        val trial = plan.freeTrial?.let { trialLabel(it) }
        ui.cta.setText(
            when {
                plan.period == Billing.Period.LIFETIME -> R.string.cta_lifetime
                trial != null -> R.string.cta_trial
                else -> R.string.cta_continue
            }
        )

        val period = getString(
            if (plan.period == Billing.Period.YEARLY) R.string.period_year else R.string.period_month
        )
        ui.finePrint.text = when {
            plan.period == Billing.Period.LIFETIME -> getString(R.string.fine_lifetime, plan.price)
            trial != null -> getString(R.string.fine_trial, trial, plan.price, period)
            else -> getString(R.string.fine_sub, plan.price, period)
        }
    }

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
            Billing.Outcome.PURCHASED -> render()
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

    /** Whole-percent saving of yearly over twelve months, or null if small. */
    private fun savingPercent(yearly: Billing.Plan, monthly: Billing.Plan): Int? {
        if (yearly.currencyCode != monthly.currencyCode || monthly.priceMicros <= 0) return null
        val fullYear = monthly.priceMicros * 12.0
        val percent = ((1 - yearly.priceMicros / fullYear) * 100).roundToInt()
        return percent.takeIf { it >= 5 }
    }

    private fun money(micros: Long, currencyCode: String): String? = runCatching {
        NumberFormat.getCurrencyInstance().apply {
            currency = Currency.getInstance(currencyCode)
        }.format(micros / 1_000_000.0)
    }.getOrNull()

    /** "P7D" to "7-day free trial". Null for anything unexpected. */
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
