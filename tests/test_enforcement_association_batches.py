import asyncio
from unittest.mock import AsyncMock

import pytest

from compass.config import Settings
from compass.enforcement import association_job
from compass.etl.cli import parser


def batch(count=0, identity="graph", states=None, **extra):
    return {"graph_identity": identity, "processed": count,
            "states": states or {}, "matched_sample": [], **extra}


def test_all_batches_continue_until_empty_even_after_short_batch(monkeypatch):
    run = AsyncMock(side_effect=[
        batch(500, states={"matched": 400, "ambiguous": 100}, matched_sample=["one"]),
        batch(1, states={"unknown_direction": 1}), batch(),
    ])
    monkeypatch.setattr(association_job, "run", run)
    result = asyncio.run(association_job.run_all(Settings(), 500))
    assert result == batch(501, states={"matched": 400, "ambiguous": 100,
                                       "unknown_direction": 1}, matched_sample=["one"])
    assert run.await_count == 3
    assert all(call.args[1] == 500 for call in run.await_args_list)


@pytest.mark.parametrize("batches,reason", [
    ([batch(status="source_geometry_required")], "source geometry missing"),
    ([batch(1), batch(identity="changed")], "graph changed"),
    ([batch(1, states={"source_changed": 1})], "inventory changed"),
])
def test_incomplete_or_changing_data_cannot_report_success(monkeypatch, batches, reason):
    run = AsyncMock(side_effect=batches)
    monkeypatch.setattr(association_job, "run", run)
    with pytest.raises(ValueError, match=reason):
        asyncio.run(association_job.run_all(Settings(), 500))
    assert run.await_count == len(batches)


def test_all_batch_failure_propagates_for_retry(monkeypatch):
    monkeypatch.setattr(association_job, "run", AsyncMock(side_effect=RuntimeError("offline")))
    with pytest.raises(RuntimeError, match="offline"):
        asyncio.run(association_job.run_all(Settings(), 500))


def test_cli_preserves_single_batch_default_and_exposes_all():
    args = parser().parse_args(["enforcement-associate"])
    assert not args.all and args.limit == 100
    args = parser().parse_args(["enforcement-associate", "--all", "--limit", "500"])
    assert args.all and args.limit == 500
