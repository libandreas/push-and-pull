import { mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawn } from "node:child_process";

const __filename = fileURLToPath(import.meta.url);
const repoRoot = path.dirname(__filename);
const distDir = path.join(repoRoot, "dist");

async function main() {
	await rm(distDir, { recursive: true, force: true });
	await mkdir(distDir, { recursive: true });

	await runVscePackage();
}

async function runVscePackage() {
	if (process.platform === "win32") {
		const npxPath = findOnPath("npx.cmd") || "npx.cmd";
		await run("powershell.exe", [
			"-NoProfile",
			"-ExecutionPolicy",
			"Bypass",
			"-Command",
			`& ${quotePowerShell(npxPath)} --yes @vscode/vsce package --out ${quotePowerShell(distDir)}`
		]);
		return;
	}

	await run("npx", [
		"--yes",
		"@vscode/vsce",
		"package",
		"--out",
		distDir
	]);
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

function quotePowerShell(value) {
	return `'${String(value).replace(/'/g, "''")}'`;
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

main().catch((error) => {
	console.error(error.message || error);
	process.exitCode = 1;
});
