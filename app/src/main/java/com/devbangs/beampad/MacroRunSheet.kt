package com.devbangs.beampad

import android.app.Activity
import android.widget.Toast
import com.google.android.material.progressindicator.LinearProgressIndicator

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

        val sheet = Sheet(activity)
            .title(macro.name)
            .subtitle(activity.getString(R.string.macro_starting))
        val progress = activity.layoutInflater.inflate(R.layout.ui_progress, sheet.content, false) as LinearProgressIndicator
        progress.max = macro.steps.size
        sheet.content.addView(progress)
        sheet.dialog.setCancelable(false)

        val job = service.engine.macros.run(macro, object : InputEngine.MacroListener {
            override fun onStep(index: Int, total: Int) {
                progress.max = total
                progress.setProgressCompat(index, true)
                sheet.subtitle(activity.getString(R.string.macro_step, index + 1, total))
            }

            override fun onFinished(result: InputEngine.MacroResult) {
                if (sheet.dialog.isShowing) sheet.dismiss()
                val message = when (result) {
                    InputEngine.MacroResult.DONE -> {
                        Haptics.confirm(activity)
                        Reviews.success(activity)
                        R.string.macro_done
                    }
                    InputEngine.MacroResult.CANCELLED -> R.string.macro_stopped
                    InputEngine.MacroResult.DISCONNECTED -> R.string.macro_disconnected
                    InputEngine.MacroResult.BUSY -> R.string.macro_busy
                }
                Toast.makeText(activity, activity.getString(message, macro.name), Toast.LENGTH_SHORT).show()
            }
        }) ?: return

        sheet.secondary(activity.getString(R.string.macro_stop)) { job.cancel() }
        sheet.show()
    }
}
