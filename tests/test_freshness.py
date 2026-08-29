from datetime import UTC, datetime, timedelta
from types import SimpleNamespace

from compass.freshness.service import _evaluate, load_data_freshness


def test_freshness_evaluation_distinguishes_all_states() -> None:
    now = datetime(2026, 8, 29, 6, tzinfo=UTC)
    assert (
        _evaluate(
            "source",
            source_observed_at=now - timedelta(hours=1),
            completed_at=now,
            threshold_seconds=7200,
            evaluated_at=now,
        ).state
        == "fresh"
    )
    assert (
        _evaluate(
            "source",
            source_observed_at=now - timedelta(hours=3),
            completed_at=now,
            threshold_seconds=7200,
            evaluated_at=now,
        ).state
        == "stale"
    )
    assert (
        _evaluate(
            "source",
            source_observed_at=now + timedelta(minutes=1),
            completed_at=now,
            threshold_seconds=7200,
            evaluated_at=now,
        ).state
        == "future_observation"
    )
    assert (
        _evaluate(
            "source",
            source_observed_at=None,
            completed_at=None,
            threshold_seconds=7200,
            evaluated_at=now,
        ).state
        == "missing"
    )


class _Result:
    def __init__(self, value: object) -> None:
        self.value = value

    def one_or_none(self) -> object:
        return self.value


class _Session:
    def __init__(self, rows: list[object], reconciliation: datetime | None) -> None:
        self.rows = rows
        self.reconciliation = reconciliation

    def execute(self, _statement: object) -> _Result:
        return _Result(self.rows.pop(0))

    def scalar(self, _statement: object) -> datetime | None:
        return self.reconciliation


def test_freshness_report_degrades_stale_optional_source() -> None:
    now = datetime(2026, 8, 29, 6, tzinfo=UTC)
    report = load_data_freshness(
        _Session(
            [
                SimpleNamespace(source_observed_at=now, completed_at=now),
                SimpleNamespace(source_observed_at=now - timedelta(days=8), completed_at=now),
            ],
            reconciliation=now,
        ),
        mimit_threshold_seconds=48 * 3600,
        osm_threshold_seconds=7 * 24 * 3600,
        reconciliation_threshold_seconds=48 * 3600,
        evaluated_at=now,
    )

    assert report.overall_state == "degraded"
    assert report.mimit.state == "fresh"
    assert report.osm.state == "stale"
    assert report.reconciliation.state == "fresh"


def test_freshness_report_marks_missing_required_data_unavailable() -> None:
    now = datetime(2026, 8, 29, 6, tzinfo=UTC)
    report = load_data_freshness(
        _Session([None, None], reconciliation=None),
        mimit_threshold_seconds=48 * 3600,
        osm_threshold_seconds=7 * 24 * 3600,
        reconciliation_threshold_seconds=48 * 3600,
        evaluated_at=now,
    )

    assert report.overall_state == "unavailable"
    assert report.mimit.state == "missing"
    assert report.reconciliation.state == "missing"
