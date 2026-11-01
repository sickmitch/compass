import hashlib
import json
import logging

from sqlalchemy import text
from sqlalchemy.orm import Session

from compass.enforcement.osm import parse_report
from compass.etl.acquisition import DownloadedPayload


def ingest(session: Session, payload: DownloadedPayload, scope: str) -> dict:
    report = parse_report(payload.content)
    controls = report.controls
    diagnostics = {
        "rejected_count": len(report.rejected),
        "rejected_sample": [
            {"source_key": item["source_key"], "reason": item["reason"]}
            for item in report.rejected[:20]
        ],
    }
    if report.rejected:
        logging.getLogger(__name__).warning(
            "enforcement records quarantined: %s", json.dumps(diagnostics, sort_keys=True),
        )
    # Never erase a previously valid inventory on an unexpectedly empty extraction.
    if not controls:
        raise ValueError("Empty enforcement extraction rejected; previous inventory retained")
    digest = hashlib.sha256(payload.content).hexdigest()
    with session.begin():
        # Serialize same-scope imports, including concurrent scheduled/manual jobs.
        session.execute(text("SELECT pg_advisory_xact_lock(hashtext(:scope))"),
                        {"scope": f"enforcement:osm:{scope}"})
        previous = session.execute(text("""
            SELECT id FROM enforcement_imports
            WHERE source='osm' AND scope=:scope AND sha256=:digest
        """), {"scope": scope, "digest": digest}).scalar_one_or_none()
        if previous is not None:
            return {
                "status": "unchanged", "count": len(controls),
                "import_id": previous, **diagnostics,
            }
        import_id = session.execute(text("""
            INSERT INTO enforcement_imports
            (source,scope,sha256,source_url,fetched_at,record_count,rejected)
            VALUES ('osm',:scope,:digest,:url,:at,:count,CAST(:rejected AS jsonb)) RETURNING id
        """), {"scope": scope, "digest": digest, "url": payload.source_url,
               "at": payload.fetched_at, "count": len(controls),
               "rejected": json.dumps(report.rejected)}).scalar_one()
        session.execute(text("""
            UPDATE enforcement_controls SET active=false WHERE source='osm' AND scope=:scope
            AND NOT (source_key = ANY(CAST(:rejected_keys AS text[])))
        """), {"scope": scope, "rejected_keys": [
            item["source_key"] for item in report.rejected
        ]})
        for control in controls:
            session.execute(text("""
                INSERT INTO enforcement_controls
                (source_key,source,scope,kind,location,direction_degrees,speed_limit_kph,
                 raw,import_id,fetched_at,active)
                VALUES (:source_key,'osm',:scope,:kind,
                    ST_SetSRID(ST_MakePoint(:longitude,:latitude),4326)::geography,
                    :direction_degrees,:speed_limit_kph,CAST(:raw AS jsonb),:import_id,:at,true)
                ON CONFLICT(source_key) DO UPDATE SET
                    scope=excluded.scope,kind=excluded.kind,location=excluded.location,
                    direction_degrees=excluded.direction_degrees,
                    speed_limit_kph=excluded.speed_limit_kph,raw=excluded.raw,
                    import_id=excluded.import_id,fetched_at=excluded.fetched_at,active=true
            """), {**control, "raw": json.dumps(control["raw"]), "scope": scope,
                   "import_id": import_id, "at": payload.fetched_at})
    return {"status": "imported", "count": len(controls), "import_id": import_id, **diagnostics}
