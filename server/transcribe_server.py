# -*- coding: utf-8 -*-
"""
播客 App 的服务器端转写服务（faster-whisper）

接口
  GET  /api/health                     → {"ok":true,"model":"small.en"}
  POST /api/transcribe                 → 表单 audio=<文件>, lang=en|zh
       可选请求头 X-Token: <API_TOKEN 环境变量>
       返回 {"text":"...","segments":[{"start":0.0,"end":3.2,"text":"..."}],
             "duration":6.2,"elapsed":3.1}

启动
  export API_TOKEN=你的口令
  python3 transcribe_server.py            # 默认 0.0.0.0:8787
  PORT=9000 python3 transcribe_server.py
"""
import os
import sys
import time
import tempfile

os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
os.environ.setdefault("HF_HUB_DISABLE_XET", "1")

import av
import numpy as np
from faster_whisper import WhisperModel
from flask import Flask, request, jsonify

BASE = os.path.dirname(os.path.abspath(__file__))
MODEL_DIR = os.path.join(BASE, "模型")
MODEL_NAME = os.environ.get("WHISPER_MODEL", "small.en")
TOKEN = os.environ.get("API_TOKEN", "")
MAX_MB = int(os.environ.get("MAX_MB", "60"))
PORT = int(os.environ.get("PORT", "8787"))

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_MB * 1024 * 1024

_model = None
_started = time.time()


def get_model():
    global _model
    if _model is None:
        print("[模型] 加载 %s ..." % MODEL_NAME, flush=True)
        t0 = time.time()
        _model = WhisperModel(
            MODEL_NAME,
            device="cpu",
            compute_type="int8",
            download_root=MODEL_DIR,
            cpu_threads=int(os.environ.get("CPU_THREADS", "2")),
        )
        print("[模型] 就绪 %.1fs" % (time.time() - t0), flush=True)
    return _model


def decode_audio(path, sampling_rate=16000):
    """任意格式 → 16k 单声道 float32（绕开 faster-whisper 内置解码对旧 PyAV 的依赖）"""
    with av.open(path) as container:
        if not container.streams.audio:
            raise ValueError("文件里没有音频流")
        stream = container.streams.audio[0]
        resampler = av.AudioResampler(format="s16", layout="mono", rate=sampling_rate)
        chunks = []
        for frame in container.decode(stream):
            for rf in resampler.resample(frame):
                chunks.append(rf.to_ndarray().reshape(-1))
        for rf in resampler.resample(None):
            chunks.append(rf.to_ndarray().reshape(-1))
    if not chunks:
        return np.zeros(0, dtype=np.float32)
    return np.concatenate(chunks).astype(np.float32) / 32768.0


@app.after_request
def add_cors(resp):
    resp.headers["Access-Control-Allow-Origin"] = "*"
    resp.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Token"
    resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
    return resp


@app.route("/api/health", methods=["GET"])
def health():
    return jsonify(ok=True, model=MODEL_NAME, uptime=round(time.time() - _started, 1))


@app.route("/api/transcribe", methods=["POST", "OPTIONS"])
def transcribe():
    if request.method == "OPTIONS":
        return ("", 204)

    if TOKEN and request.headers.get("X-Token", "") != TOKEN:
        return jsonify(error="口令不对"), 401

    f = request.files.get("audio")
    if f is None:
        return jsonify(error="缺少 audio 文件"), 400

    lang = (request.form.get("lang") or "en").strip() or "en"
    ext = os.path.splitext(f.filename or "")[1] or ".bin"

    tmp = tempfile.NamedTemporaryFile(delete=False, suffix=ext)
    tmp_path = tmp.name
    try:
        f.save(tmp_path)
        t0 = time.time()
        audio = decode_audio(tmp_path)
        duration = len(audio) / 16000.0
        model = get_model()
        segments, info = model.transcribe(
            audio,
            language=lang,
            beam_size=5,
            vad_filter=True,
        )
        segs = []
        for s in segments:
            t = s.text.strip()
            if t:
                segs.append({
                    "start": round(float(s.start), 2),
                    "end": round(float(s.end), 2),
                    "text": t,
                })
        text = " ".join(x["text"] for x in segs)
        return jsonify(
            text=text,
            segments=segs,          # 给 App 播放页做可点击跳转的字幕；老版本忽略这个字段
            duration=round(duration, 1),
            elapsed=round(time.time() - t0, 1),
            language=info.language,
        )
    except Exception as e:
        return jsonify(error=str(e)), 500
    finally:
        try:
            os.unlink(tmp_path)
        except OSError:
            pass


if __name__ == "__main__":
    print("[服务] %s:%d  token=%s" % ("0.0.0.0", PORT, "已设置" if TOKEN else "无（不校验）"), flush=True)
    get_model()
    app.run(host="0.0.0.0", port=PORT, threaded=True)
