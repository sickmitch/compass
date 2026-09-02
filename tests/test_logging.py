from __future__ import annotations

import logging

from compass.logging import configure_logging


def test_http_transport_info_logs_are_suppressed_to_protect_query_secrets() -> None:
    configure_logging("INFO")

    assert logging.getLogger("httpx").getEffectiveLevel() == logging.WARNING
    assert logging.getLogger("httpcore").getEffectiveLevel() == logging.WARNING
