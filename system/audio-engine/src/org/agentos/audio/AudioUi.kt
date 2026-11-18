// SPDX-License-Identifier: LicenseRef-Taku-OS-Internal
package org.agentos.audio

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Native page primitives following MemoryUi and MemoryActivity's settings cards. */
internal class AudioUi(private val activity: Activity) {
    companion object {
        const val INK = 0xff242825.toInt()
        const val MUTED = 0xff7c807b.toInt()
        const val ORANGE = 0xffff5a1f.toInt()
        const val BG = 0xfff7f8f5.toInt()
        const val LINE = 0xffe9ece5.toInt()
        const val SOFT = 0xffffeee5.toInt()
        const val GREEN = 0xff51815d.toInt()
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    fun id(name: String) = activity.resources.getIdentifier(name, "id", activity.packageName)
    fun s(name: String): String = activity.getString(activity.resources.getIdentifier(name, "string", activity.packageName))
    fun s(name: String, vararg args: Any): String = activity.getString(
        activity.resources.getIdentifier(name, "string", activity.packageName), *args
    )
    fun col() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    fun shape(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != 0) setStroke(dp(1), stroke)
    }
    fun touch(color: Int, radius: Int, stroke: Int = 0) = RippleDrawable(
        ColorStateList.valueOf(0x146a705f), shape(color, radius, stroke), shape(Color.WHITE, radius)
    )
    fun text(value: String, size: Int = 14, color: Int = MUTED, bold: Boolean = false) = TextView(activity).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        includeFontPadding = false
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        })
    }
    fun section(parent: LinearLayout, title: String) = add(parent, text(title, 13, MUTED, true), 14, 12)
    fun rule(parent: LinearLayout) = parent.addView(View(activity).apply { setBackgroundColor(LINE) }, LinearLayout.LayoutParams(-1, dp(1)))
    fun card(parent: LinearLayout) = col().apply {
        background = shape(Color.WHITE, 20, LINE)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        add(parent, this, 0, 12)
    }
    fun action(parent: LinearLayout, label: String, id: String? = null, primary: Boolean = false, onClick: () -> Unit): TextView {
        val action = text(label, 14, if (primary) Color.WHITE else INK, true).apply {
            if (id != null) this.id = id(id)
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(13), dp(18), dp(13))
            minHeight = dp(48)
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(if (primary) Color.WHITE else INK, MUTED)))
            background = touch(if (primary) ORANGE else Color.WHITE, 14, if (primary) 0 else LINE)
            setOnClickListener { onClick() }
        }
        add(parent, action, 0, 10)
        return action
    }
    fun navigation(parent: LinearLayout, title: String, summary: String, id: String? = null, onClick: () -> Unit): View = row().apply {
        if (id != null) this.id = id(id)
        setPadding(0, dp(14), 0, dp(14))
        minimumHeight = dp(60)
        background = touch(Color.TRANSPARENT, 10)
        val words = col().apply {
            addView(text(title, 15, INK, true))
            if (summary.isNotEmpty()) add(this, text(summary, 12), 6)
        }
        addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        addView(Chevron(false), LinearLayout.LayoutParams(dp(18), dp(18)))
        setOnClickListener { onClick() }
        parent.addView(this)
    }
    fun chip(label: String, name: String) = text(label, 13).apply {
        id = id(name)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        minHeight = dp(44)
    }
    fun selectChip(view: TextView, selected: Boolean) {
        view.isSelected = selected
        view.setTextColor(if (selected) ORANGE else MUTED)
        view.background = touch(if (selected) SOFT else Color.TRANSPARENT, 20)
    }
    fun toggle(parent: LinearLayout, title: String, summary: String, name: String, onChanged: (Boolean) -> Unit): Switch {
        val item = col().apply { setPadding(0, dp(8), 0, dp(8)) }
        val line = row()
        line.addView(text(title, 16, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        val control = Switch(activity).apply {
            id = id(name)
            contentDescription = title
            minHeight = dp(48)
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ORANGE, 0xffb7bdb3.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0xfff6c5b2.toInt(), LINE))
            setOnCheckedChangeListener { _, checked -> onChanged(checked) }
        }
        line.addView(control)
        line.setOnClickListener { if (control.isEnabled) control.performClick() }
        item.addView(line)
        add(item, text(summary, 12), 0, 8)
        parent.addView(item)
        return control
    }
    fun tint(control: CompoundButton) {
        control.setTextColor(INK)
        control.textSize = 14f
        control.buttonTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ORANGE, MUTED))
        control.minHeight = dp(48)
    }
    /** Shared secret entry used by the home fal card and the desktop dictation page. */
    fun keyDialog(
        title: String,
        privacy: String,
        hint: String,
        required: String,
        cancel: String,
        save: String,
        inputId: String,
        onDismiss: () -> Unit,
        onSave: (String) -> Unit
    ): AlertDialog {
        val input = EditText(activity).apply {
            id = id(inputId)
            this.hint = hint
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            isSaveEnabled = false
            setSaveFromParentEnabled(false)
            textSize = 15f
            setTextColor(INK)
            setHintTextColor(MUTED)
        }
        val content = col().apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            add(this, text(privacy, 13), bottom = 12)
            addView(input)
        }
        val dialog = AlertDialog.Builder(activity).setTitle(title).setView(content)
            .setNegativeButton(cancel, null).setPositiveButton(save, null).create()
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.setOnDismissListener { input.text.clear(); onDismiss() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (input.text.isBlank()) {
                    input.error = required
                    return@setOnClickListener
                }
                val value = input.text.toString()
                input.text.clear()
                dialog.dismiss()
                onSave(value)
            }
        }
        dialog.show()
        return dialog
    }
    fun adapter(values: List<String>) = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_dropdown_item, values) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getView(position, convertView, parent) as TextView).apply {
            setTextColor(INK)
            textSize = 14f
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = (super.getDropDownView(position, convertView, parent) as TextView).apply {
            setTextColor(INK)
            textSize = 14f
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
    }
    fun styleSpinner(spinner: Spinner) {
        val arrow = activity.getDrawable(activity.resources.getIdentifier("audio_dropdown", "drawable", activity.packageName))!!
        val layers = LayerDrawable(arrayOf(shape(Color.WHITE, 14, LINE), arrow)).apply {
            setLayerWidth(1, dp(18))
            setLayerHeight(1, dp(18))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetRelative(1, 0, 0, dp(14), 0)
        }
        spinner.background = RippleDrawable(ColorStateList.valueOf(0x146a705f), layers, shape(Color.WHITE, 14))
        spinner.setPadding(0, 0, dp(38), 0)
        spinner.minimumHeight = dp(48)
        spinner.setPopupBackgroundDrawable(shape(Color.WHITE, 14, LINE))
    }
    fun page(title: String, subtitle: String, name: String, back: Boolean = false): LinearLayout {
        val root = col().apply {
            setBackgroundColor(BG)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        val header = row().apply {
            if (back) setPadding(dp(6), dp(4), dp(8), dp(4))
            else setPadding(dp(22), dp(18), dp(22), dp(6))
        }
        if (back) header.addView(android.widget.FrameLayout(activity).apply {
            contentDescription = s("audio_ui_back")
            background = touch(Color.TRANSPARENT, 24)
            addView(Chevron(true), android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { activity.finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(text(title, if (back) 16 else 30, INK, true), LinearLayout.LayoutParams(0, -2, 1f))
        val brand = row().apply {
            contentDescription = "Taku Logo"
            val mark = ImageView(activity).apply {
                setImageResource(activity.resources.getIdentifier("taku_brand_mark", "drawable", activity.packageName))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val markSize = dp(if (back) 24 else 30)
            addView(mark, LinearLayout.LayoutParams(markSize, markSize))
            if (!back) addView(text("taku", 18, INK, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        }
        header.addView(brand)
        root.addView(header)
        if (subtitle.isNotEmpty()) add(root, text(subtitle, 13).apply { setPadding(dp(22), 0, dp(22), 0) }, 8, 6)
        val body = col().apply { id = id(name); setPadding(dp(22), dp(12), dp(22), dp(24)) }
        root.addView(ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        activity.setContentView(root)
        activity.window.statusBarColor = BG
        activity.window.navigationBarColor = Color.WHITE
        activity.window.decorView.windowInsetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
        return body
    }
    private inner class Chevron(back: Boolean) : View(activity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (back) INK else MUTED
            style = Paint.Style.STROKE
            strokeWidth = 1.65f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path().apply {
            if (back) { moveTo(14f, 5f); lineTo(7f, 12f); lineTo(14f, 19f) }
            else { moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f) }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.save()
            canvas.scale(width / 24f, height / 24f)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
    }
}
