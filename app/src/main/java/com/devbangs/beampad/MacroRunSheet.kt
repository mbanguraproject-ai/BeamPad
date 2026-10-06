package com.devbangs.beampad

import android.app.Activity
import android.widget.Toast
import com.devbangs.beampad.databinding.SheetMacroRunBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * Runs a macro with visible progress and a Stop button, from anywhere a
 * macro can be started (a panel button, the macro list, the editor's Test).
 * Long sequences must never run blind.
 */
object MacroRunSheet {

    fun run(activity: Activity, service: HidService?, macro: Macro) {
        if (service == null || !service.isReady()) {
            Toast.makeText(activity, R.string.not_connected_hint, Toast.LENGTH_SHORT).show()
            return
        }
        if (macro.steps.isEmpty()) {
            Toast.makeText(activity, R.string.macro_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val ui = SheetMacroRunBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        dialog.setContentView(ui.root)
        dialog.setCancelable(false)
        ui.title.text = macro.name
        ui.progress.max = macro.steps.size
        ui.progress.progress = 0
        ui.step.text = activity.getString(R.string.macro_starting)

        val job = service.engine.macros.run(macro, object : InputEngine.MacroListener {
            override fun onStep(index: Int, total: Int) {
                ui.progress.max = total
                ui.progress.progress = index
                ui.step.text = activity.getString(R.string.macro_step, index + 1, total)
            }

            override fun onFinished(result: InputEngine.MacroResult) {
                if (dialog.isShowing) dialog.dismiss()
                val message = when (result) {
                    InputEngine.MacroResult.DONE -> {
                        Haptics.confirm(activity)
                        R.string.macro_done
                    }
                    InputEngine.MacroResult.CANCELLED -> R.string.macro_stopped
                    InputEngine.MacroResult.DISCONNECTED -> R.string.macro_disconnected
                    InputEngine.MacroResult.BUSY -> R.string.macro_busy
                }
                Toast.makeText(activity, activity.getString(message, macro.name), Toast.LENGTH_SHORT).show()
            }
        })

        if (job == null) return
        ui.stop.setOnClickListener { job.cancel() }
        dialog.show()
    }
}
