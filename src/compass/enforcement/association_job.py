import json
from collections import Counter

import httpx
from sqlalchemy import text

from compass.config import Settings
from compass.db import SessionLocal
from compass.enforcement.association import associate, graph_identity


async def run(settings: Settings, limit: int) -> dict:
    if not 1 <= limit <= 1000:
        raise ValueError("Association limit must be between 1 and 1000")
    async with httpx.AsyncClient(headers={"User-Agent": settings.http_user_agent}) as client:
        identity = await graph_identity(client, settings)
        with SessionLocal() as session:
            controls = session.execute(text("""
                SELECT c.source_key,c.import_id,c.raw FROM enforcement_controls c
                LEFT JOIN enforcement_associations a ON a.source_key=c.source_key
                WHERE c.active AND (a.source_key IS NULL OR a.import_id<>c.import_id
                    OR a.graph_identity<>:graph)
                ORDER BY CASE WHEN c.raw->>'type'='relation' THEN 0 ELSE 1 END,
                         c.source_key LIMIT :limit
            """), {"graph": identity, "limit": limit}).mappings().all()
        results = []
        for control in controls:
            result = await associate(control["raw"], client, settings)
            results.append((control, result))
        # Do not publish any result if graph replacement occurred during this batch.
        if identity != await graph_identity(client, settings):
            raise ValueError("Valhalla graph changed; association batch discarded")
        counts = Counter()
        matched_sample = []
        with SessionLocal.begin() as session:
            for control, result in results:
                # Avoid publishing evidence against a concurrently replaced source record.
                current = session.execute(text("""
                    SELECT import_id FROM enforcement_controls
                    WHERE source_key=:key AND active FOR UPDATE
                """), {"key": control["source_key"]}).scalar_one_or_none()
                if current != control["import_id"]:
                    counts["source_changed"] += 1
                    continue
                session.execute(text("""
                    INSERT INTO enforcement_associations
                        (source_key,import_id,graph_identity,state,reason,evidence)
                    VALUES (:key,:import_id,:graph,:state,:reason,CAST(:evidence AS jsonb))
                    ON CONFLICT(source_key) DO UPDATE SET import_id=excluded.import_id,
                        graph_identity=excluded.graph_identity,state=excluded.state,
                        reason=excluded.reason,evidence=excluded.evidence,matched_at=now()
                """), {"key": control["source_key"], "import_id": current, "graph": identity,
                       "state": result.state, "reason": result.reason,
                       "evidence": json.dumps(result.evidence)})
                counts[result.state] += 1
                if result.state == "matched" and len(matched_sample) < 5:
                    matched_sample.append(control["source_key"])
    return {"graph_identity": identity, "processed": len(results),
            "states": dict(counts), "matched_sample": matched_sample}
