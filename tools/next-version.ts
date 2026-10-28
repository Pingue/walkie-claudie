// Derives the next semver release from conventional commits since the last v* tag.
// Usage (repo root, full history): bun tools/next-version.ts   -> prints JSON {released, version, tag, notes, versionCode}
// With no v* tag yet, the base is BASE_VERSION (the app's versionName) and all history counts.
import { execFileSync } from 'child_process'

export const BASE_VERSION = '0.1.1'

type Bump = 'major' | 'minor' | 'patch'
const SECTIONS: [string, string][] = [
  ['feat', 'Features'], ['fix', 'Bug fixes'], ['perf', 'Performance'], ['revert', 'Reverts'],
]
const HEADER = /^(\w+)(?:\(([^)]+)\))?(!)?: (.+)$/

/** Highest bump the commit messages call for, or null when none of them is releasable. */
export function bumpFor(messages: string[]): Bump | null {
  let bump: Bump | null = null
  for (const m of messages) {
    const h = HEADER.exec(m.split('\n')[0])
    if (!h) continue
    const type = h[1].toLowerCase()
    if (h[3] || /^BREAKING[ -]CHANGE: /m.test(m)) return 'major'
    if (type === 'feat') bump = 'minor'
    else if ((type === 'fix' || type === 'perf' || type === 'revert') && !bump) bump = 'patch'
  }
  return bump
}

export function bumpVersion(version: string, bump: Bump): string {
  const [major, minor, patch] = version.split('.').map(Number)
  if (bump === 'major') return `${major + 1}.0.0`
  if (bump === 'minor') return `${major}.${minor + 1}.0`
  return `${major}.${minor}.${patch + 1}`
}

/** Android versionCode: monotonic in semver as long as minor and patch stay below 100. */
export function versionCode(version: string): number {
  const [major, minor, patch] = version.split('.').map(Number)
  return major * 10_000 + minor * 100 + patch
}

export function notes(messages: string[]): string {
  const out: string[] = []
  const breaking = messages.filter(m => HEADER.exec(m.split('\n')[0])?.[3] || /^BREAKING[ -]CHANGE: /m.test(m))
  if (breaking.length) out.push('### Breaking changes', ...breaking.map(m => `- ${m.split('\n')[0]}`), '')
  for (const [type, title] of SECTIONS) {
    const lines = messages.map(m => HEADER.exec(m.split('\n')[0])).filter(h => h && h[1].toLowerCase() === type)
      .map(h => `- ${h![2] ? `**${h![2]}:** ` : ''}${h![4]}`)
    if (lines.length) out.push(`### ${title}`, ...lines, '')
  }
  return out.join('\n').trim()
}

export function next(lastVersion: string, messages: string[]) {
  const bump = bumpFor(messages)
  if (!bump) return { released: false, version: lastVersion, tag: `v${lastVersion}`, notes: '', versionCode: versionCode(lastVersion) }
  const version = bumpVersion(lastVersion, bump)
  return { released: true, version, tag: `v${version}`, notes: notes(messages), versionCode: versionCode(version) }
}

const git = (...args: string[]) => execFileSync('git', args, { encoding: 'utf8' }).trim()

if (import.meta.main) {
  const tags = git('tag', '--list', 'v[0-9]*.[0-9]*.[0-9]*', '--sort=-v:refname').split('\n').filter(Boolean)
  const last = tags[0]
  const range = last ? `${last}..HEAD` : 'HEAD'
  const messages = git('log', '--no-merges', '--format=%B%x00', range).split('\0').map(m => m.trim()).filter(Boolean)
  console.log(JSON.stringify(next(last ? last.slice(1) : BASE_VERSION, messages)))
}
