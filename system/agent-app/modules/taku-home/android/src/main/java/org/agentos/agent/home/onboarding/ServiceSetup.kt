package org.agentos.agent.home.onboarding

import android.content.Context
import android.content.Intent

/**
 * Reopens the Jev (Figma 1379:2556 "2-3") or Fal (1379:2782 "2-5") setup page after onboarding,
 * for a user who skipped it there: from a GUI card Jev could not compose, from voice input that
 * needs a Fal key, or from an Agent (`ai-setup/open`). The page is
 * [org.agentos.agent.home.ui.onboarding.ServiceSetupActivity]; connecting goes through
 * [OnboardingRepository.setUp], the same operations as the onboarding card.
 */
object ServiceSetup {
  const val EXTRA_SERVICE = "org.agentos.agent.extra.SETUP_SERVICE"
  private const val ACTIVITY = "org.agentos.agent.home.ui.onboarding.ServiceSetupActivity"

  fun intent(context: Context, service: OnboardingService): Intent {
    if (service == OnboardingService.CHATGPT) throw OnboardingException("INVALID_SERVICE", "set up jev or fal here; chatgpt has its own account page")
    return Intent().setClassName(context.packageName, ACTIVITY).putExtra(EXTRA_SERVICE, service.id)
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
  }

  fun open(context: Context, service: OnboardingService) = context.startActivity(intent(context, service))
}
