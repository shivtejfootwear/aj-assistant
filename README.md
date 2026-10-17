# AJ — अपना personal assistant

सिर्फ अपने फ़ोन के लिए बना Iron Man के JARVIS जैसा assistant। नाम: **AJ**।

## Phase 1 में क्या है
- **“AJ” बोलकर जगाना** (फ़ोन के अंदर offline पहचान, free) → बीप → अपनी बात बोलें
- सिर्फ “AJ” बोलकर चुप रहें तो AJ पूछेगा: “मैं आपकी कैसे सहायता कर सकता हूँ?”
- बड़ा **बोलो** बटन, notification में भी ‘बोलो’ बटन, और लिखकर पूछने का खाना
- आज के **SMS** और **कॉल** (missed, आई, की) बताना
- **स्टोरेज, बैटरी, आज के downloads**, समय और तारीख
- **किसी को कॉल लगाना**, **SMS भेजना** (भेजने से पहले हाँ/नहीं पूछेगा)
- **टाइमर, अलार्म, टॉर्च**, **Google search**, कोई भी **app खोलना**
- **AJ को सिखाओ**: “जब मैं यह बोलूँ → AJ यह बोले” (जोड़ना, बदलना, हटाना)
- **Dashboard**: आज का हाल, हाल की बातचीत, permissions

## कुछ बोलकर देखें
| आप बोलें | AJ करेगा |
|---|---|
| AJ … (चुप) | “मैं आपकी कैसे सहायता कर सकता हूँ?” |
| आज किसका मैसेज आया | आज के SMS बताएगा |
| आज के मैसेज पढ़ो | सबसे नए मैसेज पढ़कर सुनाएगा |
| आज किसकी कॉल आई | missed / आई कॉल बताएगा |
| राहुल को कॉल करो | राहुल को कॉल लगाएगा |
| राहुल को मैसेज भेजो कि मैं 10 बजे आऊँगा | पूछेगा, “हाँ” बोलने पर भेजेगा |
| स्टोरेज कितना है | कुल / खाली / भरी जगह |
| आज क्या डाउनलोड हुआ | आज की नई फाइलें |
| 5 मिनट का टाइमर लगाओ | टाइमर |
| सुबह साढ़े 6 बजे का अलार्म लगाओ | अलार्म |
| टॉर्च चालू करो / बंद करो | टॉर्च |
| गूगल पे आज का मौसम सर्च करो | Google search |
| WhatsApp खोलो | app खुलेगा |
| तुम क्या कर सकते हो | सारे काम बताएगा |

## Install
1. GitHub पर इस repo के **Releases** में सबसे नया `AJ-xx.apk` फ़ोन पर download करें।
2. Install करें (“Unknown apps” की इजाज़त माँगे तो दें)।
3. AJ खोलें → **Permissions** वाले हिस्से में सब ✅ करें।
4. **Model download** दबाएँ (≈36 MB, एक बार) → **“AJ” बोलकर जगाओ** switch चालू करें।

नया version आने पर बस नया APK install करें। पुराना हटाने की ज़रूरत नहीं, AJ की याददाश्त बची रहेगी।

## ध्यान रखें
- हिंदी में बात समझने के लिए Google की आवाज़ पहचान इस्तेमाल होती है (internet चाहिए, या Google की offline हिंदी download कर लें)।
- फ़ोन restart होने के बाद AJ app एक बार खोल दें, ताकि “AJ” सुनना फिर शुरू हो।
- कोई permission “restricted setting” बोलकर न मिले: Settings → Apps → AJ → ⋮ → **Allow restricted settings**।

## Version 2 में नया
- **AI दिमाग:** फ़ोन वाले commands के बाहर का हर सवाल Gemini (free) को, Google Search के साथ। Key app में डालनी है (SMS/OTP Gemini को नहीं जाते)।
- **अच्छी आवाज़:** फ़ोन की हिंदी आवाज़ें चुनना (speed, pitch) + Gemini की इंसान जैसी आवाज़; न चले तो फ़ोन वाली।
- **Cloud backup:** Supabase login; सीखी बातें, बातचीत, settings cloud में; reinstall पर login करते ही वापस; रोज़ की अलग copy (180 दिन)।
- Supabase setup: `supabase/aj_setup.sql` SQL Editor में Run करें। Project URL और anon key GitHub secrets `AJ_SUPABASE_URL`, `AJ_SUPABASE_ANON_KEY` में डालें (या app के अंदर)।

## Version 3 में नया
- **नया futuristic dashboard:** चलता-फिरता robot orb, glass cards, आज का हाल tiles, quick buttons, chat bubbles, 3 tabs (होम / सिखाओ / Settings), नया robot logo।
- **Groq तेज़ दिमाग:** internet search वाला free AI (groq/compound), उसकी limit पर Gemini backup।
- **सुधार:** Gemini आवाज़ अब निर्देश नहीं बोलती; search की दिक्कत पर बिना search के दोबारा कोशिश; आवाज़ streaming से चलती है (खरखराहट कम)।

## Version 4 में नया — मेरे फ़ोन (admin)
- अपने सभी फ़ोन एक ही account से login करें → Settings → “मेरे फ़ोन” में सबका हाल दिखे (battery, storage, आज के SMS/call count, location)।
- किसी फ़ोन को **बजाओ** (ढूँढने के लिए ज़ोर से बजेगा), **location** मँगाएँ, या **status** ताज़ा करें।
- हर काम सामने वाले फ़ोन पर भी दिखता है (AJ का notification/आवाज़), कुछ छुपा नहीं। सिर्फ अपने फ़ोनों के लिए।
- Supabase: `supabase/aj_devices_setup.sql` एक बार Run करें। नियंत्रित फ़ोन पर AJ चालू/login रहे तभी वह जुड़ा रहता है।
- (अगले update में: camera/recording — साफ़ indicator के साथ।)

## आगे (Phase 3 और बाद)
Gemini AI दिमाग (free) · WhatsApp/Instagram मैसेज पढ़ना और reply · Emergency (112 auto call + location SMS) · Laptop से file · Fingerprint lock · Tasks/reminders
