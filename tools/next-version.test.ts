import { test, expect } from 'bun:test'
import { bumpFor, bumpVersion, next, notes, versionCode } from './next-version.ts'

test('bumpFor picks the highest bump', () => {
  expect(bumpFor(['docs: x', 'chore: y', 'Merge pull request #1'])).toBeNull()
  expect(bumpFor(['fix: a'])).toBe('patch')
  expect(bumpFor(['perf(ui): a', 'fix: b'])).toBe('patch')
  expect(bumpFor(['fix: a', 'feat(mic): b'])).toBe('minor')
  expect(bumpFor(['feat: a', 'fix!: b'])).toBe('major')
  expect(bumpFor(['feat: a', 'refactor: b\n\nBREAKING CHANGE: gone'])).toBe('major')
  expect(bumpFor(['feat: a\n\nmentions BREAKING CHANGE: inline is not a footer'])).toBe('minor')
})

test('bumpVersion', () => {
  expect(bumpVersion('0.1.1', 'patch')).toBe('0.1.2')
  expect(bumpVersion('0.1.1', 'minor')).toBe('0.2.0')
  expect(bumpVersion('0.9.4', 'major')).toBe('1.0.0')
})

test('versionCode grows with semver and clears the current build', () => {
  expect(versionCode('0.1.1')).toBe(101)
  expect(versionCode('0.2.0')).toBeGreaterThan(versionCode('0.1.9'))
  expect(versionCode('1.0.0')).toBeGreaterThan(versionCode('0.99.99'))
})

test('next: nothing releasable keeps the version', () => {
  expect(next('1.2.3', ['docs: readme', 'ci: tweak'])).toMatchObject({ released: false, version: '1.2.3' })
})

test('next + notes', () => {
  const r = next('0.1.1', ['feat(mic): choose phone or headset', 'fix: crash on start', 'chore: deps', 'feat!: drop Android 10'])
  expect(r).toMatchObject({ released: true, version: '1.0.0', tag: 'v1.0.0', versionCode: 10000 })
  expect(r.notes).toContain('### Breaking changes\n- feat!: drop Android 10')
  expect(r.notes).toContain('- **mic:** choose phone or headset')
  expect(r.notes).toContain('### Bug fixes\n- crash on start')
  expect(r.notes).not.toContain('deps')
  expect(notes(['chore: x'])).toBe('')
})
