ARG VALHALLA_IMAGE=ghcr.io/valhalla/valhalla-scripted:3.8.3@sha256:24ef7955899dececb94e26c6dfb89d64fabfae875f980432694b0261eb6c251b
FROM ${VALHALLA_IMAGE}

USER root

RUN apt-get update \
    && apt-get install --no-install-recommends -y g++ ca-certificates rapidjson-dev \
    && rm -rf /var/lib/apt/lists/*

COPY traffic_tar_tool.cc /tmp/traffic_tar_tool.cc
COPY test_traffic_tar_tool.py /tmp/test_traffic_tar_tool.py

RUN g++ -std=c++20 -O2 -Wall -Wextra -pedantic \
    -I/usr/local/include \
    /tmp/traffic_tar_tool.cc \
    -L/usr/local/lib -lvalhalla \
    -Wl,-rpath,/usr/local/lib \
    -o /usr/local/bin/compass-valhalla-traffic-tool \
    && python3 /tmp/test_traffic_tar_tool.py

ENTRYPOINT ["compass-valhalla-traffic-tool"]
