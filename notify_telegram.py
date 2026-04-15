import requests
import os
import glob
import sys
import re

def get_version_name():
    """استخراج رقم الإصدار من ملف الجريدل"""
    try:
        path = "app/build.gradle.kts"
        with open(path, "r", encoding="utf-8") as f:
            content = f.read()
            # البحث عن الـ versionName باستخدام Regex
            match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
            if match:
                return match.group(1)
    except Exception as e:
        print(f"⚠️ Could not read version name: {e}")
    return "Unknown"

def send_to_telegram():
    # سحب المتغيرات من البيئة
    bot_token = os.getenv('BOT_TOKEN', '').strip()
    chat_id = os.getenv('CHAT_ID', '').strip()

    if not bot_token or not chat_id:
        print("❌ Error: BOT_TOKEN or CHAT_ID is missing!")
        sys.exit(1)

    # البحث عن ملف الـ APK في مسارات الديباج والريس
    # يبحث في المجلدات الفرعية لضمان إيجاد أي ملف APK ناتج
    apk_files = glob.glob("app/build/outputs/apk/**/*.apk", recursive=True)

    if not apk_files:
        print("❌ Error: No APK file found!")
        sys.exit(1)

    # اختيار أول ملف APK يظهر (الأحدث عادة في بيئة الـ CI)
    apk_path = apk_files[0]
    build_type = "RELEASE" if "release" in apk_path.lower() else "DEBUG"
    version_name = get_version_name()

    print(f"📦 Found {build_type} APK: {apk_path}")

    # تجهيز النص الاحترافي
    caption = (
        f"🚀 **New Build Dispatch**\n\n"
        f"🔹 **Type:** {build_type}\n"
        f"🔢 **Version:** `{version_name}`\n"
        f"📂 **File:** `{os.path.basename(apk_path)}`"
    )

    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"

    try:
        with open(apk_path, 'rb') as f:
            response = requests.post(
                url, 
                data={
                    'chat_id': chat_id, 
                    'caption': caption,
                    'parse_mode': 'Markdown' # لكي تظهر النصوص بشكل منسق (بولد وكود)
                }, 
                files={'document': f},
                timeout=120
            )

        if response.status_code == 200:
            print(f"🚀 Telegram: {build_type} version {version_name} sent successfully!")
        else:
            print(f"❌ Telegram Error: {response.status_code} - {response.text}")
            sys.exit(1)

    except Exception as e:
        print(f"💥 Exception occurred: {str(e)}")
        sys.exit(1)

if __name__ == "__main__":
    send_to_telegram()
