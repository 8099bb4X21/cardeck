#!/usr/bin/env python3
"""从 CarDeck 运行日志提取「车型重放 fixtures」。

用法：
    python3 tools/extract_car_fixtures.py <日志文件或目录>... [-o app/src/test/resources/carplay/fixtures]

产出：每个日志 → <车型>__<日志名>.jsonl
  首行 meta（车型/来源/轮次概览），其后按层分事件：
  - bt 层：{"layer":"bt","round":N,"dir":"rx|tx","hex":"…"}（RAW rx/tx 按轮次拼接）
  - tunnel 层：{"layer":"tunnel","dir":"rx","id":…,"hex":"…"}（IAPLINK rx 的 CSM 体）
               {"layer":"tunnel","dir":"tx","id":…}（隧道 tx 只有消息号——golden 序列）

轮次界定：进入（接收端已连入/已连入…RFCOMM 建立）→ 结束（LINK closed/SESSION 失败/
SESSION 结束/保活结束）。仅保留含 0x1d01 的轮次（没走到识别的轮次没有重放价值），
并带 outcome 与时长（重放测试据此控制时长与配额）。

fixture 含真实地址/热点名——目录默认 gitignore，入册需单独评审（见目录内 README）。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

LINE_RE = re.compile(r"^\[(\d\d):(\d\d):(\d\d)\.(\d\d\d)\] (.*)$")
RAW_RX_RE = re.compile(r"^RAW rx: ((?:[0-9a-f]{2} ?)+)$")
RAW_TX_RE = re.compile(r"^RAW tx: ((?:[0-9a-f]{2} ?)+)$")
LINK_CLOSE_RE = re.compile(r"LINK closed|SESSION 失败|SESSION 结束|保活结束")
ROUND_START_RE = re.compile(r"接收端已连入|已连入 .+ 的无线 iAP2 服务")
IDENT_RE = re.compile(r"iap2 rx=0x1d01 车机身份 name=(.*?) model=(.*?) manufacturer=(.*)$")
NSD_MODEL_RE = re.compile(r"NSD: TXT .*model=(\S+?),")
TUNNEL_RX_RE = re.compile(r"^IAPLINK: rx 0x([0-9a-f]+) \(.*\) \d+B hex=((?:[0-9a-f]{2} ?)+)")
TUNNEL_TX_RE = re.compile(r"^IAPLINK: 隧道 tx 0x([0-9a-f]+)")
PHASE1_RE = re.compile(r"Phase 1 完成")


def ms_of(m: re.Match) -> int:
    h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
    return ((h * 60 + mi) * 60 + s) * 1000 + ms


def car_slug(text: str) -> str:
    ident = IDENT_RE.search(text)
    if ident:
        parts = [p.lower() for p in ident.groups()]
        return re.sub(r"[^a-z0-9]+", "_", "_".join(parts)).strip("_")[:48]
    nsd = NSD_MODEL_RE.search(text)
    if nsd:
        return re.sub(r"[^a-z0-9]+", "_", nsd.group(1).lower()).strip("_")[:48]
    return "unknown"


class Round:
    def __init__(self, index: int, start_ms: int):
        self.index = index
        self.start_ms = start_ms
        self.end_ms = start_ms
        self.rx = bytearray()
        self.tx = bytearray()
        self.ids: list[str] = []  # 本轮出现过的 CSM 消息号（识别判定用）
        self.phase1 = False
        self.ident_text = ""


def extract(log_path: Path) -> dict | None:
    rounds: list[Round] = []
    current: Round | None = None
    tunnel_rx: list[dict] = []
    tunnel_tx: list[str] = []
    car_id = "unknown"

    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        m = LINE_RE.match(line)
        if not m:
            continue
        body = m.group(5).strip()
        now = ms_of(m)

        ident = IDENT_RE.search(body)
        if ident:
            car_id = car_slug(body)

        if ROUND_START_RE.search(body):
            current = Round(len(rounds), now)
            rounds.append(current)
        if current is None:
            # 日志从中途开始：仍聚一个匿名轮
            current = Round(0, now)
            rounds.append(current)
        current.end_ms = now

        if TUNNEL_RX_RE.match(body):
            t = TUNNEL_RX_RE.match(body)
            tunnel_rx.append(
                {"layer": "tunnel", "dir": "rx", "id": int(t.group(1), 16), "hex": t.group(2).replace(" ", "")}
            )
        else:
            t = TUNNEL_TX_RE.match(body)
            if t:
                tunnel_tx.append(t.group(1))

        rx = RAW_RX_RE.match(body)
        tx = RAW_TX_RE.match(body)
        if rx:
            data = bytes.fromhex(rx.group(1).replace(" ", ""))
            # RAW 转储预算截断会留半行（实测出现过整行只剩 "ff"）：帧/marker 最短 6B，
            # 更短的碎片直接丢，避免污染重放字节流
            if len(data) >= 6:
                current.rx.extend(data)
                current.end_ms = now
        elif tx:
            data = bytes.fromhex(tx.group(1).replace(" ", ""))
            if len(data) >= 6:
                current.tx.extend(data)
                current.end_ms = now
        if PHASE1_RE.search(body) and current is not None:
            current.phase1 = True
            current.ident_text = body

    keep = []
    for r in rounds:
        if not r.rx:
            continue
        ident = IDENT_RE.search(r.ident_text) or IDENT_RE.search("")  # ident per-round 取自该轮正文
        raw = bytes(r.rx)
        # RAW 转储预算按字节截断（旧日志 512B）：截断点常落在帧中间——按帧边界
        # 裁掉尾部残帧，并记录识别帧（0x1D01 完整 CSM）是否幸存。识别帧被截断的
        # 轮只能做链路层重放（引擎吃残帧会死等校验），识别帧完整的才做全量断言。
        trimmed, truncated, has_ident, has_syn = trim_to_frame_boundary(raw)
        if not ("1d01" in raw.hex() or r.phase1):
            continue  # 没走到识别：无重放价值（纯协商失败轮由链路层单测覆盖）
        if not has_syn:
            continue  # 校验完好的 SYN 都没有（残骸轮）：链路层都驱动不了，无重放价值
        keep.append(
            {
                "layer": "bt",
                "round": r.index,
                "outcome": "phase1" if r.phase1 else "identified",
                "durationMs": r.end_ms - r.start_ms,
                "rxTruncated": truncated,
                "rxHasIdent": has_ident,
                "rxHex": trimmed.hex(),
                "txHex": bytes(r.tx).hex(),
            }
        )
    if not keep and not tunnel_rx:
        return None
    return {"car": car_id, "source": log_path.name, "btRounds": keep, "tunnelRx": tunnel_rx, "tunnelTxIds": tunnel_tx}


MARKER = bytes.fromhex("ff550200ee10")


def trim_to_frame_boundary(data: bytes) -> tuple[bytes, bool, bool]:
    """返回（裁剪后的字节流, 是否发生裁剪, 完好帧里是否含 0x1D01 的 CSM）。

    「帧长完整」不等于「字节完好」：RAW 转储预算按字节截断会把不同 read 的
    尾巴拼进同一条流，伪造出长度恰好完整的帧——载荷校验和不过，引擎会按协议
    静默丢弃（2026-10-20 重放调试实测：回放停在 0x1D00 的根因）。因此逐帧按
    引擎同款算法校验（帧头 9B 求和为 0；载荷+尾校验求和为 0），第一个不过的
    帧起全部裁掉——留下的流引擎必然逐帧接受。
    """
    off = 0
    has_ident = False
    has_syn = False
    while off < len(data):
        if data[off : off + 6] == MARKER:
            off += 6
            continue
        if off + 4 <= len(data) and data[off] == 0xFF and data[off + 1] == 0x5A:
            frame_len = (data[off + 2] << 8) | data[off + 3]
            if frame_len < 9 or off + frame_len > len(data):
                break  # 长度不完整（预算截断点）
            frame = data[off : off + frame_len]
            if sum(frame[:9]) & 0xFF != 0:
                break  # 帧头校验不过：拼接伪造的帧
            if frame_len > 9 and sum(frame[9:frame_len]) & 0xFF != 0:
                break  # 载荷校验不过：同上
            if frame_len > 9:
                payload = frame[9 : frame_len - 1]
                if frame[4] & 0x80:  # ctl 含 SYN 位（带会话表的同步包）
                    has_syn = True
                if payload[:2] == b"\x40\x40" and payload[4:6] == b"\x1d\x01":
                    has_ident = True
            off += frame_len
            continue
        break  # 无法识别的字节：停止（保守丢弃尾部）
    return data[:off], off < len(data), has_ident, has_syn


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("inputs", nargs="+")
    ap.add_argument("-o", "--out", default="app/src/test/resources/carplay/fixtures")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    written = 0
    for raw_path in args.inputs:
        p = Path(raw_path)
        files = [p] if p.is_file() else sorted(p.glob("CarDeck-log-*.txt")) if p.is_dir() else []
        for f in files:
            data = extract(f)
            if data is None:
                print(f"skip（无可重放内容）: {f.name}")
                continue
            target = out_dir / f"{data['car']}__{f.stem}.jsonl"
            with target.open("w", encoding="utf-8") as w:
                w.write(json.dumps({"meta": data["meta"] if "meta" in data else {k: data[k] for k in ("car", "source")}}, ensure_ascii=False) + "\n")
                for r in data["btRounds"]:
                    w.write(json.dumps(r, ensure_ascii=False) + "\n")
                for e in data["tunnelRx"]:
                    w.write(json.dumps(e, ensure_ascii=False) + "\n")
                for tid in data["tunnelTxIds"]:
                    w.write(json.dumps({"layer": "tunnel", "dir": "tx", "id": int(tid, 16)}, ensure_ascii=False) + "\n")
            print(f"wrote {target.name}（bt轮 {len(data['btRounds'])}，tunnel rx {len(data['tunnelRx'])}）")
            written += 1
    print(f"完成：{written} 个 fixture → {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
