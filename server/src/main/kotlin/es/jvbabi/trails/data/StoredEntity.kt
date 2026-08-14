package es.jvbabi.trails.data

import org.jetbrains.exposed.v1.dao.Entity

/**
 * Reads a freshly created entity back from the database before it is mapped.
 *
 * A column filled by a database-side default — every `created_at` here — has no value
 * on the client until the row actually exists, and touching it before that throws
 * ("… is not initialized yet"). Flushing the insert and re-reading the row also makes
 * the timestamp in the model the one the database really stored, rather than a second
 * guess at "now".
 *
 * Only needed right after [org.jetbrains.exposed.v1.dao.EntityClass.new]: an entity
 * that was read from the database is complete already. Must be called inside the
 * transaction that created it.
 */
fun <ID : Comparable<ID>, E : Entity<ID>> E.stored(): E = apply { refresh(flush = true) }
