import httpx
import pytest

from compass.etl.acquisition import AcquisitionError, download


def test_download_is_bounded_and_identifies_the_operator() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.method == "POST"
        assert request.headers["user-agent"] == "compass-test/operator@example.test"
        assert request.content == b"data=query"
        return httpx.Response(
            200,
            content=b'{"elements": []}',
            headers={"content-type": "application/json"},
        )

    result = download(
        "https://overpass.example.test/api",
        timeout_seconds=5,
        max_bytes=1024,
        user_agent="compass-test/operator@example.test",
        method="POST",
        data={"data": "query"},
        transport=httpx.MockTransport(handler),
    )

    assert result.content == b'{"elements": []}'
    assert result.source_url == "https://overpass.example.test/api"
    assert result.content_type == "application/json"
    assert result.fetched_at.tzinfo is not None


def test_download_rejects_payload_over_configured_limit() -> None:
    transport = httpx.MockTransport(lambda _request: httpx.Response(200, content=b"too large"))

    with pytest.raises(AcquisitionError, match="exceeded configured 3-byte limit"):
        download(
            "https://source.example.test/data",
            timeout_seconds=5,
            max_bytes=3,
            user_agent="compass-test",
            transport=transport,
        )


def test_download_wraps_http_failures_without_returning_payload() -> None:
    transport = httpx.MockTransport(lambda _request: httpx.Response(503))

    with pytest.raises(AcquisitionError, match="503 Service Unavailable"):
        download(
            "https://source.example.test/data",
            timeout_seconds=5,
            max_bytes=1024,
            user_agent="compass-test",
            transport=transport,
        )
