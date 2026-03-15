#!/usr/bin/env python3
"""
EduGeyser Token Extractor v2

Captures the server token by briefly impersonating the discovery service.
No proxy needed — just a local HTTPS server with a hosts file redirect.

Requirements:
    pip install requests

Usage:
    1. Run as Administrator: python edu_token_extractor_v2.py
    2. Follow the on-screen prompts
"""

import subprocess
import sys
import os
import ssl
import json
import random
import signal
import time
import threading
import atexit
import tempfile
from http.server import HTTPServer, BaseHTTPRequestHandler

import ctypes
if not ctypes.windll.shell32.IsUserAnAdmin():
    print("ERROR: Run as Administrator!")
    print("Right-click -> Run as administrator")
    input("Press Enter to exit...")
    sys.exit(1)

try:
    import requests
except ImportError:
    print("Installing requests...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "requests"])
    import requests

try:
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    import datetime
except ImportError:
    print("Installing cryptography...")
    subprocess.check_call([sys.executable, "-m", "pip", "install", "cryptography"])
    from cryptography import x509
    from cryptography.x509.oid import NameOID
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    import datetime

BEARER_TOKEN = None
CAPTURE_DONE = threading.Event()
ORIGINAL_HOSTS = None
CLEANED_UP = False
CERT_DIR = os.path.join(tempfile.gettempdir(), "edugeyser_certs")
CA_KEY = os.path.join(CERT_DIR, "ca.key")
CA_CERT = os.path.join(CERT_DIR, "ca.pem")
SRV_KEY = os.path.join(CERT_DIR, "srv.key")
SRV_CERT = os.path.join(CERT_DIR, "srv.pem")
HOSTS_PATH = r"C:\Windows\System32\drivers\etc\hosts"
HOSTS_MARKER = "# EduGeyser Token Extractor"
TARGET_DOMAIN = "discovery.minecrafteduservices.com"

# ─── Cleanup ───

def cleanup():
    global CLEANED_UP, ORIGINAL_HOSTS
    if CLEANED_UP:
        return
    CLEANED_UP = True
    print("\n  [*] Cleaning up...")
    if ORIGINAL_HOSTS is not None:
        try:
            with open(HOSTS_PATH, "w") as f:
                f.write(ORIGINAL_HOSTS)
            ORIGINAL_HOSTS = None
            subprocess.run(["ipconfig", "/flushdns"], capture_output=True)
            print("  [*] Hosts file restored.")
        except Exception as e:
            print(f"  [!] WARNING: Could not restore hosts file: {e}")
            print(f"  [!] Manually remove the EduGeyser line from {HOSTS_PATH}")
    print("  [*] Done.")

atexit.register(cleanup)

def signal_handler(*args):
    cleanup()
    os._exit(0)

signal.signal(signal.SIGINT, signal_handler)
signal.signal(signal.SIGTERM, signal_handler)

# ─── Certificate Generation ───

def generate_certs():
    """Generate a CA and a server cert using Python cryptography library."""
    os.makedirs(CERT_DIR, exist_ok=True)

    if os.path.exists(SRV_CERT) and os.path.exists(CA_CERT):
        age = time.time() - os.path.getmtime(SRV_CERT)
        if age < 86400 * 25:
            return True

    # Generate CA key and cert
    ca_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    ca_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "EduGeyser Token Extractor CA")])
    ca_cert = (
        x509.CertificateBuilder()
        .subject_name(ca_name)
        .issuer_name(ca_name)
        .public_key(ca_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.utcnow())
        .not_valid_after(datetime.datetime.utcnow() + datetime.timedelta(days=30))
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
        .sign(ca_key, hashes.SHA256())
    )

    with open(CA_KEY, "wb") as f:
        f.write(ca_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
    with open(CA_CERT, "wb") as f:
        f.write(ca_cert.public_bytes(serialization.Encoding.PEM))

    # Also write DER format for certutil
    ca_der_path = os.path.join(CERT_DIR, "ca.cer")
    with open(ca_der_path, "wb") as f:
        f.write(ca_cert.public_bytes(serialization.Encoding.DER))

    # Generate server key and cert
    srv_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    srv_name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, TARGET_DOMAIN)])
    srv_cert = (
        x509.CertificateBuilder()
        .subject_name(srv_name)
        .issuer_name(ca_name)
        .public_key(srv_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.datetime.utcnow())
        .not_valid_after(datetime.datetime.utcnow() + datetime.timedelta(days=30))
        .add_extension(
            x509.SubjectAlternativeName([x509.DNSName(TARGET_DOMAIN)]),
            critical=False,
        )
        .sign(ca_key, hashes.SHA256())
    )

    with open(SRV_KEY, "wb") as f:
        f.write(srv_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
    with open(SRV_CERT, "wb") as f:
        f.write(srv_cert.public_bytes(serialization.Encoding.PEM))

    return True

def install_ca_cert():
    """Install the CA cert into Windows Trusted Root store."""
    ca_der = os.path.join(CERT_DIR, "ca.cer")
    check = subprocess.run(
        ["certutil", "-verifystore", "Root", "EduGeyser"],
        capture_output=True, text=True
    )
    if "EduGeyser" in check.stdout:
        return True

    result = subprocess.run(
        ["certutil", "-addstore", "Root", ca_der],
        capture_output=True, text=True
    )
    return result.returncode == 0

# ─── Fake HTTPS Server ───

class TokenCaptureHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        global BEARER_TOKEN

        auth = self.headers.get("Authorization", "")
        content_len = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_len) if content_len > 0 else b""

        if "/host" in self.path and auth.startswith("Bearer "):
            BEARER_TOKEN = auth[7:]
            print()
            print("  [!] ════════════════════════════════════")
            print("  [!]   Bearer token captured!")
            print("  [!] ════════════════════════════════════")
            print()

            # Return a fake successful response so the game doesn't error
            fake_response = json.dumps({
                "serverToken": "fake-token-for-capture",
                "passcode": "0,0,0,0"
            }).encode()

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(fake_response)))
            self.end_headers()
            self.wfile.write(fake_response)

            CAPTURE_DONE.set()
        else:
            # Handle other requests (like /update) gracefully
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_OPTIONS(self):
        self.send_response(204)
        self.end_headers()

    def log_message(self, format, *args):
        # Only log relevant requests
        msg = format % args
        if "/host" in msg:
            print(f"        Request: {msg}")

def run_https_server():
    server = HTTPServer(("127.0.0.1", 443), TokenCaptureHandler)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.load_cert_chain(SRV_CERT, SRV_KEY)
    server.socket = ctx.wrap_socket(server.socket, server_side=True)

    while not CAPTURE_DONE.is_set():
        server.handle_request()

    # Handle a few more requests (game might send /update after /host)
    server.timeout = 2
    try:
        server.handle_request()
    except:
        pass

    server.server_close()

# ─── Hosts File ───

def add_hosts_entry():
    global ORIGINAL_HOSTS
    with open(HOSTS_PATH, "r") as f:
        ORIGINAL_HOSTS = f.read()

    if TARGET_DOMAIN in ORIGINAL_HOSTS:
        # Remove any existing entry for this domain first
        lines = [l for l in ORIGINAL_HOSTS.splitlines(True) 
                 if TARGET_DOMAIN not in l and HOSTS_MARKER not in l]
        ORIGINAL_HOSTS = "".join(lines)

    with open(HOSTS_PATH, "w") as f:
        f.write(ORIGINAL_HOSTS)
        f.write(f"\n{HOSTS_MARKER}\n")
        f.write(f"127.0.0.1 {TARGET_DOMAIN}\n")

    subprocess.run(["ipconfig", "/flushdns"], capture_output=True)

def remove_hosts_entry():
    global ORIGINAL_HOSTS
    if ORIGINAL_HOSTS is not None:
        with open(HOSTS_PATH, "w") as f:
            f.write(ORIGINAL_HOSTS)
        ORIGINAL_HOSTS = None
        subprocess.run(["ipconfig", "/flushdns"], capture_output=True)

# ─── Token Exchange ───

def get_server_token(bearer_token):
    resp = requests.post(
        f"https://{TARGET_DOMAIN}/host",
        headers={
            "Authorization": f"Bearer {bearer_token}",
            "api-version": "2.0"
        },
        json={
            "build": 12232001,
            "locale": "en_US",
            "maxPlayers": 40,
            "networkId": str(random.randint(10**18, 10**19 - 1)),
            "playerCount": 0,
            "protocolVersion": 1,
            "serverDetails": "EduGeyser",
            "serverName": "EduGeyser Server",
            "transportType": 2
        }
    )
    if resp.status_code != 200:
        print(f"  [!] /host call failed: {resp.status_code} {resp.text}")
        return None
    return resp.json().get("serverToken")

# ─── Main ───

def main():
    print()
    print("  ╔═══════════════════════════════════════════╗")
    print("  ║     EduGeyser Token Extractor v2          ║")
    print("  ╚═══════════════════════════════════════════╝")
    print()

    # Step 1: Generate and install certs
    print("  [1/5] Setting up certificates...")
    if not generate_certs():
        print("        ERROR: Failed to generate certificates.")
        input("\n  Press Enter to exit...")
        return
    print("        Certificates generated.")

    if not install_ca_cert():
        ca_der = os.path.join(CERT_DIR, "ca.cer")
        print("        WARNING: Could not install CA certificate.")
        print(f"        Manually run: certutil -addstore Root \"{ca_der}\"")
        input("        Press Enter to continue anyway...")
    else:
        print("        CA certificate trusted.")

    # Step 2: UWP loopback
    print("  [2/5] Enabling UWP loopback exemption...")
    subprocess.run(
        ["CheckNetIsolation", "LoopbackExempt", "-a",
         "-n=Microsoft.MinecraftEducationEdition_8wekyb3d8bbwe"],
        capture_output=True
    )
    print("        Done.")

    # Step 3: User signs into game
    print()
    print("  [3/5] Sign into Minecraft Education Edition")
    print()
    print("  ┌─────────────────────────────────────────────┐")
    print("  │                                             │")
    print("  │  1. Open Minecraft Education Edition        │")
    print("  │  2. Sign in with your school account        │")
    print("  │  3. Get to the main menu                    │")
    print("  │  4. Do NOT host a world yet!                │")
    print("  │                                             │")
    print("  └─────────────────────────────────────────────┘")
    print()
    input("  Press Enter when signed in and ready... ")

    # Step 5: Start fake server and redirect
    print()
    print("  [4/5] Starting capture server...")

    # Start HTTPS server first
    server_thread = threading.Thread(target=run_https_server, daemon=True)
    server_thread.start()
    time.sleep(1)

    # Now redirect the domain to localhost
    add_hosts_entry()
    print(f"        Redirected {TARGET_DOMAIN} -> 127.0.0.1")
    print(f"        HTTPS server listening on port 443")

    print()
    print("  ┌─────────────────────────────────────────────┐")
    print("  │                                             │")
    print("  │  Now host any world in Education Edition.   │")
    print("  │  You can leave immediately after hosting.   │")
    print("  │                                             │")
    print("  │  Waiting for token...                       │")
    print("  │  (Press Ctrl+C to cancel)                   │")
    print("  │                                             │")
    print("  └─────────────────────────────────────────────┘")
    print()

    try:
        while not CAPTURE_DONE.is_set():
            CAPTURE_DONE.wait(timeout=1)
    except KeyboardInterrupt:
        print("\n  Cancelled.")
        cleanup()
        input("\n  Press Enter to exit...")
        return

    # Immediately restore hosts file
    remove_hosts_entry()
    print("  [*] Hosts file restored.")

    if not BEARER_TOKEN:
        print("  [!] No token captured.")
        input("\n  Press Enter to exit...")
        return

    # Step 6: Call real server
    print()
    print("  [5/5] Getting server token from Microsoft...")
    server_token = get_server_token(BEARER_TOKEN)

    if not server_token:
        print("  [!] Failed to get server token.")
        input("\n  Press Enter to exit...")
        return

    print()
    print("  ╔═══════════════════════════════════════════╗")
    print("  ║            SUCCESS!                       ║")
    print("  ╚═══════════════════════════════════════════╝")
    print()
    print("  Server token (paste into Geyser config):")
    print()
    print(f"  {server_token[:70]}...")
    print()

    try:
        subprocess.run(["clip"], input=server_token.encode(), check=True)
        print("  Copied to clipboard!")
    except:
        print(f"  Full token: {server_token}")

    with open("server_token.txt", "w") as f:
        f.write(server_token)
    print("  Saved to server_token.txt")
    print()
    print("  Token expires approximately 10 days from now.")
    print("  Re-run this tool when it expires.")
    print()
    print("  To remove the CA cert: certutil -delstore Root \"EduGeyser Token Extractor CA\"")
    print()

    input("  Press Enter to exit...")

if __name__ == "__main__":
    main()
