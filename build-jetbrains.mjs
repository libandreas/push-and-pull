import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const __filename = fileURLToPath(import.meta.url);
const repoRoot = path.dirname(__filename);
const backupDistDir = "H:\\Το Drive μου\\ceres-assistant.com - Builds\\dist";

async function main() {
	const gradleCommand = getGradleCommand();

	if (!gradleCommand) {
		throw new Error(
			"Gradle was not found. Install Gradle or add the Gradle Wrapper, then run this script again."
		);
	}

	await run(gradleCommand.command, [...gradleCommand.args, "buildPlugin"]);
	await copyJetBrainsBackups();
}

function getGradleCommand() {
	const wrapper = process.platform === "win32" ? "gradlew.bat" : "gradlew";
	const wrapperPath = path.join(repoRoot, wrapper);

	if (existsSync(wrapperPath)) {
		return {
			command: wrapperPath,
			args: []
		};
	}

	const gradlePath = findOnPath(process.platform === "win32" ? "gradle.bat" : "gradle") ||
		findOnPath("gradle");

	if (gradlePath) {
		return {
			command: gradlePath,
			args: []
		};
	}

	return undefined;
}

function findOnPath(fileName) {
	const pathEntries = (process.env.PATH || process.env.Path || "")
		.split(path.delimiter)
		.filter(Boolean);

	for (const entry of pathEntries) {
		const candidate = path.join(entry, fileName);
		if (existsSync(candidate)) {
			return candidate;
		}
	}

	return undefined;
}

function run(command, args) {
	return new Promise((resolve, reject) => {
		const child = spawn(command, args, {
			cwd: repoRoot,
			stdio: "inherit"
		});

		child.on("error", reject);
		child.on("exit", (code) => {
			if (code === 0) {
				resolve();
				return;
			}

			reject(new Error(`${command} exited with code ${code}`));
		});
	});
}

async function copyJetBrainsBackups() {
	const { copyFile, mkdir, readdir } = await import("node:fs/promises");
	const distributionsDir = path.join(repoRoot, "build", "distributions");

	if (!existsSync(distributionsDir)) {
		return;
	}

	await mkdir(backupDistDir, { recursive: true });
	const dirents = await readdir(distributionsDir, { withFileTypes: true });

	for (const dirent of dirents) {
		if (!dirent.isFile() || !dirent.name.endsWith(".zip")) {
			continue;
		}

		const sourcePath = path.join(distributionsDir, dirent.name);
		const backupPath = path.join(backupDistDir, dirent.name);
		await copyFile(sourcePath, backupPath);
		console.log(`Backup package: ${backupPath}`);
	}
}

main().catch((error) => {
	console.error(error.message || error);
	process.exitCode = 1;
});
