import json
import uuid
from dataclasses import dataclass
from datetime import datetime

from fastapi import FastAPI, WebSocket

from config import BASE_DIR, load_config

config = load_config()
upload_dir = BASE_DIR / config.get("upload_dir", "uploads")
upload_dir.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="Parking Server")


@dataclass
class StreamState:
    scanning: bool = False
    frame_count: int = 0


def _looks_like_jpeg(data: bytes) -> bool:
    return len(data) >= 10 and data[:2] == b"\xff\xd8"


def _save_jpeg(jpeg_bytes: bytes) -> str:
    filename = f"{datetime.now().strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:8]}.jpg"
    save_path = upload_dir / filename
    save_path.write_bytes(jpeg_bytes)
    return filename


async def _send_json(websocket: WebSocket, payload: dict) -> None:
    await websocket.send_text(json.dumps(payload, ensure_ascii=False))


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.websocket("/ws/parking")
async def parking_websocket(websocket: WebSocket) -> None:
    await parking_ws_core(websocket)


@app.websocket("/ws/parking/")
async def parking_websocket_slash(websocket: WebSocket) -> None:
    await parking_ws_core(websocket)


async def parking_ws_core(websocket: WebSocket) -> None:
    await websocket.accept()
    peer = websocket.client
    peer_s = f"{peer.host}:{peer.port}" if peer else "unknown"
    print(f"[WS] 客户端已连接 {peer_s}", flush=True)
    stream_state = StreamState()

    try:
        while True:
            message = await websocket.receive()
            mtype = message.get("type")

            if mtype == "websocket.disconnect":
                print(f"[WS] 客户端断开 {peer_s}", flush=True)
                break

            if mtype != "websocket.receive":
                continue

            if message.get("text") is not None:
                text_data = message["text"]
                try:
                    data_dict = json.loads(text_data)
                    msg_type = data_dict.get("type")

                    if msg_type == "hello":
                        print(f"[WS] 收到 hello", flush=True)
                        await _send_json(
                            websocket,
                            {"type": "hello_ok", "message": "服务器已连接"},
                        )
                        continue

                    if msg_type == "scan_begin":
                        stream_state = StreamState(scanning=True)
                        fps = data_dict.get("fps", 15)
                        resolution = data_dict.get("resolution", "480p")
                        print(
                            f"[WS] 开始接收视频流: {resolution} @ {fps}fps",
                            flush=True,
                        )
                        await _send_json(
                            websocket,
                            {"type": "scan_begin_ok", "message": "准备接收视频流"},
                        )
                        continue

                    if msg_type == "scan_end":
                        total = stream_state.frame_count
                        stream_state = StreamState()
                        print(f"[WS] 视频流结束，共收到 {total} 帧", flush=True)
                        await _send_json(
                            websocket,
                            {
                                "type": "scan_end_ok",
                                "frames_received": total,
                            },
                        )
                        continue
                except json.JSONDecodeError:
                    pass

                print(f"[WS] 收到文本: {text_data!r}", flush=True)
                continue

            if message.get("bytes") is not None:
                byte_data = message["bytes"]

                if not _looks_like_jpeg(byte_data):
                    print(f"[WS] 收到无效 JPEG 数据，{len(byte_data)} 字节", flush=True)
                    continue

                stream_state.frame_count += 1
                filename = _save_jpeg(byte_data)

                if (
                    stream_state.frame_count == 1
                    or stream_state.frame_count % 15 == 0
                ):
                    print(
                        f"[WS] 视频流帧 #{stream_state.frame_count}: "
                        f"{len(byte_data)} 字节 -> uploads/{filename}",
                        flush=True,
                    )
    except Exception as exc:
        print(f"[WS] 连接异常: {exc}", flush=True)
    finally:
        print(
            f"[WS] 连接结束 {peer_s}，累计帧数 {stream_state.frame_count}",
            flush=True,
        )


if __name__ == "__main__":
    import uvicorn

    try:
        import wsproto  # noqa: F401
    except ImportError:
        raise SystemExit(
            "缺少 WebSocket 依赖，请执行: pip install \"uvicorn[standard]\" websockets wsproto"
        )

    host = config.get("host", "0.0.0.0")
    port = int(config.get("port", "8001"))
    print(f"[Server] WebSocket 视频流: /ws/parking (480p JPEG @ 15fps)", flush=True)
    uvicorn.run(app, host=host, port=port)
