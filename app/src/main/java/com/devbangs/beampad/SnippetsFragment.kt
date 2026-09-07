package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.devbangs.beampad.databinding.FragmentSnippetsBinding
import com.devbangs.beampad.databinding.ItemSnippetBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

class SnippetsFragment : Fragment() {

    private var _ui: FragmentSnippetsBinding? = null
    private val ui get() = _ui!!

    private lateinit var store: SnippetStore
    private lateinit var adapter: SnippetAdapter

    private val host get() = activity as? MainActivity

    private val connectionObserver: (Boolean) -> Unit = { adapter.connected = it }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _ui = FragmentSnippetsBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        store = SnippetStore(requireContext())

        adapter = SnippetAdapter(
            onSend = { send(it) },
            onDelete = { confirmDelete(it) }
        )

        ui.list.layoutManager = LinearLayoutManager(requireContext())
        ui.list.adapter = adapter
        ui.add.setOnClickListener { showAddDialog() }

        refresh()
        host?.observeConnection(connectionObserver)
    }

    private fun refresh() {
        val items = store.all()
        adapter.submitList(items)
        ui.empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun send(snippet: Snippet) {
        val service = host?.service
        if (service == null || !service.isReady()) {
            toast(getString(R.string.not_connected))
            return
        }

        if (!snippet.secret) {
            type(snippet)
            return
        }

        Auth.require(
            requireActivity(),
            getString(R.string.auth_reason),
            onSuccess = { type(snippet) },
            onFail = { toast(it) }
        )
    }

    private fun type(snippet: Snippet) {
        val service = host?.service ?: return
        val plaintext = runCatching { store.reveal(snippet) }.getOrElse {
            toast("could not decrypt this snippet")
            return
        }
        service.typeText(plaintext) { sent, skipped ->
            activity?.runOnUiThread {
                if (skipped > 0) toast("sent $sent characters, $skipped unsupported")
            }
        }
    }

    private fun confirmDelete(snippet: Snippet) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(snippet.label)
            .setMessage("Delete this snippet?")
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.snippet_delete) { _, _ ->
                store.delete(snippet.id)
                refresh()
            }
            .show()
    }

    private fun showAddDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()

        val label = EditText(requireContext()).apply {
            hint = getString(R.string.snippet_label)
            isSingleLine = true
        }
        val value = EditText(requireContext()).apply {
            hint = getString(R.string.snippet_value)
        }
        val protect = MaterialSwitch(requireContext()).apply {
            text = getString(R.string.snippet_protect)
        }

        val body = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(label)
            addView(value)
            addView(protect)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.snippet_add)
            .setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ ->
                val l = label.text.toString().trim()
                val v = value.text.toString()
                if (l.isEmpty() || v.isEmpty()) {
                    toast("label and text are both required")
                    return@setPositiveButton
                }
                try {
                    store.add(
                        label = l,
                        plaintext = v,
                        secret = protect.isChecked,
                        screenLockAvailable = Auth.isAvailable(requireActivity())
                    )
                    refresh()
                } catch (e: SnippetStore.NoScreenLockException) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.snippet_protect)
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .show()
    }

    private fun toast(msg: String) =
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    override fun onDestroyView() {
        super.onDestroyView()
        host?.stopObserving(connectionObserver)
        _ui = null
    }
}

class SnippetAdapter(
    private val onSend: (Snippet) -> Unit,
    private val onDelete: (Snippet) -> Unit
) : ListAdapter<Snippet, SnippetAdapter.Holder>(DIFF) {

    /** Rows are dimmed and send is disabled while nothing is paired. */
    var connected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            notifyItemRangeChanged(0, itemCount, PAYLOAD_CONNECTION)
        }

    class Holder(val ui: ItemSnippetBinding) : RecyclerView.ViewHolder(ui.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(
            ItemSnippetBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
        )

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.ui.label.text = item.label
        holder.ui.icon.setImageResource(
            if (item.secret) R.drawable.ic_lock_key else R.drawable.ic_paper_plane_right
        )
        holder.ui.send.setOnClickListener { onSend(item) }
        holder.ui.delete.setOnClickListener { onDelete(item) }
        holder.ui.root.setOnClickListener { onSend(item) }
        applyConnection(holder)
    }

    override fun onBindViewHolder(
        holder: Holder, position: Int, payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_CONNECTION)) {
            applyConnection(holder)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /** Only touches connection-dependent state, so no flicker on rebind. */
    private fun applyConnection(holder: Holder) {
        holder.ui.send.isEnabled = connected
        holder.ui.root.isEnabled = connected
        holder.ui.send.alpha = if (connected) 1f else 0.4f
        holder.ui.label.alpha = if (connected) 1f else 0.5f
        holder.ui.icon.alpha = if (connected) 1f else 0.5f
    }

    private companion object {
        const val PAYLOAD_CONNECTION = "connection"

        val DIFF = object : DiffUtil.ItemCallback<Snippet>() {
            override fun areItemsTheSame(a: Snippet, b: Snippet) = a.id == b.id
            override fun areContentsTheSame(a: Snippet, b: Snippet) = a == b
        }
    }
}
