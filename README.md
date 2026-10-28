# 💊 My Medicine Cabinet (صندوق أدويتي)

A smart, privacy-first, and fully localized Android application designed to help you manage your home medication inventory safely and efficiently. 

Built with modern Android development standards, this app keeps track of what’s in your medicine cabinet, alerts you before medications expire, and features a completely offline AI assistant to answer your medication queries using Local LLMs (Large Language Models) without sending your private health data to the cloud.

## ✨ Key Features

* **📦 Comprehensive Inventory Management:** Track trade names, scientific names, forms, dosages, quantities, storage locations, and refrigeration needs.
* **⏰ Expiry Alerts & Notifications:** Automated warnings for medications that are nearing expiration or have already expired.
* **🤖 Local AI Assistant (Text & Voice):** Ask questions about medications, dosages, and side effects. Connects to your own locally hosted LLM (e.g., Ollama or LM Studio) for 100% privacy.
* **📷 Barcode Scanning (Preview):** Quickly add medications to your inventory by scanning their barcode (Note: currently uses simulated data for demonstration).
* **🌍 Multi-Language Support:** Fully localized in English, Arabic, French, and German with seamless runtime switching.
* **🌗 Dynamic Theming:** Supports Light, Dark, and System default themes.
* **📄 Export & Print:** Export your entire inventory to share with your doctor or keep as a physical backup.

## 🛠 Tech Stack

* **Language:** [Kotlin](https://kotlinlang.org/)
* **UI Framework:** [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material Design 3)
* **Architecture:** MVVM (Model-View-ViewModel)
* **Local AI Integration:** OkHttp for REST API communication with Local LLM servers (Ollama/OpenAI-compatible endpoints).

## 🚀 Getting Started

### Prerequisites
* Android Studio (Latest stable version recommended)
* Android SDK Minimum version 24 (Target 34)

### Building the App
1. Clone this repository:
   ```bash
   git clone https://github.com/yourusername/my-medicine-cabinet.git
   ```
2. Open the project in Android Studio.
3. Sync the project with Gradle files.
4. Run the app on an emulator or physical Android device.

## 🧠 Connecting the Local AI Assistant

To ensure absolute privacy, the AI assistant in this app connects to a Large Language Model running on your own local network (like your PC or Mac) instead of a cloud provider.

1. Install [Ollama](https://ollama.com/) or [LM Studio](https://lmstudio.ai/) on your computer.
2. Download a model (e.g., `llama3` or `mistral`).
3. Find your computer's local IP address (e.g., `192.168.1.5`).
   * *Note: If testing on an Android Emulator running on the same machine as the LLM, use `http://10.0.2.2:11434` for Ollama.*
4. Open **My Medicine Cabinet**, go to **Settings > Local LLM Settings**.
5. Enter your Host URL (e.g., `http://192.168.1.5:11434`) and the Model Name you downloaded.
6. Tap **Test Connection** to verify!

## 🛡 Privacy & Disclaimer

**My Medicine Cabinet is designed for organizational purposes only.** 
The data is stored locally on your device. The app and its AI assistant do not provide professional medical advice, diagnosis, or treatment. Always consult with a qualified healthcare provider or pharmacist regarding medical conditions and medication usage.

## Box scan (OCR)

The **Scan medicine box (`مسح علبة الدواء`)** feature provides a guided, multi-side, 100% on-device OCR workflow (`com.example.boxscan`):

- **How it works:** Users can capture 1 to 6 sides of a medication box (`FRONT`, `BACK`, `SIDE_1`, `SIDE_2`, `FLAP`, `UNKNOWN`). Each image is preprocessed locally (`ImagePreprocessor` normalizes EXIF orientation, resizes to `~1600px`, generates standard contrast-enhanced and high-contrast stamp variants, and retries 90°/180°/270° rotations if text is sparse).
- **Multilingual Extraction & Merging:** `LocalMultilingualOcrEngine` (with bundled offline profiles in `app/src/main/assets/tessdata/ara.traineddata` and `eng.traineddata`), `TextNormalizer`, `DateParser`, `FieldExtractor`, and `MultiSideMerger` extract and merge candidates across sides with confidence scoring (`DRAFT_THRESHOLD = 0.60`, `HIGH_CONFIDENCE_THRESHOLD = 0.85`, `+0.15` multi-side corroboration bonus).
- **Supported Fields Only:** Trade/brand name, active ingredient (`scientificName`), strength, dosage form (`MedForm`), pack quantity, manufacturer, batch/lot number (`LOT`), manufacturing date (`MFG`), expiry date (`EXP`), and refrigeration suggestion (`2–8°C` / `يحفظ في الثلاجة`).
- **Safety Boundaries & Limitations:**
  - **No Barcode Decoding in Box Scan:** Barcode stripes, GTIN/GS1 Application Identifiers, and 12–14 digit barcode strings are ignored (`MedicineDraft.barcode` is always `null`).
  - **No Medical Advice Extraction:** `indications`, `warnings`, and `usageMode` (dosage instructions) are never populated from OCR.
  - **Mandatory Human Review:** Extracted values create an in-memory `MedicineDraft` reviewed in `BoxScanReviewScreen` and `AddMedicineScreen`. Expiry dates require explicit user confirmation before saving, and expired packages display `SafetyStrings.EXPIRED_DISPOSAL`.

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
