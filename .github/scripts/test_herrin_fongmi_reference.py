"""Original UI locator unit fixtures only, never reference runtime evidence."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location('reference', Path(__file__).with_name('herrin_fongmi_reference.py'))
ref = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ref)


class ReferenceUiTest(unittest.TestCase):
    def test_invalid_snapshot_is_reacquired_without_replaying_ui_actions(self):
        responses = ['', '', '<hierarchy>', '', '', '<hierarchy><node /></hierarchy>']
        with patch.object(ref, 'report', {}), patch.object(ref, 'adb', side_effect=responses) as adb, patch.object(ref.time, 'sleep'):
            self.assertEqual(len(ref.ui()), 1)
            self.assertEqual(len(ref.report['invalid_ui_snapshots']), 1)
        self.assertEqual(adb.call_count, 6)
        self.assertEqual([c.args[:3] for c in adb.call_args_list], [
            ('shell', 'rm', '-f'), ('shell', 'uiautomator', 'dump'), ('exec-out', 'cat', '/data/local/tmp/p0-reference.xml')]*2)

    def test_three_invalid_snapshots_fail_closed_without_exporting_xml(self):
        with patch.object(ref, 'report', {}), patch.object(ref, 'adb', side_effect=['', '', '<secret-private>']*3), patch.object(ref.time, 'sleep'):
            with self.assertRaisesRegex(RuntimeError, 'REFERENCE_UI_XML_SNAPSHOT_UNAVAILABLE'):
                ref.ui()
            self.assertEqual(len(ref.report['invalid_ui_snapshots']), 3)
            self.assertNotIn('secret-private', str(ref.report))

    def test_real_clickable_parent_bounds_used_without_tab_or_double_toggle(self):
        root = ET.fromstring('<node clickable="true" bounds="[10,20][110,60]"><node clickable="false" /></node>')
        ref.parents = {root[0]: root}
        with patch.object(ref, 'adb') as adb:
            ref.tap_node(root[0])
        adb.assert_called_once_with('shell', 'input', 'tap', '60', '40')

    def test_selected_search_mode_is_not_toggled_off(self):
        node = ET.Element('node', {'resource-id': ref.PACKAGE+':id/search', 'selected': 'true'})
        with patch.object(ref, 'ui', return_value=[node]), patch.object(ref, 'tap_node') as tap:
            ref.search_mode()
        tap.assert_not_called()

    def test_waits_for_site_dialog_after_settings_click_without_replaying_click(self):
        node = ET.Element('node', {'resource-id': ref.PACKAGE+':id/search', 'selected': 'true'})
        with patch.object(ref, 'ui', side_effect=[[], [node], [node]]), patch.object(ref, 'tap_node') as tap, patch.object(ref, 'key') as key, patch.object(ref.time, 'sleep'):
            ref.search_mode()
        tap.assert_not_called()
        key.assert_not_called()

    def test_unconfirmed_search_mode_fails_closed(self):
        node = ET.Element('node', {'resource-id': ref.PACKAGE+':id/search', 'selected': 'false'})
        with patch.object(ref, 'ui', return_value=[node]), patch.object(ref, 'tap_node'), patch.object(ref, 'activate_focused'):
            with self.assertRaisesRegex(RuntimeError, 'SEARCH_MODE_NOT_SELECTED'):
                ref.search_mode()

    def test_focus_only_touch_gets_one_confirmed_remote_activation(self):
        focused = ET.Element('node', {'resource-id': ref.PACKAGE+':id/search', 'selected': 'false', 'focused': 'true', 'clickable': 'true'})
        selected = ET.Element('node', {'resource-id': ref.PACKAGE+':id/search', 'selected': 'true'})
        with patch.object(ref, 'ui', side_effect=[[focused], [focused], [selected]]), patch.object(ref, 'tap_node'), patch.object(ref, 'key') as key:
            ref.search_mode()
        key.assert_called_once_with(23)

    def test_scope_not_confirmed_when_an_extra_site_remains_enabled(self):
        button = ET.Element('node', {'resource-id': ref.PACKAGE+':id/cancel'})
        with patch.object(ref, 'search_mode'), patch.object(ref, 'ui', return_value=[button]), patch.object(ref, 'tap_node'), patch.object(ref, 'scan_sites', side_effect=[{'奶酪': False, '玩偶': False, 'other': False}, {'奶酪': True, '玩偶': True, 'other': True}]):
            with self.assertRaisesRegex(RuntimeError, 'EXACT_TWO_SITE_SCOPE_NOT_CONFIRMED'):
                ref.restrict_search()

    def test_single_tv_target_does_not_require_two_sites(self):
        button = ET.Element('node', {'resource-id': ref.PACKAGE+':id/cancel'})
        with patch.object(ref, 'TARGETS', ('奶酪',)), patch.object(ref, 'report', {}), patch.object(ref, 'search_mode'), patch.object(ref, 'ui', return_value=[button]), patch.object(ref, 'tap_node'), patch.object(ref, 'scan_sites', side_effect=[{'奶酪': False, '玩偶': False, 'other': False}, {'奶酪': True, '玩偶': False, 'other': False}]):
            ref.restrict_search()
            self.assertTrue(ref.report['scope_verified_by_checkboxes'])
            self.assertEqual(ref.report['search_scope_observation']['enabled_count'], 1)


if __name__ == '__main__':
    unittest.main()
