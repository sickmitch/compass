from dataclasses import dataclass
from datetime import UTC, datetime

import httpx


class AcquisitionError(RuntimeError):
    pass


@dataclass(frozen=True)
class DownloadedPayload:
    content: bytes
    source_url: str
    content_type: str
    fetched_at: datetime


def download(
    url: str,
    *,
    timeout_seconds: float,
    max_bytes: int,
    user_agent: str,
    method: str = "GET",
    data: dict[str, str] | None = None,
    transport: httpx.BaseTransport | None = None,
) -> DownloadedPayload:
    headers = {"User-Agent": user_agent, "Accept": "*/*"}
    try:
        with (
            httpx.Client(
                timeout=timeout_seconds,
                follow_redirects=True,
                headers=headers,
                transport=transport,
            ) as client,
            client.stream(method, url, data=data) as response,
        ):
            response.raise_for_status()
            chunks: list[bytes] = []
            size = 0
            for chunk in response.iter_bytes():
                size += len(chunk)
                if size > max_bytes:
                    raise AcquisitionError(
                        f"download from {url} exceeded configured {max_bytes}-byte limit"
                    )
                chunks.append(chunk)
            content = b"".join(chunks)
            if not content:
                raise AcquisitionError(f"download from {url} returned an empty body")
            return DownloadedPayload(
                content=content,
                source_url=str(response.url),
                content_type=response.headers.get("content-type", "application/octet-stream")[:128],
                fetched_at=datetime.now(UTC),
            )
    except httpx.HTTPError as error:
        raise AcquisitionError(f"download from {url} failed: {error}") from error
