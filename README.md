# AJ — मेरे फ़ोनों का admin

सिर्फ अपने फ़ोनों के लिए बना खुला device-admin। कोई voice/assistant नहीं।
एक account से अपने सभी फ़ोन जोड़ें, और किसी भी फ़ोन (app में या web portal में) से बाकी फ़ोन देखें और चलाएँ।

## क्या कर सकता है
- हर फ़ोन का हाल: 🔋 बैटरी, 💾 storage, ✉ आज के SMS count, 📞 missed, 📍 location
- **🔔 बजाओ** (फ़ोन ढूँढने के लिए), **📍 location**, **↻ status**
- **📷 फ़ोटो** (पीछे/आगे), **🎙 रिकॉर्डिंग** — और signed link से खोलना
- हर फ़ोटो/रिकॉर्डिंग सामने वाले फ़ोन पर दिखती है: notification + आवाज़ + Android का हरा निशान। कुछ छुपा नहीं। सिर्फ अपने फ़ोनों के लिए।

## हिस्से
- **Android app (AJ):** हर फ़ोन पर install; login के बाद वह फ़ोन admin से जुड़ जाता है और एक notification हमेशा दिखता है।
- **Web portal:** `portal/index.html` — browser से login करके वही सब देखें और चलाएँ।
- **Supabase (free):** account, devices, commands, media सब यहीं। SQL: SQL Editor में `supabase/aj_devices_setup.sql` + `supabase/aj_setup.sql` (या chat में दिया combined SQL) एक बार Run करें।

## Install
1. हर फ़ोन पर Releases से सबसे नया `AJ-xx.apk` install करें (पुराना हटाने की ज़रूरत नहीं)।
2. AJ → Settings → Cloud login: Supabase URL + anon key डालें, फिर email-password से account बनाएँ/login करें (सब फ़ोन पर वही account)।
3. हर फ़ोन का नाम रखें, और Permissions में Location, Camera, Microphone, files, बैटरी छूट दें।
4. किसी भी फ़ोन या web portal से “मेरे फ़ोन” खोलें।

ध्यान: जिस फ़ोन को दूर से देखना है, उस पर AJ login रहे और notification चलता रहे (बैटरी बचत से छूट दें), तभी वह जुड़ा रहता है।
