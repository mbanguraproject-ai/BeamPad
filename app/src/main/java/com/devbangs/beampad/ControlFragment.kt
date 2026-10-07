package com.devbangs.beampad

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.devbangs.beampad.databinding.FragmentControlBinding
import com.devbangs.beampad.databinding.ItemModeChipBinding

/**
 * The Control tab: a row of control surfaces (the built-in modes, then the
 * user's custom panels) above the surface itself. Remembers the last one,
 * and a device profile can choose which one opens on connect.
 */
class ControlFragment : Fragment() {

    private var _ui: FragmentControlBinding? = null
    private val ui get() = _ui!!

    private val prefs get() = (requireActivity().application as BeamPadApp).prefs

    /** What is showing: Home, a built-in mode, or a panel id. */
    private var home = false
    private var mode: ControlMode? = null
    private var panelId: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, state: Bundle?
    ): View {
        _ui = FragmentControlBinding.inflate(inflater, container, false)
        return ui.root
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        val savedPanel = state?.getString(KEY_PANEL)
        val savedMode = enumOrNull<ControlMode>(state?.getString(KEY_MODE))
        when {
            state?.getBoolean(KEY_HOME) == true -> home = true
            state == null && prefs.lastHome -> home = true
            savedPanel != null -> panelId = savedPanel
            savedMode != null -> mode = savedMode
            prefs.lastPanelId != null && PanelStore(requireContext()).get(prefs.lastPanelId) != null &&
                Features.isPro(requireContext()) -> panelId = prefs.lastPanelId
            else -> mode = prefs.lastMode
        }
        if (mode != null && !Features.allowed(requireContext(), mode!!)) mode = ControlMode.KEYBOARD

        // The child is restored by the fragment manager after recreation;
        // only a first creation needs one added.
        if (childFragmentManager.findFragmentById(R.id.modeContainer) == null) swapChild(animate = false)
        buildChips()
    }

    override fun onResume() {
        super.onResume()
        // Panels may have been added, renamed or deleted, and Pro may have changed.
        buildChips()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_HOME, home)
        outState.putString(KEY_MODE, mode?.name)
        outState.putString(KEY_PANEL, panelId)
    }

    /** Shows a built-in mode. Locked modes open the Pro page instead. */
    fun showMode(next: ControlMode) {
        val context = context ?: return
        if (!Features.allowed(context, next)) {
            next.pro?.let { ProActivity.open(context, it) }
            return
        }
        if (mode == next && panelId == null && !home) return
        val from = position()
        home = false
        prefs.lastHome = false
        mode = next
        panelId = null
        prefs.lastMode = next
        prefs.lastPanelId = null
        swapChild(animate = true, forward = position() >= from)
        buildChips()
    }

    /** Shows a custom panel. Panels are Pro. */
    fun showPanel(id: String) {
        val context = context ?: return
        if (!Features.isPro(context)) {
            ProActivity.open(context, Features.Pro.PANELS)
            return
        }
        if (PanelStore(context).get(id) == null) return
        if (panelId == id && !home) return
        val from = position()
        home = false
        prefs.lastHome = false
        panelId = id
        mode = null
        prefs.lastPanelId = id
        swapChild(animate = true, forward = position() >= from)
        buildChips()
    }

    /** The surface showing now, for routing hardware keys to it. */
    fun currentSurface(): SurfaceFragment? =
        if (_ui == null) null else childFragmentManager.findFragmentById(R.id.modeContainer) as? SurfaceFragment

    /** Home: the four big modes, recent devices and quick actions. */
    fun showHome() {
        if (home) return
        val from = position()
        home = true
        mode = null
        panelId = null
        prefs.lastHome = true
        swapChild(animate = true, forward = position() >= from)
        buildChips()
    }

    /** Focus mode (the full keyboard): the mode bar steps aside. */
    fun setFocus(on: Boolean) {
        _ui?.modeTrack?.isVisible = !on
    }

    /** Where the showing surface sits in the track: Home, modes in order, then panels. */
    private fun position(): Int {
        if (home) return -1
        panelId?.let { id ->
            val index = PanelStore(requireContext()).all().indexOfFirst { it.id == id }
            return ControlMode.entries.size + index.coerceAtLeast(0)
        }
        return (mode ?: ControlMode.KEYBOARD).ordinal
    }

    private fun swapChild(animate: Boolean, forward: Boolean = true) {
        if (_ui == null) return
        val fragment = when {
            home -> HomeFragment()
            panelId != null -> PanelFragment.newInstance(panelId!!)
            else -> (mode ?: ControlMode.KEYBOARD).newFragment()
        }
        val tx = childFragmentManager.beginTransaction()
        // A short slide in the direction of the segment tapped, so the
        // surfaces read as a row: switching modes is spatial, never a wait.
        if (animate && !Motion.reduced(requireContext())) {
            if (forward) tx.setCustomAnimations(R.animator.mode_in_forward, R.animator.mode_out_forward)
            else tx.setCustomAnimations(R.animator.mode_in_back, R.animator.mode_out_back)
        }
        tx.replace(R.id.modeContainer, fragment).commit()
    }

    /** One segment's content; [key] identifies it across rebuilds. */
    private data class Segment(
        val key: String,
        val label: String,
        val icon: Int,
        val selected: Boolean,
        val locked: Boolean,
        val onTap: () -> Unit
    )

    private fun buildChips() {
        val ui = _ui ?: return
        val context = requireContext()
        val pro = Features.isPro(context)

        val segments = listOf(
            Segment(
                key = "home",
                label = getString(R.string.home_segment),
                icon = R.drawable.ic_house,
                selected = home,
                locked = false
            ) { showHome() }
        ) + ControlMode.entries.map { m ->
            Segment(
                key = "mode:${m.name}",
                label = getString(m.labelRes),
                icon = m.iconRes,
                selected = !home && panelId == null && mode == m,
                locked = !Features.allowed(context, m)
            ) { showMode(m) }
        } + PanelStore(context).all().map { panel ->
            Segment(
                key = "panel:${panel.id}",
                label = panel.name,
                icon = R.drawable.ic_layout,
                selected = panelId == panel.id,
                locked = !pro
            ) { showPanel(panel.id) }
        }

        val sameSet = ui.modeChips.childCount == segments.size &&
            segments.indices.all { ui.modeChips.getChildAt(it).tag == segments[it].key }
        if (sameSet) {
            // Update in place so the selected segment visibly widens to show
            // its name instead of the row being rebuilt under the finger.
            if (!Motion.reduced(context)) {
                android.transition.TransitionManager.beginDelayedTransition(
                    ui.modeScroll,
                    android.transition.AutoTransition().setDuration(170)
                )
            }
            segments.forEachIndexed { i, seg -> bindSegment(ItemModeChipBinding.bind(ui.modeChips.getChildAt(i)), seg) }
        } else {
            ui.modeChips.removeAllViews()
            segments.forEach { seg ->
                val chip = ItemModeChipBinding.inflate(layoutInflater, ui.modeChips, false)
                chip.root.tag = seg.key
                bindSegment(chip, seg)
                ui.modeChips.addView(chip.root)
            }
        }

        // Keep the selected segment in view.
        ui.modeChips.post {
            val selected = (0 until ui.modeChips.childCount)
                .map { ui.modeChips.getChildAt(it) }
                .firstOrNull { it.isSelected } ?: return@post
            ui.modeScroll.smoothScrollTo((selected.left - selected.width).coerceAtLeast(0), 0)
        }
    }

    private fun bindSegment(chip: ItemModeChipBinding, seg: Segment) {
        chip.label.text = seg.label
        chip.label.isVisible = seg.selected
        chip.icon.setImageResource(seg.icon)
        chip.lock.isVisible = seg.locked
        chip.root.isSelected = seg.selected
        chip.root.contentDescription = seg.label
        androidx.appcompat.widget.TooltipCompat.setTooltipText(chip.root, seg.label)
        chip.root.setOnClickListener {
            Haptics.tick(it)
            seg.onTap()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _ui = null
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_PANEL = "panel"
        const val KEY_HOME = "home"
    }
}
