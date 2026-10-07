package com.devbangs.beampad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Renders a custom panel on its 4-column grid, live or in the editor.
 *
 * Live, every component drives the device through [Host]. Editing, the
 * same components draw exactly as they will appear but do not send: a tap
 * opens the component's settings and a long press drags it to a new place.
 * One renderer for both means the editor can never show something the
 * panel does not.
 */
class PanelGrid @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    /** What live components need from the screen hosting them. */
    interface Host {
        fun sendAction(action: Action)
        fun liveService(nudge: Boolean): HidService?
        fun bindPad(pad: TrackpadView)
    }

    var host: Host? = null

    /** Editor callbacks: tap to configure, and the new order after a drag. */
    var onEdit: ((PanelComponent) -> Unit)? = null
    var onReorder: ((List<PanelComponent>) -> Unit)? = null

    var editing = false
        set(value) {
            field = value
            adapter?.notifyDataSetChanged()
        }

    private val items = mutableListOf<PanelComponent>()
    private val gap = Ui.dp(context, 10)
    private val cell: Int get() = (Ui.dp(context, CELL_DP) * ControlSizing.scale(context)).roundToInt()

    private val dragHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END, 0
    ) {
        override fun isLongPressDragEnabled() = editing

        override fun onMove(rv: RecyclerView, from: ViewHolder, to: ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            items.add(b, items.removeAt(a))
            adapter?.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(viewHolder: ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.let { Haptics.tick(it) }
                viewHolder?.itemView?.animate()?.scaleX(1.03f)?.scaleY(1.03f)?.setDuration(120)?.start()
            }
        }

        override fun clearView(rv: RecyclerView, viewHolder: ViewHolder) {
            super.clearView(rv, viewHolder)
            viewHolder.itemView.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            onReorder?.invoke(items.toList())
        }
    })

    init {
        val grid = GridLayoutManager(context, Panel.COLUMNS)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = items.getOrNull(position)?.span?.coerceIn(1, Panel.COLUMNS) ?: 1
        }
        layoutManager = grid
        clipToPadding = false
        overScrollMode = OVER_SCROLL_NEVER
        addItemDecoration(object : ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: State) {
                out.set(gap / 2, gap / 2, gap / 2, gap / 2)
            }
        })
        adapter = Adapter()
        dragHelper.attachToRecyclerView(this)
    }

    /** The side padding the screen asked for, before any centring. */
    private var baseSide = -1

    /**
     * Keeps the grid at a phone-like width on wide windows (tablets,
     * unfolded foldables, phones in landscape), centred, so four columns
     * never stretch into long thin buttons and a giant pad.
     */
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        if (baseSide < 0) baseSide = paddingLeft
        val available = MeasureSpec.getSize(widthSpec)
        val max = (Ui.dp(context, MAX_WIDTH_DP) * ControlSizing.scale(context)).roundToInt()
        val side = maxOf(baseSide, (available - max) / 2)
        if (side != paddingLeft || side != paddingRight) setPadding(side, paddingTop, side, paddingBottom)
        super.onMeasure(widthSpec, heightSpec)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(components: List<PanelComponent>) {
        items.clear()
        items.addAll(components)
        adapter?.notifyDataSetChanged()
    }

    private class Holder(val frame: FrameLayout) : ViewHolder(frame)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(FrameLayout(parent.context).apply {
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, cell)
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val c = items[position]
            holder.frame.layoutParams = holder.frame.layoutParams.apply {
                height = cell * c.rows + gap * (c.rows - 1)
            }
            holder.frame.removeAllViews()
            holder.frame.addView(build(c), FrameLayout.LayoutParams(MATCH, MATCH))
            if (editing) {
                // A shield over the live component: taps configure, long
                // presses drag, nothing reaches the device.
                holder.frame.addView(View(context).apply {
                    setBackgroundResource(R.drawable.bg_edit_outline)
                    setOnClickListener {
                        Haptics.tick(it)
                        onEdit?.invoke(c)
                    }
                    contentDescription = context.getString(R.string.panel_edit_component, label(c))
                }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }
    }

    // ---- Components ---------------------------------------------------------

    private val inflater = LayoutInflater.from(context)

    /** The label a component shows: the user's, else its action's name, else its type's. */
    fun label(c: PanelComponent): String = c.label?.takeIf { it.isNotBlank() }
        ?: c.action?.let { ActionPicker.describe(context, it) }
        ?: context.getString(c.type.labelRes)

    private fun key(text: String?, icon: Int?, accent: Boolean = false): MaterialButton {
        val key = inflater.inflate(if (accent) R.layout.ui_key_accent else R.layout.ui_key, this, false) as MaterialButton
        key.text = text
        if (icon != null) key.setIconResource(icon)
        if (text.isNullOrEmpty()) key.iconPadding = 0
        return key
    }

    private fun send(action: Action?) {
        if (action != null) host?.sendAction(action)
    }

    private fun build(c: PanelComponent): View = when (c.type) {
        ComponentType.BUTTON, ComponentType.SHORTCUT -> {
            val showIcon = c.label.isNullOrBlank() && c.action?.let { Actions.find(it)?.iconRes } != null
            key(if (showIcon) null else label(c), if (showIcon) ActionPicker.icon(c.action) else null).apply {
                if (showIcon) contentDescription = label(c)
                setOnClickListener { Haptics.tick(it); send(c.action) }
            }
        }

        ComponentType.MACRO -> key(label(c), R.drawable.ic_magic_wand).apply {
            iconPadding = Ui.dp(context, 8)
            setOnClickListener { Haptics.tick(it); send(c.action) }
        }

        ComponentType.DPAD -> FrameLayout(context).apply {
            addView(DpadView(context).apply {
                onKey = { k ->
                    Haptics.tick(this)
                    send(
                        Action.Key(
                            when (k) {
                                DpadView.Key.UP -> HidReports.KEY_UP
                                DpadView.Key.DOWN -> HidReports.KEY_DOWN
                                DpadView.Key.LEFT -> HidReports.KEY_LEFT
                                DpadView.Key.RIGHT -> HidReports.KEY_RIGHT
                                DpadView.Key.OK -> HidReports.KEY_ENTER
                            }.toInt()
                        )
                    )
                }
                connected = host?.liveService(false) != null
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        }

        ComponentType.TRACKPAD -> TrackpadView(context).apply {
            setBackgroundResource(R.drawable.bg_trackpad)
            contentDescription = label(c)
            host?.bindPad(this)
        }

        ComponentType.SLIDER -> LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val up = Action.fromJson(c.config.optJSONObject(CONFIG_UP)) ?: Action.Consumer(HidReports.CC_VOLUME_UP)
            val down = Action.fromJson(c.config.optJSONObject(CONFIG_DOWN)) ?: Action.Consumer(HidReports.CC_VOLUME_DOWN)
            // A one-row slider is all track; taller ones have room to name it.
            if (c.rows > 1) {
                addView(TextView(context).apply {
                    setTextAppearance(R.style.Text_Label_Small)
                    text = c.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.volume)
                    setPadding(Ui.dp(context, 4), 0, 0, Ui.dp(context, 6))
                })
            }
            addView(StepSliderView(context).apply {
                onStep = { dir -> send(if (dir > 0) up else down) }
                contentDescription = label(c)
            }, LinearLayout.LayoutParams(MATCH, minOf(Ui.dp(context, 52), cell)))
        }

        ComponentType.TOGGLE -> {
            val on = Action.fromJson(c.config.optJSONObject(CONFIG_ON)) ?: c.action
            val off = Action.fromJson(c.config.optJSONObject(CONFIG_OFF)) ?: on
            key(label(c), ActionPicker.icon(on)).apply {
                iconPadding = Ui.dp(context, 8)
                setOnClickListener {
                    Haptics.tick(it)
                    isSelected = !isSelected
                    send(if (isSelected) on else off)
                }
            }
        }

        ComponentType.MEDIA -> LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                Triple(R.drawable.ic_skip_back, HidReports.CC_SCAN_PREV, false),
                Triple(R.drawable.ic_play_pause, HidReports.CC_PLAY_PAUSE, true),
                Triple(R.drawable.ic_skip_forward, HidReports.CC_SCAN_NEXT, false)
            ).forEachIndexed { i, (icon, usage, accent) ->
                addView(key(null, icon, accent).apply {
                    setOnClickListener { Haptics.tick(it); send(Action.Consumer(usage)) }
                }, LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (i > 0) marginStart = gap })
            }
        }

        ComponentType.KEYBOARD -> liveField(c)
        ComponentType.TEXT_INPUT -> sendField(c)
    }

    /** A field that mirrors itself onto the device as it changes. */
    private fun liveField(c: PanelComponent): View {
        val view = inflater.inflate(R.layout.ui_panel_field, this, false)
        val input = view.findViewById<EditText>(R.id.input)
        view.findViewById<ImageButton>(R.id.send).apply {
            setImageResource(R.drawable.ic_arrow_elbow_down_left)
            contentDescription = context.getString(R.string.key_enter)
        }
        input.hint = c.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.keyboard_hint_live)
        var baseline = ""
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, d: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val now = s?.toString().orEmpty()
                val service = host?.liveService(true)
                if (service == null) {
                    baseline = now
                    return
                }
                val common = baseline.commonPrefixWith(now).length
                val erase = baseline.length - common
                val added = now.substring(common)
                // One ordered step, so autocorrect's backspaces cannot land
                // in the middle of the characters they correct.
                if (erase > 0 || added.isNotEmpty()) service.engine.keyboard.replace(erase, added)
                baseline = now
            }
        })
        view.findViewById<ImageButton>(R.id.send).setOnClickListener {
            Haptics.tick(it)
            host?.liveService(true)?.typeKey(HidReports.MOD_NONE, HidReports.KEY_ENTER)
            baseline = ""
            input.text?.clear()
        }
        return view
    }

    /** A field that types its text, then Enter, when sent. */
    private fun sendField(c: PanelComponent): View {
        val view = inflater.inflate(R.layout.ui_panel_field, this, false)
        val input = view.findViewById<EditText>(R.id.input)
        input.hint = c.label?.takeIf { it.isNotBlank() } ?: context.getString(R.string.keyboard_hint)
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        val submit = {
            val text = input.text?.toString().orEmpty()
            val service = if (text.isNotEmpty()) host?.liveService(true) else null
            if (service != null) {
                // Cleared at once, so a second tap has nothing to send twice;
                // a drop part way puts back what never reached the device.
                input.text?.clear()
                service.engine.keyboard.type(text + "\n") { result ->
                    if (result.interrupted && input.text.isNullOrEmpty()) {
                        input.setText(text.drop(result.consumed))
                    }
                }
            }
        }
        input.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        view.findViewById<ImageButton>(R.id.send).setOnClickListener {
            Haptics.tick(it)
            submit()
        }
        return view
    }

    companion object {
        const val CELL_DP = 60
        const val MAX_WIDTH_DP = 520
        const val CONFIG_UP = "up"
        const val CONFIG_DOWN = "down"
        const val CONFIG_ON = "on"
        const val CONFIG_OFF = "off"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Config holding two actions, as the slider and toggle store them. */
        fun pair(first: String, a: Action, second: String, b: Action): JSONObject =
            JSONObject().put(first, a.toJson()).put(second, b.toJson())
    }
}
