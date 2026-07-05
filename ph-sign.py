#!/usr/bin/env python3
# /// script
# requires-python = ">=3.8"
# dependencies = [
#     "cryptography",
#     "pyperclip",
# ]
# ///

import time
import sys
import pyperclip
from cryptography.hazmat.primitives.asymmetric import ed25519
from cryptography.hazmat.primitives import serialization

# Default dummy private key matching the public key hardcoded in the Java client
DEFAULT_PRIV_PEM = """-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEICZsF/kFZVoCRSf5Y/2Qzp6tWKodfVszUeMRoQaTi0l0
-----END PRIVATE KEY-----"""

def main():
    # Calculate current 30-second step
    current_step = str(int(time.time()) // 30)
    
    # Load private key
    private_key = None
    import os
    pem_path = os.path.join(os.path.dirname(__file__), "private_key.pem")
    if os.path.exists(pem_path):
        try:
            with open(pem_path, "r", encoding="utf-8") as f:
                pem_data = f.read()
            private_key = serialization.load_pem_private_key(
                pem_data.encode("utf-8"),
                password=None
            )
            print("🔑 Loaded production private key from private_key.pem")
        except Exception as e:
            print(f"Warning: Failed to load private_key.pem: {e}. Falling back to dummy key.")
            
    if private_key is None:
        try:
            private_key = serialization.load_pem_private_key(
                DEFAULT_PRIV_PEM.encode("utf-8"),
                password=None
            )
            print("ℹ️ Loaded default dummy private key")
        except Exception as e:
            print(f"Error loading fallback private key: {e}")
            sys.exit(1)

    # Sign the step
    msg_bytes = current_step.encode("utf-8")
    signature = private_key.sign(msg_bytes)
    sig_hex = signature.hex()

    cmd = f"/ph unlock {sig_hex}"
    
    # Copy to clipboard
    try:
        pyperclip.copy(cmd)
        copied = True
    except Exception as e:
        print(f"Warning: Could not copy to clipboard: {e}")
        copied = False

    print(f"Message (Time-Step): {current_step}")
    print(f"Signature (Hex):\n{sig_hex}\n")
    print(f"Command:\n{cmd}\n")
    
    if copied:
        print("⚡ The command has been copied to your clipboard! Paste it directly in Minecraft (Ctrl+V or Cmd+V).")
    else:
        print("Copy the command above and run it in Minecraft.")

if __name__ == "__main__":
    main()
