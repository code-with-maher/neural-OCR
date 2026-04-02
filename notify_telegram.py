import requests
import os
import glob
import sys

def send_to_telegram():
    # سحب المتغيرات من البيئة وتنظيفها
    bot_token = os.getenv('BOT_TOKEN', '').strip()
    chat_id = os.getenv('CHAT_ID', '').strip()
    caption = "✅ تم بناء نسخة ريليز بنجاح يا ماهر! خذها قبل أن تبرد."

    if not bot_token or not chat_id:
        print("❌ Error: BOT_TOKEN or CHAT_ID is missing!")
        sys.exit(1)

    # البحث عن ملف الـ APK في مسارات المخرجات
    apk_files = glob.glob("app/build/outputs/apk/release/*.apk")
    
    if not apk_files:
        print("❌ Error: No APK file found in release folder!")
        sys.exit(1)

    apk_path = apk_files[0]
    print(f"📦 Found APK: {apk_path}")

    # إرسال الملف
    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"
    
    try:
        with open(apk_path, 'rb') as f:
            response = requests.post(
                url, 
                data={'chat_id': chat_id, 'caption': caption}, 
                files={'document': f},
                timeout=60 # وقت مستقطع طويل للملفات الكبيرة
            )
        
        if response.status_code == 200:
            print("🚀 Telegram: Document sent successfully!")
        else:
            print(f"❌ Telegram Error: {response.status_code} - {response.text}")
            sys.exit(1)
            
    except Exception as e:
        print(f"💥 Exception occurred: {str(e)}")
        sys.exit(1)

if __name__ == "__main__":
    send_to_telegram()
