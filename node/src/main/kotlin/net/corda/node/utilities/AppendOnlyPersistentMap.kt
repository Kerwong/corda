package net.corda.node.utilities

import net.corda.core.utilities.loggerFor
import java.util.*


/**
 * Implements a caching layer on top of an *append-only* table accessed via Hibernate mapping. Note that if the same key is [put] twice the
 * behaviour is unpredictable! There is a best-effort check for double inserts, but this should *not* be relied on, so
 * ONLY USE THIS IF YOUR TABLE IS APPEND-ONLY
 */
class AppendOnlyPersistentMap<K, V, E, EK> (
        val cacheBound: Long,
        val toPersistentEntityKey: (K) -> EK,
        val fromPersistentEntity: (E) -> Pair<K,V>,
        val toPersistentEntity: (key: K, value: V) -> E,
        val persistentEntityClass: Class<E>
) {
    private companion object {
        val log = loggerFor<AppendOnlyPersistentMap<*, *, *, *>>()
    }

    private val cache = NonInvalidatingCache<K, Optional<V>>(
            bound = cacheBound,
            concurrencyLevel = 8,
            loadFunction = { key -> Optional.ofNullable(loadValue(key)) }
    )

    operator fun get(key: K): V? {
        return cache.get(key).orElse(null)
    }

    fun loadAll(): Sequence<Pair<K, V>> {
        val query = DatabaseTransactionManager.current().session.createQuery("FROM " + persistentEntityClass.name )
        val result = query.resultList as MutableList<E>
        return result.map { x -> fromPersistentEntity(x) }.asSequence()
    }

    /**
     * Puts the value into the map and caches it.
     */
    operator tailrec fun set(key: K, value: V) {
        var inserted = false
        val existing = cache.get(key) {
            inserted = true
            Optional.of(value)
        }
        if (inserted) {
            // Value was inserted into cache fine, store the value. Note that if the key-value pair is already in the DB
            // but was evicted from the cache then this operation will overwrite the entry in the DB!
            storeValue(key, value)
        } else if (existing.isPresent) {
            // An existing value is cached, in this case we know for sure that there is a problem.
            log.error("Double insert detected in AppendOnlyJDBCMap for key $key, not inserting the second time")
        } else {
            // This happens when the key was queried before with no value associated. We invalidate the cached null
            // value and recursively call set again. This is to avoid race conditions where another thread queries after
            // the invalidate but before the set.
            cache.invalidate(key)
            set(key, value)
        }
    }

    private fun loadValue(key: K): V? {
        val result = DatabaseTransactionManager.current().session.find(persistentEntityClass, toPersistentEntityKey(key))
        return result?.let(fromPersistentEntity)?.second
    }

    private fun storeValue(key: K, value: V) {
        val session = DatabaseTransactionManager.current().session
        val old = session.find(persistentEntityClass, toPersistentEntityKey(key))
        if (old == null) {
            session.save(toPersistentEntity(key,value))
        } else {
            //exposedLogger.warn("Duplicate recording of transaction ${transaction.id}")
        }
    }
}
