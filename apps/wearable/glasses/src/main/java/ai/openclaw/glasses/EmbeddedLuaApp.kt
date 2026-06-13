package ai.openclaw.glasses

/**
 * On-device Lua app for the Brilliant Frame.
 *
 * Two firmware facts driving this design (discovered via on-device probes):
 *  - Frame's `frame.file.open(name, 'a')` does NOT append — only 'w' truncate
 *    and 'r' read work. So we can't chunk a long file by repeated open+append.
 *    Instead we build the source string in `_G.oc` across many tiny eval
 *    chunks, then do one `open('w')`/write/close at the end.
 *  - Frame's Lua has no standard `package` table — `require()` exists but
 *    `package.loaded` does not, so we can't clear a require cache. We bypass
 *    `require` entirely with `load(code)()` to always run fresh code.
 *
 * Also: camera is buffer-based (`frame.camera.read(n)` after `capture()`),
 * NOT file-based.
 *
 * All persistent state is in `_G.*` so it survives whatever scoping the REPL
 * applies to each eval (we've seen evidence that bare locals don't persist).
 */
object EmbeddedLuaApp {

    // Frame -> phone channels (Frame auto-prepends 0x01 to every bluetooth.send).
    const val CH_TAP: Byte = 0x20
    const val CH_HDG: Byte = 0x21
    const val CH_BAT: Byte = 0x30
    const val CH_CAM_CHUNK: Byte = 0x51
    const val CH_CAM_DONE: Byte = 0x52
    const val CH_AUDIO_CHUNK: Byte = 0x53
    const val CH_AUDIO_DONE: Byte = 0x54
    const val CH_STATUS: Byte = 0x7D
    const val STATUS_PONG: Byte = 0x03
    const val STATUS_BOOT: Byte = 0x04

    // Phone -> Frame command channels (we prepend 0x01 in OcGlassesClient.sendData).
    const val CMD_CAMERA: Byte = 0x50
    const val CMD_TEXT_SHOW: Byte = 0x60
    const val CMD_TEXT_CLEAR: Byte = 0x61
    const val CMD_MIC_START: Byte = 0x62
    const val CMD_MIC_STOP: Byte = 0x63
    const val CMD_PALETTE_DEMO: Byte = 0x64
    const val CMD_CORNER_DEMO: Byte = 0x65
    const val CMD_COLOR_TEST: Byte = 0x66
    const val CMD_BITMAP_SHOW: Byte = 0x67
    const val CMD_LETTERBOX_DEMO: Byte = 0x68
    const val CMD_BITMAP_BEGIN: Byte = 0x69
    const val CMD_BITMAP_CHUNK: Byte = 0x6A
    const val CMD_BITMAP_END: Byte = 0x6B
    const val CMD_PING: Byte = 0x7F

    const val STATUS_MIC_ERR: Byte = 0x05

    // Frame's 16-color palette, in index order. Phone sends an index 0..15;
    // Lua looks the name up before calling frame.display.text{color=...}.
    val PALETTE: List<String> = listOf(
        "VOID", "WHITE", "GREY", "RED",
        "PINK", "DARKBROWN", "BROWN", "ORANGE",
        "YELLOW", "DARKGREEN", "GREEN", "LIGHTGREEN",
        "NIGHTBLUE", "SEABLUE", "SKYBLUE", "CLOUDBLUE",
    )

    // Legacy alias used by older callers.
    const val CH_CAM_REQUEST: Byte = CMD_CAMERA

    // Lua source. ASCII-only (chunk size math is byte-based) and uses _G.NAME
    // for every global so per-eval REPL scoping can't lose state.
    //
    // Per Frame BLE spec: "While a Lua script is running, Frame will ignore
    // any other Lua strings that are sent over Bluetooth." So once this
    // script enters its while-true loop, plain-text eval writes from the
    // phone are DROPPED. All runtime commands MUST therefore come in as
    // 0x01-prefixed data writes that hit the receive_callback registered
    // below. Acks come back on CH_STATUS so the phone UI can confirm each
    // command landed.
    private const val SOURCE: String = """-- OpenClaw glasses_app v11
_G.CH_TAP = 0x20
_G.CH_HDG = 0x21
_G.CH_BAT = 0x30
_G.CH_CAMC = 0x51
_G.CH_CAMD = 0x52
_G.CH_AUDC = 0x53
_G.CH_AUDD = 0x54
_G.CH_STATUS = 0x7D
_G.S_PONG = 0x03
_G.S_BOOT = 0x04
_G.OC_COLORS = {"VOID","WHITE","GREY","RED","PINK","DARKBROWN","BROWN","ORANGE","YELLOW","DARKGREEN","GREEN","LIGHTGREEN","NIGHTBLUE","SEABLUE","SKYBLUE","CLOUDBLUE"}
function _G.oc_send(ch, p)
if p == nil then p = "" end
local ok = pcall(function() frame.bluetooth.send(string.char(ch) .. p) end)
return ok
end
function _G.oc_send_retry(ch, p)
for i = 1, 8 do
if _G.oc_send(ch, p) then return true end
frame.sleep(0.02)
end
return false
end
function _G.oc_chunk_size()
local m = 200
if frame.bluetooth.max_length then m = frame.bluetooth.max_length() end
if m < 21 then m = 21 end
return m - 1
end
if frame.imu and frame.imu.tap_callback then
pcall(function() frame.imu.tap_callback(function() _G.oc_send(_G.CH_TAP) end) end)
end
function _G.oc_f32(x)
if string.pack then return string.pack("<f", x) end
if x == 0 then return "\0\0\0\0" end
local sign = 0
if x < 0 then sign = 1 end
x = math.abs(x)
local e = math.floor(math.log(x) / math.log(2))
local m = x / (2 ^ e) - 1
local mant = math.floor(m * 2 ^ 23 + 0.5)
local biased = e + 127
local b0 = mant % 256
local b1 = math.floor(mant / 256) % 256
local b2 = (math.floor(mant / 65536) % 128) + (biased % 2) * 128
local b3 = math.floor(biased / 2) + sign * 128
return string.char(b0, b1, b2, b3)
end
function _G.oc_camera()
local ok1 = pcall(function() frame.camera.capture() end)
if not ok1 then
pcall(function() _G.oc_send(_G.CH_CAMD) end)
return
end
frame.sleep(1.0)
local cs = _G.oc_chunk_size()
local total = 0
local sent_ok = 0
while true do
local ok2, chunk = pcall(function() return frame.camera.read(cs) end)
if not ok2 then break end
if chunk == nil then break end
if #chunk == 0 then break end
if _G.oc_send_retry(_G.CH_CAMC, chunk) then
sent_ok = sent_ok + 1
total = total + #chunk
end
frame.sleep(0.01)
end
pcall(function() _G.oc_send(_G.CH_CAMD) end)
end
function _G.oc_status(kind, payload)
if payload == nil then payload = "" end
_G.oc_send_retry(_G.CH_STATUS, string.char(kind) .. payload)
end
function _G.oc_handle_ping(p)
local nonce = 0
if #p >= 1 then nonce = string.byte(p, 1) end
_G.oc_status(_G.S_PONG, string.char(nonce))
end
function _G.oc_text_show(ci, x, y, sp, t)
local c = _G.OC_COLORS[ci + 1]
if c == nil then c = "WHITE" end
if sp == nil or sp == 0 then sp = 4 end
local ok, err = pcall(function()
local cy = y
local s = 1
while true do
local nl = string.find(t, "\n", s, true)
local line
if nl then line = string.sub(t, s, nl - 1); s = nl + 1
else line = string.sub(t, s) end
if line and #line > 0 then
frame.display.text(line, x, cy, {color = c, spacing = sp})
end
cy = cy + 32
if not nl then break end
end
frame.display.show()
end)
if not ok then print('text ERR='..tostring(err)) end
end
function _G.oc_text_clear()
pcall(function() frame.display.show() end)
end
function _G.oc_palette_demo()
pcall(function()
for i = 1, 16 do
local row = math.floor((i - 1) / 4)
local col = (i - 1) % 4
local x = 10 + col * 160
local y = 10 + row * 100
frame.display.text(_G.OC_COLORS[i], x, y, {color = _G.OC_COLORS[i]})
end
frame.display.show()
end)
end
function _G.oc_corner_demo()
pcall(function()
frame.display.text("0,0", 1, 1, {color = "WHITE"})
frame.display.text("320,1", 290, 1, {color = "WHITE"})
frame.display.text("640,1", 580, 1, {color = "WHITE"})
frame.display.text("0,200", 1, 185, {color = "WHITE"})
frame.display.text("CENTER", 280, 185, {color = "YELLOW"})
frame.display.text("640,200", 560, 185, {color = "WHITE"})
frame.display.text("0,400", 1, 370, {color = "WHITE"})
frame.display.text("320,400", 280, 370, {color = "WHITE"})
frame.display.text("640,400", 560, 370, {color = "WHITE"})
frame.display.show()
end)
end
-- Tries every form of the color arg we can think of so the wearer can see
-- which one their firmware actually honors. Anything that renders in any
-- colour other than the "no color" baseline is a working form.
function _G.oc_color_test()
pcall(function()
frame.display.text("str RED", 30, 10, {color = "RED"})
frame.display.text("str GREEN", 30, 50, {color = "GREEN"})
frame.display.text("str SKYBLUE", 30, 90, {color = "SKYBLUE"})
frame.display.text("int 4 PINK", 30, 130, {color = 4})
frame.display.text("int 8 YELLOW", 30, 170, {color = 8})
frame.display.text("int 11 LGRN", 30, 210, {color = 11})
frame.display.text("int 15 SKY", 30, 250, {color = 15})
frame.display.text("default (no arg)", 30, 290, {})
frame.display.text("WHITE baseline", 30, 330, {color = "WHITE"})
frame.display.show()
end)
end
-- bitmap draw. cf=2/4/16 is Frame's color_format arg (number of palette
-- colors used by the data — 2 = 1bpp, 4 = 2bpp, 16 = 4bpp). po is the
-- palette offset (foreground colour index for cf=2). Calls show() so a
-- single-bitmap draw lands on the display without the caller needing a
-- separate show command.
function _G.oc_bitmap(x, y, w, cf, po, data)
local ok, err = pcall(function()
frame.display.bitmap(x, y, w, cf, po, data)
frame.display.show()
end)
if not ok then print('bm ERR='..tostring(err)) end
end
-- Lifted from FrameDinoGame's set_viewport: draws 20-px-wide solid columns
-- at each side of the 640-wide screen to letterbox the playable / readable
-- area. Uses WHITE (palette index 1) so it's clearly visible during testing;
-- production callers can re-send their own bitmap with a different colour.
function _G.oc_letterbox()
local ok, err = pcall(function()
local block = string.rep("\xFF", math.ceil(20 * 400 / 8))
frame.display.bitmap(1, 1, 20, 2, 1, block)
frame.display.bitmap(620, 1, 20, 2, 1, block)
frame.display.show()
end)
if not ok then print('lb ERR='..tostring(err)) end
end
-- Chunked bitmap upload. Lua string concat is O(n²) (strings are immutable),
-- so for a full-screen 640×400 1bpp = 32 KB blit we accumulate into a TABLE
-- of chunks and join with table.concat at end (O(n)). Single packets still go
-- through oc_bitmap above — this path is only for sprites bigger than maxWrite.
function _G.oc_bm_begin(x, y, w, cf, po)
_G.oc_bm_x = x; _G.oc_bm_y = y; _G.oc_bm_w = w
_G.oc_bm_cf = cf; _G.oc_bm_po = po
_G.oc_bm_parts = {}
end
function _G.oc_bm_chunk(data)
if _G.oc_bm_parts == nil then return end
_G.oc_bm_parts[#_G.oc_bm_parts + 1] = data
end
function _G.oc_bm_end()
if _G.oc_bm_parts == nil then return end
local data = table.concat(_G.oc_bm_parts)
local ok, err = pcall(function()
frame.display.bitmap(_G.oc_bm_x, _G.oc_bm_y, _G.oc_bm_w, _G.oc_bm_cf, _G.oc_bm_po, data)
frame.display.show()
end)
_G.oc_bm_parts = nil
if not ok then print('bm end ERR='..tostring(err)) end
end
_G.oc_mic_on = false
_G.oc_mic_cs = 200
_G.oc_mic_empty = 0
_G.oc_mic_sent = 0
function _G.oc_mic_start(rate, depth)
_G.oc_mic_cs = _G.oc_chunk_size()
_G.oc_mic_empty = 0
_G.oc_mic_sent = 0
if not frame.microphone then
print('mic FAIL: frame.microphone is nil')
_G.oc_status(0x05, "no_api")
return
end
local ok, err = pcall(function()
frame.microphone.start{sample_rate = rate, bit_depth = depth}
end)
if ok then
_G.oc_mic_on = true
print('mic on '..tostring(rate)..'Hz '..tostring(depth)..'b cs='..tostring(_G.oc_mic_cs))
-- One-shot probe: wait briefly then explicitly read. This tells us what
-- the firmware returns right after start (nil/empty/data), which the
-- background pump can hide if it returns early on the first empty read.
frame.sleep(0.25)
local pok, pdata = pcall(function() return frame.microphone.read(_G.oc_mic_cs) end)
local plen
if pdata then plen = #pdata else plen = -1 end
print('mic probe ok='..tostring(pok)..' t='..type(pdata)..' len='..tostring(plen))
if pok and type(pdata) == "string" and #pdata > 0 then
_G.oc_send_retry(_G.CH_AUDC, pdata)
_G.oc_mic_sent = _G.oc_mic_sent + #pdata
end
else
_G.oc_mic_on = false
print('mic ERR='..tostring(err))
_G.oc_status(0x05, string.sub(tostring(err), 1, 32))
end
end
function _G.oc_mic_stop()
_G.oc_mic_on = false
pcall(function() frame.microphone.stop() end)
local cs = _G.oc_mic_cs
local guard = 0
local drained = 0
while guard < 256 do
local ok, data = pcall(function() return frame.microphone.read(cs) end)
if not ok then break end
if data == nil then break end
if #data == 0 then break end
if _G.oc_send_retry(_G.CH_AUDC, data) then drained = drained + #data end
guard = guard + 1
end
print('mic stop sent='..tostring(_G.oc_mic_sent)..' drain='..tostring(drained)..' empty='..tostring(_G.oc_mic_empty))
pcall(function() _G.oc_send(_G.CH_AUDD) end)
end
function _G.oc_mic_pump()
if not _G.oc_mic_on then return end
local cs = _G.oc_mic_cs
for i = 1, 6 do
local ok, data = pcall(function() return frame.microphone.read(cs) end)
if not ok then
print('mic read FAIL')
_G.oc_mic_on = false
return
end
if data == nil then
_G.oc_mic_on = false
pcall(function() _G.oc_send(_G.CH_AUDD) end)
print('mic read nil (stopped)')
return
end
if #data == 0 then
_G.oc_mic_empty = _G.oc_mic_empty + 1
if _G.oc_mic_empty == 1 then print('mic read empty (waiting)') end
if _G.oc_mic_empty == 200 then print('mic read empty x200 - no audio coming') end
return
end
if _G.oc_send_retry(_G.CH_AUDC, data) then
_G.oc_mic_sent = _G.oc_mic_sent + #data
if _G.oc_mic_sent == #data then print('mic first chunk #'..#data) end
else
print('mic send FAIL')
return
end
end
end
frame.bluetooth.receive_callback(function(d)
if d == nil then return end
if #d < 1 then return end
local ch = string.byte(d, 1)
local rest = string.sub(d, 2)
if ch == 0x50 then _G.oc_camera()
elseif ch == 0x7F then _G.oc_handle_ping(rest)
elseif ch == 0x60 then
if #rest >= 6 then
local ci = string.byte(rest, 1)
local x = string.byte(rest, 2) + string.byte(rest, 3) * 256
local y = string.byte(rest, 4) + string.byte(rest, 5) * 256
local sp = string.byte(rest, 6)
local t = string.sub(rest, 7)
_G.oc_text_show(ci, x, y, sp, t)
end
elseif ch == 0x61 then _G.oc_text_clear()
elseif ch == 0x62 then
if #rest >= 2 then
local r = string.byte(rest, 1) * 1000
local dp = string.byte(rest, 2)
_G.oc_mic_start(r, dp)
end
elseif ch == 0x63 then _G.oc_mic_stop()
elseif ch == 0x64 then _G.oc_palette_demo()
elseif ch == 0x65 then _G.oc_corner_demo()
elseif ch == 0x66 then _G.oc_color_test()
elseif ch == 0x67 then
-- bitmap: header is x(2) y(2) w(2) cf(1) po(1) = 8 bytes, then data.
if #rest >= 8 then
local x = string.byte(rest, 1) + string.byte(rest, 2) * 256
local y = string.byte(rest, 3) + string.byte(rest, 4) * 256
local w = string.byte(rest, 5) + string.byte(rest, 6) * 256
local cf = string.byte(rest, 7)
local po = string.byte(rest, 8)
local data = string.sub(rest, 9)
_G.oc_bitmap(x, y, w, cf, po, data)
end
elseif ch == 0x68 then _G.oc_letterbox()
elseif ch == 0x69 then
-- bitmap begin: same 8-byte header as 0x67 (x, y, w, cf, po), payload >= 8.
if #rest >= 8 then
local x = string.byte(rest, 1) + string.byte(rest, 2) * 256
local y = string.byte(rest, 3) + string.byte(rest, 4) * 256
local w = string.byte(rest, 5) + string.byte(rest, 6) * 256
local cf = string.byte(rest, 7)
local po = string.byte(rest, 8)
_G.oc_bm_begin(x, y, w, cf, po)
end
elseif ch == 0x6A then _G.oc_bm_chunk(rest)
elseif ch == 0x6B then _G.oc_bm_end()
end
end)
-- Initialise the 16-color display palette to known RGBs. Some firmware
-- builds ship with palette slots zeroed, which makes every text colour
-- render identical (usually black/invisible). Re-assigning here makes
-- the script self-contained regardless of boot state.
if frame.display and frame.display.assign_color then
pcall(function()
frame.display.assign_color("VOID", 0, 0, 0)
frame.display.assign_color("WHITE", 255, 255, 255)
frame.display.assign_color("GREY", 128, 128, 128)
frame.display.assign_color("RED", 255, 0, 0)
frame.display.assign_color("PINK", 255, 128, 192)
frame.display.assign_color("DARKBROWN", 92, 64, 51)
frame.display.assign_color("BROWN", 165, 110, 78)
frame.display.assign_color("ORANGE", 255, 140, 0)
frame.display.assign_color("YELLOW", 240, 240, 32)
frame.display.assign_color("DARKGREEN", 32, 96, 32)
frame.display.assign_color("GREEN", 32, 192, 64)
frame.display.assign_color("LIGHTGREEN", 128, 255, 128)
frame.display.assign_color("NIGHTBLUE", 16, 16, 64)
frame.display.assign_color("SEABLUE", 32, 96, 160)
frame.display.assign_color("SKYBLUE", 64, 160, 240)
frame.display.assign_color("CLOUDBLUE", 176, 224, 255)
end)
end
-- Boot status payload: byte0 = imu present (0/1), byte1 = camera present (0/1), byte2 = mic present (0/1).
do
local iv = 0
if frame.imu then iv = 1 end
local cv = 0
if frame.camera then cv = 1 end
local mv = 0
if frame.microphone then mv = 1 end
_G.oc_status(_G.S_BOOT, string.char(iv, cv, mv))
print('boot fw='..tostring(frame.FIRMWARE_VERSION)..' mic='..tostring(mv))
end
_G.oc_hd = 0
_G.oc_bd = 0
function _G.oc_main_tick()
_G.oc_mic_pump()
_G.oc_hd = _G.oc_hd + 1
if _G.oc_hd >= 10 then
_G.oc_hd = 0
if frame.imu and frame.imu.direction then
local ok, r, p, h = pcall(function()
local dd = frame.imu.direction()
if type(dd) == "table" then return dd.roll, dd.pitch, dd.heading end
return dd, 0, 0
end)
if ok and type(r) == "number" then
local pp = 0
local hh = 0
if type(p) == "number" then pp = p end
if type(h) == "number" then hh = h end
_G.oc_send(_G.CH_HDG, _G.oc_f32(r) .. _G.oc_f32(pp) .. _G.oc_f32(hh))
end
end
end
_G.oc_bd = _G.oc_bd + 1
if _G.oc_bd >= 50 then
_G.oc_bd = 0
if frame.battery_level then
local ok, lvl = pcall(function() return frame.battery_level() end)
if ok and type(lvl) == "number" then
_G.oc_send(_G.CH_BAT, string.char(math.floor(lvl)))
end
end
end
end
-- Outer loop wraps each tick in pcall so a transient error skips ONE
-- tick instead of crashing the whole script (previously a single
-- "bluetooth is busy" killed heading/battery forever).
while true do
pcall(_G.oc_main_tick)
frame.sleep(0.04)
end
"""

    /**
     * Uploads [SOURCE] using the in-memory-accumulator pattern, then runs it
     * via `load(code)()` (bypasses `require` and any caching).
     *
     *  1. `_G.oc = ""` to initialise (or reset) the accumulator
     *  2. N chunks of `_G.oc = _G.oc .. [=[…]=]` (each is a complete eval —
     *     no reliance on REPL state persisting across calls)
     *  3. Verify accumulator length before touching disk
     *  4. One open('w')/write/close to materialise main.lua
     *  5. Verify file length
     *  6. `load(_G.oc, 'main')()` to execute — always fresh, no require cache
     *  7. Clear `_G.oc` to free RAM (~4 KB on a 256 KB device, not critical
     *     but tidy)
     */
    suspend fun install(
        client: OcGlassesClient,
        gapMs: Long = 50L,
        delay: suspend (Long) -> Unit,
        log: (String) -> Unit,
    ) {
        require(SOURCE.all { it.code < 128 }) {
            "EmbeddedLuaApp.SOURCE contains non-ASCII chars; chunk-size math is byte-based"
        }
        val normalized = SOURCE.replace("\r\n", "\n").replace('\r', '\n')

        val level = pickBracketLevel(normalized)
        val open = "[" + "=".repeat(level) + "["
        val close = "]" + "=".repeat(level) + "]"
        // Each chunk eval is `_G.oc=_G.oc..[=[\n<body>]=]`. Wrapper overhead
        // (excluding the leading \n we prepend per chunk) is:
        //   13 chars `_G.oc=_G.oc..` + open + close
        // For level=1 that's 13 + 3 + 3 = 19 bytes. We subtract that plus a
        // small safety margin (newline + 1) to land safely under the link's
        // max write.
        // Each accumulator-chunk eval is:
        //   "_G.oc=_G.oc..[=[" + "\n" + <body> + "]=]"
        // i.e. 13 + open + 1 + body + close bytes on the wire.
        val accumOverhead = "_G.oc=_G.oc..".length + open.length + close.length + 1

        // Adapt to the BLE link's actual max write. If MTU negotiation gave
        // us 256, maxWrite ~ 252 and we can push ~50-byte bodies. If
        // negotiation came back at 23 (the BLE default) we get maxWrite ~ 20
        // and the link is too small for ANY install -- bail with a clear
        // error rather than the cryptic "write rejected" you get otherwise.
        // The 64-byte cap stays below Frame's REPL eval buffer; pushing
        // larger evals via the eval channel has been observed to fail even
        // when BLE MTU is higher.
        val maxWrite = client.transport.maxWriteLength
        val minAccumEval = accumOverhead + 4 // 4-byte body floor to make any progress
        if (maxWrite < minAccumEval) {
            error("BLE link too small: maxWrite=$maxWrite, need >= $minAccumEval. Disconnect, forget the bond, and reconnect to re-negotiate MTU.")
        }
        val targetWrite = maxWrite.coerceAtMost(64)
        val chunkSize = (targetWrite - accumOverhead).coerceAtLeast(4)
        // ACK-write transport already provides flow control, so a long gap
        // just slows install for no benefit. Keep a small gap so Frame's
        // REPL can parse each eval before the next lands.
        val effectiveGap = if (chunkSize >= 30) gapMs else 20L

        log("install: source=${normalized.length}B maxWrite=$maxWrite chunkSize=$chunkSize gap=${effectiveGap}ms")

        // Reusable size-guarded write. Every Lua eval MUST fit in maxWrite,
        // so we check before sending and fail loudly with the size, which
        // is far more useful than the generic "write rejected" you get
        // when the BLE layer trips on it.
        suspend fun sendEval(eval: String) {
            val bytes = eval.toByteArray(Charsets.UTF_8).size
            if (bytes > maxWrite) {
                error("eval too big: ${bytes}B > maxWrite=${maxWrite}B [${eval.take(30)}...]")
            }
            client.evalRaw(eval)
        }

        // 0. Break out of any running script so the REPL accepts our evals.
        //    Per Frame's BLE spec, a single 0x03 byte interrupts the current
        //    script; without this, Frame silently drops every Lua-text eval
        //    while main.lua's main loop is running — the failure mode that
        //    made earlier installs no-op against a device that already had
        //    a main.lua on flash. We deliberately don't send 0x04 (reset)
        //    because that re-runs main.lua immediately, defeating the break.
        runCatching { client.sendBreak() }
        delay(600)
        // Stub the old loop too, if the link can carry the eval. Harmless on
        // a fresh device. On small-MTU links the break alone has to suffice.
        val stubEval = "if _G.oc_main_tick then _G.oc_main_tick=function() end end"
        if (stubEval.length <= maxWrite) {
            runCatching { client.evalRaw(stubEval) }
            delay(200)
        }
        // Sentinel print — if it appears in the Frame log, REPL is accepting
        // evals. If it doesn't, we already know the install will fail.
        runCatching { sendEval("print('inst0 brk')") }
        delay(200)
        log("install: break sent, REPL should be idle")

        // 1. Init accumulator.
        sendEval("_G.oc=''")
        delay(effectiveGap)
        sendEval("print('inst1 init')")
        delay(effectiveGap)

        // 2. Stream chunks into the global.
        //    Lua's long-bracket rule strips an immediately-following newline.
        //    Prepending '\n' to every body absorbs that strip predictably:
        //    if body starts with '\n', the prepended one gets stripped and the
        //    original survives; if not, the prepended '\n' is the stripped one
        //    and the original body is intact.
        var i = 0
        var chunkIdx = 0
        while (i < normalized.length) {
            val end = minOf(i + chunkSize, normalized.length)
            val body = "\n" + normalized.substring(i, end)
            sendEval("_G.oc=_G.oc..$open$body$close")
            chunkIdx++
            i = end
            delay(effectiveGap)
        }
        log("install: streamed $chunkIdx chunks, settling")
        delay(SETTLE_MS)

        // Final steps get bigger gaps so each one's print output drains over
        // BLE before the next eval lands (prints were getting dropped at 50ms).
        val finalGap = 400L

        // 3. Verify accumulator size in RAM. Kept short to fit small MTUs;
        //    the expected size is in the install log already.
        sendEval("print('inst2 acc='..#_G.oc..'B')")
        delay(finalGap)

        // 4. Write to disk, phone-driven. The previous single-eval h:write loop
        //    was both too big for small MTU links AND only persisted ~26B
        //    because the Frame's underlying f:write truncates large strings.
        //    Driving the loop from Kotlin solves both: each chunk is a tiny
        //    `_G.h:write([=[<body>]=])` eval that fits the link and lands
        //    fully on disk.
        //
        //    `_G.h=frame.file.open('main.lua','w')` is 37 chars — if the link
        //    can't carry that, skip persistence and rely on load-from-RAM.
        val canPersist = "_G.h=frame.file.open('main.lua','w')".length <= maxWrite
        if (canPersist) {
            runCatching { sendEval("_G.h=frame.file.open('main.lua','w')") }
                .onFailure { log("install: skip disk persist (${it.message})") }
                .onSuccess {
                    delay(effectiveGap)
                    val writeWrapperOH = "_G.h:write($open$close)".length + 1 // +1 leading \n
                    val diskChunk = (maxWrite - writeWrapperOH).coerceAtLeast(4)
                    var p = 0
                    var diskIdx = 0
                    while (p < normalized.length) {
                        val end = minOf(p + diskChunk, normalized.length)
                        val body = "\n" + normalized.substring(p, end)
                        sendEval("_G.h:write($open$body$close)")
                        diskIdx++
                        p = end
                        delay(effectiveGap)
                    }
                    sendEval("_G.h:close()")
                    delay(effectiveGap)
                    sendEval("print('inst3 wrote n=${normalized.length}')")
                    log("install: persisted $diskIdx disk chunks")
                    delay(finalGap)

                    // 5. Verify on-disk size.
                    runCatching {
                        sendEval("_G.h=frame.file.open('main.lua','r')")
                        delay(effectiveGap)
                        sendEval("_G.c=_G.h:read('*a')")
                        delay(effectiveGap)
                        sendEval("_G.h:close()")
                        delay(effectiveGap)
                        sendEval("print('inst4 disk='..#_G.c..'B')")
                        delay(finalGap)
                        sendEval("_G.c=nil")
                    }.onFailure { log("install: disk verify failed (${it.message})") }
                }
        } else {
            log("install: maxWrite=$maxWrite too small for disk persist; running from RAM only")
        }

        // 6. Parse and execute.
        sendEval("_G.fn,_G.er=load(_G.oc,'main')")
        delay(effectiveGap)
        sendEval("print('inst5a fn='..tostring(_G.fn~=nil))")
        delay(effectiveGap)
        sendEval("print('inst5b er='..tostring(_G.er))")
        delay(finalGap)
        // exec — short eval. pcall blocks forever inside v9's main loop,
        // which is fine: subsequent evals are dropped (script running),
        // but the script IS now serving commands via its receive_callback.
        sendEval("if _G.fn then pcall(_G.fn) end")
        delay(effectiveGap)
    }

    private const val SETTLE_MS = 1500L

    private fun pickBracketLevel(source: String): Int {
        for (n in 1..16) {
            val candidate = "]" + "=".repeat(n) + "]"
            if (!source.contains(candidate)) return n
        }
        error("could not find safe bracket level")
    }

    val PROBE_APIS_LUA: String = listOf(
        """print("fw="..tostring(frame.FIRMWARE_VERSION).." hw="..tostring(frame.HARDWARE_VERSION))""",
        """for k,v in pairs(frame) do print("frame."..k.."="..type(v)) end""",
        """if frame.camera then for k,v in pairs(frame.camera) do print("cam."..k.."="..type(v)) end end""",
        """if frame.imu then for k,v in pairs(frame.imu) do print("imu."..k.."="..type(v)) end end""",
        """if frame.file then for k,v in pairs(frame.file) do print("file."..k.."="..type(v)) end end""",
        """if frame.display then for k,v in pairs(frame.display) do print("disp."..k.."="..type(v)) end end""",
        """if frame.microphone then for k,v in pairs(frame.microphone) do print("mic."..k.."="..type(v)) end else print("mic=nil") end""",
    ).joinToString(";")
}
