#!/usr/bin/env kotlin
@file:DependsOn("com.kgit2:kommand-jvm:2.3.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

import com.kgit2.kommand.io.Output
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Builds the changelog for a release from the per-issue entries in
 * docs/changelog/issues/<id>/changelog.<type>.json.
 *
 * Only changes to the app are listed: a release ships the app, and its changelog
 * is read by the app itself, so server and web app changes have no place in it.
 * See [APP_LABEL].
 *
 * Produces one JSON file per language plus an English markdown version for the
 * release body. Nothing is written into the repository: everything lands in the
 * output directory and is attached to the release as an artifact.
 *
 * Usage: generate_changelog.main.kts <release_name> [output_directory]
 *        RELEASE_NAME / OUTPUT_DIR work as well.
 */

fun execute(program: String, vararg arguments: String): Output = Command(program)
    .args(*arguments)
    .stdout(Stdio.Pipe)
    .output()

fun capture(program: String, vararg arguments: String): String? {
    val output = execute(program, *arguments)
    if (output.status != 0) return null
    return output.stdout?.trim()?.takeIf { it.isNotEmpty() }
}

fun warn(message: String) = println("::warning::$message")

val releaseName = args.getOrNull(0)?.takeIf { it.isNotBlank() }
    ?: System.getenv("RELEASE_NAME")?.takeIf { it.isNotBlank() }
    ?: error("No release name. Pass it as the first argument or set RELEASE_NAME (e.g. v20260731_1812).")

val repoRoot = File(
    capture("git", "rev-parse", "--show-toplevel") ?: error("Not inside a git repository.")
)

// A relative output directory is resolved against the repository root, so the
// script does not depend on where it was started from.
val outputDirectory = (args.getOrNull(1)?.takeIf { it.isNotBlank() }
    ?: System.getenv("OUTPUT_DIR")?.takeIf { it.isNotBlank() })
    ?.let { File(it).takeIf(File::isAbsolute) ?: File(repoRoot, it) }
    ?: File(repoRoot, "build/changelog")

fun File.displayPath(): String = runCatching { relativeTo(repoRoot).path }.getOrDefault(path)

/** The category an entry ends up in, derived from the GitHub issue type. */
enum class Category(val issueType: String, val key: String, val heading: String) {
    Feature(issueType = "feature", key = "features", heading = "Features"),
    Fix(issueType = "bug", key = "fixes", heading = "Fixes"),
    Task(issueType = "task", key = "tasks", heading = "Other changes"),
    ;

    /** The entry file carries its type in the name, e.g. changelog.feature.json. */
    val fileName: String get() = "changelog.$issueType.json"
}

fun categoryOf(issueType: String?): Category? =
    Category.entries.firstOrNull { it.issueType == issueType?.lowercase() }

/** Features carry a title and a description, fixes and tasks only a description. */
data class Text(
    val title: String?,
    val description: String?,
)

data class Entry(
    val issue: Int,
    val category: Category,
    val default: Text,
    val localized: Map<String, Text>,
) {
    /** Rendered as "(#17)" behind an entry, so a reader can jump to the issue. */
    val issueReference: String get() = " (#$issue)"

    /** A localization overrides only the fields it actually provides. */
    fun textFor(language: String?): Text {
        if (language == null) return default
        val localization = localized[language]
        return Text(
            title = localization?.title ?: default.title,
            description = localization?.description ?: default.description,
        )
    }
}

// --- read the entries -----------------------------------------------------

// The first release has no predecessor, so fall back to the full history.
val latestRelease = capture("gh", "release", "view", "--json", "tagName", "--jq", ".tagName")
val range = latestRelease?.let { "$it..HEAD" } ?: "HEAD"

val issues = capture("git", "log", range, "--pretty=format:%s")
    .orEmpty()
    .lines()
    .mapNotNull { subject -> Regex("#(\\d+)").find(subject)?.groupValues?.get(1)?.toIntOrNull() }
    .distinct()
    .sorted()

fun textOf(source: JsonObject?) = Text(
    title = (source?.get("title") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
    description = (source?.get("description") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() },
)

/**
 * The label that marks an issue as a change to the app.
 *
 * The other side of the same coin is the deploy workflow, which builds the app
 * only for a pull request carrying this label. An issue without it never reached
 * the app, so listing it would tell users about something they cannot see.
 */
val APP_LABEL = "project:app"

/** The project:* labels on an issue, empty when it carries none. */
fun labelsOf(issue: Int): List<String> =
    capture("gh", "issue", "view", "$issue", "--json", "labels", "--jq", "[.labels[].name] | join(\",\")")
        .orEmpty()
        .split(",")
        .map { it.trim() }
        .filter { it.isNotEmpty() }

val entries = issues.mapNotNull { issue ->
    // Asked before the issue type, so a server-only issue costs one call and
    // never reports a missing type or a missing entry it does not need.
    val labels = labelsOf(issue)
    if (APP_LABEL !in labels) {
        if (labels.isEmpty()) {
            // Almost always a forgotten label rather than a deliberate omission,
            // and the entry disappears without a trace otherwise.
            warn("Issue #$issue has no labels, so it is not treated as an app change. Add $APP_LABEL if it is one.")
        } else {
            println("Issue #$issue is labelled ${labels.joinToString()}, not $APP_LABEL, leaving it out.")
        }
        return@mapNotNull null
    }

    val issueType = capture("gh", "issue", "view", "$issue", "--json", "issueType", "--jq", ".issueType.name // \"\"")
    val category = categoryOf(issueType) ?: run {
        // Better a wrong section than a lost entry, and the pull request check
        // already warns about issues without a type.
        warn("Issue #$issue has no issue type, listing it under \"${Category.Task.heading}\".")
        Category.Task
    }

    val relative = "docs/changelog/issues/$issue/${category.fileName}"
    val file = File(repoRoot, relative)
    if (!file.exists()) {
        warn("No changelog for issue #$issue ($relative), skipping it.")
        return@mapNotNull null
    }

    val root = Json.parseToJsonElement(file.readText()).jsonObject
    val default = textOf(root)

    when (category) {
        Category.Feature -> {
            if (default.title == null) error("$relative needs a \"title\", #$issue is a Feature.")
            if (default.description == null) error("$relative needs a \"description\", #$issue is a Feature.")
        }

        Category.Fix -> {
            if (default.description == null) error("$relative needs a \"description\", #$issue is a Bug.")
        }

        // A description is optional for tasks, and without one there is nothing to show.
        Category.Task -> if (default.description == null) {
            warn("Issue #$issue has no description, leaving it out of the changelog.")
            return@mapNotNull null
        }
    }

    Entry(
        issue = issue,
        category = category,
        default = default,
        localized = (root["localized"] as? JsonObject).orEmpty().mapValues { (_, localization) ->
            textOf(localization as? JsonObject)
        },
    )
}

// --- render ---------------------------------------------------------------

fun entriesOf(category: Category) = entries.filter { it.category == category }

fun renderJson(language: String?) = buildJsonObject {
    put("release", releaseName)
    language?.let { put("language", it) }
    Category.entries.forEach { category ->
        put(
            category.key,
            // Keyed by issue number, so a consumer can look an entry up directly.
            buildJsonObject {
                entriesOf(category).forEach { entry ->
                    val text = entry.textFor(language)
                    put(
                        "${entry.issue}",
                        buildJsonObject {
                            if (category == Category.Feature) put("title", text.title)
                            put("description", text.description)
                        },
                    )
                }
            },
        )
    }
}

fun renderMarkdown(language: String?) = buildString {
    if (entries.isEmpty()) {
        // The release still needs a body, and an empty one reads like a mistake.
        appendLine("No user-facing changes.")
        return@buildString
    }

    Category.entries.forEach { category ->
        val categoryEntries = entriesOf(category)
        if (categoryEntries.isEmpty()) return@forEach

        if (isNotEmpty()) appendLine()
        appendLine("## ${category.heading}")
        if (category != Category.Feature) appendLine()

        categoryEntries.forEach { entry ->
            val text = entry.textFor(language)
            if (category == Category.Feature) {
                appendLine()
                appendLine("### ${text.title}${entry.issueReference}")
                appendLine()
                appendLine(text.description)
            } else {
                // Fixes and tasks are one-liners.
                appendLine("- ${text.description}${entry.issueReference}")
            }
        }
    }
}

// --- write ----------------------------------------------------------------

if (entries.isEmpty()) {
    warn("No changelog entries for $releaseName.")
}

outputDirectory.mkdirs()

// null is the default (English), every language found in any entry gets its own file.
val languages = listOf(null) + entries.flatMap { it.localized.keys }.distinct().sorted()
val pretty = Json { prettyPrint = true }

// Always written, even with no entries at all: the release attaches these files
// and would otherwise fail on the missing ones.
val written = languages.map { language ->
    val file = File(outputDirectory, if (language == null) "changelog.json" else "changelog.$language.json")
    file.writeText(pretty.encodeToString(JsonObject.serializer(), renderJson(language)) + "\n")
    file
} + File(outputDirectory, "CHANGELOG.md").apply {
    // English only, this one becomes the release body.
    writeText(renderMarkdown(null))
}

written.forEach { println("wrote ${it.displayPath()}") }
