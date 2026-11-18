import hashlib
import importlib.util
import io
import json
import tarfile
import zipfile

import pytest

spec = importlib.util.spec_from_file_location("offline_export", "tools/export-offline-routing.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_export_preserves_graph_and_writes_android_package_contract(tmp_path):
    archive = tmp_path / "tiles.tar"
    with tarfile.open(archive, "w") as tar:
        tile = tarfile.TarInfo("2/000/001.gph")
        tile.size = 4
        tar.addfile(tile, io.BytesIO(b"tile"))
    original = archive.read_bytes()
    output = tmp_path / "area.zip"
    kwargs = dict(engine_version="3.9.0", label="Verona", source_date="2026-11-17",
                  bounds=[45, 10, 46, 12], probe=[[45.4, 11.0], [45.5, 11.1]])
    manifest = module.export(archive, output, **kwargs)
    with zipfile.ZipFile(output) as package:
        assert package.namelist() == ["manifest.json", "tiles.tar"]
        assert json.loads(package.read("manifest.json")) == manifest
        assert hashlib.sha256(package.read("tiles.tar")).hexdigest() == manifest["sha256"]
    assert archive.read_bytes() == original
    with pytest.raises(FileExistsError):
        module.export(archive, output, **kwargs)
    kwargs["engine_version"] = "3.8.3"
    with pytest.raises(ValueError, match="separate"):
        module.export(archive, tmp_path / "bad.zip", **kwargs)
    assert not (tmp_path / "bad.zip").exists()
    kwargs["engine_version"] = "3.9.0"
    partial = tmp_path / "busy.zip.part"
    partial.write_bytes(b"another export")
    with pytest.raises(FileExistsError):
        module.export(archive, tmp_path / "busy.zip", **kwargs)
    assert partial.read_bytes() == b"another export"
