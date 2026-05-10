package com.pushpull

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

private const val pluginTitle = "Push & Pull"

@Service(Service.Level.APP)
@State(name = "DeploySettings", storages = [Storage("deploy-settings.xml")])
class Settings : PersistentStateComponent<Settings.State> {
    data class State(
        var transfers: Int = 4,
        var checkers: Int = 8,
        var pushErrors: Boolean = false,
    )

    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    companion object {
        fun getInstance(): Settings =
            ApplicationManager.getApplication().getService(Settings::class.java)
    }
}

class SettingsConfigurable : Configurable {
    private val settings = Settings.getInstance()
    private var transfers = settings.state.transfers
    private var checkers = settings.state.checkers
    private var pushErrors = settings.state.pushErrors

    override fun getDisplayName(): String = pluginTitle

    override fun createComponent(): JComponent = panel {
        group("Rclone Args") {
            row("Transfers") {
                intTextField(1..128)
                    .bindIntText(::transfers)
                    .comment("Maximum parallel uploads/downloads. rclone --transfers. Default: 4.")
            }
            row("Checkers") {
                intTextField(1..256)
                    .bindIntText(::checkers)
                    .comment("How many files rclone checks while scanning and comparing. Default: 8.")
            }
        }
        group("Upload Rules") {
            row {
                checkBox("Push Errors")
                    .bindSelected(::pushErrors)
                    .comment("Upload files even when IDE Problems contains errors for the selected file or folder.")
            }
        }
    }

    override fun isModified(): Boolean =
        transfers != settings.state.transfers ||
            checkers != settings.state.checkers ||
            pushErrors != settings.state.pushErrors

    override fun apply() {
        settings.state.transfers = transfers.coerceAtLeast(1)
        settings.state.checkers = checkers.coerceAtLeast(1)
        settings.state.pushErrors = pushErrors
    }

    override fun reset() {
        transfers = settings.state.transfers
        checkers = settings.state.checkers
        pushErrors = settings.state.pushErrors
    }
}

class PushFileAction : TransferAction(Direction.Push, Target.File)
class PullFileAction : TransferAction(Direction.Pull, Target.File)
class PushFolderAction : TransferAction(Direction.Push, Target.Folder)
class PullFolderAction : TransferAction(Direction.Pull, Target.Folder)

abstract class TransferAction(
    private val direction: Direction,
    private val target: Target,
) : AnAction() {
    enum class Direction {
        Push,
        Pull,
    }

    enum class Target {
        File,
        Folder,
    }

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        event.presentation.isEnabledAndVisible = file != null && when (target) {
            Target.File -> !file.isDirectory
            Target.Folder -> file.isDirectory
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        try {
            val settings = Settings.getInstance().state
            val transferRoot = resolveTransferRoot(project, file)

            if (transferRoot == null) {
                Messages.showWarningDialog(
                    project,
                    "Could not find rclone.conf for this item. Put rclone.conf in this file's project root or open the correct project.",
                    pluginTitle,
                )
                return
            }

            if (direction == Direction.Push && !settings.pushErrors) {
                val blockingFile = findFirstFileWithFatalProblemsOrContinue(project, file)

                if (blockingFile != null) {
                    Messages.showErrorDialog(
                        project,
                        "Errors found in IDE Problems. Upload skipped: ${blockingFile.path}\n\n" +
                            "You can allow this in Settings by enabling $pluginTitle: Push Errors.",
                        pluginTitle,
                    )
                    return
                }
            }

            makeRclonePasswd(transferRoot.rootPath, transferRoot.configPath)
            openTerminal(project, transferRoot.rootPath.toString(), buildRcloneCommand(transferRoot, file))
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "$pluginTitle failed.", pluginTitle)
        }
    }

    private fun resolveTransferRoot(project: Project, file: VirtualFile): TransferRoot? {
        val localPath = Path.of(file.path).toAbsolutePath().normalize()
        val projectRoot = project.basePath
            ?.takeUnless { it.isBlank() }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
        val rootPath = if (projectRoot != null && localPath.startsWith(projectRoot)) {
            projectRoot
        } else {
            findRcloneRoot(localPath, file.isDirectory)
        } ?: return null
        val relativePath = rootPath.relativize(localPath).toString()

        if (relativePath.isBlank() || relativePath.startsWith("..") || Path.of(relativePath).isAbsolute) {
            throw IllegalStateException("Could not make a project-relative file path.")
        }

        return TransferRoot(
            rootPath = rootPath,
            configPath = rootPath.resolve("rclone.conf"),
            relativePath = relativePath,
        )
    }

    private fun findRcloneRoot(itemPath: Path, isDirectory: Boolean): Path? {
        var currentPath = if (isDirectory) itemPath else itemPath.parent

        while (currentPath != null) {
            if (Files.exists(currentPath.resolve("rclone.conf"))) {
                return currentPath
            }

            currentPath = currentPath.parent
        }

        return null
    }

    private fun buildRcloneCommand(transferRoot: TransferRoot, file: VirtualFile): String {
        val settings = Settings.getInstance().state
        val localPath = Path.of(file.path).toAbsolutePath().normalize()
        val relativePath = transferRoot.relativePath
        val remotePath = joinRemotePath("my-project:", relativePath)
        val configPath = transferRoot.configPath.toString()

        val args = when (direction) {
            Direction.Push -> {
                val remoteTarget = when (target) {
                    Target.File -> remoteParent("my-project:", relativePath)
                    Target.Folder -> remotePath
                }
                listOf("rclone", "--config", configPath, "copy", localPath.toString(), remoteTarget)
            }
            Direction.Pull -> {
                val localTarget = when (target) {
                    Target.File -> localPath.parent?.toString() ?: transferRoot.rootPath.toString()
                    Target.Folder -> localPath.toString()
                }
                listOf("rclone", "--config", configPath, "copy", remotePath, localTarget, "--local-no-preallocate")
            }
        }

        val defaultArgs = mutableListOf(
            "--progress",
            "--ignore-size",
        )

        if (isWebdavProjectRemote(transferRoot.configPath)) {
            defaultArgs += "--ignore-times"
        }

        defaultArgs += listOf(
            "--transfers",
            settings.transfers.coerceAtLeast(1).toString(),
            "--checkers",
            settings.checkers.coerceAtLeast(1).toString(),
        )

        return (args + defaultArgs).joinToString(" ") { shellQuote(it) }
    }

    private fun openTerminal(project: Project, workingDirectory: String, command: String) {
        val terminal = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workingDirectory, pluginTitle)
        terminal.executeCommand(command)
    }

    private fun remoteParent(remoteRoot: String, relativePath: String): String {
        val normalized = relativePath.replace('\\', '/')
        val slashIndex = normalized.lastIndexOf('/')

        return if (slashIndex == -1) remoteRoot else "$remoteRoot/${normalized.substring(0, slashIndex)}"
    }

    private fun joinRemotePath(remoteRoot: String, relativePath: String): String =
        "$remoteRoot/${relativePath.replace('\\', '/').trimStart('/')}"

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"
}

private fun isWebdavProjectRemote(configPath: Path): Boolean {
    val sections = parseRcloneConfig(Files.readString(configPath))

    return resolveRcloneRemoteType(sections, "my-project") == "webdav"
}

private fun parseRcloneConfig(text: String): Map<String, Map<String, String>> {
    val sections = mutableMapOf<String, MutableMap<String, String>>()
    var currentSection: MutableMap<String, String>? = null

    for (rawLine in text.lines()) {
        val line = rawLine.trim()

        if (line.isBlank() || line.startsWith("#") || line.startsWith(";")) {
            continue
        }

        val sectionMatch = Regex("""^\[([^\]]+)\]$""").matchEntire(line)

        if (sectionMatch != null) {
            currentSection = mutableMapOf()
            sections[sectionMatch.groupValues[1].trim()] = currentSection
            continue
        }

        val equalsIndex = line.indexOf("=")

        if (currentSection == null || equalsIndex == -1) {
            continue
        }

        val key = line.substring(0, equalsIndex).trim().lowercase()
        val value = line.substring(equalsIndex + 1).trim()
        currentSection[key] = value
    }

    return sections
}

private fun resolveRcloneRemoteType(sections: Map<String, Map<String, String>>, remoteName: String): String {
    val visited = mutableSetOf<String>()
    var currentName = rcloneRemoteName(remoteName)

    while (currentName.isNotBlank() && currentName !in visited) {
        visited += currentName

        val section = sections[currentName] ?: return ""
        val type = section["type"]?.lowercase() ?: return ""

        if (type != "alias") {
            return type
        }

        currentName = rcloneRemoteName(section["remote"].orEmpty())
    }

    return ""
}

private fun rcloneRemoteName(remote: String): String =
    remote.trim().substringBefore(":")

private data class TransferRoot(
    val rootPath: Path,
    val configPath: Path,
    val relativePath: String,
)

private fun findFirstFileWithFatalProblemsOrContinue(project: Project, file: VirtualFile): VirtualFile? =
    try {
        findFirstFileWithFatalProblems(project, file)
    } catch (_: Exception) {
        null
    }

private fun findFirstFileWithFatalProblems(project: Project, file: VirtualFile): VirtualFile? {
    if (!file.isDirectory) {
        return if (hasFatalProblems(project, file)) file else null
    }

    for (child in file.children) {
        val blockingFile = findFirstFileWithFatalProblems(project, child)

        if (blockingFile != null) {
            return blockingFile
        }
    }

    return null
}

private fun hasFatalProblems(project: Project, file: VirtualFile): Boolean =
    ApplicationManager.getApplication().runReadAction<Boolean> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runReadAction false

        DaemonCodeAnalyzerImpl
            .getHighlights(document, HighlightSeverity.ERROR, project)
            .any { it.severity == HighlightSeverity.ERROR }
    }

private fun makeRclonePasswd(projectRoot: Path, configPath: Path) {
    if (!Files.exists(configPath)) {
        return
    }

    val text = Files.readString(configPath)

    if (!Regex("""(?m)^\s*pass-visible\s*=""").containsMatchIn(text)) {
        return
    }

    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
    val lines = text.split(Regex("""\r?\n""")).toMutableList()
    var changed = false

    configSections(lines).forEach { section ->
        val visibleIndex = lines.indexOfFirstInRange(section.first, section.second) {
            Regex("""^\s*pass-visible\s*=""").containsMatchIn(it)
        }

        if (visibleIndex == -1) {
            return@forEach
        }

        val visiblePassword = lines[visibleIndex].replace(Regex("""^\s*pass-visible\s*=\s*"""), "")
        val obscuredPassword = obscureRclonePassword(visiblePassword, projectRoot)
        val indent = Regex("""^(\s*)""").find(lines[visibleIndex])?.groupValues?.get(1).orEmpty()

        for (index in section.second - 1 downTo section.first) {
            if (Regex("""^\s*pass\s*=""").containsMatchIn(lines[index])) {
                lines.removeAt(index)
            }
        }

        val adjustedVisibleIndex = lines.indexOfFirstInRange(section.first, lines.size) {
            Regex("""^\s*pass-visible\s*=""").containsMatchIn(it)
        }
        lines.add(adjustedVisibleIndex + 1, "${indent}pass = ${obscuredPassword}")
        changed = true
    }

    if (changed) {
        Files.writeString(configPath, lines.joinToString(newline))
    }
}

private fun obscureRclonePassword(password: String, cwd: Path): String {
    val process = ProcessBuilder("rclone", "obscure", password)
        .directory(cwd.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
        throw IllegalStateException("Could not run rclone obscure.")
    }

    return output
}

private fun configSections(lines: List<String>): List<Pair<Int, Int>> {
    val starts = lines.indices.filter { Regex("""^\s*\[[^\]]+]\s*$""").matches(lines[it]) }

    if (starts.isEmpty()) {
        return listOf(0 to lines.size)
    }

    return starts.mapIndexed { index, start ->
        val end = starts.getOrNull(index + 1) ?: lines.size
        start to end
    }
}

private fun List<String>.indexOfFirstInRange(start: Int, end: Int, predicate: (String) -> Boolean): Int {
    for (index in start until end.coerceAtMost(size)) {
        if (predicate(this[index])) {
            return index
        }
    }

    return -1
}
