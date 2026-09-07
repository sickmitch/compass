from collections.abc import Mapping, Sequence
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

GOOGLE_PLACES_TEXT_SEARCH_FIELD_MASK = ",".join(
    (
        "places.id",
        "places.displayName",
        "places.formattedAddress",
        "places.location",
        "places.primaryType",
        "places.types",
        "places.addressComponents",
    )
)


class GooglePlacesNewSearchProvider:
    """Google Places API (New) adapter used only for ephemeral corroboration."""

    provider_name = "google_places_new"

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        timeout_seconds: float,
        region_code: str = "IT",
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not api_key:
            raise ValueError("Google Places API key must not be blank")
        self._url = f"{base_url.rstrip('/')}/places:searchText"
        self._api_key = api_key
        self._timeout = httpx.Timeout(timeout_seconds)
        self._region_code = region_code
        self._client = client

    async def search(self, request: PlaceSearchRequest) -> tuple[PlaceSearchResult, ...]:
        payload = {
            "textQuery": request.query.strip(),
            "languageCode": request.language,
            "regionCode": self._region_code,
            "maxResultCount": request.limit,
        }
        headers = {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": self._api_key,
            "X-Goog-FieldMask": GOOGLE_PLACES_TEXT_SEARCH_FIELD_MASK,
        }
        try:
            if self._client is not None:
                response = await self._client.post(
                    self._url,
                    json=payload,
                    headers=headers,
                    timeout=self._timeout,
                )
            else:
                async with httpx.AsyncClient() as client:
                    response = await client.post(
                        self._url,
                        json=payload,
                        headers=headers,
                        timeout=self._timeout,
                    )
        except httpx.TransportError as error:
            raise PlaceSearchUnavailableError("Google Places is unavailable") from error
        if response.status_code == 429 or response.status_code >= 500:
            raise PlaceSearchUnavailableError(
                f"Google Places returned HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise PlaceSearchProviderError(
                f"Google Places rejected the request with HTTP {response.status_code}"
            )
        try:
            body = response.json()
            if not isinstance(body, Mapping):
                raise TypeError("Google Places response must be an object")
            places = body.get("places", [])
            if not isinstance(places, Sequence) or isinstance(places, str | bytes):
                raise TypeError("Google Places results must be an array")
            return tuple(_normalize_result(place) for place in places)
        except (KeyError, TypeError, ValueError) as error:
            raise PlaceSearchProviderError("Google Places returned invalid data") from error


def _normalize_result(raw: Any) -> PlaceSearchResult:
    if not isinstance(raw, Mapping):
        raise TypeError("Google place must be an object")
    place_id = _required_text(raw.get("id"), "Google place has no id")
    display_name_value = raw.get("displayName")
    if not isinstance(display_name_value, Mapping):
        raise TypeError("Google place has no display name")
    display_name = _required_text(
        display_name_value.get("text"), "Google place has no display name"
    )
    location = raw.get("location")
    if not isinstance(location, Mapping):
        raise TypeError("Google place has no location")
    latitude = float(location["latitude"])
    longitude = float(location["longitude"])
    if not -90 <= latitude <= 90 or not -180 <= longitude <= 180:
        raise ValueError("Google place coordinate is outside valid bounds")

    primary_type = _optional_text(raw.get("primaryType"))
    raw_types = raw.get("types", [])
    types = (
        {
            text
            for item in raw_types
            if (text := _optional_text(item)) is not None
        }
        if isinstance(raw_types, Sequence) and not isinstance(raw_types, str | bytes)
        else set()
    )
    components = _address_components(raw.get("addressComponents"))
    kind = _place_kind(primary_type, types)
    return PlaceSearchResult(
        result_id=f"google_places_new:{place_id}",
        display_name=display_name,
        address=_optional_text(raw.get("formattedAddress")),
        coordinate=Coordinate(latitude, longitude),
        kind=kind,
        category=primary_type,
        poi_name=display_name if kind == "poi" else None,
        provider="google_places_new",
        provider_place_id=place_id,
        street_name=components.get("route"),
        house_number=components.get("street_number"),
        locality=(
            components.get("locality")
            or components.get("postal_town")
            or components.get("administrative_area_level_3")
        ),
    )


def _address_components(value: Any) -> dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, Sequence) or isinstance(value, str | bytes):
        raise TypeError("Google address components must be an array")
    components: dict[str, str] = {}
    for component in value:
        if not isinstance(component, Mapping):
            raise TypeError("Google address component must be an object")
        text = _optional_text(component.get("longText") or component.get("shortText"))
        types = component.get("types", [])
        if (
            text is None
            or not isinstance(types, Sequence)
            or isinstance(types, str | bytes)
        ):
            continue
        for component_type in types:
            normalized_type = _optional_text(component_type)
            if normalized_type is not None:
                components.setdefault(normalized_type, text)
    return components


def _place_kind(primary_type: str | None, types: set[str]) -> PlaceKind:
    all_types = types | ({primary_type} if primary_type is not None else set())
    if all_types & {
        "street_address",
        "premise",
        "subpremise",
        "route",
        "intersection",
    }:
        return "address"
    if all_types & {
        "locality",
        "postal_town",
        "administrative_area_level_3",
        "sublocality",
        "neighborhood",
    }:
        return "locality"
    if all_types & {"point_of_interest", "establishment"}:
        return "poi"
    return "unknown"


def _required_text(value: Any, message: str) -> str:
    text = _optional_text(value)
    if text is None:
        raise ValueError(message)
    return text


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None
