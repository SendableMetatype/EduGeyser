#!/usr/bin/env python3
"""
EduGeyser Token Tool v1.0

Obtains a Minecraft Education server token for use with EduGeyser standalone mode.
No admin privileges required. Run on any Windows machine with a school M365 account.

Requirements:
    pip install msal pymsalruntime requests

Package as standalone .exe:
    pip install pyinstaller
    pyinstaller --onefile --windowed --icon=NONE --name "EduGeyser Token Tool" edu_token_tool_gui.py

Source: https://github.com/SendableMetatype/EduGeyser
License: MIT
"""

import sys
import os
import json
import time
import base64
import threading

if sys.platform != "win32":
    print("This tool requires Windows for Microsoft sign-in.")
    print("Run it on any Windows machine, then paste the token into your server config.")
    sys.exit(1)

# ---- Dependency check ----
missing = []
try:
    import msal
except ImportError:
    missing.append("msal")
try:
    import pymsalruntime  # noqa: F401 — needed for WAM broker
except ImportError:
    missing.append("pymsalruntime")
try:
    import requests
except ImportError:
    missing.append("requests")

if missing:
    import subprocess
    result = subprocess.run(
        [sys.executable, "-m", "pip", "install"] + missing,
        capture_output=True, text=True
    )
    if result.returncode != 0:
        import tkinter as tk
        from tkinter import messagebox
        root = tk.Tk()
        root.withdraw()
        messagebox.showerror(
            "Missing Dependencies",
            f"Could not install required packages: {', '.join(missing)}\n\n"
            f"Please run:\n  pip install {' '.join(missing)}"
        )
        sys.exit(1)
    # Re-import after install
    import msal
    import pymsalruntime  # noqa: F401
    import requests

import tkinter as tk
from tkinter import font as tkfont

# ---- Constants ----
EDU_CLIENT_ID = "b36b1432-1a1c-4c82-9b76-24de1cab42f2"
EDU_RESOURCE = "16556bfc-5102-43c9-a82a-3ea5e4810689"
SCOPE = [f"{EDU_RESOURCE}/.default"]
AUTHORITY = "https://login.microsoftonline.com/organizations"
HOST_URL = "https://discovery.minecrafteduservices.com/host"
VERSION = "1.0"


class TokenToolApp:
    # ---- Color scheme ----
    BG = "#1B2838"           # Steam-inspired dark blue
    BG_CARD = "#1E3044"
    BG_INPUT = "#0F1923"
    FG = "#C6D4DF"
    FG_DIM = "#7A8B9C"
    FG_BRIGHT = "#FFFFFF"
    ACCENT = "#5CB85C"       # Minecraft green
    ACCENT_HOVER = "#4EA44E"
    ACCENT_DIM = "#3D7A3D"
    ERROR = "#D9534F"
    BORDER = "#2A475E"

    def __init__(self):
        self.root = tk.Tk()
        self.root.title(f"EduGeyser Token Tool v{VERSION}")
        self.root.configure(bg=self.BG)
        self.root.resizable(False, False)

        # Center window
        w, h = 520, 580
        x = (self.root.winfo_screenwidth() - w) // 2
        y = (self.root.winfo_screenheight() - h) // 2
        self.root.geometry(f"{w}x{h}+{x}+{y}")

        # Fonts
        self.font_title = tkfont.Font(family="Segoe UI", size=18, weight="bold")
        self.font_subtitle = tkfont.Font(family="Segoe UI", size=10)
        self.font_body = tkfont.Font(family="Segoe UI", size=10)
        self.font_small = tkfont.Font(family="Segoe UI", size=9)
        self.font_mono = tkfont.Font(family="Consolas", size=9)
        self.font_btn = tkfont.Font(family="Segoe UI", size=11, weight="bold")

        self.server_token = None
        self.tenant_id = None
        self.username = None

        self.build_ui()

    def build_ui(self):
        root = self.root

        # ---- Header ----
        header = tk.Frame(root, bg=self.BG)
        header.pack(fill="x", padx=30, pady=(25, 0))

        tk.Label(
            header, text="EduGeyser Token Tool",
            font=self.font_title, fg=self.ACCENT, bg=self.BG, anchor="w"
        ).pack(anchor="w")

        tk.Label(
            header, text="Get a server token for Education Edition standalone mode",
            font=self.font_subtitle, fg=self.FG_DIM, bg=self.BG, anchor="w"
        ).pack(anchor="w", pady=(2, 0))

        # ---- Divider ----
        tk.Frame(root, bg=self.BORDER, height=1).pack(fill="x", padx=30, pady=(15, 0))

        # ---- Main content area ----
        self.content = tk.Frame(root, bg=self.BG)
        self.content.pack(fill="both", expand=True, padx=30, pady=15)

        self.show_sign_in_screen()

    def clear_content(self):
        for widget in self.content.winfo_children():
            widget.destroy()

    # ---- Screen 1: Sign In ----
    def show_sign_in_screen(self):
        self.clear_content()
        f = self.content

        tk.Label(
            f, text="Sign in with your school account to generate\n"
                     "a server token for your school's tenant.",
            font=self.font_body, fg=self.FG, bg=self.BG, justify="left", anchor="w"
        ).pack(anchor="w", pady=(10, 5))

        tk.Label(
            f, text="Any student or teacher account will work.\nNo admin access required.",
            font=self.font_small, fg=self.FG_DIM, bg=self.BG, justify="left", anchor="w"
        ).pack(anchor="w", pady=(0, 20))

        # Sign in button
        self.btn_signin = tk.Button(
            f, text="Sign in with Microsoft",
            font=self.font_btn, fg=self.FG_BRIGHT, bg=self.ACCENT,
            activebackground=self.ACCENT_HOVER, activeforeground=self.FG_BRIGHT,
            relief="flat", cursor="hand2", padx=20, pady=10,
            command=self.start_auth
        )
        self.btn_signin.pack(anchor="w", pady=(0, 15))

        # Status area
        self.status_label = tk.Label(
            f, text="", font=self.font_small, fg=self.FG_DIM, bg=self.BG,
            anchor="w", justify="left"
        )
        self.status_label.pack(anchor="w", fill="x")

        # ---- Footer info ----
        spacer = tk.Frame(f, bg=self.BG)
        spacer.pack(fill="both", expand=True)

        info = tk.Frame(f, bg=self.BG_CARD, highlightbackground=self.BORDER,
                        highlightthickness=1)
        info.pack(fill="x", pady=(10, 0))

        tk.Label(
            info, text="How it works",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.FG, bg=self.BG_CARD, anchor="w"
        ).pack(anchor="w", padx=12, pady=(10, 4))

        steps = (
            "1.  Click 'Sign in' — a Windows sign-in dialog appears\n"
            "2.  Log in with your school Microsoft 365 account\n"
            "3.  The tool exchanges your login for a server token\n"
            "4.  Copy the token into your EduGeyser config"
        )
        tk.Label(
            info, text=steps,
            font=self.font_small, fg=self.FG_DIM, bg=self.BG_CARD,
            justify="left", anchor="w"
        ).pack(anchor="w", padx=12, pady=(0, 10))

    def set_status(self, text, error=False):
        self.status_label.config(text=text, fg=self.ERROR if error else self.FG_DIM)
        self.root.update_idletasks()

    # ---- Auth Flow ----
    def start_auth(self):
        self.btn_signin.config(state="disabled", bg=self.ACCENT_DIM, text="Signing in...")
        self.set_status("Opening Microsoft sign-in...")
        threading.Thread(target=self.do_auth, daemon=True).start()

    def do_auth(self):
        try:
            app = msal.PublicClientApplication(
                EDU_CLIENT_ID,
                authority=AUTHORITY,
                allow_broker=True,
            )

            # Try silent first
            accounts = app.get_accounts()
            result = None
            if accounts:
                self.root.after(0, self.set_status, f"Found cached account: {accounts[0].get('username', '?')}")
                result = app.acquire_token_silent(scopes=SCOPE, account=accounts[0])

            if not result or "access_token" not in result:
                self.root.after(0, self.set_status, "Waiting for sign-in...")
                result = app.acquire_token_interactive(
                    scopes=SCOPE,
                    prompt="select_account",
                    parent_window_handle=msal.PublicClientApplication.CONSOLE_WINDOW_HANDLE,
                )

            if "access_token" not in result:
                error = result.get("error_description", result.get("error", "Unknown error"))
                self.root.after(0, self.auth_failed, error)
                return

            access_token = result["access_token"]

            # Decode JWT for display info
            parts = access_token.split(".")
            if len(parts) >= 2:
                padded = parts[1] + "=" * (4 - len(parts[1]) % 4)
                payload = json.loads(base64.urlsafe_b64decode(padded))
                self.username = payload.get("unique_name", payload.get("upn", "unknown"))
                self.tenant_id = payload.get("tid", "unknown")

            self.root.after(0, self.set_status, f"Signed in as {self.username}. Getting server token...")

            # Exchange for server token via /host
            resp = requests.post(
                HOST_URL,
                headers={
                    "Authorization": f"Bearer {access_token}",
                    "Content-Type": "application/json",
                    "api-version": "2.0",
                },
                json={
                    "build": 12232001,
                    "locale": "en_US",
                    "maxPlayers": 40,
                    "networkId": str(int(time.time() * 1000)),
                    "playerCount": 0,
                    "protocolVersion": 1,
                    "serverDetails": "EduGeyser",
                    "serverName": "EduGeyser Server",
                    "transportType": 2,
                },
                timeout=15,
            )

            if resp.status_code != 200:
                self.root.after(0, self.auth_failed, f"Token exchange failed (HTTP {resp.status_code}): {resp.text[:200]}")
                return

            data = resp.json()
            self.server_token = data.get("serverToken", "")

            if not self.server_token:
                self.root.after(0, self.auth_failed, "No server token in response")
                return

            # Extract tenant from token
            parts = self.server_token.split("|")
            if len(parts) >= 4:
                self.tenant_id = parts[0]

            self.root.after(0, self.show_success_screen)

        except Exception as e:
            self.root.after(0, self.auth_failed, str(e))

    def auth_failed(self, error):
        self.btn_signin.config(state="normal", bg=self.ACCENT, text="Sign in with Microsoft")
        self.set_status(f"Error: {error}", error=True)

    # ---- Screen 2: Success ----
    def show_success_screen(self):
        self.clear_content()
        f = self.content

        # Success header
        tk.Label(
            f, text="✓  Token obtained",
            font=tkfont.Font(family="Segoe UI", size=14, weight="bold"),
            fg=self.ACCENT, bg=self.BG, anchor="w"
        ).pack(anchor="w", pady=(10, 10))

        # Info row
        info_frame = tk.Frame(f, bg=self.BG_CARD, highlightbackground=self.BORDER,
                              highlightthickness=1)
        info_frame.pack(fill="x", pady=(0, 12))

        for label, value in [("Account", self.username), ("Tenant", self.tenant_id)]:
            row = tk.Frame(info_frame, bg=self.BG_CARD)
            row.pack(fill="x", padx=12, pady=(6, 0))
            tk.Label(row, text=f"{label}:", font=self.font_small, fg=self.FG_DIM,
                     bg=self.BG_CARD, width=8, anchor="w").pack(side="left")
            tk.Label(row, text=value, font=self.font_mono, fg=self.FG,
                     bg=self.BG_CARD, anchor="w").pack(side="left")
        # Bottom padding
        tk.Frame(info_frame, bg=self.BG_CARD, height=6).pack()

        # Token display
        tk.Label(
            f, text="Server Token",
            font=tkfont.Font(family="Segoe UI", size=9, weight="bold"),
            fg=self.FG, bg=self.BG, anchor="w"
        ).pack(anchor="w", pady=(0, 4))

        token_frame = tk.Frame(f, bg=self.BG_INPUT, highlightbackground=self.BORDER,
                               highlightthickness=1)
        token_frame.pack(fill="x", pady=(0, 8))

        self.token_text = tk.Text(
            token_frame, font=self.font_mono, fg=self.FG, bg=self.BG_INPUT,
            height=4, wrap="char", relief="flat", padx=8, pady=8,
            selectbackground=self.ACCENT, selectforeground=self.FG_BRIGHT,
            insertbackground=self.FG
        )
        self.token_text.pack(fill="x")
        self.token_text.insert("1.0", self.server_token)
        self.token_text.config(state="disabled")

        # Buttons row
        btn_row = tk.Frame(f, bg=self.BG)
        btn_row.pack(fill="x", pady=(4, 12))

        self.btn_copy = tk.Button(
            btn_row, text="Copy Token",
            font=self.font_btn, fg=self.FG_BRIGHT, bg=self.ACCENT,
            activebackground=self.ACCENT_HOVER, activeforeground=self.FG_BRIGHT,
            relief="flat", cursor="hand2", padx=16, pady=8,
            command=self.copy_token
        )
        self.btn_copy.pack(side="left")

        tk.Button(
            btn_row, text="Save to File",
            font=self.font_btn, fg=self.FG, bg=self.BORDER,
            activebackground="#3A5A7A", activeforeground=self.FG_BRIGHT,
            relief="flat", cursor="hand2", padx=16, pady=8,
            command=self.save_token
        ).pack(side="left", padx=(8, 0))

        # Config snippet
        tk.Label(
            f, text="Add this to your EduGeyser config.yml:",
            font=self.font_small, fg=self.FG_DIM, bg=self.BG, anchor="w"
        ).pack(anchor="w", pady=(4, 4))

        config_frame = tk.Frame(f, bg=self.BG_INPUT, highlightbackground=self.BORDER,
                                highlightthickness=1)
        config_frame.pack(fill="x")

        config_text = tk.Text(
            config_frame, font=self.font_mono, fg=self.ACCENT, bg=self.BG_INPUT,
            height=4, wrap="char", relief="flat", padx=8, pady=8,
            selectbackground=self.ACCENT, selectforeground=self.FG_BRIGHT,
            insertbackground=self.FG
        )
        config_text.pack(fill="x")
        config_snippet = (
            "education:\n"
            "  tenancy-mode: standalone\n"
            "  server-tokens:\n"
            f'    - "{self.server_token}"'
        )
        config_text.insert("1.0", config_snippet)
        config_text.config(state="disabled")

        # Copy feedback
        self.copy_feedback = tk.Label(
            f, text="", font=self.font_small, fg=self.ACCENT, bg=self.BG, anchor="w"
        )
        self.copy_feedback.pack(anchor="w", pady=(6, 0))

    def copy_token(self):
        self.root.clipboard_clear()
        self.root.clipboard_append(self.server_token)
        self.root.update()
        self.btn_copy.config(text="Copied!")
        self.copy_feedback.config(text="Token copied to clipboard")
        self.root.after(2000, lambda: self.btn_copy.config(text="Copy Token"))
        self.root.after(2000, lambda: self.copy_feedback.config(text=""))

    def save_token(self):
        from tkinter import filedialog
        path = filedialog.asksaveasfilename(
            defaultextension=".txt",
            filetypes=[("Text files", "*.txt"), ("All files", "*.*")],
            initialfile="edugeyser_token.txt"
        )
        if path:
            with open(path, "w") as f:
                f.write(self.server_token)
            self.copy_feedback.config(text=f"Saved to {os.path.basename(path)}")
            self.root.after(3000, lambda: self.copy_feedback.config(text=""))

    def run(self):
        self.root.mainloop()


if __name__ == "__main__":
    app = TokenToolApp()
    app.run()
