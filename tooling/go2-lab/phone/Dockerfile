FROM python:3.12.13-slim-bookworm@sha256:4766d8b510c428e595d74b9cc5bbb2fae8e26316fffb4adc89908d79aacd58a2
RUN apt-get update && apt-get install -y --no-install-recommends libportaudio2 ca-certificates git \
    && rm -rf /var/lib/apt/lists/*
COPY requirements.txt /tmp/go2-requirements.txt
RUN pip install --no-cache-dir -r /tmp/go2-requirements.txt \
    && pip install --no-cache-dir --no-deps 'unitree_webrtc_connect @ git+https://github.com/legion1581/unitree_webrtc_connect.git@e0abc5780761539eff89a382a79319e5cf6ad1f4'
RUN python -c 'import aiortc, av, numpy, wasmtime; from unitree_webrtc_connect.webrtc_driver import UnitreeWebRTCConnection' \
    && mkdir -p /opt/taku-go2/tooling/go2-lab /opt/taku-go2/out/go2-lab /tmp
WORKDIR /opt/taku-go2/tooling/go2-lab
