package org.agentos.agent.home.apps

/** Feed first, every app page together, then panels. The drag destination is an app page. */
data class HomePagerOrder(val homePages: Int, val panels: Int, val extraDropPage: Boolean = false) {
  val homeCount: Int get() = homePages + if (extraDropPage) 1 else 0
  val pageCount: Int get() = 1 + homeCount + panels
  fun homeAt(page: Int): Int? = (page - 1).takeIf { it in 0 until homeCount }
  fun homePage(home: Int): Int = 1 + home
  fun panelAt(page: Int): Int? = (page - 1 - homeCount).takeIf { it in 0 until panels }
  fun panelPage(panel: Int): Int = 1 + homeCount + panel
}
