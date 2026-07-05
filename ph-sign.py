#!/usr/bin/env python3
import time
import sys

# Default dummy private key matching the public key hardcoded in the Java client
DEFAULT_PRIV_PEM = """-----BEGIN PRIVATE KEY-----
MC4CAQAwBQYDK2VwBCIEICZsF/kFZVoCRSf5Y/2Qzp6tWKodfVszUeMRoQaTi0l0
-----END PRIVATE KEY-----"""

def main():
    try:
        from cryptography.hazmat.primitives.asymmetric import ed25519
        from cryptography.hazmat.primitives import serialization
    except ImportError:
        print("Error: The 'cryptography' library is required. Install it using 'pip install cryptography'.")
        sys.exit(1)

    # Calculate current 30-second step
    current_step = str(int(time.time()) // 30)
    
    # Load private key
    try:
        private_key = serialization.load_pem_private_key(
            DEFAULT_PRIV_PEM.encode("utf-8"),
            password=None
        )
    except Exception as e:
        print(f"Error loading private key: {e}")
        sys.exit(1)

    # Sign the step
    msg_bytes = current_step.encode("utf-8")
    signature = private_key.sign(msg_bytes)
    sig_hex = signature.hex()

    print(f"Message (Time-Step): {current_step}")
    print(f"Signature (Hex):\n{sig_hex}")
    print("\nCopy the signature above and run this command in Minecraft:")
    print(f"/ph unlock {sig_hex}")

if __name__ == "__main__":
    main()
