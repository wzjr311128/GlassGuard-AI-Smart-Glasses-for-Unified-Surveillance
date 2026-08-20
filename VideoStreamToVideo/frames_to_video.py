#!/usr/bin/env python3
"""Convert saved image frames into an MP4 video."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime
from pathlib import Path


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png"}
PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INPUT_DIR = PROJECT_ROOT / "Server" / "uploads"
DEFAULT_OUTPUT_DIR = PROJECT_ROOT / "Server" / "videos"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Convert a directory of image frames into an MP4 video."
    )
    parser.add_argument(
        "-i",
        "--input-dir",
        type=Path,
        default=DEFAULT_INPUT_DIR,
        help=f"Directory containing frame images. Default: {DEFAULT_INPUT_DIR}",
    )
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        default=None,
        help="Output MP4 path. Default: Server/videos/parking_<timestamp>.mp4",
    )
    parser.add_argument(
        "--fps",
        type=float,
        default=15.0,
        help="Frames per second for the output video. Default: 15",
    )
    parser.add_argument(
        "--sort",
        choices=("mtime", "name"),
        default="mtime",
        help="Frame order. Use mtime for backend-saved frames. Default: mtime",
    )
    parser.add_argument(
        "--recursive",
        action="store_true",
        help="Search for frames recursively inside input-dir.",
    )
    parser.add_argument(
        "--keep-temp",
        action="store_true",
        help="Keep the temporary numbered frame directory for debugging.",
    )
    return parser.parse_args()


def find_frames(input_dir: Path, recursive: bool, sort_mode: str) -> list[Path]:
    if not input_dir.exists():
        raise FileNotFoundError(f"Input directory does not exist: {input_dir}")
    if not input_dir.is_dir():
        raise NotADirectoryError(f"Input path is not a directory: {input_dir}")

    pattern = "**/*" if recursive else "*"
    frames = [
        path
        for path in input_dir.glob(pattern)
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    ]

    if sort_mode == "mtime":
        frames.sort(key=lambda path: (path.stat().st_mtime_ns, path.name))
    else:
        frames.sort(key=lambda path: path.name)

    return frames


def ensure_ffmpeg() -> str:
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise RuntimeError(
            "ffmpeg is not installed or not in PATH. Install it with: brew install ffmpeg"
        )
    return ffmpeg


def default_output_path() -> Path:
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    return DEFAULT_OUTPUT_DIR / f"parking_{timestamp}.mp4"


def link_numbered_frames(frames: list[Path], temp_dir: Path) -> None:
    for index, frame in enumerate(frames, start=1):
        target = temp_dir / f"frame_{index:06d}.jpg"
        target.symlink_to(frame.resolve())


def convert_frames(frames: list[Path], output: Path, fps: float, keep_temp: bool) -> None:
    ffmpeg = ensure_ffmpeg()
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_context: tempfile.TemporaryDirectory[str] | None = None
    temp_dir: Path | None = None

    try:
        if keep_temp:
            temp_dir = Path(tempfile.mkdtemp(prefix="parking_frames_"))
        else:
            temp_context = tempfile.TemporaryDirectory(prefix="parking_frames_")
            temp_dir = Path(temp_context.name)

        link_numbered_frames(frames, temp_dir)
        input_pattern = temp_dir / "frame_%06d.jpg"

        command = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "warning",
            "-y",
            "-framerate",
            str(fps),
            "-i",
            str(input_pattern),
            "-c:v",
            "libx264",
            "-preset",
            "medium",
            "-crf",
            "20",
            "-vf",
            "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
            "-movflags",
            "+faststart",
            str(output),
        ]

        subprocess.run(command, check=True)
    finally:
        if keep_temp:
            print(f"Temporary frames kept at: {temp_dir}")
        elif temp_context is not None:
            temp_context.cleanup()


def main() -> int:
    args = parse_args()

    if args.fps <= 0:
        print("Error: --fps must be greater than 0", file=sys.stderr)
        return 2

    input_dir = args.input_dir.resolve()
    output = (args.output or default_output_path()).resolve()

    try:
        frames = find_frames(input_dir, args.recursive, args.sort)
        if not frames:
            print(f"No image frames found in: {input_dir}", file=sys.stderr)
            return 1

        print(f"Input directory: {input_dir}")
        print(f"Frames found: {len(frames)}")
        print(f"Sort mode: {args.sort}")
        print(f"FPS: {args.fps:g}")
        print(f"Output: {output}")

        convert_frames(frames, output, args.fps, args.keep_temp)
        print(f"Done: {output}")
        return 0
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
