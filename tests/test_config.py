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
