package es.jvbabi.trails.domain.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

interface KeyValueRepository {
    fun <T> get(key: Key<T>): Flow<T?>
    suspend fun <T> set(key: Key<T>, value: T)
    suspend fun <T> delete(key: Key<T>)
}

sealed class Key<VALUE>(val key: String) {
    abstract fun toValue(value: String): VALUE
    abstract fun fromValue(value: VALUE): String

    open val defaultValue: VALUE? = null

    abstract class SerializableKey<T>(
        key: String,
        private val serializer: KSerializer<T>
    ) : Key<T>(key) {
        override fun toValue(value: String): T = Json.decodeFromString(serializer, value)
        override fun fromValue(value: T): String = Json.encodeToString(serializer, value)
    }

    abstract class StringKey(key: String) : Key<String>(key) {
        override fun toValue(value: String): String = value
        override fun fromValue(value: String): String = value
    }

    abstract class IntKey(key: String): Key<Int>(key) {
        override fun toValue(value: String): Int = value.toInt()
        override fun fromValue(value: Int): String = value.toString()
    }

    abstract class BooleanKey(key: String): Key<Boolean>(key) {
        override fun toValue(value: String): Boolean = value.toBoolean()
        override fun fromValue(value: Boolean): String = value.toString()
    }

    abstract class UuidKey(key: String): Key<Uuid>(key) {
        override fun toValue(value: String): Uuid = Uuid.parse(value)
        override fun fromValue(value: Uuid): String = value.toString()
    }

    data object ThisDeviceId: UuidKey("trails.thisDeviceId")
    data object UserId: UuidKey("trails.userId")
    data object Host: StringKey("trails.host")
    data object Token: StringKey("trails.token")

    data object MinimumMovementDistanceToNextSnapshot: IntKey("trails.minimumMovementDistanceToNextSnapshot") {
        override val defaultValue: Int = 10
    }

    data object ShowHomeserverInPersistentNotification: BooleanKey("trails.notification.persistence.showHomeserver") {
        override val defaultValue: Boolean = true
    }

    /**
     * Whether the user has settled on installing updates by hand rather than letting the app do it.
     *
     * Android only — no other platform installs its own updates. Being allowed to install takes
     * priority over this: someone who has since granted that permission has plainly changed their
     * mind, so the app installs the update itself and this is left as it was, ready to take over
     * again if the permission is ever withdrawn.
     */
    data object AlwaysInstallUpdatesManually: BooleanKey("app.update.alwaysInstallManually") {
        override val defaultValue: Boolean = false
    }

    data object Theme: Key<es.jvbabi.trails.domain.repository.Theme>("app.theme") {
        override fun fromValue(value: es.jvbabi.trails.domain.repository.Theme): String = value.name
        override fun toValue(value: String): es.jvbabi.trails.domain.repository.Theme = es.jvbabi.trails.domain.repository.Theme.valueOf(value)
    }
}

enum class Theme {
    Light, Dark, System
}