from collections.abc import Mapping
from typing import Any

import httpx

from compass.routing.domain import Coordinate
from compass.search.domain import (
    PlaceKind,
    PlaceSearchProviderError,
    PlaceSearchRequest,
    PlaceSearchResult,
    PlaceSearchUnavailableError,
)


class NominatimPlaceSearchProvider:
    provider_name = "nominatim"

    def __init__(
        self,
        *,
        base_url: str,
        timeout_seconds: float,
        user_agent: str,
        country_codes: str = "it",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = httpx.Timeout(timeout_seconds)
        self._headers = {"User-Agent": user_agent}
        self._country_codes = country_codes
        self._client = client

    async def search(self, request: PlaceSearchRequest) -> tuple[PlaceSearchResult, ...]:
        params = {
            "q": request.query.strip(),
            "format": "jsonv2",
            "addressdetails": 1,
            "namedetails": 1,
            "limit": request.limit,
            "countrycodes": self._country_codes,
            "accept-language": request.language,
        }
        try:
            if self._client is not None:
                response = await self._client.get(
                    f"{self._base_url}/search",
                    params=params,
                    headers=self._headers,
                    timeout=self._timeout,
                )
            else:
                async with httpx.AsyncClient() as client:
                    response = await client.get(
                        f"{self._base_url}/search",
                        params=params,
                        headers=self._headers,
                        timeout=self._timeout,
                    )
        except httpx.TransportError as error:
            raise PlaceSearchUnavailableError("place search provider is unavailable") from error
        if response.status_code == 429 or response.status_code >= 500:
            raise PlaceSearchUnavailableError(
                f"place search provider returned HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise PlaceSearchProviderError(
                f"place search provider rejected the request with HTTP {response.status_code}"
            )
        try:
            payload = response.json()
            if not isinstance(payload, list):
                raise TypeError("search response must be a list")
            return tuple(_normalize_result(item) for item in payload)
        except (KeyError, TypeError, ValueError) as error:
            raise PlaceSearchProviderError("place search provider returned invalid data") from error


def _normalize_result(raw: Any) -> PlaceSearchResult:
    if not isinstance(raw, Mapping):
        raise TypeError("search result must be an object")
    latitude = float(raw["lat"])
    longitude = float(raw["lon"])
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise ValueError("search result coordinate is outside valid bounds")
    display_name = str(raw["display_name"]).strip()
    if not display_name:
        raise ValueError("search result has no display name")
    category = _optional_text(raw.get("category") or raw.get("class"))
    result_type = _optional_text(raw.get("type"))
    address = raw.get("address")
    address_text = _format_address(address) if isinstance(address, Mapping) else None
    osm_type = _optional_text(raw.get("osm_type")) or "place"
    osm_id = _optional_text(raw.get("osm_id")) or str(raw.get("place_id", "unknown"))
    provider_id = f"{osm_type}:{osm_id}"
    return PlaceSearchResult(
        result_id=f"nominatim:{provider_id}",
        display_name=display_name,
        address=address_text,
        coordinate=Coordinate(latitude, longitude),
        kind=_place_kind(category, result_type),
        category=result_type or category,
        poi_name=_result_name(raw),
        provider="nominatim",
        provider_place_id=provider_id,
    )


def _place_kind(category: str | None, result_type: str | None) -> PlaceKind:
    if category in {"amenity", "shop", "tourism", "leisure", "office", "craft"}:
        return "poi"
    if category in {"highway", "building"} or result_type in {
        "house",
        "residential",
        "road",
        "street",
    }:
        return "address"
    if result_type in {"city", "town", "village", "hamlet", "municipality", "locality"}:
        return "locality"
    if category in {"place", "boundary"}:
        return "locality"
    return "unknown"


def _result_name(raw: Mapping[str, Any]) -> str | None:
    namedetails = raw.get("namedetails")
    if isinstance(namedetails, Mapping):
        return _optional_text(namedetails.get("name"))
    return _optional_text(raw.get("name"))


def _format_address(address: Mapping[str, Any]) -> str | None:
    parts: list[str] = []
    road = _optional_text(address.get("road") or address.get("pedestrian"))
    house_number = _optional_text(address.get("house_number"))
    if road:
        parts.append(f"{road} {house_number}".strip() if house_number else road)
    locality = _optional_text(
        address.get("city")
        or address.get("town")
        or address.get("village")
        or address.get("municipality")
    )
    if locality:
        parts.append(locality)
    province = _optional_text(address.get("state") or address.get("province"))
    if province and province not in parts:
        parts.append(province)
    return ", ".join(parts) or None


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
