from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

from compass.etl.mimit import MimitParseError, dataset_observed_at, parse_cng_prices, parse_stations

FIXTURES = Path(__file__).parent / "fixtures"


def test_parse_current_pipe_delimited_stations() -> None:
    result = parse_stations((FIXTURES / "mimit_stations.csv").read_bytes())

    assert result.dataset_date == date(2026, 8, 25)
    assert result.rows_seen == 3
    assert result.records[0].station_id == "1001"
    assert result.records[0].latitude == Decimal("45.464200")
    assert result.records[0].municipality == "Milano"


def test_parse_filters_and_models_only_cng_prices() -> None:
    result = parse_cng_prices((FIXTURES / "mimit_prices.csv").read_bytes())

    assert result.rows_seen == 5
    assert [record.station_id for record in result.records] == ["1001", "1002"]
    assert result.records[0].unit_price == Decimal("1.499")
    assert result.records[0].observed_at.tzinfo is not None
    assert result.records[1].is_self_service is True


def test_semicolon_delimiter_remains_supported_for_historical_fixtures() -> None:
    payload = b"Estrazione del 01/02/2025\nidImpianto;Comune;Provincia\n42;Roma;Roma\n"
    result = parse_stations(payload)
    assert result.records[0].station_id == "42"
    assert result.dataset_date == date(2025, 2, 1)


def test_invalid_price_is_rejected() -> None:
    payload = (
        b"Estrazione del 2026-08-25\n"
        b"idImpianto|descCarburante|prezzo|isSelf|dtComu\n"
        b"1|Metano|invalid|0|25/08/2026 07:00:00\n"
    )
    with pytest.raises(MimitParseError, match="invalid price"):
        parse_cng_prices(payload)


def test_dataset_observation_is_explicitly_rome_time() -> None:
    observed = dataset_observed_at(date(2026, 8, 25))
    assert observed is not None
    assert observed.isoformat() == "2026-08-25T08:00:00+02:00"
