# VideoStreamToVideo

把后端保存下来的一帧一帧图片合成为 MP4 视频。

默认输入目录：

```bash
/Users/wang/Downloads/ParkingSystem/Server/uploads
```

默认输出目录：

```bash
/Users/wang/Downloads/ParkingSystem/Server/videos
```

## 使用

在项目根目录运行：

```bash
python3 VideoStreamToVideo/frames_to_video.py
```

指定帧率：

```bash
python3 VideoStreamToVideo/frames_to_video.py --fps 15
```

指定输入和输出：

```bash
python3 VideoStreamToVideo/frames_to_video.py \
  --input-dir Server/uploads \
  --output Server/videos/test.mp4 \
  --fps 15
```

## 排序说明

工具默认按图片文件的修改时间排序：

```bash
--sort mtime
```

这是为了适配当前后端的保存方式。当前图片文件名只有秒级时间，同一秒内可能保存多帧，按修改时间排序会更接近真实视频流顺序。

如果以后后端把帧编号写进文件名，例如 `frame_000001.jpg`，可以改用：

```bash
--sort name
```

## 依赖

本工具依赖 `ffmpeg`。如果你的 Mac 没有安装：

```bash
brew install ffmpeg
```
