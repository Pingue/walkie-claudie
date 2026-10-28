// End-to-end check: spawns server.ts as Claude Code would, drives it over MCP and HTTP. Run: bun test
import { test, expect } from 'bun:test'
import { Client } from '@modelcontextprotocol/sdk/client/index.js'
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js'
import { mkdtempSync, readFileSync } from 'fs'
import { tmpdir } from 'os'
import { dirname, join } from 'path'
import { isPrivate, parseUsage } from './server.ts'

test('parseUsage', () => {
  const line = JSON.stringify({ type: 'control_response', response: { subtype: 'success', request_id: 'usage', response: {
    rate_limits: { five_hour: { utilization: 65, resets_at: '2026-10-08T04:40:00Z' }, seven_day: { utilization: 55 }, seven_day_opus: null },
  } } })
  expect(parseUsage(line)).toEqual({
    five_hour: { used_percentage: 65, resets_at: '2026-10-08T04:40:00Z' },
    seven_day: { used_percentage: 55, resets_at: undefined },
  })
  expect(parseUsage(JSON.stringify({ type: 'control_response', response: { response: { rate_limits: null } } }))).toBeNull()
  expect(parseUsage('{"type":"system"}')).toBeUndefined()
  expect(parseUsage('not json')).toBeUndefined()
})

test('isPrivate', () => {
  for (const ip of ['192.168.0.5', '10.1.2.3', '172.20.0.1', '100.101.102.103', '127.0.0.1', '::1', '::ffff:192.168.1.2', 'fd7a:115c:a1e0::1'])
    expect(isPrivate(ip)).toBe(true)
  for (const ip of ['8.8.8.8', '172.32.0.1', '100.128.0.1', '2001:db8::1', ''])
    expect(isPrivate(ip)).toBe(false)
})

async function start(enable: boolean, dir = mkdtempSync(join(tmpdir(), 'walkie-')), port = 17700 + Math.floor(Math.random() * 1000), name = '') {
  const client = new Client({ name: 'test', version: '0' })
  const inbound: any[] = []
  client.fallbackNotificationHandler = async n => { inbound.push(n) }
  const env = { ...process.env, PATH: dirname(process.execPath), WALKIE_STATE_DIR: dir, WALKIE_PORT: String(port), WALKIE_ENABLE: enable ? '1' : '', WALKIE_NAME: name }
  await client.connect(new StdioClientTransport({
    command: 'bun', args: [join(import.meta.dir, 'server.ts')], env: env as Record<string, string>,
  }))
  return { dir, port, client, inbound }
}

test('inert without WALKIE_ENABLE: no tools, no port', async () => {
  const { port, client } = await start(false)
  try {
    expect((await client.listTools()).tools).toEqual([])
    expect(await fetch(`http://127.0.0.1:${port}/poll`).catch(() => null)).toBeNull()
  } finally {
    await client.close()
  }
})

test('say → channel notification, reply → poll', async () => {
  const { dir, port, client, inbound } = await start(true)
  const base = `http://127.0.0.1:${port}`
  const auth = { authorization: `Bearer ${readFileSync(join(dir, 'key'), 'utf8').trim()}` }
  try {
    expect((await fetch(`${base}/poll`, { headers: { authorization: 'Bearer nope' } })).status).toBe(401)

    const say = () => fetch(`${base}/say`, { method: 'POST', headers: auth, body: JSON.stringify({ id: 'm1', text: 'hello' }) })
    expect((await say()).status).toBe(200)
    expect((await say()).status).toBe(200) // retry: acked, not re-delivered
    await Bun.sleep(100)
    expect(inbound.length).toBe(1)
    expect(inbound[0].method).toBe('notifications/claude/channel')
    expect(inbound[0].params).toMatchObject({ content: 'hello', meta: { msg_id: 'm1' } })

    const pending = fetch(`${base}/poll?after=0`, { headers: auth }).then(r => r.json())
    await Bun.sleep(100)
    await client.callTool({ name: 'set_issues', arguments: { items: ['A awaiting approval'] } })
    expect((await pending).issues).toEqual(['A awaiting approval']) // long-poll woke on issues change

    // statusline.ts saves the status JSON; poll passes the usage limits through
    const status = JSON.stringify({ model: {}, rate_limits: { five_hour: { used_percentage: 23.4, resets_at: 1 } } })
    const sl = Bun.spawnSync(['bun', join(import.meta.dir, 'statusline.ts')], { stdin: Buffer.from(status), env: { ...process.env, WALKIE_STATE_DIR: dir } })
    expect(sl.stdout.toString().trim()).toBe('5h 23% · 7d –')

    await client.callTool({ name: 'reply', arguments: { text: 'Yes, got it', reply_to: 'm1' } })
    const got = await (await fetch(`${base}/poll?after=0`, { headers: auth })).json()
    expect(got.replies).toHaveLength(1)
    expect(got.replies[0]).toMatchObject({ text: 'Yes, got it', reply_to: 'm1' })
    expect(got.usage.five_hour.used_percentage).toBe(23.4)
    const again = await (await fetch(`${base}/poll?after=${got.replies[0].seq}`, { headers: auth, signal: AbortSignal.timeout(300) }).catch(() => null))
    expect(again).toBeNull() // nothing new → still waiting
    const quick = await (await fetch(`${base}/poll?after=${got.replies[0].seq}&wait=0`, { headers: auth, signal: AbortSignal.timeout(300) })).json()
    expect(quick.replies).toHaveLength(0) // wait=0 → answers at once
  } finally {
    await client.close()
  }

  // A new session on the same state dir still hands out the reply the phone may have missed.
  const next = await start(true, dir)
  try {
    const got = await (await fetch(`http://127.0.0.1:${next.port}/poll?after=0`, { headers: auth })).json()
    expect(got.replies.map((r: any) => r.text)).toEqual(['Yes, got it'])
  } finally {
    await next.client.close()
  }
}, 15_000)

test('two sessions → two channels on neighbouring ports, each with its own name and outbox', async () => {
  const a = await start(true, undefined, 18800 + Math.floor(Math.random() * 1000), 'assistant')
  const b = await start(true, a.dir, a.port, 'layout')
  const auth = { authorization: `Bearer ${readFileSync(join(a.dir, 'key'), 'utf8').trim()}` }
  try {
    const hello = (p: number) => fetch(`http://127.0.0.1:${p}/hello`, { headers: auth }).then(r => r.json())
    expect(await hello(a.port)).toEqual({ name: 'assistant', port: a.port })
    expect(await hello(a.port + 1)).toEqual({ name: 'layout', port: a.port + 1 })
    await b.client.callTool({ name: 'reply', arguments: { text: 'layout reply' } })
    const poll = (p: number) => fetch(`http://127.0.0.1:${p}/poll?after=0&wait=0`, { headers: auth }).then(r => r.json())
    expect((await poll(a.port)).replies).toHaveLength(0)
    expect((await poll(a.port + 1)).replies.map((r: any) => r.text)).toEqual(['layout reply'])
  } finally {
    await a.client.close()
    await b.client.close()
  }
}, 15_000)
