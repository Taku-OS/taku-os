package org.agentos.assistant

import java.io.IOException
import java.util.ArrayList
import java.util.Comparator
import java.util.HashMap
import java.util.HashSet
import org.json.JSONArray
import org.json.JSONObject

/** Retention runs in the runtime's transaction, including panel operations. Receipts/fences are history,
 * independent of reclaimable projections. Only unowned transient projections are recycled. */
internal class MatterRetention {
    interface Storage {
        @Throws(Exception::class)
        fun all(table: String): JSONArray
        @Throws(Exception::class)
        fun allBindings(): JSONArray
        @Throws(Exception::class)
        fun removeMatter(id: String)
        fun removeSource(id: String)
    }

    companion object {
        private const val MAX_TRANSIENT_MATTERS = 200
        @JvmStatic
        @Throws(Exception::class)
        fun reclaim(store: Storage, retained: Set<String>, busySources: Set<String>,
                    maxMatters: Int, maxSources: Int, reserveMatters: Int, reserveSources: Int) {
            val matters = store.all("matters")
            val sources = store.all("sources")
            val bindings = store.allBindings()
            val protectedMatters = HashSet(retained)
            val protectedSources = HashSet(busySources)
            val unresolvedCommands = HashSet<String>()
            val rules = store.all("rules")
            val commands = store.all("commands")
            val occurrences = store.all("occurrences")
            for (i in 0 until rules.length()) {
                val rule = rules.getJSONObject(i)
                protectedMatters.add(rule.getString("matterId")) // Paused rules still belong to the user.
                if (rule.has("sourceId")) protectedSources.add(rule.getString("sourceId"))
            }
            for (i in 0 until commands.length()) {
                val command = commands.getJSONObject(i)
                if (!command.optBoolean("terminal")) {
                    protectedMatters.add(command.getString("matterId"))
                    unresolvedCommands.add(command.getString("id"))
                }
            }
            for (i in 0 until occurrences.length()) {
                val occurrence = occurrences.getJSONObject(i)
                if (!occurrence.optBoolean("terminal") || "unknown-outcome" == occurrence.optString("state"))
                    protectedMatters.add(occurrence.getString("matterId"))
            }
            val sourceById = HashMap<String, JSONObject>()
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val id = source.getString("id")
                sourceById[id] = source
                val observation = source.optJSONObject("observation")
                if (unresolvedCommands.contains(source.optString("commandId"))) protectedSources.add(id)
                if ("task-receipt" == source.optString("kind") && (observation == null || !observation.optBoolean("terminal")) ||
                    "audio-session" == source.optString("kind") && (observation == null || !java.util.Set.of("stopped", "failed", "interrupted").contains(observation.optString("status"))))
                    protectedSources.add(id)
            }
            // Active/in-flight sources own their matter as well as every bound projection.
            for (id in protectedSources) {
                val source = sourceById[id]
                if (source != null && source.has("matterId")) protectedMatters.add(source.getString("matterId"))
            }
            val references = HashMap<String, MutableSet<String>>()
            val matterSources = HashMap<String, MutableList<String>>()
            for (i in 0 until bindings.length()) {
                val binding = bindings.getJSONObject(i)
                val matter = binding.getString("matterId")
                val source = binding.getString("sourceId")
                references.computeIfAbsent(source) { HashSet() }.add(matter)
                matterSources.computeIfAbsent(matter) { ArrayList() }.add(source)
                if (protectedSources.contains(source)) protectedMatters.add(matter)
            }
            val remainingSources = HashSet(sourceById.keys)
            reclaimOrphans(store, remainingSources, protectedSources, references)
            val candidates = ArrayList<JSONObject>()
            for (i in 0 until matters.length()) {
                val matter = matters.getJSONObject(i)
                if (!protectedMatters.contains(matter.getString("matterId"))) candidates.add(matter)
            }
            // all(matters) uses persisted update order; stable sorting keeps that order for old schemas.
            candidates.sortWith(Comparator.comparingLong { matter ->
                val meta = matter.optJSONObject("meta")
                matter.optLong("retainedAt", if (meta == null) 0L else meta.optLong("observedAt"))
            })
            var remainingMatters = matters.length()
            var transientCount = candidates.size
            for (matter in candidates) {
                if (remainingMatters + reserveMatters <= maxMatters && transientCount + reserveMatters <= MAX_TRANSIENT_MATTERS &&
                    remainingSources.size + reserveSources <= maxSources) break
                val id = matter.getString("matterId")
                store.removeMatter(id)
                remainingMatters--
                transientCount--
                val attached = matterSources[id]
                if (attached != null) for (source in attached) references.getValue(source).remove(id)
                reclaimOrphans(store, remainingSources, protectedSources, references)
            }
            if (remainingMatters + reserveMatters > maxMatters || remainingSources.size + reserveSources > maxSources)
                throw IOException("MATTER_STORAGE_FULL")
        }

        private fun reclaimOrphans(store: Storage, sources: MutableSet<String>, protectedSources: Set<String>, references: Map<String, Set<String>>) {
            for (id in ArrayList(sources)) {
                val refs = references[id]
                if (!protectedSources.contains(id) && (refs == null || refs.isEmpty())) {
                    store.removeSource(id)
                    sources.remove(id)
                }
            }
        }
    }
}
