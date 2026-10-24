@file:OptIn(ExperimentalForeignApi::class)

package es.jvbabi.trails.di

import androidx.room.Room
import androidx.room.RoomDatabase
import es.jvbabi.trails.data.database.TrailsDatabase
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import org.koin.core.module.Module
import org.koin.dsl.module
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

/**
 * Empty: everything iOS contributes is registered from `MainViewController`, and the one thing that
 * lives in a platform module — the in-app updater — is Android-only.
 */
actual fun platformModule(): Module = module { }

/** Darwin, the engine iOS uses for everything anyway; it has no header line limit to run into. */
actual fun thirdPartyHttpClientEngine(): HttpClientEngineFactory<*> = Darwin

actual fun getDatabaseBuilder(): RoomDatabase.Builder<TrailsDatabase> {
    val dbFilePath = documentDirectory() + "/trails.db"
    return Room.databaseBuilder<TrailsDatabase>(
        name = dbFilePath,
    )
}

private fun documentDirectory(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )
    return requireNotNull(documentDirectory?.path)
}