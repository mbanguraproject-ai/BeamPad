package com.devbangs.beampad

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentPageBinding
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Snippets 2.0: saved text typed onto the device in one tap. Wi-Fi
 * passwords, accounts, common text and links, each encrypted with a key
 * held in this phone's keystore, never synced, and gated by the screen
 * lock when marked protected (with the auto-lock window from Settings).
 */
class SnippetsFragment : Fragment() {

    private var _ui: FragmentPageBinding? = null
    private val ui get() = _ui!!

    private lateinit var store: SnippetStore
    private var filter: SnippetCategory? = null

    private val host get() = activity as? MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _ui = FragmentPageBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        store = SnippetStore(requireContext())
        filter = enumOrNull<SnippetCategory>(state?.getString(KEY_FILTER))
        Ui.header(ui.header.root, getString(R.string.snippets_title), getString(R.string.snippets_subtitle))
        Ui.headerButton(ui.header.root, getString(R.string.add), R.drawable.ic_plus) { startAdd() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_FILTER, filter?.name)
    }

    private fun render() {
        val ui = _ui ?: return
        val context = requireContext()
        val content = ui.content
        content.removeAllViews()
        val all = store.all()
        val pro = Features.isPro(context)

        if (all.isEmpty()) {
            Ui.empty(content, R.drawable.ic_vault, getString(R.string.snippets_empty_title),
                getString(R.string.snippets_empty_body), getString(R.string.snippet_add)) { startAdd() }
        } else {
            val used = all.map { it.category }.toSet()
            if (used.size > 1) {
                val chips = LinearLayout(context).apply {
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 4), Ui.dp(context, 16), 0)
                }
                content.addView(chips)
                val options = listOf<Pair<SnippetCategory?, CharSequence>>(null to getString(R.string.snip_all)) +
                    SnippetCategory.entries.filter { it in used }.map { it to getString(it.labelRes) }
                Ui.chipGroup(chips, options, filter) {
                    filter = it
                    render()
                }
            }

            val shown = all.filter { filter == null || it.category == filter }
            Ui.section(content, if (pro) resources.getQuantityString(R.plurals.snippet_count, all.size, all.size)
                else getString(R.string.snippet_count_free, all.size, Features.FREE_SNIPPET_LIMIT))
            val card = Ui.card(content)
            shown.forEachIndexed { i, s ->
                if (i > 0) Ui.divider(card)
                val subtitle = if (s.secret) getString(R.string.snip_protected, getString(s.category.labelRes))
                else runCatching { store.reveal(s) }.getOrNull()?.replace('\n', ' ')?.take(PREVIEW) ?: getString(s.category.labelRes)
                val row = Ui.row(card, s.label, subtitle, if (s.secret) R.drawable.ic_lock_key else s.category.iconRes,
                    if (s.secret) Ui.Tone.ACCENT else Ui.Tone.NEUTRAL) { startEdit(s) }
                row.subtitle.maxLines = 1
                row.subtitle.ellipsize = android.text.TextUtils.TruncateAt.END
                row.trailing.addView(Ui.button(row.trailing, Ui.ButtonKind.SMALL, getString(R.string.snippet_send_short),
                    R.drawable.ic_paper_plane_right) { send(s) })
            }
        }

        // The blueprint's product message, where people decide to trust it.
        Ui.space(content, 8)
        val privacy = Ui.card(content)
        Ui.row(privacy, getString(R.string.snip_privacy_title), getString(R.string.snip_privacy_body),
            R.drawable.ic_shield_check, Ui.Tone.LIVE).subtitle.maxLines = 4
    }

    // ---- Sending ----------------------------------------------------------------

    private fun send(snippet: Snippet) {
        val service = host?.service
        if (service == null || !service.isReady()) {
            toast(getString(R.string.not_connected_hint))
            return
        }
        if (!snippet.secret || SnippetLock.isUnlocked(requireContext())) {
            type(snippet)
            return
        }
        Auth.require(
            requireActivity(),
            getString(R.string.auth_reason),
            onSuccess = {
                SnippetLock.unlocked()
                type(snippet)
            },
            onFail = { toast(it) }
        )
    }

    private fun type(snippet: Snippet) {
        val service = host?.service ?: return
        val plaintext = runCatching { store.reveal(snippet) }.getOrElse {
            toast(getString(R.string.snippet_decrypt_failed))
            return
        }
        service.typeText(plaintext) { sent, skipped ->
            activity?.runOnUiThread {
                if (skipped > 0) toast(getString(R.string.snippet_partly_sent, sent, skipped))
                else toast(getString(R.string.snip_sent, snippet.label))
            }
        }
    }

    // ---- Adding and editing -------------------------------------------------------

    private fun startAdd() {
        val context = requireContext()
        if (!Features.canAddSnippet(context, store.all().size)) {
            ProActivity.open(context, Features.Pro.SNIPPETS)
            return
        }
        editor(null)
    }

    /** Protected snippets ask for the screen lock before their value can be seen or changed. */
    private fun startEdit(snippet: Snippet) {
        if (!snippet.secret || SnippetLock.isUnlocked(requireContext())) {
            editor(snippet)
            return
        }
        Auth.require(requireActivity(), getString(R.string.auth_reason_edit),
            onSuccess = {
                SnippetLock.unlocked()
                editor(snippet)
            },
            onFail = { toast(it) })
    }

    private fun editor(existing: Snippet?) {
        val context = requireContext()
        var category = existing?.category ?: filter ?: SnippetCategory.TEXT
        val sheet = Sheet(context)
            .title(getString(if (existing == null) R.string.snippet_add else R.string.snip_edit))
            .subtitle(getString(R.string.snip_editor_body))
            .withKeyboard()

        val label = Ui.field(sheet.content, getString(R.string.snippet_label), existing?.label)
        val value = Ui.field(sheet.content, getString(R.string.snippet_value),
            existing?.let { runCatching { store.reveal(it) }.getOrNull() })

        fun applyCategory() {
            label.input.hint = getString(
                when (category) {
                    SnippetCategory.WIFI -> R.string.snip_hint_wifi_label
                    SnippetCategory.ACCOUNT -> R.string.snip_hint_account_label
                    SnippetCategory.LINK -> R.string.snip_hint_link_label
                    SnippetCategory.TEXT -> R.string.snippet_label_hint
                }
            )
            value.label.setText(
                when (category) {
                    SnippetCategory.WIFI -> R.string.snip_value_wifi
                    SnippetCategory.ACCOUNT -> R.string.snip_value_account
                    SnippetCategory.LINK -> R.string.snip_value_link
                    SnippetCategory.TEXT -> R.string.snippet_value
                }
            )
            val multi = category == SnippetCategory.TEXT
            value.input.isSingleLine = !multi
            value.input.minLines = if (multi) 3 else 1
            value.input.maxLines = if (multi) 6 else 1
            value.input.gravity = if (multi) android.view.Gravity.TOP or android.view.Gravity.START
            else android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.START
            value.input.inputType = when (category) {
                // Visible-password: no suggestions and no learning, so the
                // keyboard never stores a password the user typed here.
                SnippetCategory.WIFI, SnippetCategory.ACCOUNT ->
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                SnippetCategory.LINK -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                SnippetCategory.TEXT -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            value.input.hint = getString(
                when (category) {
                    SnippetCategory.WIFI -> R.string.snip_hint_wifi
                    SnippetCategory.ACCOUNT -> R.string.snip_hint_account
                    SnippetCategory.LINK -> R.string.snip_hint_link
                    SnippetCategory.TEXT -> R.string.snippet_value_hint
                }
            )
        }

        // Category first: it shapes the two fields below it.
        val chipsTitle = TextView(context).apply {
            setTextAppearance(R.style.Text_Label_Small)
            text = getString(R.string.snip_kind)
            setPadding(Ui.dp(context, 4), Ui.dp(context, 8), 0, Ui.dp(context, 8))
        }
        sheet.content.addView(chipsTitle, 0)
        val chipsHolder = LinearLayout(context)
        sheet.content.addView(chipsHolder, 1)
        Ui.chipGroup(chipsHolder, SnippetCategory.entries.map { it to getString(it.labelRes) }, category) {
            category = it
            applyCategory()
        }
        applyCategory()

        // Protection, in its own card so it reads as a decision, not a detail.
        Ui.space(sheet.content, 16)
        val protectCard = layoutInflater.inflate(R.layout.ui_card, sheet.content, false) as LinearLayout
        (protectCard.layoutParams as ViewGroup.MarginLayoutParams).apply { marginStart = 0; marginEnd = 0 }
        sheet.content.addView(protectCard)
        val (_, protect) = Ui.switchRow(
            protectCard,
            getString(R.string.snippet_protect),
            getString(R.string.snip_protect_body),
            R.drawable.ic_fingerprint,
            existing?.secret ?: (category == SnippetCategory.WIFI || category == SnippetCategory.ACCOUNT)
        ) { it }

        if (existing != null) {
            Ui.space(sheet.content, 8)
            val delete = Ui.button(sheet.content, Ui.ButtonKind.TEXT, getString(R.string.snippet_delete), R.drawable.ic_trash) {
                sheet.dismiss()
                Sheets.confirm(context, getString(R.string.snip_delete_confirm, existing.label),
                    getString(R.string.delete_message), getString(R.string.snippet_delete), destructive = true) {
                    store.delete(existing.id)
                    render()
                }
            }
            delete.setTextColor(context.themeColor(R.attr.bpDanger))
            delete.iconTint = android.content.res.ColorStateList.valueOf(context.themeColor(R.attr.bpDanger))
            sheet.content.addView(delete)
        }

        sheet.secondary(getString(R.string.cancel))
        sheet.primary(getString(R.string.save)) {
            val l = label.text.trim()
            val v = value.text
            label.help(if (l.isEmpty()) getString(R.string.field_required) else null)
            value.help(if (v.isEmpty()) getString(R.string.field_required) else null)
            if (l.isEmpty() || v.isEmpty()) return@primary false
            try {
                val lockAvailable = Auth.isAvailable(requireActivity())
                if (existing == null) {
                    store.add(l, v, protect.isChecked, lockAvailable, category)
                } else {
                    store.update(existing.id, l, v, protect.isChecked, category, lockAvailable)
                }
                render()
                true
            } catch (e: SnippetStore.NoScreenLockException) {
                toast(e.message ?: Auth.NO_LOCK_MESSAGE)
                false
            }
        }
        sheet.show()
        if (existing == null) label.input.requestFocus()
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }

    private companion object {
        const val KEY_FILTER = "filter"
        const val PREVIEW = 48
    }
}
