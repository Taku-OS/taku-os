package org.agentos.agent.bridge

import android.content.Context
import android.graphics.Color
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.util.TypedValue
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.CharBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * A credential field whose text never enters JavaScript. React places and styles it; the typed
 * value stays in this view until [SecretFields] hands it to the system gateway as a read-only
 * file descriptor.
 */
class SecretFieldView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val input = EditText(context).apply {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
    setSingleLine()
    isSaveEnabled = false
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    background = null
    setPadding(0, 0, 0, 0)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    // No copy, cut or share of the typed credential.
    val noActions = object : ActionMode.Callback {
      override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
      override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
      override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
      override fun onDestroyActionMode(mode: ActionMode) = Unit
    }
    customSelectionActionModeCallback = noActions
    setOnEditorActionListener { _, action, _ ->
      if (action == EditorInfo.IME_ACTION_DONE) fieldId.takeIf(String::isNotEmpty)?.let(SecretFields::submitted)
      action == EditorInfo.IME_ACTION_DONE
    }
  }

  override val shouldUseAndroidLayout = true

  var fieldId = ""
    set(value) {
      if (field == value) return
      SecretFields.remove(field, this)
      field = value
      if (isAttachedToWindow) SecretFields.add(value, this)
    }
  var autoFocus = false

  init { addView(input, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }

  fun placeholder(value: String) { input.hint = value; input.contentDescription = value }
  fun textColor(value: String) { color(value)?.let { input.setTextColor(it) } }
  fun placeholderColor(value: String) { color(value)?.let { input.setHintTextColor(it) } }

  /** Shows or masks what has been typed, keeping the caret where it was. */
  fun reveal(revealed: Boolean) {
    val start = input.selectionStart
    val end = input.selectionEnd
    input.inputType = InputType.TYPE_CLASS_TEXT or
      if (revealed) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
    if (start >= 0 && end >= 0) input.setSelection(start, end)
  }

  /** `#RRGGBB`, `#AARRGGBB` or the design tokens' `rgba(r,g,b,a)`; anything else keeps the default. */
  private fun color(value: String): Int? {
    val rgba = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*(?:,\s*([0-9.]+)\s*)?\)""").matchEntire(value.trim())
    if (rgba != null) {
      val (red, green, blue, alpha) = rgba.destructured
      val opacity = alpha.toFloatOrNull() ?: 1f
      return Color.argb((opacity.coerceIn(0f, 1f) * 255).toInt(), red.toInt().coerceAtMost(255), green.toInt().coerceAtMost(255), blue.toInt().coerceAtMost(255))
    }
    return try { Color.parseColor(value) } catch (_: IllegalArgumentException) { null }
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    // React sizes this view; the field always fills it.
    input.measure(MeasureSpec.makeMeasureSpec(right - left, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(bottom - top, MeasureSpec.EXACTLY))
    input.layout(0, 0, right - left, bottom - top)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    SecretFields.add(fieldId, this)
    if (autoFocus) post {
      if (!input.requestFocus()) return@post
      (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0)
    }
  }

  override fun onDetachedFromWindow() {
    SecretFields.remove(fieldId, this)
    clear()
    super.onDetachedFromWindow()
  }

  fun clear() = input.text.clear()

  /** The typed value without surrounding whitespace, as UTF-8. The caller wipes the array. */
  fun bytes(): ByteArray {
    val text = input.text
    var start = 0
    var end = text.length
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    val chars = CharArray(end - start)
    text.getChars(start, end, chars, 0)
    try {
      val encoded = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
      val bytes = ByteArray(encoded.remaining())
      encoded.get(bytes)
      if (encoded.hasArray()) encoded.array().fill(0)
      return bytes
    } finally {
      chars.fill('\u0000')
    }
  }
}

/** Mounted credential fields by the id React gave them. Fields are read on the main thread. */
internal object SecretFields {
  private val fields = ConcurrentHashMap<String, SecretFieldView>()

  /** Announces that the keyboard's Done key was pressed in a field. */
  @Volatile var onSubmit: ((String) -> Unit)? = null

  fun add(id: String, view: SecretFieldView) { if (id.isNotEmpty()) fields[id] = view }
  fun remove(id: String, view: SecretFieldView) { if (id.isNotEmpty()) fields.remove(id, view) }
  fun submitted(id: String) { onSubmit?.invoke(id) }
  fun clear(id: String) { fields[id]?.clear() }

  /** The field's trimmed value as UTF-8; [descriptor] consumes and wipes it. */
  fun bytes(id: String): ByteArray = fields[id]?.bytes() ?: throw IOException("SECRET_FIELD_UNAVAILABLE")

  /**
   * [bytes] as the gateway's `credential` extra: a read-only descriptor of a private file that
   * is already unlinked, so the value has no name on disk once this returns. Wipes [bytes].
   */
  fun descriptor(context: Context, bytes: ByteArray): ParcelFileDescriptor {
    try {
      if (bytes.isEmpty()) throw IOException("SECRET_EMPTY")
      val directory = File(context.cacheDir, "secret-entry").apply { mkdirs() }
      val file = File.createTempFile("credential", null, directory)
      try {
        FileOutputStream(file).use { it.write(bytes) }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
      } finally {
        file.delete()
      }
    } finally {
      bytes.fill(0)
    }
  }
}
