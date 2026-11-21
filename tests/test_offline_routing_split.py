import hashlib
import importlib.util
import io
import json
import tarfile
import zipfile

import pytest

spec = importlib.util.spec_from_file_location("offline_split", "tools/split-offline-routing.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def path(lat, lon):
    tile = int((lat + 90) / 0.25) * 1440 + int((lon + 180) / 0.25)
    digits = f"{tile:09d}"
    return f"2/{digits[:3]}/{digits[3:6]}/{digits[6:]}.gph"


def source(tmp_path, changed=False):
    archive = tmp_path / ("new.tar" if changed else "graph.tar")
    data = {
        "0/003/017.gph": b"shared hierarchy",
        path(45.1, 10.1): b"west",
        path(45.1, 11.1): b"east changed" if changed else b"east",
    }
    with tarfile.open(archive, "w") as tar:
        for name, content in {"index.bin": b"old offsets", **data}.items():
            member = tarfile.TarInfo(name)
            member.size = len(content)
            tar.addfile(member, io.BytesIO(content))
    return archive, data


def regions():
    return [
        dict(
            id="west",
            label="West",
            bounds=[45, 10, 45.25, 10.25],
            probe=[[45.1, 10.1], [45.2, 10.2]],
        ),
        dict(
            id="east",
            label="East",
            bounds=[45, 11, 45.25, 11.25],
            probe=[[45.1, 11.1], [45.2, 11.2]],
        ),
    ]


def test_regions_reunite_original_tiles_without_changing_cross_region_ids(tmp_path):
    archive, original = source(tmp_path)
    output = tmp_path / "regions"
    results = module.export_regions(archive, output, regions(), "2026-11-21")
    assert len({r["graphFamily"] for r in results}) == 1
    combined = {}
    for result in results:
        with zipfile.ZipFile(output / result["file"]) as z:
            manifest = json.loads(z.read("manifest.json"))
            assert manifest["schemaVersion"] == 2
            assert manifest["sha256"] == module.inventory_digest(manifest["tiles"])
            assert manifest["bytes"] == sum(t["bytes"] for t in manifest["tiles"])
            assert "index.bin" not in z.namelist()
            for t in manifest["tiles"]:
                content = z.read(t["path"])
                assert hashlib.sha256(content).hexdigest() == t["sha256"]
                if t["path"] in combined:
                    assert combined[t["path"]] == content
                combined[t["path"]] = content
    assert combined == original
    assert module.tile_bounds(path(45.1, 11.1)) == (45, 11, 45.25, 11.25)
    with pytest.raises(FileExistsError):
        module.export_regions(archive, output, regions(), "2026-11-21")


def test_new_build_cannot_be_mixed_even_when_regional_tiles_are_unchanged(tmp_path):
    first, _ = source(tmp_path)
    second, _ = source(tmp_path, changed=True)
    a = module.export_regions(first, tmp_path / "a", regions(), "2026-11-21")
    b = module.export_regions(second, tmp_path / "b", regions(), "2026-11-21")
    assert a[0]["graphFamily"] != b[0]["graphFamily"]


@pytest.mark.parametrize(
    "name,kind",
    [
        ("../evil.gph", tarfile.REGTYPE),
        ("2/777/001.gph", tarfile.SYMTYPE),
        ("2/777/001.gph", tarfile.LNKTYPE),
    ],
)
def test_export_rejects_unsafe_graph_entries(tmp_path, name, kind):
    archive = tmp_path / "bad.tar"
    with tarfile.open(archive, "w") as tar:
        member = tarfile.TarInfo(name)
        member.type = kind
        member.size = 1 if kind == tarfile.REGTYPE else 0
        tar.addfile(member, io.BytesIO(b"x") if member.size else None)
    with pytest.raises(ValueError, match="Unexpected"):
        module.export_regions(archive, tmp_path / "output", regions(), "2026-11-21")


def test_empty_regions_and_duplicate_names_fail_without_publishing(tmp_path):
    archive, _ = source(tmp_path)
    with pytest.raises(ValueError, match="distinct"):
        module.export_regions(archive, tmp_path / "output", [regions()[0]] * 2, "2026-11-21")
    far = dict(id="absent", label="Absent", bounds=[0, 0, 1, 1], probe=[[0.1, 0.1], [0.2, 0.2]])
    with pytest.raises(ValueError, match="No local"):
        module.export_regions(archive, tmp_path / "output", [far], "2026-11-21")
    assert not list((tmp_path / "output").glob("*.zip"))


def test_rejects_aliases_for_the_same_tile_id():
    with pytest.raises(ValueError, match="Invalid tile id"):
        module.tile_bounds("2/777/001.gph")
    with pytest.raises(ValueError, match="Invalid tile id"):
        module.tile_bounds("2/999/999/999.gph")
