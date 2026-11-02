"""Run inside the API container; queries only the local Compass API and Valhalla."""
import base64
import json
import os
import urllib.error
import urllib.request

headers = {"Content-Type": "application/json"}
if os.environ.get("API_AUTH_ENABLED", "true").lower() == "true":
    token = base64.b64encode(
        (os.environ["API_AUTH_USERNAME"] + ":" + os.environ["API_AUTH_PASSWORD"]).encode()
    ).decode()
    headers["Authorization"] = "Basic " + token


def check(label, points):
    request = urllib.request.Request(
        "http://127.0.0.1:8000/api/v1/enforcement/road-context",
        data=json.dumps({"points": points}).encode(), headers=headers, method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            print(label, response.status, response.read().decode())
    except urllib.error.HTTPError as error:
        print(label, error.code, error.read().decode())


# Synthetic connectivity probe near an imported camera; not a recorded driving trace.
points = [
    {"latitude": lat, "longitude": lon, "elapsed_seconds": i * 5, "accuracy_meters": 10}
    for i, (lat, lon) in enumerate([
        (45.0010579, 8.9540968), (45.0013, 8.9531), (45.0015868, 8.9519647),
    ])
]
check("moving", points)
check("stationary", [{**point, "latitude": 45, "longitude": 9} for point in points])
check("invalid_timestamps", [{**point, "elapsed_seconds": 0} for point in points])
