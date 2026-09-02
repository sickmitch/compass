from __future__ import annotations

import asyncio
import json
from collections.abc import Mapping
from dataclasses import dataclass
from math import isfinite
from typing import Protocol

from compass.routing.domain import Coordinate


class OpenLrDecodeError(RuntimeError):
    """Raised when the pinned native Valhalla decoder rejects a reference."""


@dataclass(frozen=True, slots=True)
class DecodedOpenLrLine:
    reference: str
    lrps: tuple[Coordinate, ...]


class OpenLrDecoder(Protocol):
    async def decode_line(self, reference: str) -> DecodedOpenLrLine: ...


class NativeValhallaOpenLrDecoder:
    """Decode OpenLR through the helper compiled against the deployed Valhalla."""

    def __init__(self, *, executable_path: str, timeout_seconds: float) -> None:
        if not executable_path:
            raise ValueError("OpenLR decoder executable path must not be empty")
        if timeout_seconds <= 0:
            raise ValueError("OpenLR decoder timeout must be positive")
        self._executable_path = executable_path
        self._timeout_seconds = timeout_seconds

    async def decode_line(self, reference: str) -> DecodedOpenLrLine:
        if not reference:
            raise OpenLrDecodeError("OpenLR reference must not be empty")
        try:
            process = await asyncio.create_subprocess_exec(
                self._executable_path,
                "decode-openlr",
                "--reference",
                reference,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except OSError as error:
            raise OpenLrDecodeError("native OpenLR decoder is unavailable") from error
        try:
            stdout, stderr = await asyncio.wait_for(
                process.communicate(), timeout=self._timeout_seconds
            )
        except TimeoutError as error:
            process.kill()
            await process.wait()
            raise OpenLrDecodeError("native OpenLR decoder timed out") from error
        if process.returncode != 0:
            detail = stderr.decode("utf-8", errors="replace").strip()
            raise OpenLrDecodeError(
                f"native OpenLR decoder rejected the reference: {detail[:200]}"
            )
        if len(stdout) > 64 * 1024:
            raise OpenLrDecodeError("native OpenLR decoder returned excessive output")
        try:
            payload = json.loads(stdout)
            return parse_decoded_openlr_line(payload, expected_reference=reference)
        except (TypeError, ValueError, json.JSONDecodeError) as error:
            raise OpenLrDecodeError("native OpenLR decoder returned malformed output") from error


def parse_decoded_openlr_line(
    payload: object, *, expected_reference: str
) -> DecodedOpenLrLine:
    if not isinstance(payload, Mapping):
        raise TypeError("decoded OpenLR must be an object")
    if payload.get("reference") != expected_reference:
        raise ValueError("decoded OpenLR reference differs from the request")
    if payload.get("canonical_reference") != expected_reference:
        raise ValueError("native OpenLR round trip changed the reference")
    if payload.get("location_type") != "line":
        raise ValueError("only OpenLR line locations are supported")
    if payload.get("line_direction") != "first_lrp_to_last_lrp":
        raise ValueError("OpenLR line direction is missing")
    raw_lrps = payload.get("lrps")
    if not isinstance(raw_lrps, list) or len(raw_lrps) < 2:
        raise ValueError("OpenLR line needs at least two ordered LRPs")
    lrps: list[Coordinate] = []
    for index, raw_lrp in enumerate(raw_lrps):
        if not isinstance(raw_lrp, Mapping) or raw_lrp.get("index") != index:
            raise ValueError("OpenLR LRPs are not ordered")
        latitude = _coordinate_number(raw_lrp.get("latitude"), minimum=-90, maximum=90)
        longitude = _coordinate_number(
            raw_lrp.get("longitude"), minimum=-180, maximum=180
        )
        lrps.append(Coordinate(latitude=latitude, longitude=longitude))
    return DecodedOpenLrLine(reference=expected_reference, lrps=tuple(lrps))


def _coordinate_number(value: object, *, minimum: float, maximum: float) -> float:
    if isinstance(value, bool) or not isinstance(value, int | float):
        raise TypeError("OpenLR coordinate must be numeric")
    result = float(value)
    if not isfinite(result) or not minimum <= result <= maximum:
        raise ValueError("OpenLR coordinate is out of range")
    return result
