"""Authenticated, immutable downloads from complete scheduler publications only."""

import json
import re
from datetime import datetime
from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from compass.api.contracts import ErrorResponse
from compass.config import Settings, get_api_settings

router = APIRouter(
    prefix="/api/v1/offline-routing",
    tags=["offline-routing"],
    responses={404: {"model": ErrorResponse}, 503: {"model": ErrorResponse}},
)
AREAS = {
    "nord-ovest": "Nord-Ovest",
    "nord-est": "Nord-Est",
    "centro": "Centro",
    "sud-isole": "Sud e isole",
    "italia": "Italia",
}
FILENAME = re.compile(r"(nord-ovest|nord-est|centro|sud-isole|italia)_([0-9]{8})\.zip")


class OfflinePackage(BaseModel):
    model_config = ConfigDict(extra="forbid")
    area: str
    label: str
    filename: str
    bytes: int = Field(gt=0, le=9 * 1024**3)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")


class OfflinePackages(BaseModel):
    model_config = ConfigDict(extra="forbid")
    graph_family: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")
    created_at: datetime | None = None
    packages: list[OfflinePackage] = Field(default_factory=list)


def load_publication(root: Path, marker: str) -> OfflinePackages:
    path = root / marker
    if path.is_symlink():
        raise ValueError("Invalid publication")
    with path.open("rb") as stream:
        raw = stream.read(16385)
    if len(raw) > 16384:
        raise ValueError("Publication too large")
    record = json.loads(raw)
    if not isinstance(record, dict) or not isinstance(record.get("files"), dict):
        raise ValueError("Invalid publication")
    created = datetime.fromisoformat(record["created_at"])
    if record["schema_version"] != 1 or created.utcoffset() is None:
        raise ValueError("Invalid publication")
    if not isinstance(record["graph_family"], str) or not re.fullmatch(
        r"[0-9a-f]{64}", record["graph_family"]
    ):
        raise ValueError("Invalid graph family")
    packages = []
    dates = set()
    for filename, metadata in record["files"].items():
        match = FILENAME.fullmatch(filename)
        if not match:
            raise ValueError("Invalid filename")
        area, date = match.groups()
        datetime.strptime(date, "%d%m%Y")
        dates.add(date)
        item = OfflinePackage(area=area, label=AREAS[area], filename=filename, **metadata)
        file = root / filename
        if file.is_symlink() or not file.is_file() or file.stat().st_size != item.bytes:
            raise ValueError("Incomplete publication")
        packages.append(item)
    if len(packages) != 5 or {p.area for p in packages} != set(AREAS) or len(dates) != 1:
        raise ValueError("Incomplete publication")
    if marker != "latest.json" and marker != f"set-{dates.pop()}.json":
        raise ValueError("Wrong publication")
    return OfflinePackages(
        graph_family=record["graph_family"],
        created_at=created,
        packages=sorted(packages, key=lambda p: list(AREAS).index(p.area)),
    )


@router.get("/packages", response_model=OfflinePackages)
def catalog(
    settings: Annotated[Settings, Depends(get_api_settings)],
) -> OfflinePackages | JSONResponse:
    root = Path(settings.offline_assets_path)
    try:
        return load_publication(root, "latest.json")
    except FileNotFoundError:
        if not (root / "latest.json").exists():
            return OfflinePackages()
        return JSONResponse(
            status_code=503,
            content={
                "code": "offline_catalog_unavailable",
                "message": "The offline package catalog is unavailable.",
            },
        )
    except (OSError, ValueError, KeyError, TypeError):
        return JSONResponse(
            status_code=503,
            content={
                "code": "offline_catalog_unavailable",
                "message": "The offline package catalog is unavailable.",
            },
        )


@router.get(
    "/packages/{filename}",
    response_class=FileResponse,
    response_model=None,
    responses={
        200: {"content": {"application/zip": {"schema": {"type": "string", "format": "binary"}}}},
        206: {"description": "Partial ZIP content (Range request)."},
        416: {"description": "Requested byte range is not satisfiable."},
    },
)
def download(
    filename: str, settings: Annotated[Settings, Depends(get_api_settings)]
) -> FileResponse | JSONResponse:
    match = FILENAME.fullmatch(filename)
    if not match:
        return JSONResponse(
            status_code=404,
            content={
                "code": "offline_package_not_found",
                "message": "The offline package is unavailable.",
            },
        )
    root = Path(settings.offline_assets_path)
    try:
        publication = load_publication(root, f"set-{match[2]}.json")
        item = next(p for p in publication.packages if p.filename == filename)
    except (OSError, ValueError, KeyError, TypeError, StopIteration):
        return JSONResponse(
            status_code=404,
            content={
                "code": "offline_package_not_found",
                "message": "The offline package is unavailable.",
            },
        )
    return FileResponse(
        root / filename,
        media_type="application/zip",
        filename=filename,
        headers={"ETag": f'"{item.sha256}"', "Cache-Control": "private, immutable"},
    )
