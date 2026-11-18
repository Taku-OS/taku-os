package org.agentos.agent.bridge

/** Prefer the tracked conversation even while the shared React host points at Home. */
internal fun <T : Any> conversationHost(cached: T?, current: T?, usable: (T) -> Boolean): T? =
  cached?.takeIf(usable) ?: current?.takeIf(usable)
