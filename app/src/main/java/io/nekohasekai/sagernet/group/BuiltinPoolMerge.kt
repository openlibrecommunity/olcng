package io.nekohasekai.sagernet.group

internal data class PoolMergePlan(
    val retained: Map<String, Long>,
    val added: Set<String>,
    val deleted: Set<Long>,
    val owners: Map<String, Set<String>>,
)

internal object BuiltinPoolMerge {
    fun plan(existing: List<Pair<Long, String>>, snapshots: Map<String, Set<String>>): PoolMergePlan {
        val owners = linkedMapOf<String, MutableSet<String>>()
        snapshots.forEach { (source, fingerprints) ->
            fingerprints.forEach { owners.getOrPut(it) { linkedSetOf() }.add(source) }
        }
        val retained = linkedMapOf<String, Long>()
        val deleted = linkedSetOf<Long>()
        existing.forEach { (id, fingerprint) ->
            if (fingerprint in owners && fingerprint !in retained) {
                retained[fingerprint] = id
            } else {
                deleted.add(id)
            }
        }
        return PoolMergePlan(retained, owners.keys - retained.keys, deleted, owners)
    }
}
