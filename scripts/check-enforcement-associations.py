"""Inside the API container: inspect current association results through the protected API."""
import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request

from sqlalchemy import text

from compass.db import SessionLocal

headers = {}
if os.environ.get("API_AUTH_ENABLED", "false").lower() == "true":
    encoded = base64.b64encode(
        (os.environ["API_AUTH_USERNAME"] + ":" + os.environ["API_AUTH_PASSWORD"]).encode()
    ).decode()
    headers["Authorization"] = "Basic " + encoded
with SessionLocal() as session:
    counts = session.execute(text(
        "SELECT state,count(*) AS count FROM enforcement_associations GROUP BY state"
    )).mappings().all()
    geometry = session.execute(text("""
        SELECT count(*) AS active_relations,
               count(*) FILTER (WHERE NOT (raw ? 'road_ways')) AS missing_source_geometry
        FROM enforcement_controls WHERE active AND raw->>'type'='relation'
    """)).mappings().one()
    keys = session.execute(text("""
        SELECT a.source_key FROM enforcement_associations a
        JOIN enforcement_controls c USING (source_key) WHERE c.active
        ORDER BY CASE WHEN a.state='matched' THEN 0 ELSE 1 END,a.source_key LIMIT 3
    """)).scalars().all()
print("source_geometry", json.dumps(dict(geometry)))
print("stored_states", json.dumps([dict(row) for row in counts]))
for key in keys:
    request = urllib.request.Request(
        "http://127.0.0.1:8000/api/v1/enforcement/association?" +
        urllib.parse.urlencode({"source_key": key}), headers=headers,
    )
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            result = json.load(response)
            evidence = result.pop("evidence", {})
            result["edge_count"] = len(evidence.get("edge_ids", []))
            result["length_meters"] = evidence.get("length_meters")
            print(response.status, json.dumps(result))
    except urllib.error.HTTPError as error:
        print(error.code, error.read().decode())
