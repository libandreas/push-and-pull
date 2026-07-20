import fs from 'node:fs/promises';
import fsSync from 'node:fs';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const projectRoot = path.dirname(fileURLToPath(import.meta.url));
const distRoot = path.dirname(projectRoot);
const projectFolder = path.basename(projectRoot);
const busyRetryCount = 8;
const busyRetryDelayMs = 500;

async function main() {
	console.log(`Building ${projectFolder} VS Code extension...\n`);

	const version = await getNextBuildVersion();
	await setPackageVersion(version);
	await buildVsCodeProject();

	console.log('\nVS Code build finished.');
}

async function getNextBuildVersion() {
	const packagePath = path.join(projectRoot, 'package.json');
	const packageJson = await readJsonFile(packagePath);
	const currentVersion = getRequiredVersion(packageJson, packagePath);
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

async function buildVsCodeProject() {
	const packagePath = path.join(projectRoot, 'package.json');
	const packageJson = await readJsonFile(packagePath);
	getRequiredVersion(packageJson, packagePath);

	const outputPath = path.join(distRoot, `${projectFolder}-latest.vsix`);
	await removeMatchingOutputs(distRoot, `${projectFolder}-`, '.vsix');
	await removeFileWithRetry(outputPath);
	await runVscePackage(outputPath);

	console.log(`VS Code: ${path.relative(projectRoot, outputPath)}`);
}

async function runVscePackage(outputPath) {
	if (process.platform === 'win32') {
		const npxPath = findOnPath('npx.cmd') || 'npx.cmd';
		await run('powershell.exe', [
			'-NoProfile',
			'-ExecutionPolicy',
			'Bypass',
			'-Command',
			`& ${quotePowerShell(npxPath)} --yes @vscode/vsce package --out ${quotePowerShell(outputPath)}`
		]);
		return;
	}

	await run('npx', ['--yes', '@vscode/vsce', 'package', '--out', outputPath]);
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
