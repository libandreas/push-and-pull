const path = require("path");
const os = require("os");
const fs = require("fs");
const fsPromises = require("fs/promises");
const https = require("https");
const { spawn, execFile } = require("child_process");
const { promisify } = require("util");
const AdmZip = require("adm-zip");
const vscode = require("vscode");

const execFileAsync = promisify(execFile);

const RCLONE_DOWNLOAD_HOST = "downloads.rclone.org";
const RCLONE_CURRENT_VERSION_PATH = "/version.txt";
const RCLONE_DOWNLOAD_TIMEOUT_MS = 30000;
const RCLONE_PROGRESS_THROTTLE_MS = 250;
const RCLONE_UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000;
const RCLONE_FINISH_MESSAGE_MS = 1000;
const PLATFORM_ARCHIVE_NAMES = {
	win32: {
		amd64: "windows-amd64",
		"386": "windows-386",
		arm64: "windows-arm64"
	},
	linux: {
		amd64: "linux-amd64",
		"386": "linux-386",
		arm64: "linux-arm64",
		arm: "linux-arm",
		"arm-v6": "linux-arm-v6",
		"arm-v7": "linux-arm-v7",
		loong64: "linux-loong64",
		ppc64: "linux-ppc64",
		ppc64le: "linux-ppc64le",
		riscv64: "linux-riscv64",
		s390x: "linux-s390x",
		mips: "linux-mips",
		mipsle: "linux-mipsle",
		mips64: "linux-mips64",
		mips64le: "linux-mips64le"
	},
	darwin: {
		amd64: "osx-amd64",
		arm64: "osx-arm64"
	},
	freebsd: {
		amd64: "freebsd-amd64",
		"386": "freebsd-386",
		arm: "freebsd-arm",
		"arm-v6": "freebsd-arm-v6",
		"arm-v7": "freebsd-arm-v7"
	},
	openbsd: {
		amd64: "openbsd-amd64",
		"386": "openbsd-386"
	},
	netbsd: {
		amd64: "netbsd-amd64",
		"386": "netbsd-386",
		arm: "netbsd-arm",
		"arm-v6": "netbsd-arm-v6",
		"arm-v7": "netbsd-arm-v7"
	},
	sunos: {
		amd64: "solaris-amd64"
	},
	aix: {
		ppc64: "aix-ppc64"
	}
};

let extensionContext;
let statusBarItem;
let rcloneReadyPromise;

function activate(context) {
	extensionContext = context;
	statusBarItem = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 100);
	context.subscriptions.push(
		statusBarItem,
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
	try {
		const itemUris = getSelectedFileUris(uri, selectedUris);

		if (!itemUris.length) {
			vscode.window.showWarningMessage(expectedType === vscode.FileType.Directory
				? "Select a folder first."
				: "Open or select a file first.");
			return;
		}

		const items = [];

		for (const itemUri of itemUris) {
			const stat = await vscode.workspace.fs.stat(itemUri);

			if (stat.type !== expectedType) {
				vscode.window.showWarningMessage(expectedType === vscode.FileType.Directory
					? "Please select only folders for this action."
					: "Please select only files for this action.");
				return;
			}

			let transferRoot;

			try {
				transferRoot = await resolveTransferRoot(itemUri, expectedType);
			} catch (error) {
				vscode.window.showErrorMessage(error.message);
				return;
			}

			if (!transferRoot) {
				vscode.window.showWarningMessage("Could not find rclone.conf for this item. Put rclone.conf in this file's project root or open the correct workspace.");
				return;
			}

			items.push({
				uri: itemUri,
				rootPath: transferRoot.rootPath,
				configFile: transferRoot.configFile,
				relativePath: transferRoot.relativePath
			});
		}

		await vscode.window.withProgress({
			location: vscode.ProgressLocation.Notification,
			cancellable: false
		}, async (progress) => {
			try {
				setStatusBar(action === "upload" ? "$(arrow-up) Uploading..." : "$(arrow-down) Downloading...");
				progress.report({ message: "Checking rclone..." });

				const rclonePath = await ensureLatestRclone(progress);
				const transferRoots = [...new Map(items.map((item) => [item.configFile, item])).values()];

				for (const item of transferRoots) {
					progress.report({ message: `Preparing ${path.basename(item.configFile)}...` });
					await makeRclonePasswd(rclonePath, item.rootPath, item.configFile);
				}

				for (let index = 0; index < items.length; index += 1) {
					const item = items[index];
					const itemLabel = items.length > 1
						? `${index + 1}/${items.length} ${item.relativePath}`
						: item.relativePath;

					progress.report({
						message: `${action === "upload" ? "Starting upload" : "Starting download"}: ${itemLabel}`
					});

					await runTransfer({
						action,
						expectedType,
						item,
						itemLabel,
						progress,
						rclonePath
					});
				}

				progress.report({
					message: action === "upload" ? "Upload finished." : "Download finished."
				});
				await delay(RCLONE_FINISH_MESSAGE_MS);
			} catch (error) {
				progress.report({
					message: `${action === "upload" ? "Upload failed" : "Download failed"}: ${error.message}`
				});
				await delay(RCLONE_FINISH_MESSAGE_MS);
			}
		});

		clearStatusBar();
	} catch (error) {
		clearStatusBar();
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

async function resolveTransferRoot(itemUri, resourceType) {
	const workspaceFolder = vscode.workspace.getWorkspaceFolder(itemUri);
	const rootPath = workspaceFolder?.uri.fsPath || await findRcloneRoot(itemUri.fsPath, resourceType);

	if (!rootPath) {
		return undefined;
	}

	const relativePath = path.relative(rootPath, itemUri.fsPath);

	if (!relativePath || relativePath.startsWith("..") || path.isAbsolute(relativePath)) {
		throw new Error("Could not make a project-relative file path.");
	}

	return {
		rootPath,
		configFile: path.join(rootPath, "rclone.conf"),
		relativePath
	};
}

async function findRcloneRoot(itemPath, resourceType) {
	let currentPath = resourceType === vscode.FileType.Directory ? itemPath : path.dirname(itemPath);

	while (true) {
		const configFile = path.join(currentPath, "rclone.conf");

		try {
			await fsPromises.access(configFile);
			return currentPath;
		} catch (error) {
			if (error.code !== "ENOENT") {
				throw error;
			}
		}

		const parentPath = path.dirname(currentPath);

		if (parentPath === currentPath) {
			return undefined;
		}

		currentPath = parentPath;
	}
}

async function makeRclonePasswd(rclonePath, rootPath, configFile) {
	let text;

	try {
		text = await fsPromises.readFile(configFile, "utf8");
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
		const obscuredPassword = await obscureRclonePassword(rclonePath, visiblePassword, rootPath);
		const indent = lines[visibleIndex].match(/^(\s*)/)?.[1] || "";

		for (let index = section.end - 1; index >= section.start; index -= 1) {
			if (/^\s*pass\s*=/.test(lines[index])) {
				lines.splice(index, 1);
				section.end -= 1;
			}
		}

		const adjustedVisibleIndex = findLineIndex(lines, section.start, section.end, /^\s*pass-visible\s*=/);
		lines.splice(adjustedVisibleIndex + 1, 0, `${indent}pass = ${obscuredPassword}`);
		changed = true;
	}

	if (changed) {
		await fsPromises.writeFile(configFile, lines.join(newline), "utf8");
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

async function obscureRclonePassword(rclonePath, password, cwd) {
	const { stdout } = await execFileAsync(rclonePath, ["obscure", password], {
		cwd,
		windowsHide: true
	});

	return stdout.trim();
}

async function runTransfer({ action, expectedType, item, itemLabel, progress, rclonePath }) {
	const rcloneArgs = await getDefaultRcloneArgs(item.configFile);
	const args = action === "upload"
		? buildUploadArgs(item.rootPath, item.configFile, item.relativePath, expectedType, rcloneArgs)
		: buildDownloadArgs(item.rootPath, item.configFile, item.relativePath, expectedType, rcloneArgs);

	await runRcloneCommand({
		rclonePath,
		args,
		cwd: item.rootPath,
		progress,
		action,
		itemLabel
	});
}

function buildUploadArgs(rootPath, configFile, relativePath, resourceType, rcloneArgs) {
	const localPath = path.join(rootPath, relativePath);
	const remoteRoot = getRemoteRoot();
	const remoteDir = resourceType === vscode.FileType.Directory
		? joinRemotePath(remoteRoot, relativePath)
		: getRemoteDir(remoteRoot, relativePath);

	return [
		"--config",
		configFile,
		"copy",
		localPath,
		remoteDir,
		...rcloneArgs
	];
}

function buildDownloadArgs(rootPath, configFile, relativePath, resourceType, rcloneArgs) {
	const remotePath = joinRemotePath(getRemoteRoot(), relativePath);
	const localDir = resourceType === vscode.FileType.Directory
		? path.join(rootPath, relativePath)
		: getLocalDir(rootPath, relativePath);

	return [
		"--config",
		configFile,
		"copy",
		remotePath,
		localDir,
		"--local-no-preallocate",
		...rcloneArgs
	];
}

function getRemoteDir(remoteRoot, relativePath) {
	const remoteParent = toRemotePath(path.dirname(relativePath));

	return remoteParent === "." ? remoteRoot : joinRemotePath(remoteRoot, remoteParent);
}

function getLocalDir(rootPath, relativePath) {
	const localParent = path.dirname(relativePath);

	return localParent === "." ? rootPath : path.join(rootPath, localParent);
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

async function getDefaultRcloneArgs(configFile) {
	const config = vscode.workspace.getConfiguration("pushPull");
	const transfers = Math.max(1, Number(config.get("transfers", 4)) || 4);
	const checkers = Math.max(1, Number(config.get("checkers", 8)) || 8);
	const args = [
		"--progress",
		"--stats",
		"1s",
		"--stats-one-line",
		"--ignore-size"
	];

	if (await isWebdavProjectRemote(configFile)) {
		args.push("--ignore-times");
	}

	return [
		...args,
		"--transfers",
		String(transfers),
		"--checkers",
		String(checkers)
	];
}

async function isWebdavProjectRemote(configFile) {
	const text = await fsPromises.readFile(configFile, "utf8");
	const sections = parseRcloneConfig(text);

	return resolveRcloneRemoteType(sections, "my-project") === "webdav";
}

function parseRcloneConfig(text) {
	const sections = new Map();
	let currentSection;

	for (const rawLine of text.split(/\r?\n/)) {
		const line = rawLine.trim();

		if (!line || line.startsWith("#") || line.startsWith(";")) {
			continue;
		}

		const sectionMatch = line.match(/^\[([^\]]+)\]$/);

		if (sectionMatch) {
			currentSection = {};
			sections.set(sectionMatch[1].trim(), currentSection);
			continue;
		}

		const equalsIndex = line.indexOf("=");

		if (!currentSection || equalsIndex === -1) {
			continue;
		}

		const key = line.slice(0, equalsIndex).trim().toLowerCase();
		const value = line.slice(equalsIndex + 1).trim();
		currentSection[key] = value;
	}

	return sections;
}

function resolveRcloneRemoteType(sections, remoteName) {
	let currentName = getRcloneRemoteName(remoteName);
	const visited = new Set();

	while (currentName && !visited.has(currentName)) {
		visited.add(currentName);

		const section = sections.get(currentName);
		const type = section?.type?.toLowerCase();

		if (!section || !type) {
			return "";
		}

		if (type !== "alias") {
			return type;
		}

		currentName = getRcloneRemoteName(section.remote);
	}

	return "";
}

function getRcloneRemoteName(remote) {
	return String(remote || "").trim().split(":")[0];
}

async function runRcloneCommand({ rclonePath, args, cwd, progress, action, itemLabel }) {
	await new Promise((resolve, reject) => {
		const child = spawn(rclonePath, args, {
			cwd,
			windowsHide: true
		});
		let stdoutText = "";
		let stderrText = "";
		let lastProgressAt = 0;
		let stdoutPending = "";
		let stderrPending = "";

		const reportLine = (line, sinkName) => {
			const cleanLine = line.trim();

			if (!cleanLine) {
				return;
			}

			if (sinkName === "stdout") {
				stdoutText += `${cleanLine}\n`;
			} else {
				stderrText += `${cleanLine}\n`;
			}

			if (Date.now() - lastProgressAt < RCLONE_PROGRESS_THROTTLE_MS) {
				return;
			}

			lastProgressAt = Date.now();
			const compactLine = cleanLine.replace(/\s+/g, " ").slice(0, 140);
			progress.report({ message: compactLine });
			setStatusBar(action === "upload"
				? `$(arrow-up) Uploading: ${itemLabel}`
				: `$(arrow-down) Downloading: ${itemLabel}`);
		};

		const consumeChunk = (chunk, pendingKey) => {
			let pending = pendingKey === "stdout" ? stdoutPending : stderrPending;
			pending += String(chunk || "");
			const parts = pending.split(/\r\n|[\r\n]/);
			pending = parts.pop() || "";

			for (const line of parts) {
				reportLine(line, pendingKey);
			}

			if (pendingKey === "stdout") {
				stdoutPending = pending;
			} else {
				stderrPending = pending;
			}
		};

		child.stdout.on("data", (chunk) => {
			consumeChunk(chunk, "stdout");
		});
		child.stderr.on("data", (chunk) => {
			consumeChunk(chunk, "stderr");
		});
		child.on("error", reject);
		child.on("close", (code) => {
			if (stdoutPending.trim()) {
				stdoutText += `${stdoutPending.trim()}\n`;
			}

			if (stderrPending.trim()) {
				stderrText += `${stderrPending.trim()}\n`;
			}

			if (code === 0) {
				resolve();
				return;
			}

			const details = getLastMeaningfulLine(stderrText) || getLastMeaningfulLine(stdoutText) || `rclone exited with code ${code}.`;
			reject(new Error(details));
		});
	});
}

async function ensureLatestRclone(progress) {
	if (!rcloneReadyPromise) {
		rcloneReadyPromise = ensureLatestRcloneInternal(progress).finally(() => {
			rcloneReadyPromise = undefined;
		});
	}

	return rcloneReadyPromise;
}

async function ensureLatestRcloneInternal(progress) {
	const platform = getSupportedPlatform();
	const storageDir = getRcloneStorageDir();
	const versionFile = path.join(storageDir, "version.txt");
	const checkedAtFile = path.join(storageDir, "checked-at.txt");
	const executablePath = path.join(storageDir, platform.binaryName);

	await fsPromises.mkdir(storageDir, { recursive: true });
	const installedVersion = await readTextIfExists(versionFile);
	const hasCachedBinary = await fileExists(executablePath);
	const shouldCheckForUpdates = await shouldCheckForRcloneUpdates(checkedAtFile);

	if (hasCachedBinary && !shouldCheckForUpdates) {
		progress.report({ message: "Using cached rclone..." });
		return executablePath;
	}

	try {
		progress.report({ message: "Checking latest rclone version..." });
		const latestVersion = await downloadText(`https://${RCLONE_DOWNLOAD_HOST}${RCLONE_CURRENT_VERSION_PATH}`);
		await writeUpdateCheckTime(checkedAtFile);

		if (installedVersion === latestVersion && hasCachedBinary) {
			return executablePath;
		}

		const zipName = `rclone-current-${platform.archiveName}.zip`;
		const zipUrl = `https://${RCLONE_DOWNLOAD_HOST}/${zipName}`;
		const zipPath = path.join(storageDir, zipName);

		progress.report({ message: `Downloading ${latestVersion} for ${platform.label}...` });
		await downloadFile(zipUrl, zipPath, progress, `Downloading ${latestVersion}`);

		progress.report({ message: "Extracting rclone..." });
		await extractRcloneBinary(zipPath, executablePath, platform.binaryName);
		await fsPromises.writeFile(versionFile, latestVersion, "utf8");

		return executablePath;
	} catch (error) {
		if (hasCachedBinary) {
			progress.report({ message: "Using cached rclone..." });
			return executablePath;
		}

		throw error;
	}
}

function getSupportedPlatform() {
	const platform = normalizeNodePlatform(process.platform);
	const arch = normalizeNodeArchitecture(process.arch, os.machine());
	const archiveName = PLATFORM_ARCHIVE_NAMES[platform]?.[arch];

	if (!archiveName) {
		throw new Error(`No official rclone download mapping is configured for platform ${platform} ${arch}.`);
	}

	return {
		archiveName,
		binaryName: platform === "win32" ? "rclone.exe" : "rclone",
		label: `${platform} ${arch}`
	};
}

function normalizeNodePlatform(platform) {
	switch (platform) {
		case "win32":
		case "linux":
		case "freebsd":
		case "openbsd":
		case "netbsd":
		case "aix":
			return platform;
		case "darwin":
			return "darwin";
		case "sunos":
			return "sunos";
		default:
			return platform;
	}
}

function normalizeNodeArchitecture(arch, machine) {
	const normalizedMachine = String(machine || "").toLowerCase();

	switch (arch) {
		case "x64":
			return "amd64";
		case "ia32":
			return "386";
		case "arm64":
			return "arm64";
		case "arm":
			if (normalizedMachine.includes("armv7")) {
				return "arm-v7";
			}

			if (normalizedMachine.includes("armv6")) {
				return "arm-v6";
			}

			return "arm";
		case "ppc64":
			return normalizedMachine.includes("ppc64le") ? "ppc64le" : "ppc64";
		case "s390x":
		case "riscv64":
		case "loong64":
		case "mips":
		case "mips64":
			return arch;
		case "mipsel":
			return "mipsle";
		case "mips64el":
			return "mips64le";
		default:
			return arch;
	}
}

function getRcloneStorageDir() {
	const basePath = extensionContext.globalStorageUri.fsPath;
	return path.join(basePath, "rclone");
}

async function extractRcloneBinary(zipPath, executablePath, binaryName) {
	const zip = new AdmZip(zipPath);
	const entry = zip.getEntries().find((item) => path.basename(item.entryName) === binaryName);

	if (!entry) {
		throw new Error("Could not find the rclone binary inside the downloaded archive.");
	}

	await fsPromises.mkdir(path.dirname(executablePath), { recursive: true });
	await fsPromises.writeFile(executablePath, entry.getData());

	if (process.platform !== "win32") {
		await fsPromises.chmod(executablePath, 0o755);
	}

	await fsPromises.unlink(zipPath).catch(() => undefined);
}

async function downloadText(url) {
	const response = await request(url);
	return response.body.toString("utf8").trim();
}

async function downloadFile(url, targetPath, progress, title) {
	await new Promise((resolve, reject) => {
		const requestInstance = https.get(url, {
			timeout: RCLONE_DOWNLOAD_TIMEOUT_MS,
			headers: {
				"User-Agent": "push-and-pull-vscode-extension"
			}
		}, (response) => {
			if (response.statusCode && response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
				response.resume();
				downloadFile(response.headers.location, targetPath, progress, title).then(resolve, reject);
				return;
			}

			if (response.statusCode !== 200) {
				response.resume();
				reject(new Error(`Download failed with status ${response.statusCode}.`));
				return;
			}

			const totalBytes = Number(response.headers["content-length"] || 0);
			let downloadedBytes = 0;
			const fileStream = fs.createWriteStream(targetPath);

			response.on("data", (chunk) => {
				downloadedBytes += chunk.length;

				if (totalBytes > 0) {
					const percent = Math.min(100, Math.round((downloadedBytes / totalBytes) * 100));
					progress.report({
						increment: 0,
						message: `${title}... ${percent}%`
					});
					return;
				}

				progress.report({
					message: `${title}... ${formatBytes(downloadedBytes)}`
				});
			});

			response.on("error", reject);
			fileStream.on("error", reject);
			fileStream.on("finish", resolve);
			response.pipe(fileStream);
		});

		requestInstance.on("timeout", () => {
			requestInstance.destroy(new Error("Request timed out."));
		});
		requestInstance.on("error", reject);
	});
}

async function request(url) {
	return new Promise((resolve, reject) => {
		const requestInstance = https.get(url, {
			timeout: RCLONE_DOWNLOAD_TIMEOUT_MS,
			headers: {
				"User-Agent": "push-and-pull-vscode-extension"
			}
		}, (response) => {
			if (response.statusCode && response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
				response.resume();
				resolve(request(response.headers.location));
				return;
			}

			if (response.statusCode !== 200) {
				response.resume();
				reject(new Error(`Download failed with status ${response.statusCode}.`));
				return;
			}

			const chunks = [];

			response.on("data", (chunk) => {
				chunks.push(chunk);
			});

			response.on("end", () => {
				const body = Buffer.concat(chunks);
				resolve({
					headers: response.headers,
					body
				});
			});

			response.on("error", reject);
		});

		requestInstance.on("timeout", () => {
			requestInstance.destroy(new Error("Request timed out."));
		});
		requestInstance.on("error", reject);
	});
}

async function readTextIfExists(filePath) {
	try {
		return (await fsPromises.readFile(filePath, "utf8")).trim();
	} catch (error) {
		if (error.code === "ENOENT") {
			return "";
		}

		throw error;
	}
}

async function fileExists(filePath) {
	try {
		await fsPromises.access(filePath);
		return true;
	} catch (error) {
		if (error.code === "ENOENT") {
			return false;
		}

		throw error;
	}
}

function delay(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

async function shouldCheckForRcloneUpdates(checkedAtFile) {
	const lastCheckedAt = Number(await readTextIfExists(checkedAtFile));

	if (!lastCheckedAt) {
		return true;
	}

	return Date.now() - lastCheckedAt >= RCLONE_UPDATE_INTERVAL_MS;
}

async function writeUpdateCheckTime(checkedAtFile) {
	await fsPromises.writeFile(checkedAtFile, String(Date.now()), "utf8");
}

function getLastMeaningfulLine(text) {
	return String(text || "")
		.split(/\r?\n/)
		.map((line) => line.trim())
		.filter(Boolean)
		.pop() || "";
}

function formatBytes(value) {
	if (!value) {
		return "0 B";
	}

	const units = ["B", "KB", "MB", "GB"];
	let size = value;
	let unitIndex = 0;

	while (size >= 1024 && unitIndex < units.length - 1) {
		size /= 1024;
		unitIndex += 1;
	}

	return `${size >= 10 ? Math.round(size) : size.toFixed(1)} ${units[unitIndex]}`;
}

function setStatusBar(text) {
	statusBarItem.text = text;
	statusBarItem.show();
}

function clearStatusBar() {
	statusBarItem.hide();
}

function deactivate() { }

module.exports = {
	activate,
	deactivate
};
