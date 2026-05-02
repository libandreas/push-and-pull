const path = require("path");
const fs = require("fs/promises");
const { execFile } = require("child_process");
const { promisify } = require("util");
const vscode = require("vscode");

const execFileAsync = promisify(execFile);
let terminal;

function activate(context) {
	context.subscriptions.push(
		vscode.commands.registerCommand("pushR.uploadFile", (uri) => {
			runRclone("upload", uri);
		}),
		vscode.commands.registerCommand("pushR.downloadFile", (uri) => {
			runRclone("download", uri);
		}),
		vscode.commands.registerCommand("pushR.uploadFolder", (uri) => {
			runRclone("upload", uri, vscode.FileType.Directory);
		}),
		vscode.commands.registerCommand("pushR.downloadFolder", (uri) => {
			runRclone("download", uri, vscode.FileType.Directory);
		})
	);
}

async function runRclone(action, uri, expectedType = vscode.FileType.File) {
	const fileUri = getFileUri(uri);

	if (!fileUri) {
		vscode.window.showWarningMessage(expectedType === vscode.FileType.Directory
			? "Select a folder first."
			: "Open or select a file first.");
		return;
	}

	const workspaceFolder = vscode.workspace.getWorkspaceFolder(fileUri);

	if (!workspaceFolder) {
		vscode.window.showWarningMessage("The selected file is not inside this workspace.");
		return;
	}

	const stat = await vscode.workspace.fs.stat(fileUri);

	if (stat.type !== expectedType) {
		vscode.window.showWarningMessage(expectedType === vscode.FileType.Directory
			? "This action is available for folders only."
			: "This action is available for files only.");
		return;
	}

	const workspacePath = workspaceFolder.uri.fsPath;
	const relativePath = path.relative(workspacePath, fileUri.fsPath);
	const shellKind = getShellKind();

	if (!relativePath || relativePath.startsWith("..") || path.isAbsolute(relativePath)) {
		vscode.window.showWarningMessage("Could not make a workspace-relative file path.");
		return;
	}

	try {
		await makeRclonePasswd(workspacePath);
	} catch (error) {
		vscode.window.showErrorMessage(`Could not update rclone password: ${error.message}`);
		return;
	}

	let command;

	try {
		command = action === "upload"
			? buildUploadCommand(workspacePath, relativePath, shellKind, expectedType)
			: buildDownloadCommand(workspacePath, relativePath, shellKind, expectedType);
	} catch (error) {
		vscode.window.showErrorMessage(error.message);
		return;
	}

	getTerminal().show();
	getTerminal().sendText(command);
}

function getFileUri(uri) {
	if (uri instanceof vscode.Uri) {
		return uri;
	}

	const activeEditor = vscode.window.activeTextEditor;

	if (activeEditor?.document?.uri?.scheme === "file") {
		return activeEditor.document.uri;
	}

	return undefined;
}

async function makeRclonePasswd(workspacePath) {
	const configFile = path.join(workspacePath, "rclone.conf");
	let text;

	try {
		text = await fs.readFile(configFile, "utf8");
	} catch (error) {
		if (error.code === "ENOENT") {
			return;
		}

		throw error;
	}

	if (!/^\s*pass-visible\s*=/m.test(text)) {
		return;
	}

	const newline = text.includes("\r\n") ? "\r\n" : "\n";
	const lines = text.split(/\r?\n/);
	let changed = false;

	for (const section of getConfigSections(lines)) {
		const visibleIndex = findLineIndex(lines, section.start, section.end, /^\s*pass-visible\s*=/);

		if (visibleIndex === -1) {
			continue;
		}

		const visiblePassword = lines[visibleIndex].replace(/^\s*pass-visible\s*=\s*/, "");
		const obscuredPassword = await obscureRclonePassword(visiblePassword, workspacePath);
		const indent = lines[visibleIndex].match(/^(\s*)/)?.[1] || "";

		for (let index = section.end - 1; index >= section.start; index -= 1) {
			if (/^\s*pass\s*=/.test(lines[index])) {
				lines.splice(index, 1);
				section.end -= 1;
				if (index < visibleIndex) {
					section.visibleIndexAdjustment = (section.visibleIndexAdjustment || 0) - 1;
				}
			}
		}

		const adjustedVisibleIndex = findLineIndex(lines, section.start, section.end, /^\s*pass-visible\s*=/);
		lines.splice(adjustedVisibleIndex + 1, 0, `${indent}pass = ${obscuredPassword}`);
		changed = true;
	}

	if (changed) {
		await fs.writeFile(configFile, lines.join(newline), "utf8");
	}
}

function getConfigSections(lines) {
	const sections = [];
	let currentStart = 0;

	for (let index = 0; index < lines.length; index += 1) {
		if (/^\s*\[[^\]]+\]\s*$/.test(lines[index])) {
			if (index > currentStart) {
				sections.push({ start: currentStart, end: index });
			}

			currentStart = index;
		}
	}

	sections.push({ start: currentStart, end: lines.length });
	return sections;
}

function findLineIndex(lines, start, end, pattern) {
	for (let index = start; index < end; index += 1) {
		if (pattern.test(lines[index])) {
			return index;
		}
	}

	return -1;
}

async function obscureRclonePassword(password, cwd) {
	const { stdout } = await execFileAsync("rclone", ["obscure", password], {
		cwd,
		windowsHide: true
	});

	return stdout.trim();
}

function buildUploadCommand(workspacePath, relativePath, shellKind, resourceType) {
	const configFile = path.join(workspacePath, "rclone.conf");
	const localPath = path.join(workspacePath, relativePath);
	const remoteRoot = getRemoteRoot();
	const remoteDir = resourceType === vscode.FileType.Directory
		? joinRemotePath(remoteRoot, relativePath)
		: getRemoteDir(remoteRoot, relativePath);

	return [
		"rclone",
		"--config",
		quoteShellArg(configFile, shellKind),
		"copy",
		quoteShellArg(localPath, shellKind),
		quoteShellArg(remoteDir, shellKind),
		...getDefaultRcloneArgs()
	].join(" ");
}

function buildDownloadCommand(workspacePath, relativePath, shellKind, resourceType) {
	const configFile = path.join(workspacePath, "rclone.conf");
	const remotePath = joinRemotePath(getRemoteRoot(), relativePath);
	const localDir = resourceType === vscode.FileType.Directory
		? path.join(workspacePath, relativePath)
		: getLocalDir(workspacePath, relativePath);

	return [
		"rclone",
		"--config",
		quoteShellArg(configFile, shellKind),
		"copy",
		quoteShellArg(remotePath, shellKind),
		quoteShellArg(localDir, shellKind),
		...getDefaultRcloneArgs()
	].join(" ");
}

function getRemoteDir(remoteRoot, relativePath) {
	const remoteParent = toRemotePath(path.dirname(relativePath));

	return remoteParent === "." ? remoteRoot : joinRemotePath(remoteRoot, remoteParent);
}

function getLocalDir(workspacePath, relativePath) {
	const localParent = path.dirname(relativePath);

	return localParent === "." ? workspacePath : path.join(workspacePath, localParent);
}

function toRemotePath(value) {
	return value.split(path.sep).join("/");
}

function getRemoteRoot() {
	return "my-project:";
}

function joinRemotePath(remoteRoot, relativePath) {
	const cleanPath = toRemotePath(relativePath).replace(/^\/+/, "");

	if (!cleanPath || cleanPath === ".") {
		return remoteRoot;
	}

	return `${remoteRoot}/${cleanPath}`;
}

function getDefaultRcloneArgs() {
	const config = vscode.workspace.getConfiguration("pushPull");
	const transfers = Math.max(1, Number(config.get("transfers", 4)) || 4);
	const checkers = Math.max(1, Number(config.get("checkers", 8)) || 8);

	return [
		"--progress",
		"--transfers",
		String(transfers),
		"--checkers",
		String(checkers)
	];
}

function quoteShellArg(value, shellKind) {
	const text = normalizePathForShell(String(value), shellKind);

	if (shellKind === "cmd") {
		return `"${text.replace(/%/g, "%%").replace(/"/g, '""')}"`;
	}

	return `'${text.replace(/'/g, "'\"'\"'")}'`;
}

function normalizePathForShell(value, shellKind) {
	if (process.platform === "win32" && shellKind === "posix" && /^[a-zA-Z]:\\/.test(value)) {
		return value.replace(/\\/g, "/");
	}

	return value;
}

function getShellKind() {
	const terminalConfig = vscode.workspace.getConfiguration("terminal.integrated");
	const platformKey = process.platform === "win32"
		? "windows"
		: process.platform === "darwin" ? "osx" : "linux";
	const defaultProfile = terminalConfig.get(`defaultProfile.${platformKey}`);
	const automationProfile = terminalConfig.get(`automationProfile.${platformKey}`);
	const shellName = String(defaultProfile || automationProfile?.path || automationProfile?.source || "").toLowerCase();

	if (process.platform === "win32") {
		if (shellName.includes("cmd")) {
			return "cmd";
		}

		if (shellName.includes("bash") || shellName.includes("zsh") || shellName.includes("fish") || shellName.includes("sh")) {
			return "posix";
		}

		return "powershell";
	}

	return "posix";
}

function getTerminal() {
	if (!terminal || terminal.exitStatus) {
		terminal = vscode.window.createTerminal("Push & Pull");
	}

	return terminal;
}

function deactivate() { }

module.exports = {
	activate,
	deactivate
};
