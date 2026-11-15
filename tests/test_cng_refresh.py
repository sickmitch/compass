import logging

import pytest

from compass.etl import cli


@pytest.mark.parametrize("failed_stage", [None, "mimit", "osm", "normalize"])
def test_combined_refresh_preserves_stage_order_and_failure_policy(
    monkeypatch, caplog, failed_stage,
):
    calls = []
    args = cli.parser().parse_args(["cng"])

    def stage(name):
        def execute(options):
            calls.append(name)
            # Reuse the same importer paths and online defaults as the standalone commands.
            assert options.stations_file is None
            assert options.prices_file is None
            assert options.input_file is None
            if name == failed_stage:
                raise RuntimeError(f"{name} failed")
            return {"stage": name, "run_id": 123}
        return execute

    for name in ("mimit", "osm", "normalize"):
        monkeypatch.setattr(cli, f"_{name}", stage(name))
    with caplog.at_level(logging.INFO):
        if failed_stage in {"mimit", "normalize"}:
            with pytest.raises(RuntimeError, match=f"{failed_stage} failed"):
                args.handler(args)
        else:
            result = args.handler(args)
            assert result["status"] == "completed"
            assert result["osm_refresh_failed"] == (failed_stage == "osm")
            assert result["mimit"]["run_id"] == 123
            assert result["normalization"]["run_id"] == 123
            if failed_stage == "osm":
                assert result["osm"] is None
                assert "previously retained OSM data" in caplog.text
            else:
                assert result["osm"]["run_id"] == 123
    assert calls == (["mimit"] if failed_stage == "mimit" else ["mimit", "osm", "normalize"])


def test_individual_import_commands_remain_available():
    for name in ("mimit", "osm", "normalize"):
        args = cli.parser().parse_args([name])
        assert args.handler is getattr(cli, f"_{name}")
