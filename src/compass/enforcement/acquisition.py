"""Sequential bounded Overpass acquisition; no partial catalog is ever imported."""
import json
import logging
import math
import time
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime

import httpx

from compass.config import Settings
from compass.enforcement.osm import query, roads_query
from compass.etl.acquisition import AcquisitionError, DownloadedPayload, download

LOGGER = logging.getLogger(__name__)
TRANSIENT = {429, 502, 503, 504}


def _retry_delay(error: AcquisitionError, attempt: int) -> float | None:
    cause = error.__cause__
    delay = (10, 30)[attempt - 1]
    if isinstance(cause, httpx.HTTPStatusError):
        if cause.response.status_code not in TRANSIENT:
            return None
        retry_after = cause.response.headers.get("retry-after")
        if retry_after:
            try:
                seconds = float(retry_after)
                if not math.isfinite(seconds):
                    return None
                delay = max(delay, seconds)
            except ValueError:
                try:
                    delay = max(delay, (
                        parsedate_to_datetime(retry_after) - datetime.now(UTC)
                    ).total_seconds())
                except (ValueError, TypeError, OverflowError):
                    return None
        # Do not violate a long server-directed pause with an earlier retry.
        return delay if 0 <= delay <= 120 else None
    if isinstance(cause, httpx.TimeoutException | httpx.ConnectError | httpx.RemoteProtocolError):
        return delay
    return None


def download_inventory(
    settings: Settings, *, transport=None, sleep=time.sleep,
) -> DownloadedPayload:
    remaining = settings.overpass_max_download_bytes
    parts = []

    def fetch(statement: str, label: str) -> list[dict]:
        nonlocal remaining
        if remaining <= 0:
            raise AcquisitionError("Enforcement acquisition exceeded total byte budget")
        for attempt in range(1, 4):
            try:
                payload = download(
                    settings.overpass_url, timeout_seconds=settings.http_timeout_seconds,
                    max_bytes=remaining, user_agent=settings.http_user_agent,
                    method="POST", data={"data": statement}, transport=transport,
                )
                break
            except AcquisitionError as error:
                delay = _retry_delay(error, attempt) if attempt < 3 else None
                if delay is None:
                    raise
                LOGGER.warning("Overpass retry phase=%s attempt=%s/3 delay_seconds=%s",
                               label, attempt + 1, delay)
                sleep(delay)
        remaining -= len(payload.content)
        try:
            data = json.loads(payload.content)
        except ValueError as error:
            raise AcquisitionError(f"Invalid Overpass JSON during {label}") from error
        if not isinstance(data, dict) or data.get("remark") or not isinstance(
            data.get("elements"), list,
        ):
            raise AcquisitionError(f"Incomplete Overpass extraction during {label}")
        parts.append((payload.source_url, payload.fetched_at))
        return data["elements"]

    controls = fetch(query(settings.overpass_area_iso3166_1, include_roads=False), "controls")
    endpoints = sorted({
        member["ref"] for element in controls
        if element.get("type") == "relation"
        and element.get("tags", {}).get("type") == "enforcement"
        for member in element.get("members", [])
        if member.get("role") in {"from", "to"} and member.get("type") == "node"
        and type(member.get("ref")) is int and member["ref"] > 0
    })
    elements = {(item["type"], item["id"]): item for item in controls}
    batches = (len(endpoints) + 199) // 200
    for index in range(0, len(endpoints), 200):
        label = f"roads {index // 200 + 1}/{batches}"
        LOGGER.info("Enforcement acquisition phase=%s", label)
        for item in fetch(roads_query(endpoints[index:index + 200]), label):
            elements[(item["type"], item["id"])] = item
    content = json.dumps({
        "elements": list(elements.values()),
        "compass_acquisition": {
            "requests_completed": len(parts),
        },
    }, separators=(",", ":"), allow_nan=False).encode()
    if len(content) > settings.overpass_max_download_bytes:
        raise AcquisitionError("Combined enforcement inventory exceeds byte budget")
    return DownloadedPayload(content, parts[0][0], "application/json", parts[-1][1])
