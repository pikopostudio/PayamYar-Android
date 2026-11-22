"""Regression tests for real Qt menu interactions. Runs without Telegram login."""
import os
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

import pytest
from PySide6.QtWidgets import QApplication, QPushButton

import main
from persistence import Store


@pytest.fixture(scope="module")
def app():
    application = QApplication.instance() or QApplication([])
    yield application


@pytest.fixture
def window(app, monkeypatch, tmp_path):
    monkeypatch.setattr(main, "Store", lambda: Store(tmp_path))
    monkeypatch.setattr(main.TelegramService, "start", lambda self: None)
    w = main.Window()
    w.show()
    app.processEvents()
    yield w
    w.close()
    app.processEvents()


@pytest.mark.parametrize("page", [
    "home", "contacts", "templates", "schedule", "report", "settings"
])
def test_sidebar_click_opens_expected_page(window, app, page):
    window.show_page("home")
    target = window.nav_buttons[page]
    target.click()
    app.processEvents()
    assert window.pages.currentWidget() is window.page_widgets[page]
    assert target.property("active") is True


@pytest.mark.parametrize("title,expected", [
    ("ادامه: نوشتن پیام‌ها", "templates"),
    ("ادامه: زمان‌بندی", "schedule"),
])
def test_next_buttons_open_pages(window, app, title, expected):
    buttons = [b for b in window.findChildren(QPushButton) if b.text() == title]
    assert len(buttons) == 1
    buttons[0].click()
    app.processEvents()
    assert window.pages.currentWidget() is window.page_widgets[expected]


def test_contact_and_text_controls(window, app):
    window.contacts = [
        {"user_id": 501, "name": "علی", "source": "مخاطب تلگرام"},
        {"user_id": 502, "name": "رضا", "source": "گفتگوی خصوصی"},
    ]
    window.render_contacts()
    window.search.setText("علی")
    app.processEvents()
    assert not window.contact_table.isRowHidden(0)
    assert window.contact_table.isRowHidden(1)
    window.select_visible()
    assert window.selected == {501}
    window.clear_selected()
    assert not window.selected

    previous = len(window.templates)
    window.add_template()
    assert len(window.templates) == previous + 1
    window.editor.setPlainText("متن آزمایشی")
    window.save_template()
    assert window.templates[-1] == "متن آزمایشی"
    window.delete_template()
    assert len(window.templates) == previous
