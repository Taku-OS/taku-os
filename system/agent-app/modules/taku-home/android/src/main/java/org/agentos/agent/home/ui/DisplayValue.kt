package org.agentos.agent.home.ui

/** Display values such as "23°", "8h21m", "31%": digits use [number], other characters [unit]. */
fun splitDisplayValue(value: String): List<Pair<String, Boolean>> {
  val out = ArrayList<Pair<String, Boolean>>()
  val big = { c: Char -> c.isDigit() || c in "°$.,:+-" }
  var start = 0
  for (i in 1..value.length) {
    if (i == value.length || big(value[i]) != big(value[start])) {
      out += value.substring(start, i) to big(value[start]); start = i
    }
  }
  return out
}
