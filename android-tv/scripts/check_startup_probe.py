"""Validate actual offline UI, window ownership and lifecycle evidence, not a timer alone."""
import argparse
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "tv.herrin.app"


def check_ui(xml, window, category="首页"):
    root = ET.fromstring(xml)
    parents = {child: parent for parent in root.iter() for child in parent}
    nodes = list(root.iter("node"))

    def visible(node):
        values = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        return values is not None and int(values[3]) > int(values[1]) and int(values[4]) > int(values[2])

    def labelled(text, substring=False):
        return [node for node in nodes if node.get("package") == PACKAGE and visible(node)
                and (text in node.get("text", "") if substring else node.get("text") == text)]

    def action_owner(node):
        while node is not None and node.tag == "node":
            if node.get("enabled") != "true":
                return None
            if node.get("clickable") == "true" and visible(node):
                return node
            node = parents.get(node)
        return None

    assert re.search(r"mCurrentFocus=.*tv[.]herrin[.]app/[^\n]*MainActivity", window), "Herrin does not own the input window"
    assert labelled("HERRIN"), "Brand missing from the app UI"
    assert any(action_owner(node) is not None for node in labelled("详情")), "Enabled, clickable details action missing"
    assert not any(action_owner(node) is not None for node in labelled("播放")), "Removed Hero play action reappeared"
    owners = [action_owner(node) for node in labelled(category)]
    assert any(owner is not None and any(n.get("focused") == "true" for n in owner.iter())
               for owner in owners), "Expected navigation does not own remote focus"
    if category == "电影":
        assert labelled("精选电影", substring=True), "Remote did not open the offline movie page"


def check_lifecycle(diagnostics):
    assert "FATAL EXCEPTION" not in diagnostics, "Startup crashed"
    summaries = [line for line in diagnostics.splitlines() if "HerrinStartup:" in line and "activity_to_home_ms=" in line]
    assert summaries, "Interactive home completion event missing (underlay is not sufficient)"
    metrics = dict((key, int(value)) for key, value in re.findall(r"([a-z_]+)=(-?\d+)", summaries[-1]))
    home = metrics["activity_to_home_ms"]
    first = metrics["activity_to_first_draw_ms"]
    visible = first + metrics["first_draw_to_wordmark_ms"]
    assert 0 <= first <= visible <= metrics["home_init_ms"] < home, "Home preparation preceded visible branding or never completed"
    assert 0 <= metrics["home_init_ms"] <= metrics["network_start_ms"] < home, "Network preparation was deferred until after interactive home"
    assert visible <= metrics["wordmark_complete_ms"] < home, "Interactive home preceded the reveal completion"
    # Visible marks the first positive-time frame, one vsync after reveal t=0.
    # Do not pretend two CPU markers measure the exact glyph presentation time.
    assert home - visible >= 3400, "Interactive home skipped the V13 reveal/brighten minimum"
    return metrics


def check_hot_resume(cold, hot):
    assert "FATAL EXCEPTION" not in hot, "Hot resume crashed"
    marker = "phase=wordmark_visible "
    assert cold.count(marker) > 0 and hot.count(marker) == cold.count(marker), "Hot resume replayed or lost the startup lifecycle"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--window", type=Path, required=True)
    parser.add_argument("--diagnostics", type=Path, required=True)
    parser.add_argument("--stage", choices=("cold", "hot", "visual"), required=True)
    parser.add_argument("--cold-diagnostics", type=Path)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    check_ui(args.xml.read_text(), args.window.read_text(), "电影" if args.stage == "hot" else "首页")
    diagnostics = args.diagnostics.read_text()
    metrics = check_lifecycle(diagnostics)
    if args.stage == "hot":
        assert args.cold_diagnostics is not None
        check_hot_resume(args.cold_diagnostics.read_text(), diagnostics)
    result = {"stage": args.stage, "interactive_ui": True, "details_only_hero": True,
              "actual_window_and_focus": True, "metrics": metrics, "physical_TCL": False,
              "measurement": "app_uptime_cpu_events_not_panel_presentation",
              "performance_source": args.stage == "cold"}
    args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print("HERRIN_STARTUP_CHECK=" + json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
