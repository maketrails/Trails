package es.jvbabi.trails

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.optionalValue
import com.github.ajalt.clikt.parameters.types.path
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.absolutePathString

fun main(args: Array<String>) {
    runBlocking {
        AppCommand().main(args)
    }
}


class AppCommand: SuspendingCliktCommand("server") {

    val storageDirectory by option("--storage-directory", help = "Path to store data in")
        .path(mustExist = true, mustBeWritable = true, mustBeReadable = true)
        .default(File("./data").toPath())

    val bindHost by option("--bind-host", help = "Host to bind to")
        .optionalValue("")

    override suspend fun run() {

        val applicationLaunchConfig = ApplicationLaunchConfig(
            storageDirectory = File(storageDirectory.absolutePathString()),
        )

        embeddedServer(
            factory = Netty,
            port = 20416,
            host = bindHost ?: "0.0.0.0",
            module = { rootModule(applicationLaunchConfig) }
        ).start(wait = true)
    }
}

data class ApplicationLaunchConfig(
    val storageDirectory: File = File("./data"),
    val bindHost: String = "0.0.0.0",
)