"""Local API/Valhalla smoke test with synthetic OSM traces, never an actual device trace.

Run: docker compose exec -T api python3 < scripts/check-enforcement-approach.py
No import, update, paid provider call or database write is performed.
"""
import base64
import json
import os
import urllib.error
import urllib.request

from sqlalchemy import text

from compass.db import SessionLocal
from compass.enforcement.approach import project
from compass.routing.domain import Coordinate

headers = {"Content-Type": "application/json"}
if os.environ.get("API_AUTH_ENABLED", "true").lower() == "true":
    credentials = os.environ["API_AUTH_USERNAME"] + ":" + os.environ["API_AUTH_PASSWORD"]
    headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()


def check(label, points):
    request = urllib.request.Request(
        "http://127.0.0.1:8000/api/v1/enforcement/approach",
        data=json.dumps({"points": points}).encode(), headers=headers, method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            result = json.load(response)
            alert = result.get("alert")
            if alert:
                result["alert"] = {key: alert[key] for key in (
                    "source_key", "kind", "distance_meters", "speed_limit_kph", "forward",
                )}
            print(label, response.status, json.dumps(result), flush=True)
    except urllib.error.HTTPError as error:
        print(label, error.code, error.read().decode(), flush=True)


def point_at(shape, along):
    for first, second in zip(shape, shape[1:], strict=False):
        length = project(second, [first, second])[2]
        if length <= 0:
            continue
        if along <= length:
            fraction = along / length
            return Coordinate(first.latitude + fraction * (second.latitude - first.latitude),
                              first.longitude + fraction * (second.longitude - first.longitude))
        along -= length
    raise ValueError("Outside road")


stationary = [{"latitude": 45, "longitude": 9, "elapsed_seconds": i * 2,
               "accuracy_meters": 5} for i in range(3)]
check("stationary", stationary)
check("invalid_timestamps", [{**p, "elapsed_seconds": 0} for p in stationary])
with SessionLocal() as session:
    rows = session.execute(text("""
        SELECT c.source_key,c.raw,a.evidence FROM enforcement_controls c
        JOIN enforcement_associations a USING(source_key)
        WHERE c.active AND a.state='matched' AND a.import_id=c.import_id
        ORDER BY c.source_key LIMIT 100
    """)).mappings().all()
count = 0
for row in rows:
    proof = row["evidence"]
    roads = [w for w in row["raw"].get("road_ways", []) if w["id"] == proof["osm_way_id"]]
    if len(roads) != 1:
        continue
    way = roads[0]
    nodes = list(way["nodes"])
    shape = [Coordinate(p["lat"], p["lon"]) for p in way["geometry"]]
    if not proof["forward"]:
        shape.reverse()
        nodes.reverse()
    if len(nodes) != len(shape) or nodes.count(proof["from_node"]) != 1:
        continue
    start = project(shape[nodes.index(proof["from_node"] )], shape)[2]
    if start < 95:
        continue
    positions = [point_at(shape, start - offset) for offset in (90, 60, 30)]
    points = [{"latitude": p.latitude, "longitude": p.longitude, "elapsed_seconds": i * 2,
               "accuracy_meters": 5} for i, p in enumerate(positions)]
    check("synthetic " + row["source_key"], points)
    count += 1
    if count == 3:
        break
if not count:
    print("No matched source with 95 m of upstream geometry; return association diagnostics.")
