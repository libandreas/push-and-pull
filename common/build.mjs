import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const commonRoot = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.dirname(commonRoot);
const distRoot = path.dirname(projectRoot);
const projectFolder = path.basename(projectRoot);
const busyRetryCount = 8;
const busyRetryDelayMs = 500;
const ignoredBuildDirectoryNames = new Set(['test-files']);


async function main() {
	const buildTargets = await findBuildTargets();
	if (!buildTargets.chrome && !buildTargets.vsCode) {
		throw new Error('No service-worker.js, service-worker-N.js, extension.js, or extension-N.js was found.');
	}

	console.log(`Building ${projectFolder} packages...\n`);

	const version = await getNextBuildVersion(buildTargets);
	await setWebManifestVersion(version);

	if (buildTargets.vsCode) {
		await setPackageVersion(version);
		await buildVsCodeProject(buildTargets.vsCode.suffix);
	}

	if (buildTargets.chrome) {
		await setChromeManifestVersion(version);
		await buildChromeProject(buildTargets.chrome.suffix);
	}

	console.log('\nBuild finished.');
}

async function findBuildTargets() {
	const entries = await fs.readdir(projectRoot, { withFileTypes: true });
	const fileNames = entries.filter((entry) => entry.isFile()).map((entry) => entry.name);
	const chromeFiles = fileNames.filter((fileName) => /^service-worker(?:-(\d+))?\.js$/i.test(fileName));
	const vsCodeFiles = fileNames.filter((fileName) => /^extension(?:-(\d+))?\.js$/i.test(fileName));

	if (chromeFiles.length > 1) {
		throw new Error(`Multiple Chrome entry files were found: ${chromeFiles.join(', ')}`);
	}

	if (vsCodeFiles.length > 1) {
		throw new Error(`Multiple VS Code entry files were found: ${vsCodeFiles.join(', ')}`);
	}

	return {
		chrome: chromeFiles[0] ? { fileName: chromeFiles[0], suffix: getEntrySuffix(chromeFiles[0]) } : null,
		vsCode: vsCodeFiles[0] ? { fileName: vsCodeFiles[0], suffix: getEntrySuffix(vsCodeFiles[0]) } : null
	};
}

function getEntrySuffix(fileName) {
	const match = fileName.match(/-(\d+)\.js$/i);
	return match ? `-${match[1]}` : '';
}

async function getNextBuildVersion(buildTargets) {
	const versionPath = buildTargets.vsCode
		? path.join(projectRoot, 'package.json')
		: path.join(projectRoot, 'manifest.json');
	const versionJson = await readJsonFile(versionPath);
	const currentVersion = getRequiredVersion(versionJson, versionPath);
	return getNextCalendarVersion(currentVersion);
}

async function setPackageVersion(version) {
	const packagePath = path.join(projectRoot, 'package.json');
	const packageJson = await readJsonFile(packagePath);

	if (packageJson.version !== version) {
		packageJson.version = version;
		await writeJsonFile(packagePath, packageJson);
		console.log(`Version: ${path.relative(projectRoot, packagePath)} -> ${version}`);
	}

	await syncPackageLockVersion(version);
}

async function syncPackageLockVersion(version) {
	const lockPath = path.join(projectRoot, 'package-lock.json');
	if (!fsSync.existsSync(lockPath)) {
		return;
	}

	const lockJson = await readJsonFile(lockPath);
	let changed = false;

	if (lockJson.version !== version) {
		lockJson.version = version;
		changed = true;
	}

	if (lockJson.packages?.[''] && lockJson.packages[''].version !== version) {
		lockJson.packages[''].version = version;
		changed = true;
	}

	if (changed) {
		await writeJsonFile(lockPath, lockJson);
		console.log(`Version: ${path.relative(projectRoot, lockPath)} -> ${version}`);
	}
}

function getNextCalendarVersion(currentVersion) {
	const current = parseVersion(currentVersion);
	const calendar = getCalendarVersionPrefix();

	if (current && current.major === calendar.major && current.minor === calendar.minor) {
		return `${calendar.major}.${calendar.minor}.${current.patch + 1}`;
	}

	return `${calendar.major}.${calendar.minor}.0`;
}

function getCalendarVersionPrefix(date = new Date()) {
	return {
		major: date.getFullYear() % 100,
		minor: date.getMonth() + 1
	};
}

function parseVersion(version) {
	const match = String(version || '').trim().match(/^(\d+)\.(\d+)\.(\d+)$/);
	if (!match) {
		return null;
	}

	return {
		major: Number(match[1]),
		minor: Number(match[2]),
		patch: Number(match[3])
	};
}

async function buildVsCodeProject(suffix) {
	const packagePath = path.join(projectRoot, 'package.json');
	const packageJson = await readJsonFile(packagePath);
	getRequiredVersion(packageJson, packagePath);

	const outputPath = path.join(distRoot, `${projectFolder}-latest.vsix`);
	await removeMatchingOutputs(distRoot, `${projectFolder}-`, '.vsix');
	await removeFileWithRetry(outputPath);

	const indexPath = path.join(projectRoot, 'www', `index${suffix}.html`);
	if (!fsSync.existsSync(indexPath)) {
		await runVscePackage(outputPath);
		console.log(`VS Code: ${path.relative(projectRoot, outputPath)}`);
		return;
	}

	const originalIndex = await fs.readFile(indexPath, 'utf8');
	const packagedIndex = removePwaTags(originalIndex);

	try {
		await fs.writeFile(indexPath, packagedIndex);
		await runVscePackage(outputPath);
	} finally {
		await fs.writeFile(indexPath, originalIndex);
	}

	console.log(`VS Code: ${path.relative(projectRoot, outputPath)}`);
}

async function setChromeManifestVersion(version) {
	const manifestPath = path.join(projectRoot, 'manifest.json');
	if (!fsSync.existsSync(manifestPath)) {
		return;
	}

	const manifestJson = await readJsonFile(manifestPath);
	if (manifestJson.version !== version) {
		manifestJson.version = version;
		await writeJsonFile(manifestPath, manifestJson);
		console.log(`Version: ${path.relative(projectRoot, manifestPath)} -> ${version}`);
	}
}

async function setWebManifestVersion(version) {
	const entries = await fs.readdir(projectRoot, { withFileTypes: true });
	const webManifestFiles = entries
		.filter((entry) => entry.isFile() && /^manifest(?:-\d+)?\.webmanifest$/i.test(entry.name))
		.map((entry) => entry.name);

	for (const fileName of webManifestFiles) {
		const webManifestPath = path.join(projectRoot, fileName);
		const webManifest = await readJsonFile(webManifestPath);

		if (webManifest.version !== version) {
			webManifest.version = version;
			await writeJsonFile(webManifestPath, webManifest);
			console.log(`Version: ${path.relative(projectRoot, webManifestPath)} -> ${version}`);
		}
	}
}

async function buildChromeProject(suffix) {
	const outputPath = path.join(distRoot, `${projectFolder}-latest.zip`);
	const ignorePath = path.join(projectRoot, '.vscodeignore');
	const rules = await readChromeIgnoreRules(ignorePath);
	const files = await collectChromeFiles(projectRoot, rules, suffix);

	await removeMatchingOutputs(distRoot, `${projectFolder}-`, '.zip');
	await removeFileWithRetry(outputPath);
	await writeZip(outputPath, files);

	console.log(`Chrome: ${path.relative(projectRoot, outputPath)}`);
}

async function readChromeIgnoreRules(ignorePath) {
	const raw = await fs.readFile(ignorePath, 'utf8');

	return raw
		.split(/\r?\n/)
		.map((line) => line.trim())
		.filter((line) => line && !line.startsWith('#'))
		.map((line) => {
			const negated = line.startsWith('!');
			const pattern = normalizeZipPath(negated ? line.slice(1) : line);

			return {
				negated,
				pattern,
				regex: globToRegex(pattern)
			};
		});
}

async function collectChromeFiles(rootDir, rules, suffix) {
	const files = [];

	async function walk(currentDir) {
		const entries = await fs.readdir(currentDir, { withFileTypes: true });
		entries.sort((left, right) => left.name.localeCompare(right.name));

		for (const entry of entries) {
			const fullPath = path.join(currentDir, entry.name);
			const relativePath = normalizeZipPath(path.relative(rootDir, fullPath));

			if (entry.isDirectory()) {
				if (ignoredBuildDirectoryNames.has(entry.name.toLowerCase())) {
					console.log(`Skip: ${relativePath}/**`);
					continue;
				}

				if (isIgnored(relativePath, rules) && !hasIncludeRuleInside(relativePath, rules)) {
					console.log(`Skip: ${relativePath}/**`);
					continue;
				}

				await walk(fullPath);
				continue;
			}

			if (!entry.isFile()) {
				continue;
			}

			if (isIgnored(relativePath, rules)) {
				console.log(`Skip: ${relativePath}`);
				continue;
			}

			console.log(`Add: ${relativePath}`);
			if (relativePath === `www/index${suffix}.html`) {
				const html = await fs.readFile(fullPath, 'utf8');
				const packagedHtml = removeChromeOnlyTags(html);

				files.push({
					content: Buffer.from(packagedHtml, 'utf8'),
					fullPath,
					relativePath
				});
				continue;
			}

			if (/^www\/worker\.xnv\.[3-7]\.min\.js$/.test(relativePath) || /^www\/transformers\.(?:xnv\.3|v3)\.min\.js$/.test(relativePath)) {
				let workerSource = await fs.readFile(fullPath, 'utf8');
				if (relativePath === 'www/worker.xnv.4.min.js') {
					workerSource = createChromeBackgroundRemovalWorker(workerSource);
				} else if (relativePath === 'www/worker.xnv.5.min.js') {
					workerSource = createChromeRestoreDetailsWorker(workerSource);
				} else if (/^www\/transformers\.(?:xnv\.3|v3)\.min\.js$/.test(relativePath)) {
					workerSource = workerSource.replace('allowRemoteModels:!0', 'allowRemoteModels:!1');
				}
				// Only remote model/runtime URL literals are removed from the packaged copy.
				// Local paths and documentation embedded in diagnostic messages stay intact.
				workerSource = workerSource.replace(
					/(["'`])https:\/\/(?:huggingface\.co|cdn\.jsdelivr\.net)(?:\/[^"'`\r\n]*)?\1/g,
					'null'
				);
				files.push({
					content: Buffer.from(workerSource, 'utf8'),
					fullPath,
					relativePath
				});
				continue;
			}

			if (relativePath === 'www/embedding-worker.js') {
				const workerSource = await fs.readFile(fullPath, 'utf8');
				// Chrome loads bundled FastText models. Remove both regular and API Zenodo URLs from the ZIP copy.
				const packagedSource = workerSource.replace(/(["'`])https:\/\/zenodo\.org\/[^"'`\r\n]*\1/g, 'null');
				files.push({
					content: Buffer.from(packagedSource, 'utf8'),
					fullPath,
					relativePath
				});
				continue;
			}

			files.push({ fullPath, relativePath });
		}
	}

	await walk(rootDir);
	return files;
}

function createChromeBackgroundRemovalWorker(workerSource) {
	return workerSource
		.replace(
			'const EXTENSION_BUILD = false;',
			'const EXTENSION_BUILD = true;'
		)
		.replace(
			/^const REMOTE_MODEL_URL = .*;$/m,
			'// Chrome extension build: remote model downloads are disabled.\n// BEN2 loads only from the packaged local model path.\nconst REMOTE_MODEL_URL = null;'
		);
}

function createChromeRestoreDetailsWorker(workerSource) {
	return workerSource.replace(
		/^const REMOTE_MODEL_URL = .*;$/m,
		'// Chrome extension build: remote model downloads are disabled.\n// Restore Details loads only from the packaged local model path.\nconst REMOTE_MODEL_URL = null;'
	);
}

function removePwaTags(html) {
	return html
		.replace(/^[\t ]*<link\s+rel="manifest"\s+href="\.\.\/manifest(?:-\d+)?\.webmanifest">\r?\n/im, '')
		.replace(/^[\t ]*<link\s+rel="stylesheet"\s+href="\.\.\/common\/pwa-install\.css">\r?\n/im, '')
		.replace(/^[\t ]*<script(?:\s+defer)?\s+src="\.\.\/common\/pwa-install(?:-view)?\.js"><\/script>\r?\n/gim, '');
}

function removeChromeOnlyTags(html) {
	return html.replace(
		/^[\t ]*<!-- Not in Chrome Build Start -->[\s\S]*?^[\t ]*<!-- Not in Chrome Build End -->\r?\n?/gim,
		''
	);
}

function isIgnored(relativePath, rules) {
	let ignored = false;

	for (const rule of rules) {
		if (rule.regex.test(relativePath)) {
			ignored = !rule.negated;
		}
	}

	return ignored;
}

function hasIncludeRuleInside(relativePath, rules) {
	const directory = `${relativePath}/`;

	return rules.some((rule) => rule.negated && rule.pattern.startsWith(directory));
}

function globToRegex(pattern) {
	let sourcePattern = pattern;
	if (sourcePattern.startsWith('/')) {
		sourcePattern = sourcePattern.slice(1);
	}

	const startsAnywhere = sourcePattern.startsWith('**/');
	if (startsAnywhere) {
		sourcePattern = sourcePattern.slice(3);
	}

	if (sourcePattern.endsWith('/**/*')) {
		sourcePattern = `${sourcePattern.slice(0, -5)}/**`;
	}

	if (sourcePattern.endsWith('/**')) {
		const base = globBodyToRegex(sourcePattern.slice(0, -3));
		const prefix = startsAnywhere ? '(?:.*/)?' : '';
		return new RegExp(`^${prefix}${base}(?:/.*)?$`);
	}

	const prefix = startsAnywhere ? '(?:.*/)?' : '';
	return new RegExp(`^${prefix}${globBodyToRegex(sourcePattern)}$`);
}

function globBodyToRegex(sourcePattern) {
	let source = '';
	for (let index = 0; index < sourcePattern.length; index += 1) {
		const char = sourcePattern[index];
		const next = sourcePattern[index + 1];

		if (char === '*' && next === '*') {
			source += '.*';
			index += 1;
			continue;
		}

		if (char === '*') {
			source += '[^/]*';
			continue;
		}

		if (char === '?') {
			source += '[^/]';
			continue;
		}

		source += escapeRegex(char);
	}

	return source;
}

function escapeRegex(value) {
	return String(value).replace(/[|\\{}()[\]^$+?.]/g, '\\$&');
}

function normalizeZipPath(value) {
	return String(value).replace(/\\/g, '/');
}

async function writeZip(outputPath, files) {
	const { default: yazl } = await import('yazl');

	return new Promise((resolve, reject) => {
		const zip = new yazl.ZipFile();
		const output = fsSync.createWriteStream(outputPath);

		output.on('close', resolve);
		output.on('error', reject);
		zip.outputStream.on('error', reject);

		zip.outputStream.pipe(output);

		for (const file of files) {
			if (file.content) {
				zip.addBuffer(file.content, file.relativePath);
				continue;
			}

			zip.addFile(file.fullPath, file.relativePath);
		}

		zip.end();
	});
}

async function runVscePackage(outputPath) {
	if (process.platform === 'win32') {
		const npxPath = findOnPath('npx.cmd') || 'npx.cmd';
		await run('powershell.exe', [
			'-NoProfile',
			'-ExecutionPolicy',
			'Bypass',
			'-Command',
			`& ${quotePowerShell(npxPath)} --yes @vscode/vsce package --no-dependencies --allow-missing-repository --out ${quotePowerShell(outputPath)}`
		]);
		return;
	}

	await run('npx', [
		'--yes',
		'@vscode/vsce',
		'package',
		'--no-dependencies',
		'--allow-missing-repository',
		'--out',
		outputPath
	]);
}

function findOnPath(fileName) {
	const pathEntries = (process.env.PATH || process.env.Path || '')
		.split(path.delimiter)
		.filter(Boolean);

	for (const entry of pathEntries) {
		const candidate = path.join(entry, fileName);
		if (fsSync.existsSync(candidate)) {
			return candidate;
		}
	}

	return undefined;
}

function quotePowerShell(value) {
	return `'${String(value).replace(/'/g, "''")}'`;
}

function run(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, {
			cwd: projectRoot,
			stdio: 'inherit'
		});

		child.on('error', reject);
		child.on('exit', (code) => {
			if (code === 0) {
				resolve();
				return;
			}

			reject(new Error(`${command} exited with code ${code}`));
		});
	});
}

async function readJsonFile(filePath) {
	const raw = await fs.readFile(filePath, 'utf8');
	return JSON.parse(raw.replace(/^\uFEFF/, ''));
}

async function writeJsonFile(filePath, value) {
	await fs.writeFile(filePath, `${JSON.stringify(value, null, '\t')}\n`);
}

function getRequiredVersion(json, filePath) {
	const version = String(json.version || '').trim();
	if (!version) {
		throw new Error(`${filePath} is missing a version field.`);
	}

	return version;
}

async function removeMatchingOutputs(targetDir, prefix, extension) {
	const dirents = await fs.readdir(targetDir, { withFileTypes: true }).catch(() => []);

	for (const dirent of dirents) {
		if (!dirent.isFile()) {
			continue;
		}

		const fileName = dirent.name.toLowerCase();
		if (!fileName.startsWith(prefix.toLowerCase()) || !fileName.endsWith(extension.toLowerCase())) {
			continue;
		}

		if (fileName.includes('-latest.')) {
			continue;
		}

		await removeFileWithRetry(path.join(targetDir, dirent.name));
	}
}

async function removeFileWithRetry(filePath) {
	for (let attempt = 1; attempt <= busyRetryCount; attempt += 1) {
		try {
			await fs.rm(filePath, { force: true });
			return;
		} catch (error) {
			if (!isBusyFileError(error) || attempt === busyRetryCount) {
				throw error;
			}

			await sleep(busyRetryDelayMs);
		}
	}
}

function isBusyFileError(error) {
	return ['EBUSY', 'EPERM', 'EACCES'].includes(error?.code);
}

function sleep(ms) {
	return new Promise((resolve) => setTimeout(resolve, ms));
}

main().catch((error) => {
	console.error(error.message || error);
	process.exitCode = 1;
});
