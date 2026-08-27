import argparse
import json
import logging
from pathlib import Path

from compass.config import get_settings
from compass.db import SessionLocal
from compass.etl.acquisition import DownloadedPayload, download
from compass.etl.osm import build_cng_query
from compass.etl.service import ingest_mimit, ingest_osm
from compass.logging import configure_logging
from compass.reconciliation.service import normalize_and_reconcile, set_match_override

LOGGER = logging.getLogger(__name__)


def _file_payload(path: Path, content_type: str) -> DownloadedPayload:
    from datetime import UTC, datetime

    return DownloadedPayload(
        content=path.read_bytes(),
        source_url=path.resolve().as_uri(),
        content_type=content_type,
        fetched_at=datetime.now(UTC),
    )


def _mimit(args: argparse.Namespace) -> dict[str, object]:
    settings = get_settings()
    if bool(args.stations_file) != bool(args.prices_file):
        raise SystemExit("--stations-file and --prices-file must be supplied together")
    if args.stations_file:
        stations = _file_payload(args.stations_file, "text/csv")
        prices = _file_payload(args.prices_file, "text/csv")
    else:
        stations = download(
            settings.mimit_stations_url,
            timeout_seconds=settings.http_timeout_seconds,
            max_bytes=settings.mimit_max_download_bytes,
            user_agent=settings.http_user_agent,
        )
        prices = download(
            settings.mimit_prices_url,
            timeout_seconds=settings.http_timeout_seconds,
            max_bytes=settings.mimit_max_download_bytes,
            user_agent=settings.http_user_agent,
        )
    with SessionLocal() as session:
        return ingest_mimit(
            session,
            stations_content=stations.content,
            prices_content=prices.content,
            stations_url=stations.source_url,
            prices_url=prices.source_url,
            stations_content_type=stations.content_type,
            prices_content_type=prices.content_type,
            fetched_at=max(stations.fetched_at, prices.fetched_at),
        )


def _osm(args: argparse.Namespace) -> dict[str, object]:
    settings = get_settings()
    if args.input_file:
        payload = _file_payload(args.input_file, "application/json")
    else:
        query = build_cng_query(settings.overpass_area_iso3166_1)
        payload = download(
            settings.overpass_url,
            timeout_seconds=settings.http_timeout_seconds,
            max_bytes=settings.overpass_max_download_bytes,
            user_agent=settings.http_user_agent,
            method="POST",
            data={"data": query},
        )
    with SessionLocal() as session:
        return ingest_osm(
            session,
            content=payload.content,
            source_url=payload.source_url,
            content_type=payload.content_type,
            fetched_at=payload.fetched_at,
        )


def _normalize(_args: argparse.Namespace) -> dict[str, object]:
    with SessionLocal() as session:
        return normalize_and_reconcile(session)


def _override(args: argparse.Namespace) -> dict[str, object]:
    with SessionLocal() as session:
        return set_match_override(
            session,
            mimit_station_id=args.mimit_station_id,
            action=args.action,
            reason=args.reason,
            created_by=args.created_by,
            osm_type=args.osm_type,
            osm_id=args.osm_id,
        )


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="Compass CNG source ingestion, normalization and reconciliation"
    )
    subcommands = root.add_subparsers(dest="command", required=True)
    mimit = subcommands.add_parser("mimit", help="ingest MIMIT active stations and CNG prices")
    mimit.add_argument("--stations-file", type=Path)
    mimit.add_argument("--prices-file", type=Path)
    mimit.set_defaults(handler=_mimit)
    osm = subcommands.add_parser("osm", help="ingest OSM CNG fuel features through Overpass")
    osm.add_argument("--input-file", type=Path)
    osm.set_defaults(handler=_osm)
    normalize = subcommands.add_parser(
        "normalize", help="normalize latest raw imports and reconcile MIMIT with OSM"
    )
    normalize.set_defaults(handler=_normalize)
    override = subcommands.add_parser(
        "override", help="set or clear a manual MIMIT-to-OSM reconciliation override"
    )
    override.add_argument("--mimit-station-id", required=True)
    override.add_argument("--action", choices=("link", "unmatch", "clear"), required=True)
    override.add_argument("--osm-type", choices=("node", "way", "relation"))
    override.add_argument("--osm-id", type=int)
    override.add_argument("--reason", required=True)
    override.add_argument("--created-by", required=True)
    override.set_defaults(handler=_override)
    return root


def main() -> None:
    settings = get_settings()
    configure_logging(settings.log_level)
    args = parser().parse_args()
    try:
        result = args.handler(args)
    except Exception:
        LOGGER.exception("ingestion failed", extra={"command": args.command})
        raise
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))


if __name__ == "__main__":
    main()
