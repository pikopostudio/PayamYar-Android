"""Make the Windows sidebar navigation safe against QPushButton.clicked(bool)."""
from pathlib import Path

root = Path(__file__).resolve().parent
main = root / "src" / "main.py"
text = main.read_text(encoding="utf-8")
old = 'b=button(title,"nav",lambda key=key:self.show_page(key))'
new = 'b=button(title,"nav",lambda _checked=False, page=key: self.show_page(page))'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("Expected sidebar button callback not found: cannot safely patch")
text = text.replace("نسخه ۱.۰ بتا برای ویندوز", "نسخه ۱.۰.۱ بتا برای ویندوز")
text = text.replace("نسخه ۱.۰ بتا — بدون سرور واسط", "نسخه ۱.۰.۱ بتا — بدون سرور واسط")
main.write_text(text, encoding="utf-8")
print("Navigation callback patched: clicked(bool) can no longer change page key")
