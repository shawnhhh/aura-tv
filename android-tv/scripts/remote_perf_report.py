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
_FRAMEBASE = re.compile(r"\bm445_redraw_frames=(\d+)\b")
_DENSE = re.compile(r"\bm447_dense_dpad_events=(\d+)\b")


def parse_remote_performance(logcat: str) -> dict:
    """Latest sample per direction, or empty dict if the probe did not run."""
    results = {}
    for line in logcat.splitlines():
        if "HerrinPerf" not in line or not ("m443_" in line or "m445_" in line or "m447_" in line):
            continue
        dense = _DENSE.search(line)
        if dense:
            fields = dict(_PAIR.findall(line[dense.end():]))
            result = {"events": int(dense.group(1))}
            for key in ("focus_success", "frames_total"):
                if key in fields and fields[key] != "unavailable":
                    result[key] = int(fields[key])
            for key in ("key_to_focus_p95_ms", "end_to_idle_p95_ms",
                        "frame_p95_ms"):
                if key in fields and fields[key] != "unavailable":
                    result[key] = float(fields[key])
            results["dense_catalog"] = result
            continue
        framebase = _FRAMEBASE.search(line)
        if framebase:
            fields = dict(_PAIR.findall(line[framebase.end():]))
            baseline = {"redraw_frames": int(framebase.group(1))}
            for key in ("navigation_frames",):
                if key in fields and fields[key] != "unavailable":
                    baseline[key] = int(fields[key])
            for key in ("redraw_p95_ms", "navigation_p95_ms",
                        "navigation_to_redraw_ratio"):
                if key in fields and fields[key] != "unavailable":
                    baseline[key] = float(fields[key])
            results["frame_baseline"] = baseline
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
    baseline = performance.get("frame_baseline")
    if baseline:
        lines += ["", "## 相同模拟器：固定画面重绘与方向键切换对照", "",
                  "| 固定画面重绘 P95 | 方向键切换 P95 | 比值（切换/重绘） |",
                  "| ---: | ---: | ---: |",
                  f"| {baseline.get('redraw_p95_ms', '未采集')}ms | "
                  f"{baseline.get('navigation_p95_ms', '未采集')}ms | "
                  f"{baseline.get('navigation_to_redraw_ratio', '未采集')} |",
                  "", "固定画面重绘使用同一个主页视口的 decorView.invalidate，"
                  "仅作模拟器图形环境基线；和 Compose 实际重组不能严格等同。"]
    dense = performance.get("dense_catalog")
    if dense:
        lines += ["", "## 单仓 12 站点／60 部影片密集列表实测", "",
                  "| 按键次数 | 焦点成功 | 焦点回调 P95 | 到 Compose 空闲 P95 | 合成帧 P95 |",
                  "| ---: | ---: | ---: | ---: | ---: |",
                  f"| {dense.get('events', '未采集')} | "
                  f"{dense.get('focus_success', '未采集')} | "
                  f"{dense.get('key_to_focus_p95_ms', '未采集')}ms | "
                  f"{dense.get('end_to_idle_p95_ms', '未采集')}ms | "
                  f"{dense.get('frame_p95_ms', '未采集')}ms |",
                  "", "此测试仅选中用户配置的模拟 CMS 站点；不会执行其他 11 个 Spider 站点。"]
    lines += [
        "",
        "长帧：总合成时间 >16.67ms（按 60Hz 计算）；严重长帧：>33.33ms。"
        "帧 P95 来自 Android Window.FrameMetrics。"
        "模拟器结果不能代替 TCL T7L Ultra 真机或与 Infuse、VidHub 的同设备对比。",
    ]
    return lines
