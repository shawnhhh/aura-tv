"""Parse only opt-in HERRIN D-pad timing numbers from emulator logcat.

No titles, URLs, authorization tokens, user identifiers or unbounded logs are
exported into the metric JSON or CI acceptance table.
"""
import re

_ALLOWED_FLOATS = (
    "key_to_focus_p95_ms", "end_to_idle_p95_ms",
    "frame_p95_ms", "long_frame_pct",
)
_ALLOWED_INTS = (
    "focus_success", "frames_total",
    "frames_over_16_67ms", "frames_over_33_33ms",
)
_PAIR = re.compile(r"([a-zA-Z0-9_.]+)=([0-9.]+|unavailable)")
_START = re.compile(r"\bm443_(vertical|horizontal)_dpad_events=(\d+)\b")
_BURST = re.compile(r"\bm443_rapid_burst_events=(\d+)\b")


def parse_remote_performance(logcat: str) -> dict:
    """Latest sample per direction, or empty dict if the probe did not run."""
    results = {}
    for line in logcat.splitlines():
        if "HerrinPerf" not in line or "m443_" not in line:
            continue
        burst = _BURST.search(line)
        if burst:
            remaining = line[burst.end():]
            fields = dict(_PAIR.findall(remaining))
            results["burst"] = {
                "events": int(burst.group(1)),
                "last_focus_correct": int(fields.get("last_focus_correct", "0")),
                "burst_to_final_focus_ms": float(fields.get("burst_to_final_focus_ms", "0")),
            }
            continue
        match = _START.search(line)
        if not match:
            continue
        direction = match.group(1)
        sample = {"events": int(match.group(2))}
        for key, raw in _PAIR.findall(line[match.end():]):
            if key in _ALLOWED_INTS and raw != "unavailable":
                sample[key] = int(raw)
            elif key in _ALLOWED_FLOATS and raw != "unavailable":
                sample[key] = float(raw)
        results[direction] = sample
    return results


def render_markdown(performance: dict) -> list[str]:
    lines = [
        "## Android TV 真实 D-pad 焦点与合成帧指标",
        "",
        "以下数据来自当前模拟器运行的系统 KeyEvent → Compose 焦点回调，"
        "不是遥控器硬件到屏幕像素的光子延迟。"
        "end_to_idle 包含测试框架同步等待；非直接输入延迟。",
        "",
        "| 方向 | 次数 | 焦点成功 | 焦点回调 P95 | 到 Compose 空闲 P95 | 帧 P95 | 60Hz 超预算帧占比 |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for direction, name in (("vertical", "上下"), ("horizontal", "左右")):
        m = performance.get(direction)
        if not m:
            lines.append(f"| {name} | 未采集 | — | — | — | — | — |")
            continue
        def fmt(key: str, suffix: str = "") -> str:
            value = m.get(key)
            return "未采集" if value is None else f"{value}{suffix}"
        lines.append(
            f"| {name} | {fmt('events')} | {fmt('focus_success')} | "
            f"{fmt('key_to_focus_p95_ms', 'ms')} | "
            f"{fmt('end_to_idle_p95_ms', 'ms')} | "
            f"{fmt('frame_p95_ms', 'ms')} | "
            f"{fmt('long_frame_pct', '%')} |"
        )
    burst = performance.get("burst")
    if burst:
        lines += ["", "无逐键 idle 等待的连续遥控器反向切换：" +
                  f"{burst.get('events')} 次，最终焦点" +
                  ("正确" if burst.get("last_focus_correct") == 1 else "错误") +
                  f"，总耗时 {burst.get('burst_to_final_focus_ms')}ms。"]
    lines += [
        "",
        "长帧：总合成时间 >16.67ms（按 60Hz 计算）；严重长帧：>33.33ms。"
        "帧 P95 来自 Android Window.FrameMetrics。"
        "模拟器结果不能代替 TCL T7L Ultra 真机或与 Infuse、VidHub 的同设备对比。",
    ]
    return lines
