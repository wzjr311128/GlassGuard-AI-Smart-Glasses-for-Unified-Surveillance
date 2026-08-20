# GlassGuard AI Smart Glasses for Unified Surveillance

This repository contains the prototype code for a smart-glasses parking surveillance workflow.

## Project Structure

- `Glasses/`: Android smart-glasses client app.
- `Server/`: FastAPI WebSocket backend for receiving JPEG video frames.
- `VideoStreamToVideo/`: Utility for converting saved frame images into MP4 video.

## Backend Quick Start

```bash
cd Server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python main.py
```

The backend listens on the host and port configured in `Server/server_config.properties`.

## Glasses Client Configuration

Update `Glasses/app/src/main/assets/address.properties` so `ws.host` points to the backend machine on the same LAN.

```properties
ws.host=YOUR_SERVER_LAN_IP
ws.port=8002
```

## Frame-To-Video Utility

```bash
python3 VideoStreamToVideo/frames_to_video.py --input-dir Server/uploads --output Server/videos/output.mp4 --fps 15
```

The utility requires `ffmpeg`.
