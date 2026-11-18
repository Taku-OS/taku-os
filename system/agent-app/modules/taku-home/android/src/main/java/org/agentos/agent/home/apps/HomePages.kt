package org.agentos.agent.home.apps

import org.agentos.agent.home.feed.FeedException

/** Where an app lives: page, index on that page, and the folder holding it (if any). */
data class AppLocation(val page: Int, val index: Int, val folderId: String? = null)

/** Result of a layout operation: the new pages and what happened (`effect` plus details). */
data class PagesChange(val pages: List<List<Slot>>, val effect: String, val page: Int, val index: Int, val folderId: String? = null)

/**
 * The home screen pages as pure data, iPhone-style: an ordered list of pages, each an ordered
 * list of apps, folders and spanning widgets. Page 0 is the initial home page. Every installed app is
 * on exactly one page (or in one folder); a full page pushes overflow to the next page
 * ([paginate]) and pages other than page 0 disappear once empty.
 * Shared by the GUI and `content://org.agentos.agent.home-layout` through [HomeLayoutRepository].
 */
object HomePages {
  const val MAX_FOLDER = 12
  /** Page 0 holds seven grid rows (four default widget rows plus three app rows); other pages six rows. */
  const val FIRST_PAGE_CAPACITY = 28
  const val PAGE_CAPACITY = 24

  fun capacity(page: Int): Int = if (page == 0) FIRST_PAGE_CAPACITY else PAGE_CAPACITY

  /**
   * Overflow moves on: a page holding more than [capacity] pushes its last items to the start
   * of the next page (created when needed), like iPhone. Then empty pages close up.
   */
  fun paginate(pages: List<List<Slot>>): List<List<Slot>> {
    val work = pages.map { it.toMutableList() }.toMutableList()
    var p = 0
    while (p < work.size) {
      val fit = HomeGrid.place(work[p]).indexOfFirst { it.y + it.h > capacity(p) / 4 }.let { if (it < 0) work[p].size else it }
      if (fit < work[p].size) {
        val overflow = work[p].subList(fit, work[p].size)
        val moved = overflow.toList(); overflow.clear()
        if (p + 1 == work.size) work.add(mutableListOf())
        work[p + 1].addAll(0, moved)
      }
      p++
    }
    return compact(work)
  }

  /** All items in reading order across pages (the single scrolling page). */
  fun flatten(pages: List<List<Slot>>): List<Slot> = pages.flatten()

  /** Keys of every app on the pages, including folder members. */
  fun keys(pages: List<List<Slot>>): Set<String> =
    pages.flatten().flatMapTo(LinkedHashSet()) { when (it) { is Slot.App -> listOf(it.key); is Slot.Folder -> it.apps; is Slot.Widget -> emptyList() } }

  fun locate(pages: List<List<Slot>>, key: String): AppLocation? {
    pages.forEachIndexed { p, page ->
      page.forEachIndexed { i, slot ->
        when (slot) {
          is Slot.Widget -> Unit
          is Slot.App -> if (slot.key == key) return AppLocation(p, i)
          is Slot.Folder -> if (key in slot.apps) return AppLocation(p, i, slot.id)
        }
      }
    }
    return null
  }

  fun locateWidget(pages: List<List<Slot>>, id: Int): AppLocation? {
    pages.forEachIndexed { p, page -> page.forEachIndexed { i, s -> if (s is Slot.Widget && s.appWidgetId == id) return AppLocation(p, i) } }
    return null
  }

  fun locateFolder(pages: List<List<Slot>>, id: String): AppLocation? {
    pages.forEachIndexed { p, page -> page.forEachIndexed { i, s -> if (s is Slot.Folder && s.id == id) return AppLocation(p, i, id) } }
    return null
  }

  /**
   * Keeps the pages in step with the installed apps: uninstalled apps leave (a folder of one
   * becomes that app, an empty folder goes), newly installed apps are appended to the last page,
   * overflow moves to the next page, and empty pages after page 0 are dropped.
   */
  fun reconcile(pages: List<List<Slot>>, installed: List<String>): List<List<Slot>> {
    val present = installed.toSet()
    val kept = pages.map { page ->
      page.mapNotNull { slot ->
        when (slot) {
          is Slot.Widget -> slot
          is Slot.App -> slot.takeIf { it.key in present }
          is Slot.Folder -> {
            val apps = slot.apps.filter { it in present }.distinct()
            when (apps.size) { 0 -> null; 1 -> Slot.App(apps[0]); else -> slot.copy(apps = apps) }
          }
        }
      }
    }
    val placed = keys(kept)
    val added = installed.filter { it !in placed }.distinct().map { Slot.App(it) }
    val result = (if (kept.isEmpty()) listOf(emptyList()) else kept).toMutableList()
    if (added.isNotEmpty()) result[result.lastIndex] = result.last() + added
    return paginate(result)
  }

  /**
   * Moves an app ([key]) or a folder ([folderId]) to [page] (equal to the page count = a new
   * page). Without [into] it is inserted at [index] (end of page when null). With [into] an app
   * is dropped onto the item at [index]: onto an app it makes a folder named [folderName], onto a
   * folder it joins it.
   */
  fun move(
    pages: List<List<Slot>>, key: String?, folderId: String?, page: Int, index: Int?, into: Boolean,
    newFolderId: () -> String, folderName: (List<String>) -> String, widgetId: Int? = null,
  ): PagesChange {
    if (listOfNotNull(key, folderId, widgetId).size != 1) throw FeedException("INVALID_ARGUMENT", "give exactly one of key, folderId or widgetId")
    if (page !in 0..pages.size) throw FeedException("INVALID_ARGUMENT", "page must be 0..${pages.size} (${pages.size} = a new page)")
    val source = if (widgetId != null) locateWidget(pages, widgetId) ?: throw FeedException("NOT_FOUND", "no widget $widgetId")
                 else if (key != null) locate(pages, key) ?: throw FeedException("NOT_ON_HOME", "$key is not on the home screen")
                 else locateFolder(pages, folderId!!) ?: throw FeedException("NOT_FOUND", "no folder $folderId")
    val targetLength = pages.getOrNull(page)?.size ?: 0
    if (into) {
      if (key == null) throw FeedException("INVALID_ARGUMENT", "only an app can be dropped into another item")
      if (index == null || index !in 0 until targetLength) throw FeedException("EMPTY_CELL", "page $page has no item at ${index ?: "null"}")
    } else if (index != null && index !in 0..targetLength) throw FeedException("INVALID_ARGUMENT", "index must be 0..$targetLength on page $page")

    val target = if (into) pages[page][index!!] else null
    if (target != null) {
      when (target) {
        is Slot.Widget -> throw FeedException("INVALID_ARGUMENT", "widgets cannot contain apps")
        is Slot.App -> if (target.key == key) return PagesChange(pages, "unchanged", source.page, source.index, source.folderId)
        is Slot.Folder -> {
          if (key in target.apps) return PagesChange(pages, "unchanged", page, index!!, target.id)
          if (target.apps.size >= MAX_FOLDER) throw FeedException("FOLDER_FULL", "a folder holds at most $MAX_FOLDER apps")
        }
      }
    }

    // Take the item out, remembering where the target ends up after the removal.
    val work = pages.map { it.toMutableList() }.toMutableList()
    var targetPage = page
    var targetIndex = index ?: targetLength
    val moving: Slot
    if (source.folderId != null && key != null) {
      val folder = work[source.page][source.index] as Slot.Folder
      val left = folder.apps - key
      work[source.page][source.index] = if (left.size == 1) Slot.App(left[0]) else folder.copy(apps = left)
      moving = Slot.App(key)
    } else {
      moving = work[source.page].removeAt(source.index)
      if (source.page == targetPage && source.index < targetIndex) targetIndex--
    }

    val effect: String
    var joinedId: String? = null
    if (target == null) {
      if (targetPage == work.size) work.add(mutableListOf())
      work[targetPage].add(targetIndex.coerceIn(0, work[targetPage].size), moving)
      effect = if (page == pages.size) "page-created" else "moved"
    } else {
      val occupant = work[targetPage][targetIndex]
      val joined = when (occupant) {
        is Slot.Widget -> throw FeedException("INVALID_ARGUMENT", "widgets cannot contain apps")
        is Slot.App -> Slot.Folder(newFolderId(), folderName(listOf(occupant.key, key!!)), listOf(occupant.key, key))
        is Slot.Folder -> occupant.copy(apps = occupant.apps + key!!)
      }
      work[targetPage][targetIndex] = joined
      joinedId = joined.id
      effect = if (occupant is Slot.App) "folder-created" else "added-to-folder"
    }
    // Locations are reported after overflow moved on and empty pages closed up.
    val compacted = paginate(work)
    val location = when {
      widgetId != null -> locateWidget(compacted, widgetId)!!
      joinedId != null -> locateFolder(compacted, joinedId)!!
      key != null -> locate(compacted, key)!!
      else -> locateFolder(compacted, folderId!!)!!
    }
    return PagesChange(compacted, if (compacted == pages) "unchanged" else effect, location.page, location.index, joinedId)
  }

  /** Replaces a folder with its apps, in order, where the folder was. */
  fun ungroup(pages: List<List<Slot>>, folderId: String): PagesChange {
    val at = locateFolder(pages, folderId) ?: throw FeedException("NOT_FOUND", "no folder $folderId")
    val folder = pages[at.page][at.index] as Slot.Folder
    val work = pages.map { it.toMutableList() }
    work[at.page].removeAt(at.index)
    work[at.page].addAll(at.index, folder.apps.map { Slot.App(it) })
    return PagesChange(paginate(work), "ungrouped", at.page, at.index)
  }

  fun rename(pages: List<List<Slot>>, folderId: String, name: String): List<List<Slot>> {
    val clean = name.trim()
    if (clean.isEmpty() || clean.length > 24) throw FeedException("INVALID_ARGUMENT", "name must be 1–24 characters")
    val at = locateFolder(pages, folderId) ?: throw FeedException("NOT_FOUND", "no folder $folderId")
    return pages.mapIndexed { p, page -> if (p != at.page) page else page.mapIndexed { i, s -> if (i == at.index) (s as Slot.Folder).copy(name = clean) else s } }
  }

  /**
   * Layout from before multiple pages: one auto-arranged app page plus a user-arranged extra
   * page. The app page is paginated and the extra page's items (in cell order) follow as the
   * next page, leaving the app pages.
   */
  fun migrate(defaultGrid: List<Slot>, extraPage: List<Slot>): List<List<Slot>> {
    val moved = keys(listOf(extraPage))
    val first = defaultGrid.mapNotNull { slot ->
      when (slot) {
        is Slot.Widget -> slot
          is Slot.App -> slot.takeIf { it.key !in moved }
        is Slot.Folder -> {
          val apps = slot.apps.filter { it !in moved }
          when (apps.size) { 0 -> null; 1 -> Slot.App(apps[0]); else -> slot.copy(apps = apps) }
        }
      }
    }
    // The former extra page stays a page of its own after the app pages.
    return paginate(paginate(listOf(first)) + listOf(extraPage))
  }

  /** Page 0 always exists; other empty pages are removed. */
  fun compact(pages: List<List<Slot>>): List<List<Slot>> {
    val kept = pages.filterIndexed { i, page -> i == 0 || page.isNotEmpty() }
    return kept.ifEmpty { listOf(emptyList()) }
  }
}
