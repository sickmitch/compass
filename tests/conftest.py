"""Keep fixture-based tests independent of the operator's local configuration."""

import os
from collections.abc import Iterator

import pytest

from compass.config import Settings, get_settings


@pytest.fixture(autouse=True)
def isolated_settings(monkeypatch: pytest.MonkeyPatch) -> Iterator[None]:
    # Test-specific overrides can still enable auth/traffic or select a test DB.
    # Never inherit credentials, provider flags or endpoints from a local .env.
    monkeypatch.setitem(Settings.model_config, "env_file", None)
    for name in tuple(os.environ):
        if name.lower() in Settings.model_fields:
            monkeypatch.delenv(name)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()
