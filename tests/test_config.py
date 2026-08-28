import pytest
from pydantic import ValidationError

from compass.config import Settings


def test_valhalla_url_is_normalized() -> None:
    settings = Settings(_env_file=None, valhalla_url="http://router.internal:8002/")

    assert settings.valhalla_url == "http://router.internal:8002"


@pytest.mark.parametrize("value", ["router.internal:8002", "file:///tiles", "http:///route"])
def test_valhalla_url_requires_http_host(value: str) -> None:
    with pytest.raises(ValidationError, match="valhalla_url"):
        Settings(_env_file=None, valhalla_url=value)


def test_corridor_radius_bounds_are_ordered() -> None:
    with pytest.raises(ValidationError, match="minimum radius"):
        Settings(
            _env_file=None,
            cng_corridor_minimum_radius_km=51,
            cng_corridor_maximum_radius_km=50,
        )


@pytest.mark.parametrize("value", [0, 101])
def test_matrix_batch_size_is_bounded(value: int) -> None:
    with pytest.raises(ValidationError, match="valhalla_matrix_batch_size"):
        Settings(_env_file=None, valhalla_matrix_batch_size=value)
