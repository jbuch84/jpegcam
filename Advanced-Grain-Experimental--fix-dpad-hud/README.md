# JPEG.CAM - In-Camera Custom Color Sciene System
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jbuchanan)

**💾 DOWNLOAD LINK:** Download the latest release from https://www.jpeg.cam

**⚠️ BETA STATUS:** This project is currently in early beta. While it is stable and produces high-quality results, it is a "proof of concept" running on 2014-era hardware.

JPEG.CAM turns your older Sony Alpha camera into a modern film-simulation powerhouse. This app brings professional color grading directly to your camera hardware, allowing you to apply custom 3D LUTs (`.cube` and `.cub` files) to your photos the moment you press the shutter.

## ✨ Features
* **Custom LUT Support:** Load your own `.cube` or `.cub` files directly from your SD card.
* **JPEG.CAM Dashboard:** A built-in wireless web server allows you to manage LUTs and download photos directly from the camera via Wi-Fi.
* **Auto-Processing Engine:** The app automatically detects new photos and processes them in the background using a high-fidelity NDK engine.
* **True Color Trilinear Interpolation:** High-fidelity color math prevents banding in gradients.
* **Triple Quality Modes:** Choose between `PROXY (1.5MP)` for speed, `HIGH (6.0MP)` for a balance of quality, or `ULTRA (24MP)` for full resolution.
* **Metadata Preservation:** Graded copies attempt to retain original EXIF metadata (ISO, Shutter Speed, etc.) by injecting saved APP markers into the processed files.
* **Non-Destructive:** Original files are untouched. Graded copies are saved to a `/GRADED/` folder on your SD card.

## ❗️ Technical Notes
* **LUT Names:** Keep your LUT filenames short! Use 8 characters or less (e.g., `Kodak400.cube`). Long filenames may be invisible to the camera's file system.
* **Battery Drain:** Heavy background processing and maintaining a Wi-Fi connection for the dashboard will impact battery life more than standard shooting.

## 🚧 Known Limitations
* **Processing Speed:** Because the camera uses a legacy processor, "High" or "Ultra" quality processing can take 15–30 seconds per image.
* **JPEG Only:** This version is optimized for JPEGs. RAW files are ignored by the processor.

## 📖 How to Use

**1. Prep your SD Card**
- Create a folder named `LUTS` on the absolute root of your SD card. Drop your favorite `.cube` or `.cub` files into this folder.

**2. Select your Recipe & Settings**
- Open the app. Use the **LEFT** and **RIGHT** keys to cycle through the dial modes (RTL, Shutter, Aperture, ISO, etc.).
- In **RTL mode**, spin the control wheel to cycle through your 10 saved recipe slots.
- Press **MENU** to open the full settings to adjust LUT opacity, grain amount, highlight roll-off, and quality size.

**3. Use the Wireless Dashboard**
- Navigate to the **Connections** page in the settings menu to start the Camera Hotspot or connect to Home Wi-Fi.
- Open the provided URL on your phone or laptop to wirelessly upload new LUTs or download your graded photos.

**4. Shoot!**
- Take a picture normally. The app will automatically see the new photo and begin `PROCESSING`. Once it says `SUCCESS`, your graded photo is ready.

## 📷 Supported Cameras
Compatible with PMCA cameras (Android 2.3.7 / API 10) including: **a5100, a6000, a6300, a6500, a7S II, a7R II, RX100 III/IV/V**.

## 🚀 Installation
1. Download the [pmca-gui installer](https://github.com/ma1co/Sony-PMCA-RE/releases).
2. Download the latest release from https://www.jpeg.cam
3. Connect camera via USB (MTP or Mass Storage mode) and use **pmca-gui** to install.
