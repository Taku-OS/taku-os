package org.agentos.agent.home.feed

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.agentos.agent.home.R

/** Presents the Feed card's own attestation when an Agent requests it. */
class DiningShareConfirmationActivity : AppCompatActivity() {
  private var dialog: AlertDialog? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val cardId = intent.getStringExtra(CARD_ID) ?: return finish()
    val requested = try {
      FeedCursor.parse(intent.getStringExtra(REQUESTED_CURSOR) ?: return finish())
    } catch (_: FeedException) {
      return finish()
    }
    val repo = FeedRepository.get(this)
    val card = repo.card(cardId) ?: return finish()
    val expectedShare = card.actions["share"]?.intent as? Intent.ShareText ?: return finish()
    if (card.actions[Intent.ConfirmDiningShare.ID]?.intent != Intent.ConfirmDiningShare ||
      repo.isRemoved(cardId) || card.expired(System.currentTimeMillis()) ||
      expectedShare.text != intent.getStringExtra(SHARE_TEXT)) return finish()

    fun respond(confirmed: Boolean) {
      lifecycleScope.launch {
        try {
          withContext(Dispatchers.IO) { repo.recordDiningShareDecision(cardId, expectedShare, requested, confirmed) }
        } catch (_: FeedException) {
          Toast.makeText(this@DiningShareConfirmationActivity, R.string.home_dining_share_unavailable, Toast.LENGTH_SHORT).show()
        }
        finish()
      }
    }
    dialog = AlertDialog.Builder(this)
      .setTitle(R.string.home_dining_share_title)
      .setMessage(getString(R.string.home_dining_share_message, card.summary))
      .setPositiveButton(R.string.home_dining_share_confirm) { _, _ -> respond(true) }
      .setNegativeButton(R.string.home_cancel) { _, _ -> respond(false) }
      .setOnCancelListener { respond(false) }
      .show()
  }

  override fun onDestroy() {
    dialog?.dismiss()
    super.onDestroy()
  }

  internal companion object {
    const val CARD_ID = "cardId"
    const val SHARE_TEXT = "shareText"
    const val REQUESTED_CURSOR = "requestedCursor"
  }
}
