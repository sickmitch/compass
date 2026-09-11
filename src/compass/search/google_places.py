from collections.abc import Mapping, Sequence
from math import isfinite
from typing import Any
from urllib.parse import quote

import httpx

from compass.routing.domain import Coordinate
from compass.search.destination_domain import (
    AddressComponent,
    DestinationNotFoundError,
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchUnavailableError,
    DestinationSuggestion,
    DestinationSuggestRequest,
    DestinationUnresolvableError,
    NormalizedAddress,
    ResolvedDestinationSelection,
)
from compass.search.domain import (
    PlaceKind,
    PlaceSearchProviderError,
    PlaceSearchRequest,
    PlaceSearchResult,
    PlaceSearchUnavailableError,
)

GOOGLE_PLACES_AUTOCOMPLETE_FIELD_MASK = ",".join(
    (
        "suggestions.placePrediction.placeId",
        "suggestions.placePrediction.text",
        "suggestions.placePrediction.structuredFormat",
        "suggestions.placePrediction.types",
        "suggestions.placePrediction.distanceMeters",
    )
)
GOOGLE_PLACES_DETAILS_FIELD_MASK = ",".join(
    ("id", "formattedAddress", "addressComponents", "location", "types", "attributions")
)


class GooglePlacesNewDestinationProvider:
    """Autocomplete/Details adapter; it never performs Text Search or reverse geocoding."""

    provider_name = "google_places_new"

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        timeout_seconds: float,
        region_code: str = "it",
        included_region_codes: tuple[str, ...] = ("it",),
        client: httpx.AsyncClient,
    ) -> None:
        if not api_key:
            raise ValueError("Google Places API key must not be blank")
        self._base_url = base_url.rstrip("/")
        self._autocomplete_url = f"{self._base_url}/places:autocomplete"
        self._api_key = api_key
        self._timeout = httpx.Timeout(timeout_seconds)
        self._region_code = region_code.lower()
        self._included_region_codes = included_region_codes
        self._client = client

    async def suggest(
        self,
        request: DestinationSuggestRequest,
        provider_session_token: str,
    ) -> tuple[DestinationSuggestion, ...]:
        payload: dict[str, Any] = {
            "input": request.query.strip(),
            "languageCode": request.language,
            "regionCode": self._region_code,
            "includeQueryPredictions": False,
            "includePureServiceAreaBusinesses": False,
            "sessionToken": provider_session_token,
        }
        if self._included_region_codes:
            payload["includedRegionCodes"] = list(self._included_region_codes)
        if request.context.location is not None:
            coordinate = request.context.location
            center = {
                "latitude": coordinate.latitude,
                "longitude": coordinate.longitude,
            }
            payload["origin"] = center
            if request.context.bias_radius_meters is not None:
                payload["locationBias"] = {
                    "circle": {
                        "center": center,
                        "radius": request.context.bias_radius_meters,
                    }
                }
        if request.context.route_bounds is not None:
            south_west, north_east = request.context.route_bounds
            payload["locationRestriction"] = {
                "rectangle": {
                    "low": {
                        "latitude": south_west.latitude,
                        "longitude": south_west.longitude,
                    },
                    "high": {
                        "latitude": north_east.latitude,
                        "longitude": north_east.longitude,
                    },
                }
            }
        response = await self._request(
            "POST",
            self._autocomplete_url,
            field_mask=GOOGLE_PLACES_AUTOCOMPLETE_FIELD_MASK,
            json=payload,
        )
        try:
            body = response.json()
            suggestions = body.get("suggestions", [])
            if not isinstance(suggestions, Sequence) or isinstance(suggestions, str | bytes):
                raise TypeError("Google suggestions must be an array")
            normalized = []
            seen: set[str] = set()
            for rank, item in enumerate(suggestions):
                prediction = _prediction(item)
                if prediction is None:
                    continue
                suggestion = _normalize_prediction(prediction, rank)
                if suggestion.provider_ref in seen:
                    continue
                seen.add(suggestion.provider_ref)
                normalized.append(suggestion)
            return tuple(normalized[:5])
        except (AttributeError, KeyError, TypeError, ValueError) as error:
            raise DestinationProviderError("Google Autocomplete returned invalid data") from error

    async def resolve(
        self,
        provider_ref: str,
        *,
        language: str,
        provider_session_token: str,
    ) -> ResolvedDestinationSelection:
        if (
            not provider_ref
            or len(provider_ref) > 512
            or any(ord(character) < 32 for character in provider_ref)
        ):
            raise DestinationProviderError("Google Place ID is invalid")
        url = f"{self._base_url}/places/{quote(provider_ref, safe='')}"
        response = await self._request(
            "GET",
            url,
            field_mask=GOOGLE_PLACES_DETAILS_FIELD_MASK,
            params={
                "languageCode": language,
                "regionCode": self._region_code,
                "sessionToken": provider_session_token,
            },
        )
        try:
            body = response.json()
            if not isinstance(body, Mapping):
                raise TypeError("Google Place Details must be an object")
            location = body.get("location")
            if not isinstance(location, Mapping):
                raise DestinationUnresolvableError("Google Place has no coordinate")
            latitude = float(location["latitude"])
            longitude = float(location["longitude"])
            if (
                not isfinite(latitude)
                or not isfinite(longitude)
                or not -90 <= latitude <= 90
                or not -180 <= longitude <= 180
            ):
                raise DestinationUnresolvableError("Google Place coordinate is invalid")
            coordinate = Coordinate(latitude, longitude)
            components = _resolved_address_components(body.get("addressComponents"))
            raw_types = body.get("types", [])
            types = _text_set(raw_types)
            resolved_id = _required_text(body.get("id"), "Google Place has no id")
            if resolved_id != provider_ref:
                raise DestinationUnresolvableError("Google Place ID changed during resolution")
            return ResolvedDestinationSelection(
                provider=self.provider_name,
                provider_ref=resolved_id,
                formatted_address=_optional_text(body.get("formattedAddress")),
                address_components=components,
                normalized_address=_normalized_address(components),
                coordinate=coordinate,
                kind=_destination_kind(types),
                attribution=_resolved_attributions(body.get("attributions")),
                field_sources=(
                    ("formatted_address", self.provider_name),
                    ("address_components", self.provider_name),
                    ("location", self.provider_name),
                ),
            )
        except DestinationUnresolvableError:
            raise
        except (KeyError, TypeError, ValueError) as error:
            raise DestinationUnresolvableError("Google Place Details is incomplete") from error

    async def _request(
        self,
        method: str,
        url: str,
        *,
        field_mask: str,
        json: dict[str, Any] | None = None,
        params: dict[str, str] | None = None,
    ) -> httpx.Response:
        try:
            response = await self._client.request(
                method,
                url,
                json=json,
                params=params,
                headers={
                    "Content-Type": "application/json",
                    "X-Goog-Api-Key": self._api_key,
                    "X-Goog-FieldMask": field_mask,
                },
                timeout=self._timeout,
            )
        except httpx.TransportError as error:
            raise DestinationSearchUnavailableError("Google Places is unavailable") from error
        if response.status_code == 429:
            retry_after = response.headers.get("Retry-After")
            raise DestinationRateLimitedError(
                int(retry_after) if retry_after and retry_after.isdigit() else None
            )
        if response.status_code == 404:
            raise DestinationNotFoundError("Google Place was not found")
        if response.status_code >= 500:
            raise DestinationSearchUnavailableError(
                f"Google Places returned HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise DestinationProviderError(
                f"Google Places rejected the request with HTTP {response.status_code}"
            )
        return response


def _prediction(value: Any) -> Mapping[str, Any] | None:
    if not isinstance(value, Mapping):
        raise TypeError("Google suggestion must be an object")
    prediction = value.get("placePrediction")
    if prediction is None:
        return None
    if not isinstance(prediction, Mapping):
        raise TypeError("Google place prediction must be an object")
    return prediction


def _normalize_prediction(raw: Mapping[str, Any], rank: int) -> DestinationSuggestion:
    place_id = _required_text(raw.get("placeId"), "Google prediction has no Place ID")
    structured = raw.get("structuredFormat")
    structured = structured if isinstance(structured, Mapping) else {}
    title = _localized_text(structured.get("mainText")) or _localized_text(raw.get("text"))
    if title is None:
        raise ValueError("Google prediction has no title")
    subtitle = _localized_text(structured.get("secondaryText"))
    types = _text_set(raw.get("types", []))
    distance = raw.get("distanceMeters")
    distance_meters = int(distance) if distance is not None else None
    if distance_meters is not None and distance_meters < 0:
        raise ValueError("Google prediction distance is negative")
    return DestinationSuggestion(
        id=f"google_places_new:{place_id}",
        provider="google_places_new",
        provider_ref=place_id,
        kind=_destination_kind(types),
        title=title,
        subtitle=subtitle,
        address_preview=subtitle,
        distance_meters=distance_meters,
        provider_rank=rank,
    )


def _localized_text(value: Any) -> str | None:
    if not isinstance(value, Mapping):
        return None
    return _optional_text(value.get("text"))


def _text_set(value: Any) -> set[str]:
    if not isinstance(value, Sequence) or isinstance(value, str | bytes):
        return set()
    return {text for item in value if (text := _optional_text(item)) is not None}


def _destination_kind(types: set[str]):
    if types & {"street_address", "premise", "subpremise", "route", "intersection"}:
        return "address"
    if types & {"locality", "postal_town", "administrative_area_level_3"}:
        return "locality"
    if types & {"point_of_interest", "establishment"}:
        return "business"
    return "unknown"


def _resolved_address_components(value: Any) -> tuple[AddressComponent, ...]:
    if value is None:
        return ()
    if not isinstance(value, Sequence) or isinstance(value, str | bytes):
        raise TypeError("Google address components must be an array")
    result = []
    for raw in value:
        if not isinstance(raw, Mapping):
            raise TypeError("Google address component must be an object")
        long_text = _required_text(raw.get("longText"), "address component has no text")
        result.append(
            AddressComponent(
                long_text=long_text,
                short_text=_optional_text(raw.get("shortText")),
                types=tuple(sorted(_text_set(raw.get("types", [])))),
            )
        )
    return tuple(result)


def _normalized_address(components: tuple[AddressComponent, ...]) -> NormalizedAddress:
    by_type: dict[str, str] = {}
    for component in components:
        for component_type in component.types:
            by_type.setdefault(component_type, component.long_text)
    locality = next(
        (
            by_type[item]
            for item in ("locality", "postal_town", "administrative_area_level_3")
            if item in by_type
        ),
        None,
    )
    return NormalizedAddress(
        street=by_type.get("route"),
        street_number=by_type.get("street_number"),
        locality=locality,
        province=by_type.get("administrative_area_level_2"),
        region=by_type.get("administrative_area_level_1"),
        postal_code=by_type.get("postal_code"),
        country=by_type.get("country"),
    )


def _resolved_attributions(value: Any) -> tuple[str, ...]:
    if value is None:
        return ("Google Maps",)
    if not isinstance(value, Sequence) or isinstance(value, str | bytes):
        raise TypeError("Google attributions must be an array")
    labels = ["Google Maps"]
    for raw in value:
        if isinstance(raw, Mapping):
            label = _optional_text(raw.get("provider"))
            if label and label not in labels:
                labels.append(label)
    return tuple(labels)


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
            raise PlaceSearchUnavailableError(f"Google Places returned HTTP {response.status_code}")
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
        {text for item in raw_types if (text := _optional_text(item)) is not None}
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
        if text is None or not isinstance(types, Sequence) or isinstance(types, str | bytes):
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
