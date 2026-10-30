"""Read Minecraft 1.7.10 game sessions from a pcap (offline mode only: no encryption) or a NetworkHorizons
capture (.nhcap.gz, written by /neth capture or /nethc capture, works with encryption on).

Rebuilds each TCP direction, splits frames and labels them by packet class, or by channel and first payload byte
for custom payloads. Handles the Hodgepodge wire changes (5-byte frame length, extended S3F length, S35 byte arrays).
"""
import collections
import csv
import gzip
import os
import shutil
import struct
import subprocess
import zlib
from dataclasses import dataclass

S2C_NAMES = """KeepAlive JoinGame Chat TimeUpdate EntityEquipment SpawnPosition UpdateHealth Respawn PlayerPosLook
HeldItemChange UseBed Animation SpawnPlayer CollectItem SpawnObject SpawnMob SpawnPainting SpawnExperienceOrb
EntityVelocity DestroyEntities Entity EntityRelMove EntityLook EntityLookMove EntityTeleport EntityHeadLook EntityStatus
EntityAttach EntityMetadata EntityEffect RemoveEntityEffect SetExperience EntityProperties ChunkData MultiBlockChange
BlockChange BlockAction BlockBreakAnim MapChunkBulk Explosion Effect SoundEffect Particles ChangeGameState
SpawnGlobalEntity OpenWindow CloseWindow SetSlot WindowItems WindowProperty ConfirmTransaction UpdateSign Maps
UpdateTileEntity SignEditorOpen Statistics PlayerListItem PlayerAbilities TabComplete ScoreboardObjective UpdateScore
DisplayScoreboard Teams CustomPayload Disconnect""".split()
C2S_NAMES = """KeepAlive ChatMessage UseEntity Player PlayerPosition PlayerLook PlayerPosLook PlayerDigging
PlayerBlockPlacement HeldItemChange Animation EntityAction Input CloseWindow ClickWindow ConfirmTransaction
CreativeInventoryAction EnchantItem UpdateSign PlayerAbilities TabComplete ClientSettings ClientStatus
CustomPayload""".split()

S2C, C2S = "S2C", "C2S"
CHUNK_IDS = {0x21, 0x26}  # S21PacketChunkData, S26PacketMapChunkBulk: already deflated
S35_ID = 0x35


@dataclass
class Frame:
    time: float
    dir: str
    state: str
    size: int  # framed size, length prefix included
    body: bytes  # packet id + data
    tick: int = -1  # server tick, captures made on a server only

    @property
    def pid(self):
        return varint(self.body, 0)[0]


@dataclass
class Session:
    stream: int
    frames: list
    segments: dict  # dir -> TCP data segment count

    @property
    def start(self):
        return self.frames[0].time

    @property
    def end(self):
        return self.frames[-1].time


def varint(b, i):
    v = s = 0
    for _ in range(5):
        x = b[i]
        i += 1
        v |= (x & 0x7F) << s
        s += 7
        if not x & 0x80:
            return v, i
    raise ValueError("varint too long")


def find_tshark():
    path = os.environ.get("TSHARK") or shutil.which("tshark")
    if not path:
        for p in (r"C:\Program Files\Wireshark\tshark.exe", "/usr/bin/tshark", "/Applications/Wireshark.app/Contents/MacOS/tshark"):
            if os.path.exists(p):
                path = p
                break
    if not path:
        raise SystemExit("tshark not found: install Wireshark or set TSHARK")
    return path


def tshark_fields(pcap, display_filter, fields, extra=()):
    cmd = [find_tshark(), "-r", pcap, "-o", "tcp.desegment_tcp_streams:FALSE", *extra, "-Y", display_filter,
           "-T", "fields", "-E", "separator=,", *[a for f in fields for a in ("-e", f)]]
    out = subprocess.run(cmd, capture_output=True, text=True, check=True).stdout
    csv.field_size_limit(1 << 30)
    return list(csv.reader(out.splitlines()))


def server_ports(pcap):
    """Destination ports of client SYNs, keyed by TCP stream."""
    rows = tshark_fields(pcap, "tcp.flags.syn==1 && tcp.flags.ack==0", ["tcp.stream", "tcp.dstport"])
    return {int(st): port for st, port in rows}


def load(path):
    """Sessions from a pcap or a NetworkHorizons capture, by file name."""
    if path.endswith(".nhcap.gz") or path.endswith(".nhcap"):
        return [load_capture(path)]
    return load_sessions(path)


CAPTURE_STATES = {0: "HANDSHAKE", 1: "PLAY", 2: "STATUS", 3: "LOGIN"}


def load_capture(path):
    """One session from a capture. Directions are turned into S2C / C2S from the side that wrote it."""
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rb") as f:
        data = f.read()
    if data[:5] != b"NHCAP" or data[5] != 1:
        raise SystemExit(f"{path}: not a version 1 NetworkHorizons capture")
    client = data[6] == 1
    n = struct.unpack_from(">H", data, 7)[0]
    i = 9 + n + 8  # label, start epoch millis
    frames = []
    while i < len(data):
        nanos, out, state, tick = struct.unpack_from(">qBbi", data, i)
        ln, j = varint(data, i + 14)
        body = data[j:j + ln]
        i = j + ln
        s2c = (out == 1) != client
        state_name = CAPTURE_STATES.get(state, f"STATE{state}")
        frames.append(Frame(nanos / 1e9, S2C if s2c else C2S, state_name, _vlen(ln) + ln, body, tick))
    return Session(0, frames, {})


def _vlen(n):
    c = 1
    while n >= 0x80:
        n >>= 7
        c += 1
    return c


def load_sessions(pcap):
    """All play sessions in the capture (status pings are skipped), oldest first."""
    ports = server_ports(pcap)
    rows = tshark_fields(pcap, "tcp.len>0", ["tcp.stream", "tcp.srcport", "tcp.seq", "frame.time_relative", "tcp.payload"],
                         [a for p in set(ports.values()) for a in ("-d", f"tcp.port=={p},data")])
    streams = collections.defaultdict(lambda: {S2C: [], C2S: []})
    for st, sport, seq, t, payload in rows:
        st = int(st)
        if st in ports:
            streams[st][S2C if sport == ports[st] else C2S].append((int(seq), float(t), bytes.fromhex(payload)))

    sessions = []
    for st, dirs in sorted(streams.items()):
        frames = []
        for d, segs in dirs.items():
            buf, marks = _reassemble(segs)
            frames += _split(buf, marks, d)
        frames.sort(key=lambda f: f.time)
        if _is_play_session(frames):
            sessions.append(Session(st, frames, {d: len(s) for d, s in dirs.items()}))
    return sessions


def _reassemble(segs):
    buf = bytearray()
    marks = []  # (end offset, time): a frame gets the time of the segment holding its last byte
    expect = None
    for seq, t, p in segs:
        if expect is not None and seq < expect:
            p = p[expect - seq:]  # retransmission
            if not p:
                continue
        elif expect is not None and seq > expect:
            raise ValueError(f"gap in TCP stream at seq {expect} (lost capture packets?)")
        buf += p
        marks.append((len(buf), t))
        expect = (expect if expect is not None and seq < expect else seq) + len(p)
    return bytes(buf), marks


def _split(buf, marks, d):
    frames = []
    i = mi = 0
    state = "HANDSHAKE" if d == C2S else "LOGIN"
    while i < len(buf):
        start = i
        try:
            ln, i = varint(buf, i)
        except IndexError:
            break
        if i + ln > len(buf):
            break  # capture ended mid-frame
        body = buf[i:i + ln]
        i += ln
        while marks[mi][0] < i:
            mi += 1
        frames.append(Frame(marks[mi][1], d, state, i - start, body))
        pid = varint(body, 0)[0]
        if state == "HANDSHAKE":
            state = "LOGIN" if body[-1] == 2 else "STATUS"
        elif state == "LOGIN" and (d == C2S and pid == 0 or d == S2C and pid == 2):
            state = "PLAY"  # offline mode: no encryption request
    return frames


def _is_play_session(frames):
    return any(f.state == "PLAY" for f in frames)


def key(f, tile_entity_ids=False):
    """Label for stats: packet class, '[channel]#first_byte' for custom payloads, optionally 'S35 <TE id>'."""
    pid, i = varint(f.body, 0)
    if f.state != "PLAY":
        return f"{f.state}#{pid}"
    names = S2C_NAMES if f.dir == S2C else C2S_NAMES
    name = names[pid] if pid < len(names) else f"#{pid}"
    if name == "CustomPayload":
        n, i = varint(f.body, i)
        channel = f.body[i:i + n].decode("utf8", "replace")
        i += n
        if f.dir == S2C:
            # FML var-short, Hodgepodge-extended: 0xFFFFFF marker then varint for huge payloads
            low = struct.unpack_from(">H", f.body, i)[0]
            i += 2
            if low & 0x8000:
                high = f.body[i]
                i += 1
                if (high << 15) | (low & 0x7FFF) == 0x7FFFFF:
                    i = varint(f.body, i)[1]
        else:
            i += 2
        sub = f.body[i] if i < len(f.body) else -1
        return f"[{channel}]#{sub}"
    if name == "UpdateTileEntity" and tile_entity_ids:
        return f"S35 {tile_entity_id(f.body, i)}"
    return name


def tile_entity_id(body, i):
    # int x, short y, int z, ubyte action, then Hodgepodge marker: 0 = NBT follows, else byte array key
    action = body[i + 10]
    marker = body[i + 11]
    j = i + 12
    if marker != 0:
        return f"bytearray[{chr(marker)}]"
    ln = struct.unpack_from(">h", body, j)[0]
    j += 2
    if ln == 32767:
        ln = struct.unpack_from(">i", body, j)[0]
        j += 4
    if ln < 0:
        return "null"
    try:
        # raw inflate after the 10-byte gzip header: Hodgepodge's pooled gzip writes a wrong trailer
        nbt = zlib.decompressobj(-15).decompress(body[j + 10:j + ln])
    except zlib.error:
        return "?nbt"
    at = nbt.find(b"\x08\x00\x02id")
    if at >= 0:
        n = struct.unpack_from(">H", nbt, at + 5)[0]
        return nbt[at + 7:at + 7 + n].decode("utf8", "replace")
    return f"action{action}"
