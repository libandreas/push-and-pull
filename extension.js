const path = require("path");
const fs = require("fs/promises");
const { execFile } = require("child_process");
const { promisify } = require("util");
const vscode = require("vscode");

const execFileAsync = promisify(execFile);
let terminal;

function activate(context) {
	context.subscriptions.push(
		vscode.commands.registerCommand("pushR.uploadFile", (uri, selectedUris) => {
			runRcloneMany("upload", uri, selectedUris, vscode.FileType.File);
		}),
		vscode.commands.registerCommand("pushR.downloadFile", (uri, selectedUris) => {
			runRcloneMany("download", uri, selectedUris, vscode.FileType.File);
		}),
		vscode.commands.registerCommand("pushR.uploadFolder", (uri, selectedUris) => {
			runRcloneMany("upload", uri, selectedUris, vscode.FileType.Directory);
		}),
		vscode.commands.registerCommand("pushR.downloadFolder", (uri, selectedUris) => {
			runRcloneMany("download", uri, selectedUris, vscode.FileType.Directory);
		})
	);
}

async function runRcloneMany(action, uri, selectedUris, expectedType = vscode.FileType.File) {
	const itemUris = getSelectedFileUris(uri, selectedUris);

	if (!itemUris.length) {
		vscode.window.showWarningMessage(expectedType === vscode.FileType.Directory
			? "Select a folder first."
			: "Open or select a file first.");
		return;
	}

	const items = [];

	const shellKind = getShellKind();

	for (const itemUri of itemUris) {
		const workspaceFolder = vscode.workspace.getWorkspaceFolder(itemUri);

		if (!workspaceFolder) {
			vscode.window.showWarningMessage("Every selected item must be inside this workspace.");
			return;
		}

		const stat = await vscode.workspace.fs.stat(itemUri);

		if (stat.type !== expectedType) {
			vscode.window.showWarningMessage(expectedType === vscode.FileType.Directory
				? "Please select only folders for this action."
				: "Please select only files for this action.");
			return;
		}

		const workspacePath = workspaceFolder.uri.fsPath;
		const relativePath = path.relative(workspacePath, itemUri.fsPath);

		if (!relativePath || relativePath.startsWith("..") || path.isAbsolute(relativePath)) {
			vscode.window.showWarningMessage("Could not make a workspace-relative file path.");
			return;
		}

		items.push({
			uri: itemUri,
			workspacePath,
			relativePath
		});
	}

	if (action === "upload" && !getPushErrorsEnabled()) {
		const blockingDiagnostics = getUploadBlockingDiagnostics(items, expectedType);

		if (blockingDiagnostics.length) {
			vscode.window.showErrorMessage(formatUploadBlockedMessage(blockingDiagnostics, expectedType));
			return;
		}
	}

	const workspacePaths = [...new Set(items.map((item) => item.workspacePath))];

	try {
		for (const workspacePath of workspacePaths) {
			await makeRclonePasswd(workspacePath);
		}
	} catch (error) {
		vscode.window.showErrorMessage(`Could not update rclone password: ${error.message}`);
		return;
	}

	const commands = [];

	try {
		for (const item of items) {
			commands.push(action === "upload"
				? buildUploadCommand(item.workspacePath, item.relativePath, shellKind, expectedType)
				: buildDownloadCommand(item.workspacePath, item.relativePath, shellKind, expectedType));
		}
	} catch (error) {
		vscode.window.showErrorMessage(error.message);
		return;
	}

	const terminal = getTerminal();
	terminal.show();

	for (const command of commands) {
		terminal.sendText(command);
	}
}

function getSelectedFileUris(uri, selectedUris) {
	if (Array.isArray(selectedUris) && selectedUris.length) {
		return selectedUris.filter((selectedUri) => selectedUri instanceof vscode.Uri);
	}

	if (uri instanceof vscode.Uri) {
		return [uri];
	}

	const activeEditor = vscode.window.activeTextEditor;

	if (activeEditor?.document?.uri?.scheme === "file") {
		return [activeEditor.document.uri];
	}

	return [];
}

function getUploadBlockingDiagnostics(items, resourceType) {
	if (resourceType === vscode.FileType.File) {
		return items.flatMap((item) => vscode.languages
			.getDiagnostics(item.uri)
			.filter(isErrorDiagnostic)
			.map((diagnostic) => ({
				uri: item.uri,
				relativePath: item.relativePath,
				diagnostic
			})));
	}

	const selectedFolders = items.map((item) => ({
		uri: item.uri,
		workspacePath: item.workspacePath,
		relativePath: item.relativePath
	}));
	const blockingDiagnostics = [];

	for (const [resourceUri, diagnostics] of vscode.languages.getDiagnostics()) {
		if (resourceUri.scheme !== "file" || !diagnostics.some(isErrorDiagnostic)) {
			continue;
		}

		const selectedFolder = selectedFolders.find((item) => isSameOrChildPath(resourceUri.fsPath, item.uri.fsPath));

		if (!selectedFolder) {
			continue;
		}

		const relativePath = path.relative(selectedFolder.workspacePath, resourceUri.fsPath) || selectedFolder.relativePath;

		for (const diagnostic of diagnostics.filter(isErrorDiagnostic)) {
			blockingDiagnostics.push({
				uri: resourceUri,
				relativePath,
				diagnostic
			});
		}
	}

	return blockingDiagnostics;
}

function isErrorDiagnostic(diagnostic) {
	return diagnostic.severity === vscode.DiagnosticSeverity.Error;
}

function isSameOrChildPath(candidatePath, parentPath) {
	const relativePath = path.relative(normalizeFsPath(parentPath), normalizeFsPath(candidatePath));

	return relativePath === "" || (!!relativePath && !relativePath.startsWith("..") && !path.isAbsolute(relativePath));
}

function normalizeFsPath(value) {
	const normalizedPath = path.normalize(value);

	return process.platform === "win32" ? normalizedPath.toLowerCase() : normalizedPath;
}

function formatUploadBlockedMessage(blockingDiagnostics, resourceType) {
	const errorCount = blockingDiagnostics.length;
	const firstDiagnostic = blockingDiagnostics[0];
	const scope = resourceType === vscode.FileType.Directory ? "selected folder" : "selected file";
	const plural = errorCount === 1 ? "" : "s";

	return `Errors found in VS Code Problems. Upload skipped. ${errorCount} error${plural} in the ${scope}: ${firstDiagnostic.relativePath}. You can allow this in Settings by enabling Push & Pull: Push Errors.`;
}

function getPushErrorsEnabled() {
	return Boolean(vscode.workspace.getConfiguration("pushPull").get("pushErrors", false));
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
