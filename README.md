# Sony JPG Cookbook: Film OS for Alpha Cameras (v2.0)
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/jbuch84)

**⚠️ BETA STATUS:** This project is currently in early beta. While it is stable and produces high-quality results, it is a "proof of concept" running on legacy hardware. 

Sony JPG Cookbook turns your older Sony Alpha camera into a modern film-simulation powerhouse. By bypassing the standard Sony JPEG engine, this app applies professional 3D LUTs (`.cube` files) and organic film grain directly to your photos the moment you press the shutter. 

## ✨ Features

* **10 Programmable Banks:** Build and save 10 unique "Real Time Looks" (RTL). Toggle between them instantly using the camera's control dials.
* **Real-Time Grading:** Apply industry-standard `.cube` LUTs with adjustable opacity directly to your captures.
* **Organic Grain Engine:** High-fidelity noise with quadratic shadow suppression for a true analog look without shadow mud.
* **Native UI:** A full-screen, table-based menu built for speed and tactile dial navigation—no clunky Android scrolling.
* **High-Efficiency C++ Engine:** Cache-interleaved memory management and optimized I/O for faster processing on aging hardware without sacrificing image quality.
* **Web Dashboard:** A built-in WiFi server to browse, review detailed binary EXIF data (Aperture, Shutter, ISO), and download your "Recipes" or "Originals" from any browser.
* **Pro-Grade Optics:** Corrects the legacy Sony 4:3 Android stretch for perfect 3:2 image review on the camera's LCD.


## 🚧 Known Limitations (The "Beta" Reality)
* **Processing Speed:** Because the camera uses a legacy processor, the "High" quality mode takes time to process a single image. The app prioritizes pixel quality over speed.
* **JPEG Only:** This version is optimized for JPEGs. RAW files are ignored by the processor.
* **Battery Drain:** Heavy background processing will impact battery life more than standard shooting.
* **EXIF Data:** Currently, processed images do not retain original EXIF metadata (ISO, Shutter Speed, etc.). This is preserved on the original files only.

## 📖 How to Use

**1. Prep your SD Card**
* Create a folder named `LUTS` on the absolute root of your SD card. Drop your favorite standard `.cube` files into this folder. 

**2. Screen & Dial Navigation**
* **Control Wheel (Left/Right):** Cycle through active modes: **RTL Slot**, **Shutter**, **Aperture**, **ISO**, **EV**, and **Review**.
* **Control Wheel (Up/Down) or Spinning Dial:** Adjust settings for the currently active mode.
* **Menu Button:** Open the **Cookbook** to program your RTL Banks (LUT, Opacity, Grain size/intensity). Navigate with **Up/Down** and change values with **Left/Right**.
* **Enter Button (Center):** * **In Review Mode:** Enters custom playback screen.
    * **In HUD Mode:** Toggles the on-screen display to hide or show info.
* **Trash Button:** Safety exit the app and return to the native Sony OS.

**3. Shooting & Processing**
* Take a photo normally. The app detects the new file and begins processing in the background.
* **Quality Modes:** Choose between `PROXY (1.5MP)`, `HIGH (6MP)`, or `ULTRA (24MP)` inside the main menu. 
* **Non-Destructive:** Your original photos remain untouched in `/DCIM/`. Graded copies are safely saved to `/GRADED/`.

## 📷 Supported Cameras
Compatible with PMCA-enabled Sony cameras (Android 2.3.7 / API 10) including: **a5100, a6000, a6300, a6500, a7 II, a7S II, a7R II, RX100 III/IV/V.**

## 🚀 Installation
1. Download the [pmca-gui installer](https://github.com/ma1co/Sony-PMCA-RE/releases).
2. Download the latest `.apk` from our [Releases](../../releases) page.
3. Connect your camera via USB (MTP or Mass Storage mode) and use **pmca-gui** to install.

## 🤝 Credits
Based on the groundwork by **ma1co** and the **OpenMemories** community. Built for photographers who believe that quality is king.
