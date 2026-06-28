import requests
import os
import glob
import sys
import re
from pathlib import Path

def get_version_name():
    try:
        path = "app/build.gradle.kts"
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
            match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
            if match:
                return match.group(1)
    except Exception as e:
        print(f"⚠️ Could not read version name: {e}")
    return "Unknown"

def send_apks_to_telegram():
    bot_token = os.getenv('BOT_TOKEN', '').strip()
    chat_id = os.getenv('CHAT_ID', '').strip()

    if not bot_token or not chat_id:
        print("❌ Error: BOT_TOKEN or CHAT_ID is missing!")
        sys.exit(1)

    apk_files = glob.glob("app/build/outputs/apk/release/*.apk", recursive=True)
    release_apks = [f for f in apk_files if "release" in f.lower() and "unaligned" not in f.lower()]

    if not release_apks:
        print("❌ Error: No Release APK files found!")
        print("📁 Available APKs:", apk_files)
        sys.exit(1)

    version_name = get_version_name()
    print(f"📦 Found {len(release_apks)} Release APK files")

    success_count = 0
    for apk_path in release_apks:
        abi = "ARM64-v8a" if "arm64-v8a" in apk_path else "universal"
        file_size = os.path.getsize(apk_path) / (1024 * 1024)

        print(f"📤 Sending: {os.path.basename(apk_path)} ({abi}, {file_size:.1f} MB)")

        caption = (
            f"🚀 **New Build Dispatch**\n\n"
            f"🔹 **Type:** RELEASE\n"
            f"🔢 **Version:** `{version_name}`\n"
            f"📱 **Architecture:** `{abi}`\n"
            f"📂 **File:** `{os.path.basename(apk_path)}`\n"
            f"📦 **Size:** `{file_size:.1f} MB`"
        )

        url = f"https://api.telegram.org/bot{bot_token}/sendDocument"

        try:
            with open(apk_path, 'rb') as f:
                response = requests.post(
                    url, 
                    data={
                        'chat_id': chat_id, 
                        'caption': caption,
                        'parse_mode': 'Markdown'
                    }, 
                    files={'document': f},
                    timeout=120
                )

            if response.status_code == 200:
                print(f"✅ Successfully sent: {os.path.basename(apk_path)}")
                success_count += 1
            else:
                print(f"❌ Failed to send {os.path.basename(apk_path)}: {response.status_code} - {response.text}")

        except Exception as e:
            print(f"💥 Exception while sending {os.path.basename(apk_path)}: {str(e)}")

    print(f"\n📊 Summary: {success_count}/{len(release_apks)} APK files sent successfully")

    if success_count == 0:
        sys.exit(1)
    elif success_count < len(release_apks):
        print("⚠️ Some files failed to send, but continuing...")
        sys.exit(0)

if __name__ == "__main__":
    send_apks_to_telegram()
