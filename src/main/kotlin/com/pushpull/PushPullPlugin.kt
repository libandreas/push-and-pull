package com.pushpull

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.panel
import org.jetbrains.plugins.terminal.TerminalToolWindowManager
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.relativeTo
import javax.swing.JComponent

@Service(Service.Level.APP)
@State(name = "PushPullSettings", storages = [Storage("push-pull.xml")])
class PushPullSettings : PersistentStateComponent<PushPullSettings.State> {
    data class State(
        var transfers: Int = 4,
        var checkers: Int = 8,
    )

    private var currentState = State()

    override fun getState(): State = currentState

    override fun loadState(state: State) {
        currentState = state
    }

    companion object {
        fun getInstance(): PushPullSettings =
            ApplicationManager.getApplication().getService(PushPullSettings::class.java)
    }
}

class PushPullConfigurable : Configurable {
    private val settings = PushPullSettings.getInstance()
    private var transfers = settings.state.transfers
    private var checkers = settings.state.checkers

    override fun getDisplayName(): String = "Push & Pull"

    override fun createComponent(): JComponent = panel {
        group("Rclone Args") {
            row("Transfers") {
                intTextField(1..128)
                    .bindIntText(::transfers)
                    .comment("rclone --transfers. Default: 4.")
            }
            row("Checkers") {
                intTextField(1..256)
                    .bindIntText(::checkers)
                    .comment("rclone --checkers. Default: 8.")
            }
        }
    }

    override fun isModified(): Boolean =
        transfers != settings.state.transfers || checkers != settings.state.checkers

    override fun apply() {
        settings.state.transfers = transfers.coerceAtLeast(1)
        settings.state.checkers = checkers.coerceAtLeast(1)
    }

    override fun reset() {
        transfers = settings.state.transfers
        checkers = settings.state.checkers
    }
}

class PushFileAction : PushPullAction(Direction.Push, Target.File)
class PullFileAction : PushPullAction(Direction.Pull, Target.File)
class PushFolderAction : PushPullAction(Direction.Push, Target.Folder)
class PullFolderAction : PushPullAction(Direction.Pull, Target.Folder)

abstract class PushPullAction(
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
        val basePath = project.basePath

        if (basePath.isNullOrBlank()) {
            Messages.showWarningDialog(project, "Project base path was not found.", "Push & Pull")
            return
        }

        try {
            makeRclonePasswd(Path.of(basePath))
            openTerminal(project, basePath, buildRcloneCommand(Path.of(basePath), file))
        } catch (error: Exception) {
            Messages.showErrorDialog(project, error.message ?: "Push & Pull failed.", "Push & Pull")
        }
    }

    private fun buildRcloneCommand(projectRoot: Path, file: VirtualFile): String {
        val settings = PushPullSettings.getInstance().state
        val localPath = Path.of(file.path)
        val relativePath = localPath.relativeTo(projectRoot).toString()
        val remotePath = joinRemotePath("my-project:", relativePath)
        val configPath = projectRoot.resolve("rclone.conf").toString()

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
                    Target.File -> localPath.parent?.toString() ?: projectRoot.toString()
                    Target.Folder -> localPath.toString()
                }
                listOf("rclone", "--config", configPath, "copy", remotePath, localTarget)
            }
        }

        return (args + listOf(
            "--progress",
            "--transfers",
            settings.transfers.coerceAtLeast(1).toString(),
            "--checkers",
            settings.checkers.coerceAtLeast(1).toString(),
        )).joinToString(" ") { shellQuote(it) }
    }

    private fun openTerminal(project: Project, workingDirectory: String, command: String) {
        val terminal = TerminalToolWindowManager.getInstance(project)
            .createLocalShellWidget(workingDirectory, "Push & Pull")
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

private fun makeRclonePasswd(projectRoot: Path) {
    val configPath = projectRoot.resolve("rclone.conf")

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
