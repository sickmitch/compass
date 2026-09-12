from __future__ import annotations

import asyncio
import hashlib
import json
import time
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field, fields
from math import asin, cos, isfinite, radians, sin, sqrt
from typing import Any, Protocol
from uuid import UUID

import httpx

from compass.candidates.geometry import decode_polyline6, encode_polyline5
from compass.routing.domain import Coordinate, RoutingProviderError
from compass.search.destination_domain import (
    DestinationProviderError,
    DestinationRateLimitedError,
    DestinationSearchUnavailableError,
    DestinationSuggestion,
    NavigationTarget,
    NormalizedAddress,
    ResolvedDestinationSelection,
    StaleDestinationSelectionError,
)
from compass.search.destination_service import PerUserRequestLimiter

GOOGLE_SEARCH_ALONG_ROUTE_FIELD_MASK = ",".join(
    (
        "places.id",
        "places.displayName",
        "places.formattedAddress",
        "places.location",
        "places.types",
        "places.attributions",
        "nextPageToken",
    )
)


class RouteRequiredError(Exception):
    pass


class InvalidRouteContextError(Exception):
    pass


class UnsupportedRouteGeometryError(Exception):
    pass


@dataclass(frozen=True, slots=True)
class AlongRouteLeg:
    encoded_polyline6: str


@dataclass(frozen=True, slots=True)
class AlongRouteContext:
    route_id: str
    route_revision: int
    origin: Coordinate
    final_destination: Coordinate
    remaining_waypoints: tuple[Coordinate, ...]
    legs: tuple[AlongRouteLeg, ...]
    progress_shape_index: int | None = None
    insertion_leg_index: int | None = None


@dataclass(frozen=True, slots=True)
class AlongRouteSearchRequest:
    query: str
    session_id: str
    revision: int
    route: AlongRouteContext | None
    language: str = "it"
    page_cursor: str | None = None


@dataclass(frozen=True, slots=True)
class AlongRouteProviderResult:
    suggestion: DestinationSuggestion
    selection: ResolvedDestinationSelection


@dataclass(frozen=True, slots=True)
class AlongRouteProviderResponse:
    results: tuple[AlongRouteProviderResult, ...]
    next_page_token: str | None


@dataclass(frozen=True, slots=True)
class AlongRouteSearchResponse:
    session_id: str
    revision: int
    route_id: str
    route_revision: int
    route_fingerprint: str
    results: tuple[DestinationSuggestion, ...]
    next_page_cursor: str | None
    mode: str = "route_biased"
    limitation: str = (
        "I risultati sono orientati alla rotta dal provider; non rappresentano "
        "un corridoio geometrico rigido."
    )


class AlongRouteSearchProvider(Protocol):
    provider_name: str

    async def search(
        self,
        *,
        query: str,
        language: str,
        encoded_polyline5: str,
        page_token: str | None,
    ) -> AlongRouteProviderResponse: ...


class GooglePlacesNewAlongRouteProvider:
    provider_name = "google_places_new"

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        timeout_seconds: float,
        region_code: str,
        page_size: int,
        client: httpx.AsyncClient,
    ) -> None:
        if not api_key:
            raise ValueError("Google Places API key must not be blank")
        self._url = f"{base_url.rstrip('/')}/places:searchText"
        self._api_key = api_key
        self._timeout = httpx.Timeout(timeout_seconds)
        self._region_code = region_code.upper()
        self._page_size = page_size
        self._client = client

    async def search(
        self,
        *,
        query: str,
        language: str,
        encoded_polyline5: str,
        page_token: str | None,
    ) -> AlongRouteProviderResponse:
        payload: dict[str, Any] = {
            "textQuery": query,
            "languageCode": language,
            "regionCode": self._region_code,
            "pageSize": self._page_size,
            "searchAlongRouteParameters": {"polyline": {"encodedPolyline": encoded_polyline5}},
        }
        if page_token is not None:
            payload["pageToken"] = page_token
        try:
            response = await self._client.post(
                self._url,
                json=payload,
                headers={
                    "Content-Type": "application/json",
                    "X-Goog-Api-Key": self._api_key,
                    "X-Goog-FieldMask": GOOGLE_SEARCH_ALONG_ROUTE_FIELD_MASK,
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
        if response.status_code >= 500:
            raise DestinationSearchUnavailableError(
                f"Google Places returned HTTP {response.status_code}"
            )
        if response.status_code >= 300:
            raise DestinationProviderError(
                f"Google Places rejected Search Along Route with HTTP {response.status_code}"
            )
        try:
            body = response.json()
            if not isinstance(body, Mapping):
                raise TypeError("Google Text Search response must be an object")
            places = body.get("places", [])
            if not isinstance(places, Sequence) or isinstance(places, str | bytes):
                raise TypeError("Google Text Search places must be an array")
            normalized: list[AlongRouteProviderResult] = []
            seen: set[str] = set()
            for rank, raw in enumerate(places):
                result = _normalize_google_place(raw, rank)
                if result.suggestion.provider_ref in seen:
                    continue
                seen.add(result.suggestion.provider_ref)
                normalized.append(result)
            token = _optional_text(body.get("nextPageToken"))
            return AlongRouteProviderResponse(tuple(normalized), token)
        except (KeyError, TypeError, ValueError) as error:
            raise DestinationProviderError(
                "Google Places returned invalid Search Along Route data"
            ) from error


@dataclass(slots=True)
class AlongRouteSearchMetrics:
    provider_calls: int = 0
    latency_ms_total: int = 0
    provider_errors: int = 0
    rate_limited: int = 0
    results_returned: int = 0
    stale_responses_discarded: int = 0
    resolutions_succeeded: int = 0

    def snapshot(self) -> dict[str, int]:
        return {item.name: int(getattr(self, item.name)) for item in fields(self)}


@dataclass(slots=True)
class _AlongRouteSession:
    route_id: str
    route_revision: int
    route_fingerprint: str
    query_revision: int
    query: str
    language: str
    results: dict[str, AlongRouteProviderResult] = field(default_factory=dict)
    page_tokens: dict[str, str] = field(default_factory=dict)
    updated_at: float = field(default_factory=time.monotonic)
    concluded_refs: set[str] = field(default_factory=set)
    last_response: AlongRouteSearchResponse | None = None


class AlongRouteSearchService:
    def __init__(
        self,
        *,
        provider: AlongRouteSearchProvider,
        max_geometry_points: int,
        max_encoded_polyline_chars: int,
        session_ttl_seconds: int,
        max_concurrency: int,
        requests_per_user_minute: int,
        minimum_query_characters: int,
    ) -> None:
        self._provider = provider
        self._max_geometry_points = max_geometry_points
        self._max_encoded_polyline_chars = max_encoded_polyline_chars
        self._session_ttl_seconds = session_ttl_seconds
        self._slots = asyncio.Semaphore(max_concurrency)
        self._limiter = PerUserRequestLimiter(requests_per_user_minute)
        self._minimum_query_characters = minimum_query_characters
        self._sessions: dict[tuple[str, str], _AlongRouteSession] = {}
        self._lock = asyncio.Lock()
        self.metrics = AlongRouteSearchMetrics()

    async def search(
        self,
        owner: str,
        request: AlongRouteSearchRequest,
    ) -> AlongRouteSearchResponse:
        _require_uuid4(request.session_id)
        query = request.query.strip()
        if len("".join(query.split())) < self._minimum_query_characters:
            raise ValueError("along-route query is shorter than configured minimum")
        if request.route is None:
            raise RouteRequiredError("a calculated route is required")
        route = request.route
        encoded, fingerprint = build_google_route_polyline(
            route,
            max_points=self._max_geometry_points,
            max_encoded_chars=self._max_encoded_polyline_chars,
        )
        await self._collect_expired()
        if not await self._limiter.acquire(owner):
            self.metrics.rate_limited += 1
            raise DestinationRateLimitedError(60)
        key = (owner, request.session_id)
        async with self._lock:
            session = self._sessions.get(key)
            if request.page_cursor is None:
                if session is not None and request.revision < session.query_revision:
                    raise StaleDestinationSelectionError("along-route query revision is stale")
                if (
                    session is not None
                    and request.revision == session.query_revision
                    and (
                        session.query != query
                        or session.route_fingerprint != fingerprint
                        or session.route_revision != route.route_revision
                    )
                ):
                    raise StaleDestinationSelectionError("query revision was reused")
                if (
                    session is not None
                    and request.revision == session.query_revision
                    and session.query == query
                    and session.route_fingerprint == fingerprint
                    and session.last_response is not None
                ):
                    return session.last_response
                session = _AlongRouteSession(
                    route_id=route.route_id,
                    route_revision=route.route_revision,
                    route_fingerprint=fingerprint,
                    query_revision=request.revision,
                    query=query,
                    language=request.language,
                )
                self._sessions[key] = session
                page_token = None
            else:
                if session is None or not _session_matches(session, request, fingerprint, query):
                    raise StaleDestinationSelectionError("pagination context is stale")
                page_token = session.page_tokens.get(request.page_cursor)
                if page_token is None:
                    raise StaleDestinationSelectionError("pagination cursor is stale")
        started = time.monotonic()
        self.metrics.provider_calls += 1
        try:
            async with self._slots:
                provider_response = await self._provider.search(
                    query=query,
                    language=request.language,
                    encoded_polyline5=encoded,
                    page_token=page_token,
                )
        except DestinationRateLimitedError:
            self.metrics.rate_limited += 1
            raise
        except Exception:
            self.metrics.provider_errors += 1
            raise
        finally:
            self.metrics.latency_ms_total += round((time.monotonic() - started) * 1_000)
        async with self._lock:
            current = self._sessions.get(key)
            if current is not session or not _session_matches(current, request, fingerprint, query):
                self.metrics.stale_responses_discarded += 1
                raise StaleDestinationSelectionError("along-route response is stale")
            for result in provider_response.results:
                current.results.setdefault(result.suggestion.provider_ref, result)
            if request.page_cursor is not None:
                current.page_tokens.pop(request.page_cursor, None)
            next_cursor = None
            if provider_response.next_page_token is not None:
                next_cursor = hashlib.sha256(
                    f"{fingerprint}:{query}:{request.revision}:{time.monotonic_ns()}".encode()
                ).hexdigest()[:32]
                current.page_tokens = {next_cursor: provider_response.next_page_token}
            current.updated_at = time.monotonic()
        self.metrics.results_returned += len(provider_response.results)
        response = AlongRouteSearchResponse(
            session_id=request.session_id,
            revision=request.revision,
            route_id=route.route_id,
            route_revision=route.route_revision,
            route_fingerprint=fingerprint,
            results=tuple(item.suggestion for item in provider_response.results),
            next_page_cursor=next_cursor,
        )
        async with self._lock:
            if self._sessions.get(key) is session and request.page_cursor is None:
                session.last_response = response
        return response

    async def resolve(
        self,
        *,
        owner: str,
        session_id: str,
        revision: int,
        route_id: str,
        route_revision: int,
        route_fingerprint: str,
        provider_ref: str,
        route: AlongRouteContext,
    ) -> tuple[ResolvedDestinationSelection, NavigationTarget]:
        _require_uuid4(session_id)
        _, current_fingerprint = build_google_route_polyline(
            route,
            max_points=self._max_geometry_points,
            max_encoded_chars=self._max_encoded_polyline_chars,
        )
        if current_fingerprint != route_fingerprint:
            raise StaleDestinationSelectionError("route changed after the search")
        async with self._lock:
            session = self._sessions.get((owner, session_id))
            if session is None or (
                session.query_revision != revision
                or session.route_id != route_id
                or session.route_revision != route_revision
                or session.route_fingerprint != route_fingerprint
            ):
                raise StaleDestinationSelectionError("along-route selection is stale")
            result = session.results.get(provider_ref)
            if result is None:
                raise StaleDestinationSelectionError("place was not returned by this search")
            first_resolution = provider_ref not in session.concluded_refs
            session.concluded_refs.add(provider_ref)
            session.updated_at = time.monotonic()
        if first_resolution:
            self.metrics.resolutions_succeeded += 1
        selection = result.selection
        return selection, NavigationTarget(selection.coordinate, selection.provider_ref)

    async def _collect_expired(self) -> None:
        now = time.monotonic()
        async with self._lock:
            for key in [
                key
                for key, session in self._sessions.items()
                if now - session.updated_at >= self._session_ttl_seconds
            ]:
                self._sessions.pop(key, None)


def build_google_route_polyline(
    context: AlongRouteContext,
    *,
    max_points: int,
    max_encoded_chars: int,
) -> tuple[str, str]:
    if not context.route_id.strip() or context.route_revision < 0:
        raise InvalidRouteContextError("route identity or revision is invalid")
    if not context.legs:
        raise RouteRequiredError("route geometry is missing")
    if len(context.legs) != len(context.remaining_waypoints) + 1:
        raise InvalidRouteContextError("route legs do not match remaining waypoints")
    route_points = (context.origin, *context.remaining_waypoints, context.final_destination)
    if any(
        not isfinite(point.latitude)
        or not isfinite(point.longitude)
        or not -90 <= point.latitude <= 90
        or not -180 <= point.longitude <= 180
        for point in route_points
    ):
        raise InvalidRouteContextError("route waypoints contain an invalid coordinate")
    decoded: list[tuple[Coordinate, ...]] = []
    consumed = 0
    try:
        for leg in context.legs:
            points = decode_polyline6(
                leg.encoded_polyline6,
                max_points=max_points - consumed,
            )
            consumed += len(points)
            decoded.append(points)
    except (RoutingProviderError, ValueError) as error:
        raise InvalidRouteContextError("route geometry is invalid") from error
    for index, points in enumerate(decoded):
        if (
            _distance_meters(points[0], route_points[index]) > 250
            or _distance_meters(points[-1], route_points[index + 1]) > 250
        ):
            raise InvalidRouteContextError("route geometry does not match its waypoints")
    leg_index = context.insertion_leg_index
    if leg_index is not None:
        if leg_index not in range(len(decoded)):
            raise InvalidRouteContextError("insertion leg is outside the remaining route")
        coordinates = list(decoded[leg_index])
    else:
        coordinates = []
        for points in decoded:
            coordinates.extend(
                points
                if not coordinates
                else points[1:]
                if points[0] == coordinates[-1]
                else points
            )
    if context.progress_shape_index is not None:
        if leg_index is not None:
            raise InvalidRouteContextError("progress index and explicit leg cannot be combined")
        if context.progress_shape_index not in range(len(coordinates) - 1):
            raise InvalidRouteContextError("route progress is outside the route geometry")
        coordinates = coordinates[context.progress_shape_index :]
    if len(coordinates) < 2:
        raise InvalidRouteContextError("remaining route geometry is empty")
    if _distance_meters(coordinates[0], coordinates[-1]) < 10 and _path_length(coordinates) > 1_000:
        raise UnsupportedRouteGeometryError("circular routes are not supported by this provider")
    encoded = encode_polyline5(tuple(coordinates))
    if len(encoded) > max_encoded_chars:
        raise InvalidRouteContextError("route geometry exceeds the configured encoded size")
    fingerprint = hashlib.sha256(
        json.dumps(
            {
                "route_id": context.route_id,
                "route_revision": context.route_revision,
                "progress": context.progress_shape_index,
                "leg": context.insertion_leg_index,
                "polyline": encoded,
            },
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
    ).hexdigest()
    return encoded, fingerprint


def _normalize_google_place(raw: Any, rank: int) -> AlongRouteProviderResult:
    if not isinstance(raw, Mapping):
        raise TypeError("Google place must be an object")
    place_id = _required_text(raw.get("id"), "Google place has no id")
    display = raw.get("displayName")
    if not isinstance(display, Mapping):
        raise TypeError("Google place has no display name")
    title = _required_text(display.get("text"), "Google place has no display name")
    address = _optional_text(raw.get("formattedAddress"))
    location = raw.get("location")
    if not isinstance(location, Mapping):
        raise TypeError("Google place has no location")
    latitude = float(location["latitude"])
    longitude = float(location["longitude"])
    if (
        not isfinite(latitude)
        or not isfinite(longitude)
        or not -90 <= latitude <= 90
        or not -180 <= longitude <= 180
    ):
        raise ValueError("Google place coordinate is invalid")
    coordinate = Coordinate(latitude, longitude)
    types = (
        {value for item in raw.get("types", []) if (value := _optional_text(item)) is not None}
        if isinstance(raw.get("types", []), Sequence)
        else set()
    )
    kind = (
        "business"
        if types & {"point_of_interest", "establishment"}
        else (
            "address"
            if types & {"street_address", "premise", "route", "intersection"}
            else ("locality" if types & {"locality", "postal_town"} else "unknown")
        )
    )
    attributions = ["Google Maps"]
    raw_attributions = raw.get("attributions", [])
    if isinstance(raw_attributions, Sequence) and not isinstance(raw_attributions, str | bytes):
        for attribution in raw_attributions:
            if isinstance(attribution, Mapping):
                label = _optional_text(attribution.get("provider"))
                if label and label not in attributions:
                    attributions.append(label)
    suggestion = DestinationSuggestion(
        id=f"google_places_new:{place_id}",
        provider="google_places_new",
        provider_ref=place_id,
        kind=kind,
        title=title,
        subtitle=address,
        address_preview=address,
        distance_meters=None,
        provider_rank=rank,
        requires_resolution=False,
        attribution="Google Maps",
    )
    selection = ResolvedDestinationSelection(
        provider="google_places_new",
        provider_ref=place_id,
        formatted_address=address,
        address_components=(),
        normalized_address=NormalizedAddress(),
        coordinate=coordinate,
        kind=kind,
        attribution=tuple(attributions),
        field_sources=(
            ("formatted_address", "google_places_new"),
            ("location", "google_places_new"),
        ),
    )
    return AlongRouteProviderResult(suggestion, selection)


def _session_matches(
    session: _AlongRouteSession,
    request: AlongRouteSearchRequest,
    fingerprint: str,
    query: str,
) -> bool:
    route = request.route
    return route is not None and (
        session.query_revision == request.revision
        and session.query == query
        and session.language == request.language
        and session.route_id == route.route_id
        and session.route_revision == route.route_revision
        and session.route_fingerprint == fingerprint
    )


def _require_uuid4(value: str) -> None:
    try:
        parsed = UUID(value)
    except ValueError as error:
        raise ValueError("search session id must be UUID v4") from error
    if parsed.version != 4 or str(parsed) != value.lower():
        raise ValueError("search session id must be canonical UUID v4")


def _optional_text(value: Any) -> str | None:
    if value is None:
        return None
    text = str(value).strip()
    return text or None


def _required_text(value: Any, message: str) -> str:
    text = _optional_text(value)
    if text is None:
        raise ValueError(message)
    return text


def _distance_meters(first: Coordinate, second: Coordinate) -> float:
    lat1, lat2 = radians(first.latitude), radians(second.latitude)
    delta_lat = lat2 - lat1
    delta_lon = radians(second.longitude - first.longitude)
    value = sin(delta_lat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(delta_lon / 2) ** 2
    return 2 * 6_371_000 * asin(sqrt(value))


def _path_length(coordinates: list[Coordinate]) -> float:
    return sum(_distance_meters(a, b) for a, b in zip(coordinates, coordinates[1:], strict=False))
