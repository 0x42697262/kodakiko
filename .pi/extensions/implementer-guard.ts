import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { isAbsolute, normalize, relative, resolve } from "node:path";

const GUARD_MARKER = "KODAKIKO-IMPLEMENTER-GUARD-V1";

const BASH_RULES: readonly { readonly pattern: RegExp; readonly reason: string }[] = [
	{ pattern: /\b(sudo|doas|su)\b/, reason: "privilege escalation is not allowed" },
	{ pattern: /\bgit(\s+-[Cc]\s+\S+)*\s+(push|commit|reset|clean|rebase|merge|cherry-pick|revert|stash|filter-branch|filter-repo|update-ref|reflog|gc|prune|config|remote|tag|switch|worktree|checkout|restore|rm|mv|am|apply)\b/, reason: "this git command is reserved for the programmer (read-only git such as status, diff, log, show, ls-files, and `git add` are allowed)" },
	{ pattern: /\bgit(\s+-[Cc]\s+\S+)*\s+branch\b[^|;&]*\s-(d|D|m|M|f)\b/, reason: "deleting or renaming branches is not allowed" },
	{ pattern: /\bfind\b[^|;&]*\s-(delete|exec\s+rm)\b/, reason: "find -delete / -exec rm is not allowed" },
	{ pattern: /\bxargs\b[^|;&]*\brm\b/, reason: "xargs rm is not allowed" },
	{ pattern: /\b(shred|mkfs(\.\w+)?|dd|wipefs|fdisk|parted)\b/, reason: "disk-level tools are not allowed" },
	{ pattern: /\b(shutdown|reboot|poweroff|halt|systemctl|crontab|launchctl)\b/, reason: "system control is not allowed" },
	{ pattern: /\b(chmod|chown)\s+(-\w*R|--recursive)/, reason: "recursive permission changes are not allowed" },
	{ pattern: /\bkill(all)?\s+(-9\s+)?-1\b|\bpkill\b|\bkillall\b/, reason: "broad process killing is not allowed; kill a specific PID you started" },
	{ pattern: /\b(curl|wget)\b[^|]*\|\s*(sh|bash|zsh|node|python3?)\b/, reason: "piping downloads into a shell is not allowed" },
	{ pattern: /\b(ssh|scp|rsync|sftp)\b/, reason: "remote access is not allowed" },
	{ pattern: /\b(npm|pnpm|yarn|bun)\b[^|;&]*\s(-g|--global)\b/, reason: "global installs are not allowed" },
	{ pattern: /\b(pnpm|npm|yarn|bun)\s+(add|remove|rm|uninstall|un|update|up|upgrade|link|unlink)\b/, reason: "dependency changes are not allowed (only `pnpm install` from the lockfile); stop and ask" },
	{ pattern: /\bnpm\s+(i|install)\s+\S/, reason: "npm install of packages is not allowed; use `pnpm install`" },
	{ pattern: /\bnpx\b|\bpnpm\s+dlx\b|\bbunx\b/, reason: "running packages that are not installed is not allowed" },
	{ pattern: /\bdocker\b[^|;&]*\b(system|volume|image|network|builder)\s+(prune|rm)\b/, reason: "docker pruning/removal is not allowed" },
	{ pattern: /\bdocker\s+(rm|rmi|kill)\b/, reason: "removing docker containers/images directly is not allowed; use the project's compose files" },
	{ pattern: /\bdocker\b[^|;&]*\bcompose\b(?![^|;&]*-p\s+kodakiko-)[^|;&]*\bdown\b[^|;&]*(-v|--volumes)\b/, reason: "`docker compose down -v` is only allowed with `-p kodakiko-…` projects" },
	{ pattern: />\s*\/dev\/(sd|nvme|hd)/, reason: "writing to block devices is not allowed" },
	{ pattern: /\brm\b[^|;&]*\s--no-preserve-root\b/, reason: "not allowed" },
];

const RM_RECURSIVE = /\brm\s+((-[a-zA-Z]*[rR][a-zA-Z]*|--recursive)\b[^|;&]*)/;
const RM_ALLOWED_TARGET =
	/^(\.\/)?((apps|packages|tests|tools)\/[\w.@/-]+\/)?(node_modules|dist|build|\.expo|coverage|reports|tmp|android)\/?$|^\/tmp\/kodakiko[\w.-]*(\/[\w.@/-]*)?$/;

const PROTECTED_WRITE_PATHS: readonly RegExp[] = [
	/^docs\//,
	/^AGENTS\.md$/,
	/^\.pi\//,
	/^\.git(\/|$)/,
	/^pnpm-workspace\.yaml$/,
	/(^|\/)\.env(?!\.example$|\.ui$|\.[\w-]+\.example$)(\.[\w.-]+)?$/,
];

function checkRecursiveRm(command: string): string | null {
	const match = RM_RECURSIVE.exec(command);
	if (!match) return null;
	const targets = (match[1] ?? "").split(/\s+/).filter((part) => part.length > 0 && !part.startsWith("-"));
	if (targets.length === 0) return "rm -r without a target";
	const bad = targets.filter((target) => !RM_ALLOWED_TARGET.test(target));
	return bad.length === 0 ? null : `recursive rm is only allowed on build output (node_modules, dist, build, .expo, coverage, reports, tmp, android) inside the repository or /tmp/kodakiko*: ${bad.join(" ")}`;
}

function checkPath(cwd: string, path: string): string | null {
	const expanded = path.replace(/^~(?=\/|$)/, process.env.HOME ?? "/nonexistent-home");
	const absolute = isAbsolute(expanded) ? normalize(expanded) : resolve(cwd, expanded);
	if (absolute.startsWith("/tmp/") || absolute === "/dev/null") return null;
	const rel = relative(cwd, absolute);
	if (rel.startsWith("..") || isAbsolute(rel)) return `writing outside the repository is not allowed: ${path}`;
	const hit = PROTECTED_WRITE_PATHS.find((rule) => rule.test(rel));
	return hit ? `${rel} is owned by Opus/the programmer; stop and ask instead of editing it` : null;
}

const REDIRECT_TARGET = /(?:^|[^<>&0-9=-])(?:[0-9]?>{1,2}|&>)\s*(?!&)([^\s;&|<>()]+)|\btee\s+(?:-a\s+)?([^\s;&|<>()]+)/g;
const FILE_MUTATORS = /\b(mv|cp|ln|install|truncate|touch|sed\s+-i|perl\s+-p?i)\b([^|;&]*)/g;
const PROTECTED_TOKEN = /(^|[\s'"=])(\.\/)?(docs\/|AGENTS\.md\b|\.pi\/|\.git\/|pnpm-workspace\.yaml\b|~\/|\/(?!tmp\/|dev\/null)[\w.-]+\/)/;

const HEREDOC = /<<-?\s*(['"]?)(\w+)\1[^\n]*\n[\s\S]*?\n\s*\2\s*(?=\n|$)/g;

function stripHeredocBodies(command: string): string {
	return command.replace(HEREDOC, (whole) => whole.slice(0, whole.indexOf("\n")));
}

const LEADING_CD = /^\s*cd\s+([^\s;&|]+)\s*(?:&&|;)/;

const QUOTED = /'[^']*'|"(?:[^"\\]|\\.)*"/g;

function neutralizeQuotedOperators(command: string): string {
	return command.replace(QUOTED, (quoted) => quoted.replace(/[<>;|&]/g, " "));
}

function checkBashWrites(rootCwd: string, rawCommand: string): string | null {
	const command = neutralizeQuotedOperators(stripHeredocBodies(rawCommand));
	const cdMatch = LEADING_CD.exec(command);
	const cwd = cdMatch?.[1] ? resolve(rootCwd, cdMatch[1].replace(/^~(?=\/|$)/, process.env.HOME ?? "/nonexistent-home")) : rootCwd;
	if (relative(rootCwd, cwd).startsWith("..")) {
		const outside = /(?:[0-9]?>{1,2}|&>)\s*(?!&|\/dev\/null|\/tmp\/)\S/.test(command);
		if (outside) return "writing after `cd` to a directory outside the repository is not allowed";
	}
	for (const match of command.matchAll(REDIRECT_TARGET)) {
		const target = match[1] ?? match[2];
		if (!target) continue;
		const unquoted = target.replace(/^['"]|['"]$/g, "").replace(/^~(?=\/|$)/, process.env.HOME ?? "/nonexistent-home");
		const reason = checkPath(rootCwd, resolve(cwd, unquoted));
		if (reason) return `shell redirect: ${reason}`;
	}
	for (const match of command.matchAll(FILE_MUTATORS)) {
		const unquotedArgs = (match[2] ?? "").replace(/'[^']*'|"[^"]*"/g, " ");
		if (PROTECTED_TOKEN.test(unquotedArgs)) return `\`${match[1]}\` touching a protected path or a path outside the repository; stop and ask`;
	}
	return null;
}

export default function (pi: ExtensionAPI) {
	pi.on("tool_call", async (event, ctx) => {
		const prompt = ctx.getSystemPrompt?.() ?? "";
		if (!prompt.includes(GUARD_MARKER)) return undefined;
		const input = event.input as Record<string, unknown>;
		let reason: string | null = null;
		if (event.toolName === "bash") {
			const command = String(input.command ?? "");
			const rule = BASH_RULES.find((candidate) => candidate.pattern.test(command));
			reason = rule ? rule.reason : (checkRecursiveRm(command) ?? checkBashWrites(ctx.cwd, command));
		} else if (event.toolName === "write" || event.toolName === "edit") {
			reason = checkPath(ctx.cwd, String(input.path ?? ""));
		}
		if (reason === null) return undefined;
		return { block: true, reason: `Blocked by implementer guard: ${reason}` };
	});
}
