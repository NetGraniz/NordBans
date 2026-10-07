'use strict'
// Isolated backend tests. No production data, proxy or existing fixture source is read.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), net = require('node:net')
const { spawn } = require('node:child_process'), mineflayer = require('mineflayer')
const root = path.resolve(process.argv[2] || ''), java = process.argv[3], seed = path.resolve(process.argv[4] || ''), platform = process.argv[5]
assert(root.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nordbans-test-'))
assert(seed.startsWith('C:\\Users\\artyo\\Documents\\Codex\\nordauth-test-20261007-'))
assert(['Folia', 'Paper'].includes(platform)); assert(java); assert(!fs.existsSync(root), 'A fresh fixture is required')
const server = path.join(root, 'server'), banDir = path.join(server, 'plugins', 'NordBans'), banFile = path.join(banDir, 'bans.properties')
const clients = [], handles = [], passed = []; let current, sequence = 0
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(fn, label, timeout = 20000) {
  const start = Date.now()
  while (!await fn()) { if (Date.now() - start > timeout) throw Error('Timeout: ' + label); await sleep(100) }
}
function pass(label) { passed.push(label); console.log('PASS: ' + label) }
function cmd(text) { current.child.stdin.write(text + '\n') }
async function state() {
  const id = 's' + ++sequence, re = new RegExp('NBSTATE ' + id + ' (\\{[^\\r\\n]+\\})')
  cmd('nbtest state ' + id); await until(() => re.test(current.output), 'state ' + id)
  return JSON.parse(current.output.match(re)[1])
}
async function waitState(test, label) { await until(async () => test(await state()), label) }
function connect(name) {
  const bot = mineflayer.createBot({host:'127.0.0.1',port:25636,username:name,version:'26.2',auth:'offline',hideErrors:true})
  const c = {bot,name,ended:false,kicked:'',messages:[],frames:[]}; clients.push(c)
  bot.on('error', () => {}); bot.on('end', () => { c.ended = true })
  bot.on('kicked', reason => { c.kicked = JSON.stringify(reason) })
  bot.on('messagestr', message => c.messages.push(message))
  bot._client.on('custom_payload', packet => { if (packet.channel === 'nordfjell:bans') c.frames.push(decode(packet.data)) })
  bot.once('spawn', () => bot._client.write('custom_payload',{channel:'minecraft:register',data:Buffer.from('nordfjell:bans')}))
  return c
}
function decode(data) {
  let offset = 0
  function utf() { const n=data.readUInt16BE(offset);offset+=2;const value=data.toString('utf8',offset,offset+n);offset+=n;return value }
  const action=utf(),name=utf(),expires=Number(data.readBigInt64BE(offset));offset+=8
  return {action,name,expires,reason:utf()}
}
async function disconnect(c) { if (!c.ended) c.bot.quit(); await until(() => c.ended, 'disconnect'); await sleep(200) }
async function start() {
  const child=spawn(java,['-Dterminal.jline=false','-Dterminal.ansi=false','-Xms256M','-Xmx1200M','-jar','server.jar','nogui'],{cwd:server,windowsHide:true,stdio:['pipe','pipe','pipe']})
  current={child,output:'',exited:false};handles.push(current)
  for(const stream of [child.stdout,child.stderr])stream.on('data',b=>{currentHandle.output+=b.toString().replace(/\x1b\[[0-9;]*m/g,'')})
  const currentHandle=current
  child.on('exit',()=>{currentHandle.exited=true});child.on('error',e=>{currentHandle.output+=String(e);currentHandle.exited=true})
  return current
}
async function ready() {
  await start();await until(()=>/Done \(/.test(current.output)||current.exited,'bootstrap',90000)
  assert(!current.exited,current.output.slice(-5000));assert.match(current.output,/NordBans 1\.2\.0 enabled/)
  assert.match(current.output,/LOCAL_NORDBANS_PROBE_READY/);assert.match(current.output,new RegExp(platform+' version'))
  console.log('LOCAL '+platform+' ready, PID '+current.child.pid)
}
async function stop() { if(current&&!current.exited){cmd('stop');await until(()=>current.exited,'shutdown',40000)} }
async function main() {
  assert(!await new Promise(resolve=>{const s=net.connect({host:'127.0.0.1',port:25636});s.on('connect',()=>{s.destroy();resolve(true)});s.on('error',()=>resolve(false))}),'Port occupied')
  fs.mkdirSync(banDir,{recursive:true})
  for(const item of ['server.jar','cache','libraries','eula.txt']) {
    const source=path.join(seed,'server',item)
    if(fs.existsSync(source))fs.cpSync(source,path.join(server,item),{recursive:true})
  }
  assert(fs.readFileSync(path.join(server,'eula.txt'),'utf8').includes('eula=true'))
  fs.copyFileSync(path.join(__dirname,'../target/NordBans-1.2.0.jar'),path.join(server,'plugins/NordBans-1.2.0.jar'))
  fs.copyFileSync(path.join(__dirname,'../target/NordBansLocalTestProbe.jar'),path.join(server,'plugins/NordBansLocalTestProbe.jar'))
  const config=fs.readFileSync(path.join(__dirname,'../src/main/resources/config.yml'),'utf8').replace('maximum-pending-operations: 128','maximum-pending-operations: 2').replace('messages-per-tick: 10','messages-per-tick: 3')
  fs.writeFileSync(path.join(banDir,'config.yml'),config)
  fs.writeFileSync(path.join(server,'server.properties'),'server-ip=127.0.0.1\nserver-port=25636\nonline-mode=false\nenforce-secure-profile=false\nenable-rcon=false\nenable-query=false\nmax-players=20\nview-distance=2\nsimulation-distance=2\nlevel-name=NordBansLocalTest\nspawn-protection=0\n')
  let records='# SYNTHETIC ACCOUNTS ONLY\n';const encode=v=>Buffer.from(v).toString('base64url')
  for(let i=0;i<18;i++)records+=`seed${i}=${Date.now()+3600000}|${encode('Seed'+i)}|${encode('Local seed')}|${encode('Console')}\n`
  fs.writeFileSync(banFile,records)
  await ready()
  const keeper=connect('NBKeeper');await until(()=>keeper.bot.entity,'carrier spawn')
  await until(()=>keeper.frames.length===18,'one legacy baseline')
  assert(keeper.frames.every(f=>f.action==='BAN'&&f.name.startsWith('Seed')))
  const target=connect('NBTarget');await until(()=>target.bot.entity,'target spawn');await sleep(1500)
  assert.equal(keeper.frames.length+target.frames.length,18)
  pass('Legacy bans load and send one V1 baseline; another join does not resend it')
  target.bot.chat('/tempban NBKeeper 5m unauthorized');await sleep(700)
  assert(!(await state()).active.includes('NBKeeper'));pass('Unprivileged player cannot ban')
  cmd('tempban NBTarget 5m Local moderation');await waitState(s=>s.active.includes('NBTarget'),'durable ban')
  await until(()=>target.ended,'entity-owned kick');assert.match(target.kicked,/NORD_BAN_V1/)
  await until(()=>keeper.frames.some(f=>f.name==='NBTarget'&&f.action==='BAN'),'BAN wire frame')
  const denied=connect('NBTarget');await until(()=>denied.ended,'prelogin denial');assert.match(denied.kicked,/NORD_BAN_V1/)
  pass('Console BAN commits, kicks on entity scheduler, sends V1 and blocks reconnect')
  cmd('nbtest fault true');await until(()=>/NBFAULT true/.test(current.output),'fault enabled')
  const before=fs.readFileSync(banFile);cmd('unban NBTarget')
  await until(()=>/Ban change failed before publication/.test(current.output),'storage error');await waitState(s=>s.pending===0,'error drained')
  assert((await state()).active.includes('NBTarget'));assert.deepEqual(fs.readFileSync(banFile),before)
  cmd('tempban NBFailed 5m Disk error');await sleep(700)
  assert(!(await state()).active.includes('NBFailed'));assert.deepEqual(fs.readFileSync(banFile),before)
  pass('Write errors preserve enforced bans and never publish failed BAN/UNBAN')
  cmd('nbtest fault false');await until(()=>/NBFAULT false/.test(current.output),'fault disabled')
  const ticks=(await state()).ticks;cmd('nbtest hold 1500');await waitState(s=>s.pending===1,'slow worker')
  cmd('tempban NBOverflowA 5m Accepted');cmd('tempban NBOverflowB 5m Rejected')
  await until(()=>/Ban storage is busy/.test(current.output),'overload')
  await waitState(s=>s.pending===0&&s.active.includes('NBOverflowA'),'capacity restored')
  const after=await state();assert(!after.active.includes('NBOverflowB'));assert(after.ticks-ticks>=15)
  pass('Bounded storage overload rejects extra work while scheduler ticks continue')
  cmd('nbtest grant NBKeeper');await until(()=>/NBGRANT NBKeeper/.test(current.output),'explicit test permission')
  keeper.bot.chat('/tempban NBPlayerCmd 5m Player action');await waitState(s=>s.active.includes('NBPlayerCmd'),'player ban')
  await until(()=>keeper.messages.some(m=>m.includes('NBPlayerCmd')),'player callback')
  keeper.bot.chat('/unban NBPlayerCmd');await waitState(s=>!s.active.includes('NBPlayerCmd'),'player unban')
  await until(()=>keeper.frames.some(f=>f.name==='NBPlayerCmd'&&f.action==='UNBAN'),'player UNBAN frame')
  pass('Authorized player commands return feedback on their owning scheduler')
  await disconnect(keeper);await sleep(400)
  cmd('unban NBTarget');await waitState(s=>!s.active.includes('NBTarget')&&s.releases.includes('NBTarget'),'unban without carrier')
  await stop();await ready()
  const recovered=connect('NBRestart');await until(()=>recovered.bot.entity,'recovery carrier')
  await until(()=>recovered.frames.some(f=>f.name==='NBTarget'&&f.action==='UNBAN'),'persistent tombstone recovery')
  const released=connect('NBTarget');await until(()=>released.bot.entity,'released account admitted');await disconnect(released)
  pass('UNBAN without a carrier survives restart and sends on recovery; account rejoins')
  cmd('nbtest expire NBExpired 1');await waitState(s=>s.active.includes('NBExpired'),'short ban');await sleep(1500)
  assert(!(await state()).active.includes('NBExpired'))
  const expired=connect('NBExpired');await until(()=>expired.bot.entity,'expired login');await disconnect(expired)
  pass('Expired bans stop blocking login before background cleanup')
  await disconnect(recovered)
  assert(handles.every(h=>!/Thread failed main thread check|Cannot read world asynchronously|ConcurrentModificationException|UnsupportedOperationException|NoSuchMethodError|Ban synchronization deferred|Ban completion failed|Exception executing task/.test(h.output)))
  pass('Normal-operation logs contain no thread ownership or compatibility errors')
  cmd('nbtest disable');await until(()=>current.exited,'manual disable shutdown',40000)
  assert.match(current.output,/NordBans disabled on a running server/);pass('Manual disable shuts the server down rather than bypassing bans')
  fs.writeFileSync(banFile,'broken=not-a-record\n');await start();await until(()=>current.exited,'corrupt startup shuts down',90000)
  assert.match(current.output,/Ban initialization failed/);assert(!/NordBans 1\.2\.0 enabled/.test(current.output))
  pass('Corrupt ban storage fails closed and requests shutdown')
  fs.writeFileSync(path.join(root,'integration-results.json'),JSON.stringify({platform,total:passed.length,passed,fixture:'localhost:25636; synthetic accounts only; no proxy or production data; not a 1000-player load test'},null,2))
  console.log('ALL '+passed.length+' '+platform+' BACKEND SCENARIOS PASSED')
}
main().catch(e=>{console.error(e.stack);process.exitCode=1}).finally(async()=>{
  for(const c of clients)if(!c.ended)c.bot.quit()
  await stop().catch(()=>current.child.kill())
  for(const [i,h]of handles.entries())fs.writeFileSync(path.join(root,'server-'+i+'-test-output.log'),h.output)
})
