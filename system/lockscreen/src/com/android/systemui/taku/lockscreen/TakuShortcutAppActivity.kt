package com.android.systemui.taku.lockscreen

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.TextView
import com.android.systemui.res.R

/**
 * Chooses the app for the "Open app" lock-screen shortcut. The wallpaper picker opens it as the
 * shortcut's configure action (`android.permission.CUSTOMIZE_SYSTEM_UI` protects it in the manifest).
 * It lists this user's launcher apps, marks the current target and writes the choice to the same
 * setting the shortcut and `cmd statusbar lockscreen shortcuts app` use.
 */
class TakuShortcutAppActivity : Activity() {
    private lateinit var apps: List<TakuOpenAppTarget.Target>
    private var current: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.controls_open_app)
        apps = TakuOpenAppTarget.candidates(packageManager)
        current = TakuOpenAppTarget.resolve(packageManager,
            Settings.Secure.getString(contentResolver, TakuOpenAppTarget.SETTING))?.component?.flattenToString()
        val list = ListView(this).apply {
            adapter = Adapter()
            setOnItemClickListener { _, _, position, _ -> choose(apps[position]) }
        }
        setContentView(list)
    }

    private fun choose(app: TakuOpenAppTarget.Target) {
        val component = TakuOpenAppTarget.validate(packageManager, app.component) ?: return
        Settings.Secure.putString(contentResolver, TakuOpenAppTarget.SETTING, component.flattenToString())
        setResult(RESULT_OK)
        finish()
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(position: Int) = apps[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: newRow()
            val app = apps[position]
            (row.getChildAt(0) as ImageView).setImageDrawable(
                try { packageManager.getActivityIcon(app.component) } catch (e: Exception) { null })
            (row.getChildAt(1) as TextView).text = app.label
            (row.getChildAt(2) as RadioButton).isChecked = app.component.flattenToString() == current
            return row
        }

        private fun newRow(): LinearLayout {
            val density = resources.displayMetrics.density
            val pad = (16 * density).toInt()
            return LinearLayout(this@TakuShortcutAppActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = (64 * density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                    LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt()).apply { marginEnd = pad })
                addView(TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(RadioButton(context).apply { isClickable = false; isFocusable = false })
            }
        }
    }
}
