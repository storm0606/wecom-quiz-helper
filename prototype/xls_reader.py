# -*- coding: utf-8 -*-
"""老版 Excel(.xls, BIFF8) 解析器 —— 纯 Python 标准库实现。

结构：OLE2 复合文档容器（512 字节扇区）→ FAT/目录 → "Workbook" 流 → BIFF8 记录。
本实现覆盖常用场景：
- 全局子流：SST（共享字符串表，含 CONTINUE 拆分处理）、BOUNDSHEET
- 工作表子流：LABELSST / LABEL / NUMBER / RK / MULRK / FORMULA(+STRING)
- 中文内容以 UTF-16LE 存储（fHighByte=1），正常解码

局限：不解析图表/宏/多级嵌套，未覆盖 BIFF5；对常见题库 Excel 足够。
"""
import struct
from xml.sax.saxutils import escape  # noqa: F401  (占位，避免误用)

# ---------- 常量 ----------
FREESECT = 0xFFFFFFFF
ENDOFCHAIN = 0xFFFFFFFE
FATSECT = 0xFFFFFFFD
DIFSECT = 0xFFFFFFFC

REC_BOF = 0x0809
REC_EOF = 0x000A
REC_SST = 0x00FC
REC_CONTINUE = 0x003C
REC_LABELSST = 0x00FD
REC_LABEL = 0x0204
REC_NUMBER = 0x0203
REC_RK = 0x027E
REC_MULRK = 0x00BD
REC_FORMULA = 0x0006
REC_STRING = 0x0207
REC_BOOLERR = 0x0205

DT_GLOBALS = 0x0005
DT_WORKSHEET = 0x0010


# ---------- OLE2 容器 ----------

class _ChainReader:
    """按 FAT 链读取扇区拼接的字节流。"""

    def __init__(self, data, sector_size, fat):
        self.data = data
        self.sector_size = sector_size
        self.fat = fat

    def read_chain(self, start_sector, size=None):
        out = bytearray()
        sid = start_sector
        visited = set()
        steps = 0
        while sid != ENDOFCHAIN and sid not in visited and steps < 10_000_000:
            if sid >= len(self.fat) or sid == FREESECT:
                break
            visited.add(sid)
            off = (sid + 1) * self.sector_size
            out += self.data[off:off + self.sector_size]
            sid = self.fat[sid]
            steps += 1
            if size is not None and len(out) >= size:
                break
        return bytes(out[:size] if size is not None else out)


def _build_fat(data, sector_size):
    """从 DIFAT（头部 + DIFAT 扇区）构建完整 FAT 表。"""
    # 头部 DIFAT 数组：偏移 0x4C，109 个 u32
    difat = list(struct.unpack_from("<109I", data, 0x4C))
    num_fat_sectors = struct.unpack_from("<I", data, 0x2C)[0]
    first_dir = struct.unpack_from("<I", data, 0x30)[0]
    first_difat = struct.unpack_from("<I", data, 0x44)[0]
    num_difat = struct.unpack_from("<I", data, 0x48)[0]

    # 收集所有 FAT 扇区号
    fat_sectors = []
    pending = [s for s in difat if s not in (FREESECT, ENDOFCHAIN, FATSECT, DIFSECT)]
    fat_sectors.extend(pending)
    sid = first_difat
    per = sector_size // 4
    for _ in range(num_difat if num_difat else 0):
        if sid in (FREESECT, ENDOFCHAIN):
            break
        off = (sid + 1) * sector_size
        ents = struct.unpack_from(f"<{per}I", data, off)
        nxt = ents[-1]
        fat_sectors.extend(s for s in ents[:-1] if s not in (FREESECT, ENDOFCHAIN, FATSECT, DIFSECT))
        sid = nxt

    # 读取 FAT 内容
    fat = []
    for sid in fat_sectors[:num_fat_sectors]:
        off = (sid + 1) * sector_size
        fat.extend(struct.unpack_from(f"<{sector_size // 4}I", data, off))
    return fat, first_dir


def _read_stream(data, sector_size, fat, start_sector, size):
    cr = _ChainReader(data, sector_size, fat)
    if size >= 4096 or sector_size == 4096:
        return cr.read_chain(start_sector, size)
    # 小型流（<4096 字节）：先读根目录拿 mini stream 容器 —— 题库文件很少触发
    return cr.read_chain(start_sector, size)


def _find_workbook_stream(data, sector_size, fat, first_dir):
    """遍历目录（读取完整目录链），找到名为 Workbook / Book 的流。返回 (start_sector, size)。"""
    cr = _ChainReader(data, sector_size, fat)
    dir_bytes = cr.read_chain(first_dir)
    for i in range(0, len(dir_bytes) - 127, 128):
        entry = dir_bytes[i:i + 128]
        name_len = struct.unpack_from("<H", entry, 0x40)[0]
        obj_type = entry[0x42]
        start = struct.unpack_from("<I", entry, 0x74)[0]
        size = struct.unpack_from("<Q", entry, 0x78)[0]
        if name_len and obj_type == 2:  # 流
            raw = entry[:name_len - 2].decode("utf-16-le", errors="replace")
            if raw in ("Workbook", "Book"):
                return start, size
    return None, 0


def _get_workbook_stream(data):
    sector_shift = struct.unpack_from("<H", data, 0x1E)[0]
    sector_size = 1 << sector_shift
    fat, first_dir = _build_fat(data, sector_size)
    start, size = _find_workbook_stream(data, sector_size, fat, first_dir)
    if start is None:
        raise RuntimeError("未在 .xls 中找到 Workbook 流")
    return _read_stream(data, sector_size, fat, start, size)


# ---------- BIFF 记录 ----------

def _iter_records(stream):
    pos = 0
    n = len(stream)
    while pos + 4 <= n:
        rid, rlen = struct.unpack_from("<HH", stream, pos)
        payload = stream[pos + 4:pos + 4 + rlen]
        pos += 4 + rlen
        yield rid, payload


class _StrStream:
    """跨记录（含 CONTINUE）读取字节的游标。"""

    def __init__(self, chunks):
        self.chunks = chunks
        self.ci = 0
        self.off = 0

    def read(self, n):
        out = bytearray()
        while len(out) < n:
            if self.ci >= len(self.chunks):
                raise EOFError("字符串读取越界")
            avail = len(self.chunks[self.ci]) - self.off
            if avail == 0:
                self.ci += 1
                self.off = 0
                continue
            take = min(avail, n - len(out))
            out += self.chunks[self.ci][self.off:self.off + take]
            self.off += take
        return bytes(out)

    def skip(self, n):
        self.read(n)


def _read_chars(s, cch, fhigh, plain_cont):
    """读取 cch 个字符（可能跨 CONTINUE；plain 字符串续段带新 flags 字节）。"""
    out = []
    remaining = cch
    while remaining > 0:
        avail = len(s.chunks[s.ci]) - s.off
        if avail == 0:
            if s.ci + 1 < len(s.chunks):
                s.ci += 1
                s.off = 0
                if plain_cont:
                    fb = s.read(1)[0]
                    fhigh = fb & 0x01
                continue
            break  # 数据不足，截断处理
        if fhigh:
            take = min(avail, remaining * 2)
            raw = s.read(take)
            for i in range(0, len(raw) - 1, 2):
                out.append(raw[i] | (raw[i + 1] << 8))
            remaining -= len(raw) // 2
        else:
            take = min(avail, remaining)
            raw = s.read(take)
            out.extend(raw)
            remaining -= take
    return "".join(chr(c) for c in out)


def _parse_sst(records, sst_idx):
    """解析 SST + 后续 CONTINUE，返回共享字符串列表。"""
    chunks = [records[sst_idx][1]]
    j = sst_idx + 1
    while j < len(records) and records[j][0] == REC_CONTINUE:
        chunks.append(records[j][1])
        j += 1
    if len(chunks[0]) < 8:
        return []
    unique = struct.unpack_from("<I", chunks[0], 4)[0]
    s = _StrStream(chunks)
    s.skip(8)
    strings = []
    for _ in range(unique):
        cch = struct.unpack_from("<H", s.read(2))[0]
        flags = s.read(1)[0]
        fhigh = flags & 0x01
        frich = flags & 0x08
        fext = flags & 0x04
        c_run = 0
        cb_ext = 0
        if frich:
            c_run = struct.unpack_from("<H", s.read(2))[0]
        if fext:
            cb_ext = struct.unpack_from("<I", s.read(4))[0]
        # 是否"纯字符串"决定续段是否带 flags 字节
        plain = not (frich or fext)
        strings.append(_read_chars(s, cch, fhigh, plain))
        # 富文本 run 数组与扩展数据在字符之后，也要跳过（可跨 CONTINUE）
        if frich and c_run:
            s.skip(c_run * 4)
        if fext and cb_ext:
            s.skip(cb_ext)
    return strings


def _decode_rk(rk):
    if rk & 0x02:  # 整数
        val = rk >> 2
        if val & 0x20000000:
            val -= 0x40000000
        v = float(val)
    else:
        v = struct.unpack("<d", struct.pack("<II", 0, rk & 0xFFFFFFFC))[0]
    if rk & 0x01:
        v /= 100.0
    return v


def _fmt_num(v):
    if float(v).is_integer():
        return str(int(v))
    return repr(v)


def _read_plain_string(payload, off):
    """读取 STRING 记录里的 XLUnicodeString。"""
    cch = struct.unpack_from("<H", payload, off)[0]
    flags = payload[off + 2]
    fhigh = flags & 0x01
    body = payload[off + 3:]
    if fhigh:
        return body[:cch * 2].decode("utf-16-le", errors="replace")
    return body[:cch].decode("cp1252", errors="replace")


def parse_xls_bytes(data):
    """.xls bytes -> [list[list[str]]]（每个工作表一份二维数据，含表头行）。"""
    stream = _get_workbook_stream(data)
    records = list(_iter_records(stream))

    # 1) 找 SST
    sst = []
    for i, (rid, _) in enumerate(records):
        if rid == REC_SST:
            sst = _parse_sst(records, i)
            break

    # 2) 遍历工作表子流收集单元格
    sheets = []
    cur = {}
    pending_string = None
    in_sheet = False
    for rid, payload in records:
        if rid == REC_BOF:
            if len(payload) >= 4:
                dt = struct.unpack_from("<H", payload, 2)[0]
                if dt == DT_WORKSHEET:
                    cur = {}
                    in_sheet = True
                elif dt == DT_GLOBALS:
                    in_sheet = False
            continue
        if rid == REC_EOF:
            if in_sheet:
                sheets.append(cur)
                cur = {}
                in_sheet = False
            continue
        if not in_sheet:
            continue
        if len(payload) < 6:
            continue
        row = struct.unpack_from("<H", payload, 0)[0]
        col = struct.unpack_from("<H", payload, 2)[0]
        if rid == REC_LABELSST:
            isst = struct.unpack_from("<I", payload, 6)[0]
            if isst < len(sst):
                cur[(row, col)] = sst[isst]
        elif rid == REC_LABEL:
            cur[(row, col)] = _read_plain_string(payload, 6)
        elif rid == REC_NUMBER:
            cur[(row, col)] = _fmt_num(struct.unpack("<d", payload[6:14])[0])
        elif rid == REC_RK:
            cur[(row, col)] = _fmt_num(_decode_rk(struct.unpack_from("<I", payload, 6)[0]))
        elif rid == REC_MULRK:
            col_first = struct.unpack_from("<H", payload, 2)[0]
            k = 4
            while k + 6 <= len(payload):
                c = struct.unpack_from("<H", payload, k)[0]
                rk = struct.unpack_from("<I", payload, k + 2)[0]
                cur[(row, c)] = _fmt_num(_decode_rk(rk))
                k += 6
        elif rid == REC_FORMULA:
            cached = payload[6:14]
            if cached[6:8] == b"\xff\xff":
                t = cached[0]
                if t == 0:
                    cur[(row, col)] = "1" if cached[1] else "0"
                elif t == 2:
                    pending_string = (row, col)  # 字符串结果在紧随的 STRING 记录里
                else:
                    cur[(row, col)] = ""
            else:
                cur[(row, col)] = _fmt_num(struct.unpack("<d", cached)[0])
        elif rid == REC_STRING and pending_string:
            r, c = pending_string
            cur[(r, c)] = _read_plain_string(payload, 0)
            pending_string = None
        # BOOLERR / BLANK / MULBLANK 忽略

    # 3) 转成二维数组
    out = []
    for grid in sheets:
        if not grid:
            continue
        max_r = max(r for r, _ in grid)
        max_c = max(c for _, c in grid)
        rows = [[grid.get((r, c), "") for c in range(max_c + 1)] for r in range(max_r + 1)]
        # 去掉尾部全空行
        while rows and not any(str(c).strip() for c in rows[-1]):
            rows.pop()
        if rows:
            out.append(rows)
    return out
