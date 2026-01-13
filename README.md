
# 🔥 Firewall App with URL Scanner (Android)

An Android-based firewall application built using the **Android VPN service** to monitor and control network traffic at the application level. The app includes URL scanning functionality and applies rule-based checks to identify potentially unsafe links, with a threat logging mechanism currently under development.

---

## 📌 Features

- VPN-based traffic interception using Android VPN service
- URL scanning and validation
- Rule-based filtering for detecting suspicious URLs
- Threat logging (implementation in progress)
- Simple and user-friendly Android interface
- Configurable rule logic

---

## 🛠️ Tech Stack

- **Language:** Kotlin  
- **UI:** XML (Android Layouts)  
- **Core API:** Android VPN Service  
- **Build System:** Gradle  
- **Platform:** Android  

---

## 🧠 How It Works

1. The application establishes a local VPN connection using the Android VPN service.
2. Network traffic is routed through the VPN interface.
3. URLs extracted from traffic or user input are validated.
4. Rule-based checks are applied to identify suspicious or unsafe URLs.
5. Detected threats are flagged, and logging functionality is being incrementally implemented.

All processing is handled locally on the device.

---

## 📂 Project Structure

```

FireWall-App-With-URL-Scanner/
│
├── app/                    # Android application source code
├── gradle/                 # Gradle wrapper files
├── build.gradle            # Project-level Gradle configuration
├── settings.gradle         # Project settings
├── gradlew                 # Gradle wrapper script
├── gradlew.bat             # Windows Gradle wrapper script
└── README.md               # Project documentation

````

---

## 🚀 Getting Started

### Prerequisites
- Android Studio
- Android SDK
- Java 8 or above

### Installation & Run
1. Clone the repository:
   ```bash
   git clone https://github.com/your-username/FireWall-App-With-URL-Scanner.git
````

2. Open the project in **Android Studio**
3. Sync Gradle dependencies
4. Grant VPN permission when prompted
5. Run the app on an emulator or physical device

---

## 📈 Future Improvements

* Complete and persist threat logging
* Improve URL extraction from VPN traffic
* Integrate external threat intelligence APIs
* Optimize VPN packet handling
* Enhance UI and performance

---

## ⚠️ Limitations

* This is an **application-level firewall**, not a kernel-level firewall
* VPN-based interception is limited by Android OS restrictions
* Threat logging is currently under active development

---

## 📜 License

This project is intended for educational and learning purposes.

```
