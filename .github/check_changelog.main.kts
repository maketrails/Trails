#!/usr/bin/env kotlin
@file:DependsOn("com.kgit2:kommand-jvm:2.3.0")
@file:DependsOn("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import com.kgit2.kommand.io.Output
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.system.exitProcess

/**
 * Checks that every issue a pull request closes has a changelog entry, and that
 * the entry matches the shape its issue type will be rendered with:
 *
 *  - Feature: title and description, and the entry itself is required
 *  - Bug:     description only, entry optional
 *  - Task:    description optional, entry optional
 *
 * In a stack of pull requests, several layers may close the same issue. A
 * Feature's entry then only has to exist in the topmost of them: layers further
 * down are told it is expected higher up instead of failing.
 *
 * The pull request number comes from the environment so the workflow never
 * interpolates pull request data into the script itself. Falls back to the
 * branch name, which makes local runs work.
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

val repoRoot = File(
    capture("git", "rev-parse", "--show-toplevel") ?: error("Not inside a git repository.")
)

val pullRequest = System.getenv("PR_NUMBER")?.takeIf { it.isNotBlank() }
val branch = System.getenv("BRANCH")?.takeIf { it.isNotBlank() }
    ?: capture("git", "rev-parse", "--abbrev-ref", "HEAD")

val summaryFile = System.getenv("GITHUB_STEP_SUMMARY")?.takeIf { it.isNotBlank() }?.let(::File)

// Tags a comment as ours so previous reports can be found again, no matter
// what else commented on the pull request in between.
val commentMarker = "<!-- changelog-check -->"

val findings = StringBuilder()
var warned = false
var failed = false

fun warn(message: String) {
    println("::warning::$message")
    warned = true
}

fun fail(message: String) {
    println("::error::$message")
    failed = true
}

/**
 * Deletes every earlier report of ours so only the current one remains.
 * Comments are found by [commentMarker] rather than by author, so another bot
 * commenting on the pull request cannot be mistaken for one of our reports.
 */
fun deletePreviousReports(pullRequest: String) {
    val previousIds = capture(
        "gh", "api", "/repos/{owner}/{repo}/issues/$pullRequest/comments",
        "--paginate",
        "--jq", ".[] | select(.body | contains(\"$commentMarker\")) | .id",
    )
        ?.lines()
        ?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }
        .orEmpty()

    previousIds.forEach { id ->
        val deleted = execute("gh", "api", "-X", "DELETE", "/repos/{owner}/{repo}/issues/comments/$id")
        if (deleted.status != 0) {
            println("::warning::Could not delete the earlier report $id.")
        }
    }
}

/**
 * Writes the report to the step summary and posts it as a new pull request
 * comment, deleting the earlier ones. Log annotations alone are not enough:
 * without a file reference they only show up on the workflow run page, never in
 * the pull request itself.
 */
fun finish(headline: String): Nothing {
    val verdict = when {
        failed -> "❌ $headline"
        warned -> "⚠️ $headline"
        else -> "✅ $headline"
    }
    val report = buildString {
        appendLine(commentMarker)
        appendLine("### Changelog")
        appendLine()
        appendLine(verdict)
        if (findings.isNotEmpty()) {
            appendLine()
            append(findings)
        }
    }

    summaryFile?.appendText(report)

    if (pullRequest != null) {
        deletePreviousReports(pullRequest)

        val file = File.createTempFile("changelog-check", ".md")
        try {
            file.writeText(report)
            val comment = execute("gh", "pr", "comment", pullRequest, "--body-file", file.absolutePath)
            if (comment.status != 0) {
                println("::warning::Could not post the changelog report as a pull request comment.")
            }
        } finally {
            file.delete()
        }
    }

    exitProcess(if (failed) 1 else 0)
}

// --- which issues does this pull request close? ---------------------------
// Resolved the same way in generate_changelog.main.kts and sync-labels.yaml, the three have to agree.
//
// GitHub only links closing keywords of pull requests that target the default branch, so every
// layer of a stack above the bottom one has no closing references. The keywords are therefore also
// read from the description directly. The branch name is only a fallback for local runs and for
// pull requests that reference no issue at all.

/** "Closes #12", "fixes: #12", "Resolved #12", … */
val closingKeyword = Regex("(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?):?\\s+#(\\d+)\\b")

/** e.g. feat/15-add-minimal-movement -> 15, 5-editremove-shares -> 5 */
fun issueOfBranch(branch: String?): Int? =
    branch?.let { Regex("^([a-zA-Z]+/)?(\\d+)-").find(it)?.groupValues?.get(2)?.toIntOrNull() }

val pullRequestFields = "number,headRefName,body,closingIssuesReferences"

data class PullRequest(val number: Int, val branch: String, val issues: List<Int>)

/** Reads a pull request as returned by `gh pr view/list --json [pullRequestFields]`. */
fun JsonObject.toPullRequest(): PullRequest {
    val branch = this["headRefName"]?.jsonPrimitive?.content.orEmpty()
    val linked = this["closingIssuesReferences"]?.jsonArray.orEmpty()
        .mapNotNull { it.jsonObject["number"]?.jsonPrimitive?.intOrNull }
    val body = (this["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val mentioned = closingKeyword.findAll(body).map { it.groupValues[1].toInt() }.toList()
    return PullRequest(
        number = this["number"]!!.jsonPrimitive.intOrNull!!,
        branch = branch,
        issues = (linked + mentioned).ifEmpty { listOfNotNull(issueOfBranch(branch)) }.distinct().sorted(),
    )
}

val issues = pullRequest
    ?.let { capture("gh", "pr", "view", it, "--json", pullRequestFields) }
    ?.let { Json.parseToJsonElement(it).jsonObject.toPullRequest().issues }
    ?: listOfNotNull(issueOfBranch(branch))

if (issues.isEmpty()) {
    warn("No linked issue found for this pull request (branch '$branch'), skipping the changelog check.")
    finish("This pull request closes no issue, so there is nothing to check.")
}

/**
 * What an entry is allowed and required to contain, derived from the issue type.
 * Features are headlined in the changelog, fixes and tasks are one-liners, so a
 * title in those would be written and then silently dropped at release time.
 */
enum class Shape(val titled: Boolean, val descriptionRequired: Boolean) {
    Feature(titled = true, descriptionRequired = true),
    Bug(titled = false, descriptionRequired = true),
    Task(titled = false, descriptionRequired = false),
    ;

    /** The entry file carries its type in the name, e.g. changelog.feature.json. */
    val fileName: String get() = "changelog.${name.lowercase()}.json"
}

fun shapeOf(issueType: String?): Shape? = when (issueType?.lowercase()) {
    "feature" -> Shape.Feature
    "bug" -> Shape.Bug
    "task" -> Shape.Task
    else -> null
}

data class Changelog(
    val summary: String?,
    val problems: List<String>,
)

val JsonElement?.isText: Boolean get() = this is JsonPrimitive && isString

fun JsonObject.text(field: String): String? =
    (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

/** Reads a changelog file and collects everything that is wrong with it for [shape]. */
fun readChangelog(file: File, shape: Shape): Changelog {
    val root = try {
        Json.parseToJsonElement(file.readText()).jsonObject
    } catch (exception: Exception) {
        // Keep it to one line, a GitHub annotation only shows the first one.
        val reason = exception.message?.lineSequence()?.firstOrNull()?.trim() ?: exception::class.simpleName
        return Changelog(summary = null, problems = listOf("is not a valid JSON object ($reason)"))
    }

    val problems = mutableListOf<String>()

    fun checkFields(where: String, source: JsonObject, required: Boolean) {
        val prefix = if (where.isEmpty()) "" else "$where."

        if (shape.titled) {
            val title = source["title"]
            when {
                title == null -> if (required) problems += "\"${prefix}title\" is missing"
                !title.isText || (title as JsonPrimitive).content.isBlank() ->
                    problems += "\"${prefix}title\" must be a non-empty string"
            }
        } else if (source.containsKey("title")) {
            problems += "\"${prefix}title\" is not allowed for a ${shape.name}, only a description is used"
        }

        val description = source["description"]
        when {
            description == null ->
                if (required && shape.descriptionRequired) problems += "\"${prefix}description\" is missing"
            !description.isText || (description as JsonPrimitive).content.isBlank() ->
                problems += "\"${prefix}description\" must be a non-empty string"
        }
    }

    checkFields(where = "", source = root, required = true)

    root["localized"]?.let { localized ->
        if (localized !is JsonObject) {
            problems += "\"localized\" must be an object"
            return@let
        }
        localized.forEach { (language, localization) ->
            if (localization !is JsonObject) {
                problems += "\"localized.$language\" must be an object"
                return@forEach
            }
            // A localization may override only some fields, so nothing is required here.
            checkFields(where = "localized.$language", source = localization, required = false)
        }
    }

    return Changelog(
        summary = if (shape.titled) root.text("title") else root.text("description"),
        problems = problems,
    )
}

/**
 * The label that decides whether an issue is rendered into the changelog at all.
 *
 * generate_changelog.main.kts leaves out every issue without it -- a release ships the app, and
 * the app is what reads the changelog -- so requiring an entry here would require a file that no
 * release ever reads. The two scripts have to agree on this, see APP_LABEL over there.
 */
val APP_LABEL = "project:app"
val PROJECT_PREFIX = "project:"

fun projectLabelsOf(kind: String, number: String): List<String> =
    capture("gh", kind, "view", number, "--json", "labels", "--jq", "[.labels[].name] | join(\",\")")
        .orEmpty()
        .split(",")
        .map { it.trim() }
        .filter { it.startsWith(PROJECT_PREFIX) }

// Read once and folded into every issue below. sync-labels.yaml copies the labels between the two
// sides, but it runs on the same events this check does -- so a pull request labelled a moment ago
// may still be looking at an issue the sync has not reached yet. Taking both is what the sync
// itself would arrive at.
val pullRequestLabels = pullRequest?.let { projectLabelsOf("pr", it) }.orEmpty()

/**
 * The open pull requests stacked on top of [branch], transitively: those based on it, those based
 * on them, and so on. Found through the base branches alone, so this works for any stack, however
 * it was built.
 */
fun pullRequestsAbove(branch: String, seen: MutableSet<String> = mutableSetOf()): List<PullRequest> {
    if (!seen.add(branch)) return emptyList()
    val above = capture("gh", "pr", "list", "--state", "open", "--base", branch, "--json", pullRequestFields)
        ?.let { Json.parseToJsonElement(it).jsonArray.map { element -> element.jsonObject.toPullRequest() } }
        .orEmpty()
    return above + above.flatMap { pullRequestsAbove(it.branch, seen) }
}

// Only for an actual pull request: a local run on the default branch would take every open pull
// request for one stacked on top of it.
val stackedAbove = if (pullRequest != null && branch != null) pullRequestsAbove(branch) else emptyList()

/** The pull request highest up the stack that closes [issue] as well, if any. */
fun topmostAbove(issue: Int): PullRequest? = stackedAbove.lastOrNull { issue in it.issues }

issues.forEach { issue ->
    val labels = (projectLabelsOf("issue", "$issue") + pullRequestLabels).distinct().sorted()

    // Asked before the issue type, so an issue that is not an app change costs one call and is
    // never reported for a type or an entry it does not need. An entry that is there anyway is
    // still validated below: somebody wrote it on purpose, and a broken file is worth saying.
    if (APP_LABEL !in labels && File(repoRoot, "docs/changelog/issues/$issue").exists().not()) {
        if (labels.isEmpty()) {
            // Nothing says what this change touches. Warn rather than skip: if the label turns
            // out to be $APP_LABEL after all, a Feature must not have lost its entry meanwhile.
            warn("Issue #$issue carries no $PROJECT_PREFIX label, so it is checked as an app change. Please add one.")
            findings.appendLine("- ⚠️ **#$issue** carries no `$PROJECT_PREFIX` label, so it is checked as an app change. Please add one.")
        } else {
            println("Issue #$issue is labelled ${labels.joinToString()}, not $APP_LABEL, so it needs no changelog.")
            findings.appendLine("- ✅ **#$issue** is labelled `${labels.joinToString("`, `")}`, not `$APP_LABEL`, so it needs no changelog.")
            return@forEach
        }
    }

    val type = capture("gh", "issue", "view", "$issue", "--json", "issueType", "--jq", ".issueType.name // \"\"")
    val required = type.equals("Feature", ignoreCase = true)

    if (type == null) {
        warn("Issue #$issue has no issue type. Please set one (Feature, Bug or Task).")
        findings.appendLine("- ⚠️ **#$issue** has no issue type. Please set one (Feature, Bug or Task).")
    }

    // Without a type the entry ends up under "Other changes" at release time,
    // so validate it against the shape it would actually be rendered with.
    val shape = shapeOf(type) ?: Shape.Task

    val directory = File(repoRoot, "docs/changelog/issues/$issue")
    val relative = "docs/changelog/issues/$issue/${shape.fileName}"
    val file = File(directory, shape.fileName)

    // A file named for another type is a rename away from being correct, which is
    // worth saying instead of reporting the expected one as simply missing.
    val misnamed = if (file.exists()) emptyList()
    else Shape.entries.filter { it != shape && File(directory, it.fileName).exists() }

    val legacy = !file.exists() && File(directory, "changelog.json").exists()

    when {
        misnamed.isNotEmpty() -> {
            val found = misnamed.joinToString(", ") { it.fileName }
            fail("Issue #$issue is a ${type ?: "Task"}, so its changelog must be named ${shape.fileName}, but found $found.")
            findings.appendLine("- ❌ **#$issue** (${type ?: "no type"}): found `$found`, expected `${shape.fileName}`. Please rename it.")
        }

        legacy -> {
            fail("Issue #$issue still uses changelog.json. Please rename it to ${shape.fileName}.")
            findings.appendLine("- ❌ **#$issue** (${type ?: "no type"}): `changelog.json` is no longer read, rename it to `${shape.fileName}`.")
        }

        !file.exists() && required && topmostAbove(issue) != null -> {
            val above = topmostAbove(issue)!!.number
            warn("Issue #$issue is a Feature without a changelog here, it is expected further up the stack in #$above.")
            findings.appendLine("- ⚠️ **#$issue** (Feature) has no changelog in this layer. It is expected further up the stack, in #$above.")
        }

        !file.exists() && required -> {
            fail("Issue #$issue is a Feature but has no changelog. Please add $relative.")
            findings.appendLine("- ❌ **#$issue** (Feature) needs a changelog. Please add `$relative`.")
        }

        !file.exists() -> {
            warn("Issue #$issue has no changelog ($relative). That is optional for type '${type ?: "unset"}'.")
            findings.appendLine("- ⚠️ **#$issue** (${type ?: "no type"}) has no changelog. `$relative` is optional for this type.")
        }

        else -> {
            // A broken file is always an error, no matter the issue type:
            // generate_changelog.main.kts fails on it at release time, so
            // catching it here is the whole point.
            val changelog = readChangelog(file, shape)
            val summary = changelog.summary
            if (changelog.problems.isEmpty()) {
                // A task may legitimately carry no description at all, and then
                // it is simply left out of the changelog.
                val what = summary ?: "no description, so it will not appear in the changelog"
                println("Issue #$issue (${type ?: "no type"}) has a changelog: $what")
                findings.appendLine("- ✅ **#$issue** (${type ?: "no type"}): $what")
            } else {
                changelog.problems.forEach { fail("$relative: $it") }
                findings.appendLine("- ❌ **#$issue** (${type ?: "no type"}) has an invalid `$relative`:")
                changelog.problems.forEach { findings.appendLine("  - $it") }
            }
        }
    }
}

val checked = issues.joinToString(", ") { "#$it" }
finish(
    when {
        failed -> "The changelog is not ready for $checked."
        warned -> "The changelog needs a look for $checked."
        // Not "every issue has one": an issue that is not an app change is in order without.
        else -> "The changelog is in order for $checked."
    }
)
