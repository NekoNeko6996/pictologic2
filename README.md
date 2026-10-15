# PicToLogic 2

<p align="center">
  <img src="https://github.com/user-attachments/assets/d4ee910b-2ea2-43d5-9439-303a56cd40df" alt="PicToLogic 2 Banner" width="900" />
</p>

<p align="center">
  <strong>High-performance GIF and image sequence to Mindustry Logic schematic converter using RLE Scanline + Palette encoding.</strong>
</p>

<p align="center">
  <a href="https://github.com/Anuken/Mindustry"><img src="https://img.shields.io/badge/Mindustry-v154%2B%20%2F%20v159.7-blue.svg?style=flat-square" alt="Mindustry Version"></a>
  <a href="https://kotlinlang.org"><img src="https://img.shields.io/badge/Kotlin-2.2.10-purple.svg?style=flat-square" alt="Kotlin"></a>
  <a href="https://adoptium.net"><img src="https://img.shields.io/badge/Java-17-orange.svg?style=flat-square" alt="Java 17"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache--2.0-green.svg?style=flat-square" alt="License"></a>
  <a href="https://github.com/7003Mars/pic2tri"><img src="https://img.shields.io/badge/Inspired%20By-pic2tri%20(7003Mars)-yellow.svg?style=flat-square" alt="Inspired by pic2tri"></a>
</p>

---

## Demo Showcase

Watch **PicToLogic 2** in action inside Mindustry:

https://github.com/user-attachments/assets/082c9600-5ded-426b-b3ba-405f21399fe1

> *This mod is built upon and inspired by the previous mod [pic2tri](https://github.com/7003Mars/pic2tri) by 7003Mars. While `pic2tri` pioneered polygon and triangle decomposition, **PicToLogic 2** introduces a completely redesigned architecture based on 2D Greedy Run-Length Encoding (RLE), Median-Cut Palette Quantization, Temporal Delta compression, and Multi-Processor Pipelined Workers.*

---

## Key Features

PicToLogic 2 provides an all-in-one in-game suite to convert media into ready-to-paste Mindustry schematics.

### 1. GIF & Animation Mode
- **Dual Source Support**: Load animated `.gif` files directly or read folders of sequential images (`png`, `jpg`, `bmp`) sorted with natural numerical sorting (`img_1`, `img_2`, `img_10`).
- **Perceptual Median-Cut Palette Quantization**:
  - Employs human perceptual color distance (Redmean metric).
  - **Square-Root Frequency Damping**: Compresses dominant background color weight (`sqrt(count) * 10`) so vibrant, subtle details (hair, eyes, accessories) are never lost to flat backgrounds.
  - Palette size configurable from **2 up to 64 colors**.
- **2D Greedy Run-Length Encoding (RLE)**:
  - 1D horizontal scanline compression groups adjacent pixels of identical color into `draw rect x y width 1`.
  - **Vertical Run Merging**: Greedily merges contiguous vertical scanlines into 2D rectangles `draw rect x y width height`, slashing total instruction counts.
- **Keyframe & Temporal Delta Compression**:
  - Renders a complete initial keyframe (Frame 0).
  - Successive frames only draw pixels that changed relative to the previous frame (delta encoding), drastically reducing processor instruction workloads.
- **Multi-Processor Pipelining & Round-Robin Load Balancer**:
  - Mindustry displays have a strict limit of 256 drawing commands per buffer. PicToLogic 2 safely caps slices at ≤ 200 commands.
  - Automatically calculates processor instruction throughput (IPT) and distributes frame slices across multiple worker processors to maintain silky-smooth target framerates (up to 30+ FPS).
- **Surrounding Spiral Layout Algorithm**:
  - Automatically arranges processors, switches, memory cells, and message blocks around the display grid in an optimal mathematical spiral, ensuring all blocks remain within logic link range.
- **Real-Time "Green-Flash" Telemetry & Self-Healing Clock**:
  - A central Memory Cell synchronizes frame clocks and tracks worker performance.
  - An attached Message Block renders a live diagnostic dashboard: worker status codes (Offline, Executing, Waiting Sync, Flushed OK, Idle) with real-time green highlights (`[green]W#:OK[]`).
  - Automatically handles game pauses, save/load reloads, and `@tick` regression to reset and resynchronize without desyncing.

### 2. Static Image Mode
- **1x1 Seamless Tiled Logic Display (Architecture A)**:
  - Supports grids up to **16x16** (up to 512x512 pixels seamless canvas).
  - Uses a **Daisy-Chained Processor Ring**: Micro Processors (1x1) pass execution to the next processor (`control enabled processor1 1`), eliminating the need for bulky memory cells.
- **3x3 and 6x6 Multi-Screen Displays (Architecture B)**:
  - Supports up to 5x5 grids for standard Logic Displays (80x80) and 4x4 grids for Large Logic Displays (176x176).
  - **Bezel Gap Compensation**: Automatically accounts for 16px screen bezel borders between displays so multi-screen artwork is never distorted or misaligned.
  - Managed by a master controller with coordinated memory synchronization.
- **Pixel-Perfect Center-Tile Alignment**:
  - Mindustry positions odd-sized (3x3: `+1`) and even-sized (6x6: `+2`) blocks relative to their center tile. PicToLogic 2 applies precise coordinate offsets to prevent processor overlap.
- **Custom Watermark Overlay**:
  - Stamp custom text (up to 15 characters) onto the top-right corner using an embedded 5x7 bitmap pixel font.
  - Choose from 9 vibrant watermark colors (White, Gold, Cyan, Red, Green, Pink, Orange, Purple, Black).
- **Aspect Ratio Modes**:
  - `FIT`: Preserves original image proportions without distortion.
  - `STRETCH`: Expands the image to fill the canvas entirely.
  - `CROP`: Centers and fills the canvas, trimming overflow.
  - Transparent PNG pixels (`alpha < 64`) and empty canvas areas are automatically ignored to save processor space.

---

## Architecture & Core Mechanics

```
┌─────────────────────────────────────────────────────────────┐
│                      Input Media                            │
│           (Animated GIF, Image Sequence, or Static PNG)     │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               Perceptual Median-Cut Quantization            │
│       (Redmean distance + Sqrt count weighting + 15-bit LUT) │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   2D Greedy RLE Compression                 │
│         (1D Scanline → 2D Rect Merge → Temporal Delta)      │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                 Worker Planner & Buffer Slicer              │
│      (≤ 200 commands/slice, IPT gap check, Pipelining)      │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                Schematic Generator (Layout Engine)          │
│       - Surrounding Spiral Placement                        │
│       - Center-Tile Offset Math (+1 for 3x3, +2 for 6x6)    │
│       - Daisy-Chain Micro Processors or Memory Bus Sync     │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│                   Mindustry Schematic (.msch)               │
│               Export directly to game schematics            │
└─────────────────────────────────────────────────────────────┘
```

### Memory Cell Bus Layout (Animation Mode)

| Address | Description |
|---|---|
| `cell1[0]` | Clock / Frame Index (`< 0` = Init Phase, `0..N-1` = Active Loop, `-999999` = Reset) |
| `cell1[1]` | Total `drawflush` counter across all workers |
| `cell1[2]` | Worker ID that executed the most recent drawflush |
| `cell1[3]` | Number of actively connected & initialized workers |
| `cell1[4..27]` | Live status code of Worker 0..23 (`0`=Offline, `1`=Executing, `2`=Waiting Sync, `3`=Flushed OK, `4`=Idle) |

---

## Installation

### Option 1: Using Prebuilt JAR (Recommended)
1. Download the latest release `.jar` (e.g. `pictologic2Desktop.jar`) from the **Releases** tab.
2. Place the `.jar` file into your Mindustry mods directory:
   - **Windows**: `%AppData%\Mindustry\mods`
   - **Linux**: `~/.local/share/Mindustry/mods`
   - **macOS**: `~/Library/Application Support/Mindustry/mods`
3. Launch Mindustry and make sure the mod is enabled in the **Mods** menu.

### Option 2: Build From Source
Requirements: JDK 17+ installed.

```bash
# Clone the repository
git clone https://github.com/NekoNeko6996/pictologic2.git
cd pictologic2

# Build shadow JAR
./gradlew shadowJar

# Or automatically build and deploy directly to your local Mindustry mods folder:
./gradlew deploy
```
*(On Windows, use `gradlew.bat deploy`)*

---

## How to Use

1. **Open the Tool**:
   - Click the **PicToLogic 2** button on the **Main Menu** or inside the **Schematics Dialog** (`F` / Schematics button).
2. **Select Mode**:
   - Choose **Tạo GIF / Animation** for dynamic sequences.
   - Choose **Tạo Ảnh Tĩnh (Static)** for high-resolution static artwork.
3. **Load Media**:
   - Click **Select File** and pick your image, GIF, or the first frame of an image sequence.
4. **Tune Parameters**:
   - **Display Type**: Choose between Tiled Display (1x1), Logic Display (80x80), or Large Logic Display (176x176).
   - **Grid Size**: Adjust horizontal and vertical display counts.
   - **Aspect Ratio**: Set to `FIT`, `STRETCH`, or `CROP`.
   - **Palette Colors**: Select between 2 and 64 colors (16 or 32 is recommended for optimal balance of fidelity and schematic size).
   - **FPS (Animation Mode)**: Set playback speed (1–30 FPS).
   - **Watermark (Static Mode)**: Enter up to 15 characters and choose a highlight color.
5. **Preview & Export**:
   - Preview the quantized artwork directly on the live viewport.
   - Review statistics (estimated workers, command load, memory requirements).
   - Click **Export Schematic** to generate the blueprint.
6. **Paste In-Game**:
   - Open your schematics menu, select the newly generated schematic, and paste it anywhere on the map!
   - Toggle the attached **Switch** to pause, resume, or re-render.

---

## Recommended Presets

| Content Type | Display Type | Recommended Grid | Palette Size | Target FPS / Time |
|---|---|---|---|---|
| **Pixel Art / Memes (GIF)** | Logic Display (3x3) | 1x1 | 8–16 colors | 10–12 FPS |
| **Smooth Animation** | Large Display (6x6) | 1x1 | 16 colors | 15–20 FPS |
| **High-Res Anime / Wallpapers** | Tiled Display (1x1) | 8x8 to 12x12 | 16–32 colors | ~1.5s render |
| **Billboard Banner** | Large Display (6x6) | 2x1 or 3x1 | 24–32 colors | Static multi-screen |

> [!TIP]
> For complex animations, using **Logic Processors** (8 IPT) provides the best balance of speed and physical footprint. For ultra-dense tiled static images, **Micro Processors** (1x1) create the cleanest layout.

---

## Acknowledgments & Credits

- **[7003Mars](https://github.com/7003Mars)** – Creator of the original **[pic2tri](https://github.com/7003Mars/pic2tri)** mod that inspired this project.
- **[Anuken](https://github.com/Anuken)** – Creator of [Mindustry](https://github.com/Anuken/Mindustry) and Arc engine.
- **Nguyen Hoang Nam ([NekoNeko6996](https://github.com/NekoNeko6996))** – Author and maintainer of PicToLogic 2.

---

## License

This project is licensed under the **Apache License 2.0**. See the [LICENSE](LICENSE) file for details.
